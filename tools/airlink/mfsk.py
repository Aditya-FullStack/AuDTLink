"""Proprietary 4-FSK modem — app-to-app only, not minimodem compatible.

Four tones, two bits per symbol, gray coded, at the same 2400 baud as the
audible fast mode. Twice the throughput of 2-FSK for the same symbol rate.

    tone 0 = 1300 Hz   01
    tone 1 = 2700 Hz   00      (gray map: adjacent tones differ by one bit)
    tone 2 = 4100 Hz   11
    tone 3 = 5500 Hz   10

There are no start/stop bits: M-FSK has nothing to re-sync a byte on, so the
transmission opens with an alternating-symbol preamble that gives the receiver
a clock, and timing is then tracked with an early-late gate for the rest of the
frame. The Airlink frame layer sits on top unchanged — this modem takes bytes
and returns bytes.
"""
from __future__ import annotations

import numpy as np

FS = 48000
TONES = (1300.0, 2700.0, 4100.0, 5500.0)
BAUD = 2400.0
SPB = FS / BAUD                       # 20.0 samples per symbol, exactly

# symbol index -> 2 data bits
SYM_BITS = (0b00, 0b01, 0b11, 0b10)
# 2 data bits -> symbol index
BITS_SYM = {b: i for i, b in enumerate(SYM_BITS)}

PREAMBLE_SYMS = 40                    # alternating 0,1 — clock training
SYNC_SYMS = (3, 3, 3, 3)              # constant top tone marks the data start
TRAILER_SYMS = 8                      # idle tone after the payload
BLOCK = 16                            # symbols per timing-search block


def _bits_of(data: bytes) -> list:
    out = []
    for byte in data:
        for i in range(8):
            out.append((byte >> i) & 1)
    return out


def _bytes_of(bits: list) -> bytes:
    out = bytearray()
    for i in range(0, len(bits) - 7, 8):
        b = 0
        for k in range(8):
            b |= (bits[i + k] & 1) << k
        out.append(b)
    return bytes(out)


def modulate(data: bytes, amplitude: float = 0.72) -> np.ndarray:
    syms = [i % 2 for i in range(PREAMBLE_SYMS)]      # 0,1,0,1...
    syms += list(SYNC_SYMS)
    bits = _bits_of(data)
    for i in range(0, len(bits) - 1, 2):
        syms.append(BITS_SYM[(bits[i] << 1) | bits[i + 1]])
    if len(bits) % 2:
        syms.append(BITS_SYM[bits[-1] << 1])
    syms += [0] * TRAILER_SYMS          # keep the last data symbol away from the edge

    n = int(round(len(syms) * SPB))
    idx = np.arange(n)
    f = np.array(TONES)[np.array(syms)][(idx / SPB).astype(int).clip(max=len(syms) - 1)]
    phase = 2 * np.pi * np.cumsum(f) / FS
    return (amplitude * np.sin(phase)).astype(np.float32)


def _energies(x: np.ndarray) -> np.ndarray:
    """Per-sample energy of each tone over a one-symbol sliding window."""
    n = len(x)
    N = int(round(SPB))
    t = np.arange(n)
    out = np.empty((len(TONES), n))
    for k, f in enumerate(TONES):
        c = x * np.exp(-2j * np.pi * f * t / FS)
        cs = np.concatenate(([0 + 0j], np.cumsum(c)))
        w = cs[N:] - cs[:-N]
        e = (w.real ** 2 + w.imag ** 2)
        out[k, N - 1:] = e
        out[k, :N - 1] = e[0] if len(e) else 0
    return out


def demodulate(x: np.ndarray) -> bytes:
    x = np.asarray(x, dtype=np.float64)
    if len(x) < 4 * SPB:
        return b""

    E = _energies(x)

    # ---- acquisition: pick the symbol offset that makes the preamble sharpest
    best_off, best_score = 0.0, -1.0
    win = int(PREAMBLE_SYMS * SPB)
    if len(x) < win + int(SPB):
        return b""
    for off in np.arange(0, SPB, 0.5):
        pos = off + np.arange(PREAMBLE_SYMS) * SPB
        idx = pos.astype(int).clip(0, E.shape[1] - 1)
        score = float(np.mean(np.max(E[:, idx], axis=0)))
        if score > best_score:
            best_score, best_off = score, off

    # ---- symbol clock: block-wise timing search
    #
    # A constant tone gives a flat correlator within a symbol, so a
    # sample-by-sample PLL has almost no error signal to steer on. Instead the
    # receiver decodes in blocks of BLOCK symbols and, for each block, picks the
    # integer sample offset that makes those symbols sharpest. Drift shows up as
    # the offset creeping across blocks, and is tracked for free.
    syms = []
    base = best_off
    cur = int(round(best_off))
    limit = E.shape[1] - 2
    peak = float(np.max(E[:, int(best_off)::int(SPB)]))
    floor = 0.05 * peak

    while True:
        best_o, best_score, best_idx = None, -1.0, None
        for o in range(cur - 2, cur + 3):
            pos = base + o + np.arange(BLOCK) * SPB
            idx = pos.astype(int)
            if idx[-1] >= limit:
                idx = idx[idx < limit]
                if len(idx) < 2:
                    break
            score = float(np.mean(np.max(E[:, idx], axis=0)))
            if score > best_score:
                best_o, best_score, best_idx = o, score, idx
        if best_o is None or best_idx is None or len(best_idx) == 0:
            break
        if best_score < floor:
            break
        for i in best_idx:
            syms.append(int(np.argmax(E[:, i])))
        cur = best_o
        base += BLOCK * SPB
        if base + cur >= limit:
            break

    # ---- strip preamble, find sync word, decode payload
    start = None
    for i in range(len(syms) - len(SYNC_SYMS)):
        if tuple(syms[i:i + len(SYNC_SYMS)]) == SYNC_SYMS:
            start = i + len(SYNC_SYMS)
            break
    if start is None:
        return b""

    bits = []
    for s in syms[start:]:
        v = SYM_BITS[s]
        bits.append((v >> 1) & 1)
        bits.append(v & 1)
    return _bytes_of(bits)


def add_noise(x, snr_db, rng=None):
    rng = rng or np.random.default_rng(0)
    p = float(np.mean(x ** 2))
    return (x + rng.normal(0.0, np.sqrt(p / (10 ** (snr_db / 10))), len(x))).astype(np.float32)


def bandpass(x, lo, hi):
    S = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1.0 / FS)
    S[(f < lo) | (f > hi)] = 0
    return np.fft.irfft(S, n=len(x)).astype(np.float32)


GAP_MS = 30          # long enough to survive a filtered channel's ringing


def modulate_multi(frames, amplitude: float = 0.72) -> np.ndarray:
    """Modulate several frames as separate bursts with a short silence between.

    Decoding continuously across a frame boundary makes the timing search slip
    a symbol, which corrupts the next frame's sync word and loses it entirely.
    A gap costs ~12 ms per frame and lets the receiver re-acquire on each one.
    """
    gap = np.zeros(int(FS * GAP_MS / 1000), dtype=np.float32)
    out = []
    for f in frames:
        out.append(modulate(f, amplitude))
        out.append(gap)
    return np.concatenate(out) if out else np.zeros(0, dtype=np.float32)


def _split_bursts(x, min_gap_ms: float = 10.0, rel: float = 0.25):
    """Split a signal into bursts on silence gaps.

    The threshold is derived from the signal's own noise floor, not from its
    peak: with a noisy channel a fixed fraction of the peak sits above the
    noise, and every gap looks like signal.
    """
    env = np.abs(x)
    k = max(1, int(FS * 0.001))
    env = np.convolve(env, np.ones(k) / k, mode="same")
    if not len(env):
        return []
    lo = float(np.percentile(env, 10))
    hi = float(np.percentile(env, 95))
    thr = lo + rel * (hi - lo)
    silent = env < thr
    min_gap = int(FS * min_gap_ms / 1000)
    bursts, start = [], None
    run = 0
    for i, s in enumerate(silent):
        if s:
            run += 1
            if start is not None and run >= min_gap:
                end = i - run
                if end - start > int(0.02 * FS):
                    bursts.append((start, end))
                start = None
        else:
            if start is None:
                start = i
            run = 0
    if start is not None and len(x) - start > int(0.02 * FS):
        bursts.append((start, len(x)))
    return bursts


def demodulate_multi(x) -> bytes:
    """Decode a transmission made of several bursts, re-acquiring on each."""
    out = bytearray()
    for a, b in _split_bursts(np.asarray(x)):
        out += demodulate(np.asarray(x)[a:b])
    return bytes(out)
