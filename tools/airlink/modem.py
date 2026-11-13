"""Binary FSK modem: 8N1 async serial over two audio tones.

This is the same physical layer `minimodem -8` speaks:

  * one start bit (space = 0), eight data bits LSB first, one stop bit (mark = 1)
  * line idles at mark
  * mark is the higher-priority "1" tone

Transmit uses continuous phase so there are no clicks at bit boundaries.
Receive is a non-coherent detector: two sliding-window quadrature correlators,
their energy difference is the soft bit value, and a per-byte UART state
machine re-synchronises on every falling edge (start bit).  Re-syncing on
every byte is what makes long transmissions survivable: clock drift never
accumulates past one character.
"""

from __future__ import annotations

import struct
import wave
from dataclasses import dataclass, field
from typing import Optional, Tuple

import numpy as np


@dataclass(frozen=True)
class Profile:
    key: str
    label: str
    baud: float
    mark: float
    space: float
    sample_rate: int
    audible: bool
    chunk: int                      # payload bytes per FILE_CHUNK
    minimodem_rx_extra: Tuple[str, ...] = ()

    @property
    def bytes_per_second(self) -> float:
        return self.baud / 10.0

    def minimodem_args(self) -> list:
        def num(v: float) -> str:
            return str(int(v)) if float(v).is_integer() else ("%g" % v)
        return [num(self.baud), "-M", num(self.mark), "-S", num(self.space)]

    def minimodem_rx_args(self) -> list:
        return self.minimodem_args() + list(self.minimodem_rx_extra)


# 48 kHz throughout: it is what minimodem opens by default, what every modern
# Android device captures natively, and it keeps 19.5 kHz comfortably inside
# the band instead of 1.1 kHz below Nyquist.
PROFILES = {
    "bell202": Profile("bell202", "Audible — Bell 202 1200 baud", 1200, 1200, 2200, 48000, True, 512),
    "bell103": Profile("bell103", "Audible — Bell 103 300 baud", 300, 1270, 1070, 48000, True, 128),
    "ultrasonic": Profile("ultrasonic", "Ultrasonic — 18.5/19.5 kHz 300 baud", 300,
                          18500, 19500, 48000, False, 96, ("-b", "1400")),
}


class BfskModem:
    def __init__(self, profile: Profile, sample_rate: Optional[int] = None, amplitude: float = 0.75):
        self.p = profile
        self.fs = sample_rate or profile.sample_rate
        self.amp = amplitude

    # ------------------------------------------------------------------ tx

    def modulate(self, data: bytes, leader_bits: int = 40, trailer_bits: int = 40) -> np.ndarray:
        bits = np.empty(leader_bits + 10 * len(data) + trailer_bits, dtype=np.uint8)
        k = 0
        bits[:leader_bits] = 1
        k = leader_bits
        for byte in data:
            bits[k] = 0                       # start bit = space
            for i in range(8):
                bits[k + 1 + i] = (byte >> i) & 1
            bits[k + 9] = 1                   # stop bit = mark
            k += 10
        bits[k:k + trailer_bits] = 1

        spb = self.fs / self.p.baud
        n = int(round(len(bits) * spb))
        idx = np.arange(n, dtype=np.float64)
        bit_of = np.minimum((idx / spb).astype(np.int64), len(bits) - 1)
        freqs = np.where(bits[bit_of] == 1, self.p.mark, self.p.space)
        phase = 2.0 * np.pi * np.cumsum(freqs) / self.fs
        return (self.amp * np.sin(phase)).astype(np.float32)

    # ------------------------------------------------------------------ rx

    def demodulate(self, x: np.ndarray) -> bytes:
        """Decode a float signal (mono, self.fs) back into bytes."""
        x = np.asarray(x, dtype=np.float64)
        if x.ndim > 1:
            x = x.mean(axis=1)
        n = len(x)
        spb = self.fs / self.p.baud
        N = int(round(spb))
        if n < 4 * N:
            return b""

        t = np.arange(n, dtype=np.float64)
        cm = x * np.exp(-2j * np.pi * self.p.mark * t / self.fs)
        cs = x * np.exp(-2j * np.pi * self.p.space * t / self.fs)

        def slide(z):
            c = np.concatenate(([0 + 0j], np.cumsum(z)))
            return c[N:] - c[:-N]

        im, isp = slide(cm), slide(cs)
        d = (im.real ** 2 + im.imag ** 2) - (isp.real ** 2 + isp.imag ** 2)
        del im, isp, cm, cs

        # d[i] is the energy over x[i .. i+N-1]; its centre is x[i+(N-1)/2]
        off = (N - 1) / 2.0
        neg = d < 0
        edges = np.flatnonzero((~neg[:-1]) & (neg[1:])) + 1
        if edges.size == 0:
            return b""

        out = bytearray()
        next_free = -1.0
        nbits = 10
        for e in edges:
            start = e + off                       # x-index of the start-bit edge
            if start < next_free:
                continue
            samples = start + (np.arange(nbits) + 0.5) * spb
            q = samples - off
            j = np.floor(q).astype(np.int64)
            ok = (j >= 0) & (j + 1 < len(d))
            if not ok.all():
                continue
            frac = q - j
            v = d[j] * (1.0 - frac) + d[j + 1] * frac
            if v[0] >= 0 or v[9] <= 0:            # start must be space, stop must be mark
                continue
            byte = 0
            for i in range(8):
                if v[i + 1] > 0:
                    byte |= (1 << i)
            out.append(byte)
            next_free = start + nbits * spb - spb * 0.5
        return bytes(out)

    def demodulate_soft(self, x: np.ndarray):
        """Return (bytes, per-byte mean confidence in 0..1) — handy for UI meters."""
        data = self.demodulate(x)
        return data, min(1.0, len(data) / max(1.0, (len(x) / self.fs) * self.p.bytes_per_second))


# ---------------------------------------------------------------------- wav

def write_wav(path: str, signal: np.ndarray, fs: int) -> None:
    pcm = np.clip(signal, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(fs)
        w.writeframes(pcm.tobytes())


def read_wav(path: str) -> Tuple[np.ndarray, int]:
    with wave.open(path, "rb") as w:
        ch, sw, fs, nframes = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        raw = w.readframes(nframes)
    if sw == 2:
        a = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    elif sw == 1:
        a = (np.frombuffer(raw, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0
    elif sw == 4:
        a = np.frombuffer(raw, dtype="<i4").astype(np.float32) / 2147483648.0
    else:
        raise ValueError("unsupported sample width %d" % sw)
    if ch > 1:
        a = a.reshape(-1, ch).mean(axis=1)
    return a, fs


def add_noise(x: np.ndarray, snr_db: float, rng: Optional[np.random.Generator] = None) -> np.ndarray:
    rng = rng or np.random.default_rng(0)
    p_sig = float(np.mean(x ** 2))
    p_noise = p_sig / (10.0 ** (snr_db / 10.0))
    return (x + rng.normal(0.0, np.sqrt(p_noise), size=len(x))).astype(np.float32)


def resample_fft(x: np.ndarray, factor: float) -> np.ndarray:
    """Band-limited resample — models a receiver whose clock differs by `factor`."""
    n_out = int(round(len(x) * factor))
    X = np.fft.rfft(x)
    Y = np.zeros(n_out // 2 + 1, dtype=complex)
    k = min(len(X), len(Y))
    Y[:k] = X[:k]
    return (np.fft.irfft(Y, n=n_out) * factor).astype(np.float32)


def bandpass(x: np.ndarray, fs: int, lo: float, hi: float) -> np.ndarray:
    """Cheap FFT brick-wall filter — used to emulate a phone speaker/mic chain."""
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1.0 / fs)
    X[(f < lo) | (f > hi)] = 0
    return np.fft.irfft(X, n=len(x)).astype(np.float32)
