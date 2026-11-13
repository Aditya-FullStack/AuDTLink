#!/usr/bin/env python3
"""airlink-cli — Linux/macOS companion for the Airlink Android app.

Examples
--------
  # what does this machine support?
  airlink-cli doctor

  # turn a message into a WAV you can play at the phone (no hardware needed)
  airlink-cli wav --text "hello from linux" --out hello.wav

  # decode a WAV the phone recorded
  airlink-cli listen --wav recording.wav --outdir ./inbox

  # talk to the phone through the sound card, via minimodem
  airlink-cli send --text "hello" --profile bell202
  airlink-cli recv --profile bell202 --outdir ./inbox

  # send a file (chunk size is taken from the profile)
  airlink-cli send --file photo.jpg --profile bell103
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from airlink.frame import (  # noqa: E402
    Frame, FrameReader, encode_frame, encode_text, encode_command, encode_file,
    T_TEXT, T_COMMAND, T_FILE_META, T_FILE_CHUNK, T_FILE_END, T_ACK, T_NACK, T_HELLO,
)
from airlink.modem import BfskModem, PROFILES, read_wav, write_wav  # noqa: E402
from airlink.transfer import FileAssembler  # noqa: E402
from airlink.minimodem import MinimodemBridge, MinimodemError, probe  # noqa: E402


def _profile(key: str):
    if key not in PROFILES:
        sys.exit("unknown profile %r (choose from %s)" % (key, ", ".join(PROFILES)))
    return PROFILES[key]


def _frames_for(args) -> bytes:
    txid = args.txid if args.txid is not None else random.randint(1, 255)
    if args.file:
        p = _profile(args.profile)
        out = bytearray()
        for f in encode_file(args.file, chunk_size=args.chunk or p.chunk, txid=txid):
            out += encode_frame(f)
        return bytes(out)
    if args.command:
        return encode_command(args.command, txid=txid)
    if not args.text:
        sys.exit("give --text, --command or --file")
    return encode_text(args.text, txid=txid)


def cmd_doctor(args):
    info = probe()
    print("airlink-cli %s" % __import__("airlink").__version__)
    print("minimodem : %s%s" % (info["minimodem"] or "NOT FOUND",
                                (" (%s)" % info["version"]) if info["version"] else ""))
    print()
    print("%-11s %-8s %-7s %-7s %-9s %s" % ("profile", "baud", "mark", "space", "bytes/s", "minimodem rx args"))
    for k, p in PROFILES.items():
        print("%-11s %-8g %-7g %-7g %-9.1f %s" % (
            k, p.baud, p.mark, p.space, p.bytes_per_second,
            " ".join(["--rx"] + p.minimodem_rx_args())))
    print()
    print("hint: the phone must use the same profile and the same 8N1 framing.")
    return 0


def cmd_encode(args):
    data = _frames_for(args)
    if args.out:
        with open(args.out, "wb") as fh:
            fh.write(data)
        print("%d bytes -> %s" % (len(data), args.out))
    else:
        sys.stdout.buffer.write(data)
    return 0


def cmd_decode(args):
    data = sys.stdin.buffer.read() if args.infile in (None, "-") else open(args.infile, "rb").read()
    r = FrameReader()
    frames = r.feed(data)
    for f in frames:
        if args.json:
            print(json.dumps({"type": f.type_name, "seq": f.seq, "txid": f.txid,
                              "len": len(f.payload), "payload": f.payload.hex()}))
        else:
            print(f)
    print("-- %d frames, %d bytes, %d bad header, %d bad crc" %
          (len(frames), len(data), r.stats["bad_header"], r.stats["bad_crc"]), file=sys.stderr)
    return 0


def cmd_wav(args):
    p = _profile(args.profile)
    data = _frames_for(args)
    m = BfskModem(p, amplitude=args.volume)
    sig = m.modulate(data)
    write_wav(args.out, sig, m.fs)
    dur = len(sig) / m.fs
    print("%d frame bytes, %.1f s of audio, %d Hz -> %s" % (len(data), dur, m.fs, args.out))
    return 0


def cmd_listen(args):
    p = _profile(args.profile)
    sig, fs = read_wav(args.wav)
    m = BfskModem(p, sample_rate=fs)
    t0 = time.time()
    raw = m.demodulate(sig)
    r = FrameReader()
    frames = r.feed(raw)
    asm = FileAssembler(args.outdir)
    print("decoded %d bytes in %.1f s from %.1f s of audio" %
          (len(raw), time.time() - t0, len(sig) / fs), file=sys.stderr)
    for f in frames:
        _report(f, args, asm)
    print("-- %d frames, %d bad header, %d bad crc" %
          (len(frames), r.stats["bad_header"], r.stats["bad_crc"]), file=sys.stderr)
    return 0


def _report(f: Frame, args, asm: FileAssembler):
    path = asm.add(f)
    if args.json:
        print(json.dumps({"type": f.type_name, "seq": f.seq, "txid": f.txid,
                          "len": len(f.payload),
                          "text": f.text() if f.type in (T_TEXT, T_COMMAND, T_HELLO) else None}))
        return
    if f.type == T_FILE_CHUNK:
        if args.verbose:
            print(f)
        return
    if path:
        print("[%s] saved %s" % (time.strftime("%H:%M:%S"), path))
        return
    print("[%s] %s" % (time.strftime("%H:%M:%S"), f))


def cmd_send(args):
    p = _profile(args.profile)
    data = _frames_for(args)
    if args.to_wav:
        sig = BfskModem(p, amplitude=args.volume).modulate(data)
        write_wav(args.to_wav, sig, p.sample_rate)
        print("%d bytes -> %s (%.1f s)" % (len(data), args.to_wav, len(sig) / p.sample_rate))
        return 0
    bridge = MinimodemBridge(p)
    print("transmitting %d bytes over %s ..." % (len(data), p.label))
    bridge.transmit(data)
    print("done")
    return 0


def cmd_recv(args):
    p = _profile(args.profile)
    bridge = MinimodemBridge(p)
    r = FrameReader()
    asm = FileAssembler(args.outdir)
    print("listening on %s — Ctrl-C to stop" % p.label, file=sys.stderr)
    try:
        for chunk in bridge.receive(in_wav=args.wav):
            for f in r.feed(chunk):
                _report(f, args, asm)
    except KeyboardInterrupt:
        pass
    print("-- %d frames, %d bad header, %d bad crc" %
          (r.stats["frames"], r.stats["bad_header"], r.stats["bad_crc"]), file=sys.stderr)
    return 0


def cmd_selftest(args):
    from airlink.modem import add_noise, bandpass
    import numpy as np
    ok = True
    for key, p in PROFILES.items():
        m = BfskModem(p)
        payload = bytes(random.getrandbits(8) for _ in range(64))
        sig = m.modulate(payload)
        clean = m.demodulate(sig) == payload
        noisy = m.demodulate(add_noise(sig, args.snr)) == payload
        print("%-11s clean=%-5s snr%gdB=%s" % (key, clean, args.snr, noisy))
        ok = ok and clean and noisy
    print("PASS" if ok else "FAIL")
    return 0 if ok else 1


def build_parser():
    ap = argparse.ArgumentParser(prog="airlink-cli", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p):
        p.add_argument("--profile", default="bell202", help="bell202 | bell103 | ultrasonic")
        p.add_argument("--text", help="text message")
        p.add_argument("--command", help="command string")
        p.add_argument("--file", help="file to send")
        p.add_argument("--chunk", type=int, default=0, help="payload bytes per chunk")
        p.add_argument("--txid", type=int, default=None, help="transfer id 1..255")
        p.add_argument("--volume", type=float, default=0.75)
        p.add_argument("--json", action="store_true")
        p.add_argument("--verbose", action="store_true")

    s = sub.add_parser("doctor"); s.set_defaults(fn=cmd_doctor)
    s = sub.add_parser("encode"); common(s); s.add_argument("--out"); s.set_defaults(fn=cmd_encode)
    s = sub.add_parser("decode"); s.add_argument("infile", nargs="?")
    s.add_argument("--json", action="store_true"); s.set_defaults(fn=cmd_decode)
    s = sub.add_parser("wav"); common(s); s.add_argument("--out", required=True); s.set_defaults(fn=cmd_wav)
    s = sub.add_parser("listen"); s.add_argument("--wav", required=True)
    s.add_argument("--profile", default="bell202"); s.add_argument("--outdir", default=".")
    s.add_argument("--json", action="store_true"); s.add_argument("--verbose", action="store_true")
    s.set_defaults(fn=cmd_listen)
    s = sub.add_parser("send"); common(s); s.add_argument("--to-wav"); s.set_defaults(fn=cmd_send)
    s = sub.add_parser("recv"); s.add_argument("--profile", default="bell202")
    s.add_argument("--wav", default=None); s.add_argument("--outdir", default=".")
    s.add_argument("--json", action="store_true"); s.add_argument("--verbose", action="store_true")
    s.set_defaults(fn=cmd_recv)
    s = sub.add_parser("selftest"); s.add_argument("--snr", type=float, default=8.0)
    s.set_defaults(fn=cmd_selftest)
    return ap


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        return args.fn(args)
    except MinimodemError as e:
        sys.exit("error: %s" % e)
    except BrokenPipeError:
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
