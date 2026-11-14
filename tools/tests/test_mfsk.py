#!/usr/bin/env python3
"""Tests for the proprietary 4-FSK modem (app-to-app only).

This is the scheme behind the "Fast" mode: 2400 baud, four tones, two bits per
symbol. It must survive a phone-speaker channel — roughly 300 Hz to 7 kHz with
reverb and noise — and a clock mismatch between two devices.
"""
from __future__ import annotations

import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

from airlink.mfsk import (  # noqa: E402
    modulate, demodulate, modulate_multi, demodulate_multi, add_noise, bandpass,
)
from airlink.modem import resample_fft  # noqa: E402
from airlink.frame import encode_text, encode_frame, encode_file, FrameReader  # noqa: E402


def _trial(rng, n=200, snr=None, band=False, drift=1.0):
    payload = bytes(rng.integers(0, 256, n, dtype=np.uint8))
    sig = modulate(payload)
    if band:
        sig = bandpass(sig, 300, 7000)
    if snr is not None:
        sig = add_noise(sig, snr, rng)
    if drift != 1.0:
        sig = resample_fft(sig, drift)
    got = demodulate(sig)
    return got[:len(payload)] == payload


def test_clean_roundtrip():
    assert _trial(np.random.default_rng(1))


def test_noise():
    rng = np.random.default_rng(2)
    for snr in (20, 15, 12):
        assert _trial(rng, snr=snr), "failed at %d dB" % snr


def test_band_limited():
    assert _trial(np.random.default_rng(3), snr=15, band=True)


def test_clock_drift():
    rng = np.random.default_rng(4)
    for drift in (1.0005, 1.001, 0.999):
        assert _trial(rng, drift=drift), "failed at drift %s" % drift


def test_long_payload():
    assert _trial(np.random.default_rng(5), n=4096, snr=18, band=True)


def test_frames_survive_the_modem():
    frames_in = [encode_text("hello over 4-fsk"), encode_text("second", seq=1, txid=9)]
    sig = modulate_multi(frames_in)
    raw = demodulate_multi(add_noise(bandpass(sig, 300, 7000), 15.0))
    frames = FrameReader().feed(raw)
    assert [f.text() for f in frames] == ["hello over 4-fsk", "second"], frames


def test_file_transfer_through_4fsk():
    import tempfile
    blob = bytes(np.random.default_rng(6).integers(0, 256, 900, dtype=np.uint8))
    with tempfile.TemporaryDirectory() as td:
        src = os.path.join(td, "p.bin")
        open(src, "wb").write(blob)
        bursts = [encode_frame(f) for f in encode_file(src, chunk_size=256, txid=4)]
        raw = demodulate_multi(add_noise(bandpass(modulate_multi(bursts), 300, 7000), 12.0))
        frames = FrameReader().feed(raw)
        from airlink.transfer import FileAssembler
        asm = FileAssembler(os.path.join(td, "out"))
        saved = None
        for f in frames:
            saved = asm.add(f) or saved
        assert saved and open(saved, "rb").read() == blob


def main():
    tests = [(k, v) for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    failed = 0
    for name, fn in tests:
        try:
            fn()
            print("ok    %s" % name)
        except Exception as e:  # noqa: BLE001
            failed += 1
            print("FAIL  %s: %s" % (name, e))
    print("\n%d/%d passed" % (len(tests) - failed, len(tests)))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
