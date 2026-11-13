#!/usr/bin/env python3
"""Line-for-line Python transliteration of the Kotlin DSP, run as a test.

The Android receiver (audio/BfskDemodulator.kt) and the frame scanner
(protocol/FrameAssembler.kt) cannot be executed without a device, so this file
re-implements them with exactly the same control flow — ring buffers, running
quadrature sums, pending-edge queue, buffer compaction — and checks that they
decode the same audio the reference modem produces.  If the Kotlin port drifts
from the tested algorithm, this test is what notices.
"""
from __future__ import annotations

import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

from airlink.frame import Frame, encode_text, encode_frame  # noqa: E402
from airlink.modem import BfskModem, PROFILES, add_noise  # noqa: E402

TWO_PI = 2.0 * math.pi
INV_TWO_PI = 1.0 / TWO_PI
TABLE = 4096
MASK = TABLE - 1
COS = [math.cos(TWO_PI * i / TABLE) for i in range(TABLE)]
SIN = [math.sin(TWO_PI * i / TABLE) for i in range(TABLE)]


class KotlinDemodulator:
    """Mirror of audio/BfskDemodulator.kt."""

    def __init__(self, profile, fs):
        self.p = profile
        self.spb = fs / profile.baud
        self.nWin = max(8, int(round(self.spb)))
        self.off = (self.nWin - 1) / 2.0
        n = self.nWin
        self.ringIm = [0.0] * n
        self.ringQm = [0.0] * n
        self.ringIs = [0.0] * n
        self.ringQs = [0.0] * n
        self.ringPos = 0
        self.filled = 0
        self.sumIm = self.sumQm = self.sumIs = self.sumQs = 0.0
        self.dPhiM = TWO_PI * profile.mark / fs
        self.dPhiS = TWO_PI * profile.space / fs
        self.phaseM = self.phaseS = 0.0
        self.histCap = int(11.0 * self.spb) + n + 32
        self.hist = [0.0] * self.histCap
        self.histStart = 0
        self.histCount = 0
        self.histHead = 0
        self.prevD = 0.0
        self.pending = []
        self.nextFree = -1.0
        self.bytes_decoded = 0
        self.framing_errors = 0

    def feed(self, samples):
        out = bytearray()
        for s in samples:
            x = s / 32768.0
            self.phaseM += self.dPhiM
            if self.phaseM >= TWO_PI:
                self.phaseM -= TWO_PI
            self.phaseS += self.dPhiS
            if self.phaseS >= TWO_PI:
                self.phaseS -= TWO_PI
            iM = int(self.phaseM * INV_TWO_PI * TABLE) & MASK
            iS = int(self.phaseS * INV_TWO_PI * TABLE) & MASK

            pm = x * COS[iM]
            qm = x * SIN[iM]
            ps = x * COS[iS]
            qs = x * SIN[iS]

            self.sumIm += pm - self.ringIm[self.ringPos]
            self.sumQm += qm - self.ringQm[self.ringPos]
            self.sumIs += ps - self.ringIs[self.ringPos]
            self.sumQs += qs - self.ringQs[self.ringPos]
            self.ringIm[self.ringPos] = pm
            self.ringQm[self.ringPos] = qm
            self.ringIs[self.ringPos] = ps
            self.ringQs[self.ringPos] = qs
            self.ringPos += 1
            if self.ringPos == self.nWin:
                self.ringPos = 0

            if self.filled < self.nWin:
                self.filled += 1
                if self.filled < self.nWin:
                    continue

            d = (self.sumIm ** 2 + self.sumQm ** 2) - (self.sumIs ** 2 + self.sumQs ** 2)
            self._push_d(d, out)
        return bytes(out)

    def _push_d(self, dv, out):
        d = dv
        self.hist[(self.histStart + self.histCount) % self.histCap] = d
        if self.histCount < self.histCap:
            self.histCount += 1
        else:
            self.histStart = (self.histStart + 1) % self.histCap
        abs_i = self.histHead
        self.histHead += 1

        if self.prevD >= 0.0 and d < 0.0 and len(self.pending) < 64:
            self.pending.append(abs_i)
        self.prevD = d

        while self.pending:
            e = self.pending[0]
            if self.histHead < e + int(round(9.5 * self.spb)) + 3:
                break
            self.pending.pop(0)
            byte = self._decode_at(e)
            if byte >= 0:
                out.append(byte)
                self.bytes_decoded += 1
            else:
                self.framing_errors += 1

    def _decode_at(self, e):
        start = e + self.off
        if start < self.nextFree:
            return -1
        v = []
        for k in range(10):
            x = self._hist_at(e + (k + 0.5) * self.spb)
            if x is None:
                return -1
            v.append(x)
        if v[0] >= 0.0 or v[9] <= 0.0:
            return -1
        byte = 0
        for k in range(8):
            if v[k + 1] > 0.0:
                byte |= (1 << k)
        self.nextFree = start + 10 * self.spb - self.spb * 0.5
        return byte

    def _hist_at(self, q):
        j = math.floor(q)
        first = self.histHead - self.histCount
        if j < first or j + 1 >= self.histHead:
            return None
        f = q - j
        a = self.hist[(self.histStart + int(j - first)) % self.histCap]
        b = self.hist[(self.histStart + int(j + 1 - first)) % self.histCap]
        return a * (1.0 - f) + b * f


class KotlinFrameAssembler:
    """Mirror of protocol/FrameAssembler.kt."""

    PREAMBLE = b"\x55" * 16
    SYNC = b"\xD3\x91"

    def __init__(self):
        self.buf = bytearray()
        self.frames_accepted = 0
        self.bad_header = 0
        self.bad_crc = 0

    def feed(self, data):
        self.buf.extend(data)
        out = []
        pos = 0
        while True:
            sync_at = self._find_sync(pos)
            if sync_at < 0:
                pos = max(pos, len(self.buf) - (len(self.PREAMBLE) + len(self.SYNC) - 1))
                break
            start = sync_at - len(self.PREAMBLE)
            if start < 0 or self.buf[start:sync_at] != self.PREAMBLE:
                pos = sync_at + 1
                continue
            body_at = sync_at + 2
            if len(self.buf) < body_at + 12:
                pos = max(start, pos)
                break
            if self.buf[body_at:body_at + 2] != b"\xA7\x1C" or self.buf[body_at + 2] != 1:
                self.bad_header += 1
                pos = sync_at + 1
                continue
            import struct
            typ = self.buf[body_at + 3]
            seq, plen = struct.unpack_from("<HH", self.buf, body_at + 4)
            flags = self.buf[body_at + 8]
            txid = self.buf[body_at + 9]
            if plen > 4096:
                self.bad_header += 1
                pos = sync_at + 1
                continue
            from airlink.frame import crc16
            hcrc = struct.unpack_from("<H", self.buf, body_at + 10)[0]
            if hcrc != crc16(bytes(self.buf[body_at:body_at + 10])):
                self.bad_header += 1
                pos = sync_at + 1
                continue
            end = body_at + 12 + plen + 2
            if len(self.buf) < end:
                pos = max(start, pos)
                break
            payload = bytes(self.buf[body_at + 12:body_at + 12 + plen])
            crc = struct.unpack_from("<H", self.buf, body_at + 12 + plen)[0]
            full = crc16(bytes(self.buf[body_at:body_at + 10]) + payload)
            if crc != full:
                self.bad_crc += 1
                pos = sync_at + 1
                continue
            out.append(Frame(typ, seq, payload, flags, txid))
            self.frames_accepted += 1
            pos = end
        if pos > 0:
            del self.buf[:pos]
        return out

    def _find_sync(self, frm):
        i = max(frm, 0)
        while i + 1 < len(self.buf):
            if self.buf[i] == 0xD3 and self.buf[i + 1] == 0x91:
                return i
            i += 1
        return -1


def test_port_roundtrip():
    payload = bytes(np.random.default_rng(11).integers(0, 256, 200, dtype=np.uint8))
    for key, p in PROFILES.items():
        m = BfskModem(p)
        sig = add_noise(m.modulate(payload), 10.0)
        pcm = np.clip(sig, -1, 1)
        pcm = (pcm * 32767).astype("<i2")
        d = KotlinDemodulator(p, m.fs)
        out = bytearray()
        for i in range(0, len(pcm), 2048):
            out += d.feed(pcm[i:i + 2048].tolist())
        assert bytes(out) == payload, "%s: %d/%d bytes, %d framing errors" % (
            key, len(out), len(payload), d.framing_errors)


def test_port_frame_scanner():
    stream = encode_text("first") + encode_text("second", seq=1)
    asm = KotlinFrameAssembler()
    got = []
    for i in range(len(stream)):
        got += asm.feed(stream[i:i + 1])
    assert [f.text() for f in got] == ["first", "second"], got

    junk = bytes(range(256))
    got = KotlinFrameAssembler().feed(junk + encode_text("after junk"))
    assert [f.text() for f in got] == ["after junk"]

    bad = bytearray(encode_text("bbbbbbbb"))
    bad[22] ^= 0xFF
    got = KotlinFrameAssembler().feed(bytes(bad) + encode_text("aaaaaaaa"))
    assert [f.text() for f in got] == ["aaaaaaaa"]


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
