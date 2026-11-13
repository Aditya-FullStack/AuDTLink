#!/usr/bin/env python3
"""Real interop against the real `minimodem` binary.

Two directions, both required:

    our modulator  -> WAV -> minimodem --rx  -> must return the exact bytes
    minimodem --tx -> WAV -> our demodulator -> must return the exact bytes

Skipped (exit 0) when minimodem is not installed, so it is safe to run
anywhere; CI installs it and therefore always exercises it.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from airlink.modem import BfskModem, PROFILES, read_wav, write_wav  # noqa: E402

PAYLOAD = bytes(range(0x20, 0x80)) + bytes([0x00, 0xFF, 0x55, 0xAA, 0x0D, 0x0A])


def _have_minimodem() -> bool:
    return shutil.which("minimodem") is not None


def check(profile_key: str) -> None:
    p = PROFILES[profile_key]
    with tempfile.TemporaryDirectory() as td:
        # --- direction 1: ours -> minimodem
        ours = os.path.join(td, "ours.wav")
        m = BfskModem(p)
        write_wav(ours, m.modulate(PAYLOAD), m.fs)

        cmd = ["minimodem", "--rx", "-q"] + p.minimodem_rx_args() + ["-f", ours]
        r = subprocess.run(cmd, capture_output=True, timeout=180)
        if r.returncode != 0:
            raise AssertionError("minimodem rx exited %d: %s" % (r.returncode, r.stderr.decode()[:300]))
        if r.stdout != PAYLOAD:
            raise AssertionError(
                "%s: minimodem decoded %d/%d bytes\n  first diff at %s\n  got  %s\n  want %s"
                % (profile_key, len(r.stdout), len(PAYLOAD),
                   next((i for i in range(min(len(r.stdout), len(PAYLOAD)))
                         if r.stdout[i] != PAYLOAD[i]), "length"),
                   r.stdout[:24].hex(), PAYLOAD[:24].hex())
            )

        # --- direction 2: minimodem -> ours
        theirs = os.path.join(td, "theirs.wav")
        cmd = ["minimodem", "--tx", "-q"] + p.minimodem_args() + ["-f", theirs]
        r = subprocess.run(cmd, input=PAYLOAD, capture_output=True, timeout=180)
        if r.returncode != 0:
            raise AssertionError("minimodem tx exited %d: %s" % (r.returncode, r.stderr.decode()[:300]))

        sig, fs = read_wav(theirs)
        got = BfskModem(p, sample_rate=fs).demodulate(sig)
        if got != PAYLOAD:
            raise AssertionError(
                "%s: our demodulator read %d/%d bytes of minimodem's %d Hz output"
                % (profile_key, len(got), len(PAYLOAD), fs)
            )


def main() -> int:
    if not _have_minimodem():
        print("SKIP  minimodem not installed")
        return 0
    ver = subprocess.run(["minimodem", "--version"], capture_output=True)
    print("minimodem:", (ver.stdout or ver.stderr).decode("utf-8", "replace").strip().splitlines()[0])

    failed = 0
    for key in ("bell202", "bell103", "ultrasonic"):
        try:
            check(key)
            print("ok    %-11s both directions, %d bytes each way" % (key, len(PAYLOAD)))
        except Exception as e:  # noqa: BLE001
            failed += 1
            print("FAIL  %s: %s" % (key, e))
    print("\n%s" % ("PASS" if not failed else "%d profile(s) failed" % failed))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
