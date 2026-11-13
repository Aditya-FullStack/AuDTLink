"""Airlink — offline acoustic BFSK messaging toolkit.

Companion tooling for the Airlink Android app.  Everything here speaks the
same wire format as the phone: an 8N1 async BFSK byte stream carrying
Airlink frames.
"""

from .frame import (
    PREAMBLE, SYNC, MAGIC, VERSION,
    T_HELLO, T_TEXT, T_COMMAND, T_FILE_META, T_FILE_CHUNK, T_FILE_END, T_ACK, T_NACK,
    Frame, FrameReader, encode_frame, encode_text, encode_command,
    encode_file, crc16, crc32_of,
)
from .modem import BfskModem, PROFILES, Profile

__all__ = [
    "PREAMBLE", "SYNC", "MAGIC", "VERSION",
    "T_HELLO", "T_TEXT", "T_COMMAND", "T_FILE_META", "T_FILE_CHUNK", "T_FILE_END",
    "T_ACK", "T_NACK",
    "Frame", "FrameReader", "encode_frame", "encode_text", "encode_command",
    "encode_file", "crc16", "crc32_of",
    "BfskModem", "PROFILES", "Profile",
]
__version__ = "1.0.0"
