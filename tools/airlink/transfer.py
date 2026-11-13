"""Reassembles a FILE_META / FILE_CHUNK* / FILE_END sequence back into a file."""

from __future__ import annotations

import os
import re
from typing import Dict, Optional

from .frame import Frame, T_FILE_META, T_FILE_CHUNK, T_FILE_END, parse_file_meta, parse_file_chunk, crc32_of

_SAFE = re.compile(r"[^A-Za-z0-9._-]+")


def safe_name(name: str, fallback: str = "received.bin") -> str:
    name = os.path.basename(name.replace("\\", "/")).strip()
    name = _SAFE.sub("_", name).lstrip(".")
    return name[:120] or fallback


class _Xfer:
    def __init__(self, meta: dict):
        self.meta = meta
        self.buf = bytearray(meta["size"])
        self.received = 0
        self.gaps = 0


class FileAssembler:
    def __init__(self, outdir: str = "."):
        self.outdir = outdir
        self.active: Dict[int, _Xfer] = {}
        self.completed = []

    def add(self, f: Frame) -> Optional[str]:
        """Feed one frame; returns the written path when a file completes."""
        if f.type == T_FILE_META:
            m = parse_file_meta(f.payload)
            self.active[m["txid"]] = _Xfer(m)
            return None
        if f.type == T_FILE_CHUNK:
            txid, off, data = parse_file_chunk(f.payload)
            x = self.active.get(txid)
            if x is None:
                return None
            end = min(off + len(data), len(x.buf))
            if end > off:
                x.buf[off:end] = data[: end - off]
                x.received += end - off
            return None
        if f.type == T_FILE_END:
            txid = int.from_bytes(f.payload[:4], "little")
            x = self.active.pop(txid, None)
            if x is None:
                return None
            want = int.from_bytes(f.payload[4:8], "little")
            got = crc32_of(bytes(x.buf))
            if want != got or x.received < x.meta["size"]:
                raise ValueError(
                    "file %s failed integrity: %d/%d bytes, crc %08x != %08x"
                    % (x.meta["name"], x.received, x.meta["size"], got, want)
                )
            os.makedirs(self.outdir, exist_ok=True)
            path = os.path.join(self.outdir, safe_name(x.meta["name"]))
            base, ext = os.path.splitext(path)
            n = 1
            while os.path.exists(path):
                path = "%s-%d%s" % (base, n, ext)
                n += 1
            with open(path, "wb") as fh:
                fh.write(x.buf)
            self.completed.append(path)
            return path
        return None
