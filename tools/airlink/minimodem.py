"""Bridge to the real `minimodem` binary.

Airlink's on-air format is deliberately just "8N1 BFSK bytes", which is the
one thing minimodem has done since 2011.  So minimodem can be either end of
an Airlink link:

    phone  ---(speaker)--->  mic  ---> minimodem --rx  ---> airlink-cli recv
    phone  <--(speaker)---   mic  <--- minimodem --tx  <--- airlink-cli send

The frame layer lives entirely above the byte stream, so nothing about it
depends on minimodem internals.
"""

from __future__ import annotations

import shutil
import subprocess
from typing import Iterable, Iterator, Optional

from .modem import Profile


class MinimodemError(RuntimeError):
    pass


def find_minimodem() -> Optional[str]:
    return shutil.which("minimodem")


class MinimodemBridge:
    def __init__(self, profile: Profile, binary: Optional[str] = None):
        self.p = profile
        self.binary = binary or find_minimodem()
        if not self.binary:
            raise MinimodemError(
                "minimodem not found on PATH. Install it (apt install minimodem, "
                "brew install minimodem) or use --backend builtin."
            )

    # ------------------------------------------------------------------ tx

    def tx_command(self, out_wav: Optional[str] = None) -> list:
        cmd = [self.binary, "--tx", "-q"] + self.p.minimodem_args()
        if out_wav:
            cmd += ["-f", out_wav]
        return cmd

    def transmit(self, data: bytes, out_wav: Optional[str] = None) -> None:
        """Pipe raw frame bytes into minimodem's modulator (live or to a WAV)."""
        cmd = self.tx_command(out_wav)
        proc = subprocess.run(cmd, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if proc.returncode != 0:
            raise MinimodemError("minimodem tx failed: %s" % proc.stderr.decode("utf-8", "replace"))

    # ------------------------------------------------------------------ rx

    def rx_command(self, in_wav: Optional[str] = None, one_shot: bool = False) -> list:
        cmd = [self.binary, "--rx", "-q"] + self.p.minimodem_rx_args()
        if in_wav:
            cmd += ["-f", in_wav]
        return cmd

    def receive(self, in_wav: Optional[str] = None, chunk: int = 256) -> Iterator[bytes]:
        """Yield decoded bytes as minimodem produces them."""
        cmd = self.rx_command(in_wav)
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        assert proc.stdout is not None
        try:
            while True:
                buf = proc.stdout.read1(chunk) if hasattr(proc.stdout, "read1") else proc.stdout.read(chunk)
                if not buf:
                    break
                yield buf
        finally:
            if proc.poll() is None:
                proc.terminate()
            proc.wait(timeout=5)


def probe() -> dict:
    """Describe what this machine can do — used by `airlink-cli doctor`."""
    path = find_minimodem()
    info = {"minimodem": path, "version": None}
    if path:
        try:
            out = subprocess.run([path, "--version"], capture_output=True, timeout=10)
            info["version"] = (out.stdout or out.stderr).decode("utf-8", "replace").strip().splitlines()[0]
        except Exception:
            pass
    return info
