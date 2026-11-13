#!/usr/bin/env python3
"""End-to-end tests for the Airlink wire format and modem.

Run:  python3 tools/tests/test_airlink.py      (or: pytest tools/tests)
"""
from __future__ import annotations

import os
import sys
import tempfile

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

from airlink.frame import (  # noqa: E402
    Frame, FrameReader, encode_frame, encode_text, encode_file, crc16, crc32_of,
    T_TEXT, T_FILE_END,
)
from airlink.modem import BfskModem, PROFILES, add_noise, bandpass, resample_fft  # noqa: E402
from airlink.transfer import FileAssembler  # noqa: E402


def test_crc16_vector():
    assert crc16(b"123456789") == 0x29B1, "CRC-16/CCITT-FALSE check value"


def test_frame_roundtrip():
    data = encode_text("hello world") + encode_text("second", seq=1, txid=7)
    r = FrameReader()
    frames = r.feed(data)
    assert len(frames) == 2
    assert frames[0].text() == "hello world"
    assert frames[1].seq == 1 and frames[1].txid == 7


def test_byte_at_a_time():
    data = encode_text("streamed")
    r = FrameReader()
    out = []
    for i in range(len(data)):
        out += r.feed(data[i:i + 1])
    assert len(out) == 1 and out[0].text() == "streamed"


def test_resync_after_garbage():
    junk = bytes(range(256)) * 2
    data = junk + encode_text("after junk")
    frames = FrameReader().feed(data)
    assert len(frames) == 1 and frames[0].text() == "after junk"


def test_corrupt_payload_rejected():
    good = encode_text("aaaaaaaa")
    bad = bytearray(encode_text("bbbbbbbb"))
    bad[22] ^= 0xFF                       # flip a payload byte
    frames = FrameReader().feed(bytes(bad) + good)
    assert [f.text() for f in frames] == ["aaaaaaaa"]


def test_modem_roundtrip_all_profiles():
    payload = bytes(np.random.default_rng(1).integers(0, 256, 96, dtype=np.uint8))
    for key, p in PROFILES.items():
        m = BfskModem(p)
        sig = m.modulate(payload)
        assert m.demodulate(sig) == payload, key
        assert m.demodulate(add_noise(sig, 6.0)) == payload, key + " @6dB"


def test_band_limited_channel():
    payload = bytes(np.random.default_rng(2).integers(0, 256, 64, dtype=np.uint8))
    for key, p in PROFILES.items():
        m = BfskModem(p)
        sig = m.modulate(payload)
        # a phone speaker/mic chain: ~300 Hz to ~7 kHz, which the 2400 baud
        # profile needs because its space tone sits at 4.4 kHz.
        lo, hi = (300, 7000) if p.audible else (17500, 21000)
        assert m.demodulate(add_noise(bandpass(sig, m.fs, lo, hi), 10.0)) == payload, key


def test_clock_drift():
    """Receiver clock 0.4 % fast — per-byte resync must absorb it."""
    payload = bytes(np.random.default_rng(3).integers(0, 256, 120, dtype=np.uint8))
    for key, p in PROFILES.items():
        m = BfskModem(p)
        sig = m.modulate(payload)
        got = m.demodulate(resample_fft(sig, 1.004))
        assert got == payload, "%s drift decode failed (%d/%d bytes)" % (key, len(got), len(payload))


def test_file_transfer_end_to_end():
    blob = bytes(np.random.default_rng(4).integers(0, 256, 700, dtype=np.uint8))
    with tempfile.TemporaryDirectory() as td:
        src = os.path.join(td, "payload.bin")
        open(src, "wb").write(blob)
        p = PROFILES["bell202"]
        stream = b"".join(encode_frame(f) for f in encode_file(src, chunk_size=p.chunk, txid=9))
        m = BfskModem(p)
        sig = add_noise(m.modulate(stream), 12.0)
        raw = m.demodulate(sig)
        frames = FrameReader().feed(raw)
        asm = FileAssembler(os.path.join(td, "out"))
        saved = None
        for f in frames:
            saved = asm.add(f) or saved
        assert saved, "no file reassembled (got %d frames)" % len(frames)
        assert open(saved, "rb").read() == blob
        assert crc32_of(blob) == crc32_of(open(saved, "rb").read())


def test_file_integrity_detects_loss():
    blob = b"x" * 400
    with tempfile.TemporaryDirectory() as td:
        src = os.path.join(td, "p.bin")
        open(src, "wb").write(blob)
        frames = list(encode_file(src, chunk_size=100, txid=3))
        asm = FileAssembler(td)
        for i, f in enumerate(frames):
            if i == 2:
                continue        # lose a chunk in the middle
            if i == len(frames) - 1:
                continue        # add FILE_END separately below
            asm.add(f)
        try:
            asm.add(frames[-1])
        except ValueError:
            return
        raise AssertionError("expected integrity failure")


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
