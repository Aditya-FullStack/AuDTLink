"""Airlink frame layer (v1).

The physical layer is a plain 8N1 async BFSK byte stream, which is exactly
what `minimodem --tx/--rx` produces and consumes.  This module wraps that
byte stream in self-describing frames so a receiver can resynchronise at any
point, detect corruption, and know what it is looking at.

Wire layout of one frame::

    +-----------+--------+----------------+---------------+--------+
    | PREAMBLE  |  SYNC  |  HEADER (12 B) |    PAYLOAD    | CRC16  |
    | 16 x 0x55 | D3 91  |                |  0..4096 B    |  2 B   |
    +-----------+--------+----------------+---------------+--------+

Header::

    0..1   magic       A7 1C
    2      version     01
    3      type        see T_* below
    4..5   seq         uint16 LE
    6..7   payload len uint16 LE
    8      flags
    9      txid        uint8, random per transfer
    10..11 header CRC  CRC-16/CCITT-FALSE over bytes 0..9

The trailing CRC is CRC-16/CCITT-FALSE over header bytes 0..9 followed by the
payload.  The separate header CRC lets a receiver throw away a frame with a
corrupt length field immediately instead of waiting for 4 KB of garbage.
"""

from __future__ import annotations

import struct
import zlib
from dataclasses import dataclass, field
from typing import Iterable, Iterator, List, Optional

PREAMBLE = b"\x55" * 16          # 0x55 LSB-first = 10101010, a perfect clock
SYNC = b"\xD3\x91"               # frame delimiter, deliberately not 0x55
MAGIC = b"\xA7\x1C"
VERSION = 0x01

T_HELLO = 0x01        # payload: UTF-8 app name / version
T_TEXT = 0x10         # payload: UTF-8 message
T_COMMAND = 0x11      # payload: UTF-8 one-line command
T_FILE_META = 0x20    # payload: transfer id, size, crc32, name
T_FILE_CHUNK = 0x21   # payload: transfer id, offset, data
T_FILE_END = 0x22     # payload: transfer id, crc32
T_ACK = 0x30          # payload: acked transfer id (4) + status byte
T_NACK = 0x31         # payload: transfer id (4) + reason byte

TYPE_NAMES = {
    T_HELLO: "HELLO",
    T_TEXT: "TEXT",
    T_COMMAND: "COMMAND",
    T_FILE_META: "FILE_META",
    T_FILE_CHUNK: "FILE_CHUNK",
    T_FILE_END: "FILE_END",
    T_ACK: "ACK",
    T_NACK: "NACK",
}

MAX_PAYLOAD = 4096
HEADER_LEN = 12
OVERHEAD = len(PREAMBLE) + len(SYNC) + HEADER_LEN + 2

_HEADER = struct.Struct("<2sBBHHBB")      # 10 bytes, header CRC appended after
_HCRC = struct.Struct("<H")


def crc16(data: bytes, crc: int = 0xFFFF) -> int:
    """CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflection)."""
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc


def crc32_of(data: bytes) -> int:
    return zlib.crc32(data) & 0xFFFFFFFF


@dataclass
class Frame:
    type: int
    seq: int = 0
    payload: bytes = b""
    flags: int = 0
    txid: int = 0

    @property
    def type_name(self) -> str:
        return TYPE_NAMES.get(self.type, "0x%02X" % self.type)

    def text(self) -> str:
        return self.payload.decode("utf-8", "replace")

    def __str__(self) -> str:
        preview = self.text() if self.type in (T_TEXT, T_COMMAND, T_HELLO) else self.payload[:24].hex()
        if len(preview) > 48:
            preview = preview[:45] + "..."
        return f"<{self.type_name} seq={self.seq} txid={self.txid:02X} len={len(self.payload)}> {preview}"


def encode_frame(f: Frame) -> bytes:
    if len(f.payload) > MAX_PAYLOAD:
        raise ValueError(f"payload too large: {len(f.payload)} > {MAX_PAYLOAD}")
    body = _HEADER.pack(
        MAGIC, VERSION & 0xFF, f.type & 0xFF, f.seq & 0xFFFF,
        len(f.payload), f.flags & 0xFF, f.txid & 0xFF,
    )
    hdr = body + _HCRC.pack(crc16(body))
    crc = crc16(body + f.payload)
    return PREAMBLE + SYNC + hdr + f.payload + _HCRC.pack(crc)


class FrameReader:
    """Incremental byte-stream to frame decoder.

    Feed it whatever comes out of the modem; it yields frames and silently
    drops anything that does not validate, resynchronising on the next
    preamble+sync it sees.
    """

    def __init__(self, max_payload: int = MAX_PAYLOAD, keep_history: int = 512):
        self._buf = bytearray()
        self._max_payload = max_payload
        self._keep = keep_history
        self.stats = {"frames": 0, "bad_header": 0, "bad_crc": 0, "bytes": 0}

    def feed(self, data: bytes) -> List[Frame]:
        self._buf.extend(data)
        self.stats["bytes"] += len(data)
        out: List[Frame] = []
        while True:
            f, consumed = self._try_parse()
            if consumed == 0:
                break
            del self._buf[:consumed]
            if f is not None:
                out.append(f)
                self.stats["frames"] += 1
        # never let the buffer grow without bound on pure noise
        if len(self._buf) > self._keep and len(self._buf) > OVERHEAD:
            del self._buf[: len(self._buf) - self._keep]
        return out

    def _try_parse(self):
        buf = self._buf
        i = buf.find(SYNC)
        while i >= 0:
            start = i - len(PREAMBLE)
            if start >= 0 and buf[start:i] == PREAMBLE:
                if len(buf) < i + 2 + HEADER_LEN:
                    return None, 0            # need more bytes
                body = bytes(buf[i + 2: i + 2 + 10])
                (magic, ver, typ, seq, plen, flags, txid) = _HEADER.unpack(body)
                if magic != MAGIC or ver != VERSION or plen > self._max_payload:
                    self.stats["bad_header"] += 1
                    i = buf.find(SYNC, i + 1)
                    continue
                (hcrc,) = _HCRC.unpack(bytes(buf[i + 12: i + 14]))
                if hcrc != crc16(body):
                    self.stats["bad_header"] += 1
                    i = buf.find(SYNC, i + 1)
                    continue
                total = i + 2 + HEADER_LEN + plen + 2
                if len(buf) < total:
                    return None, 0            # need more bytes
                payload = bytes(buf[i + 14: i + 14 + plen])
                (crc,) = _HCRC.unpack(bytes(buf[i + 14 + plen: total]))
                if crc != crc16(body + payload):
                    self.stats["bad_crc"] += 1
                    i = buf.find(SYNC, i + 1)
                    continue
                return Frame(typ, seq, payload, flags, txid), total
            # a sync with no preamble in front of it is noise
            i = buf.find(SYNC, i + 1)
        # keep the tail in case a sync straddles a feed() boundary
        return None, max(0, len(buf) - (len(SYNC) + len(PREAMBLE) - 1))


def encode_text(text: str, seq: int = 0, txid: int = 0) -> bytes:
    return encode_frame(Frame(T_TEXT, seq, text.encode("utf-8"), 0, txid))


def encode_command(cmd: str, seq: int = 0, txid: int = 0) -> bytes:
    return encode_frame(Frame(T_COMMAND, seq, cmd.encode("utf-8"), 0, txid))


def encode_file(path: str, chunk_size: int = 256, txid: int = 0,
                name: Optional[str] = None, start_seq: int = 0) -> Iterator[Frame]:
    """Yield the frame sequence for one file transfer."""
    import os
    data = open(path, "rb").read()
    name = name or os.path.basename(path)
    nb = name.encode("utf-8")[:120]
    meta = struct.pack("<IIHH", txid, len(data), chunk_size, len(nb)) + nb + struct.pack("<I", crc32_of(data))
    seq = start_seq
    yield Frame(T_FILE_META, seq, meta, 0, txid)
    seq += 1
    for off in range(0, len(data), chunk_size):
        chunk = data[off: off + chunk_size]
        yield Frame(T_FILE_CHUNK, seq, struct.pack("<II", txid, off) + chunk, 0, txid)
        seq += 1
    yield Frame(T_FILE_END, seq, struct.pack("<II", txid, crc32_of(data)), 0, txid)


def parse_file_meta(payload: bytes):
    if len(payload) < 12:
        raise ValueError("short FILE_META")
    txid, size, chunk, nlen = struct.unpack("<IIHH", payload[:12])
    name = payload[12: 12 + nlen].decode("utf-8", "replace")
    (crc,) = struct.unpack("<I", payload[12 + nlen: 16 + nlen])
    return {"txid": txid, "size": size, "chunk": chunk, "name": name, "crc32": crc}


def parse_file_chunk(payload: bytes):
    if len(payload) < 8:
        raise ValueError("short FILE_CHUNK")
    txid, off = struct.unpack("<II", payload[:8])
    return txid, off, payload[8:]
