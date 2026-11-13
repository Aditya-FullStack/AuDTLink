# Airlink wire protocol — v1

Airlink moves bytes with sound. Everything below the frame layer is a plain
8N1 asynchronous serial stream, which is precisely what `minimodem -8`
produces and consumes; everything above it is the framing described here.

```
┌──────────────────────────┐
│  app: text / command /   │
│  file chunks, ACK, NACK  │
├──────────────────────────┤
│  Airlink frame layer     │  preamble · sync · header · payload · CRC16
├──────────────────────────┤
│  8N1 async serial        │  start bit, 8 data bits LSB-first, stop bit
├──────────────────────────┤
│  BFSK                    │  mark tone = 1, space tone = 0
├──────────────────────────┤
│  speaker / microphone    │
└──────────────────────────┘
```

## 1. Physical layer

Binary frequency-shift keying. Bit `1` is the **mark** frequency, bit `0` the
**space** frequency. The line idles at mark.

| Profile      | Baud | Mark     | Space    | Audible | Payload/chunk | Bytes/s |
|--------------|-----:|---------:|---------:|:-------:|--------------:|--------:|
| `bell202`    | 1200 | 1200 Hz  | 2200 Hz  | yes     | 512           | ~120    |
| `bell103`    |  300 | 1270 Hz  | 1070 Hz  | yes     | 128           | ~30     |
| `ultrasonic` |  300 | 18 500 Hz| 19 500 Hz| no      | 96            | ~30     |

Capture and playback run at 48 000 Hz mono, 16-bit. 48 kHz keeps 19.5 kHz
well inside the band (2.46 samples per cycle) instead of 1.1 kHz below
Nyquist as it would be at 44.1 kHz. The receiver is written against the
*actual* capture rate, so a device that only offers 44.1 kHz still works.

### 8N1 framing

```
idle  ┌──────────────────────────────────────────────────┐ idle
      │  start │ D0 D1 D2 D3 D4 D5 D6 D7 │ stop          │
mark  ┘        └─────────────────────────┘               └──────
       space   data bits, LSB first        mark
```

* start bit = space, 1 bit
* data bits = 8, LSB first
* stop bit = mark, 1 bit
* no parity

Ten bits on the wire per byte. `minimodem` calls this `-8` / `--ascii`.

## 2. Frame layer

Every frame is self-contained: a receiver can join mid-transmission and pick
up at the next one.

```
 offset  size   field
 ------  ----   --------------------------------------------------
      0    16   PREAMBLE   0x55 × 16   (bit pattern 1010…, trains the clock)
     16     2   SYNC       0xD3 0x91   (delimiter, never appears in preamble)
     18    10   header body
     28     2   header CRC CRC-16/CCITT-FALSE over the 10 header bytes
     30   N      payload    0..4096 bytes
  30+N     2   payload CRC CRC-16/CCITT-FALSE over header body + payload
```

Header body, little-endian:

| Offset | Size | Field     | Notes |
|-------:|-----:|-----------|-------|
| 0 | 1 | `magic0`  | `0xA7` |
| 1 | 1 | `magic1`  | `0x1C` |
| 2 | 1 | `version` | `0x01` |
| 3 | 1 | `type`    | see below |
| 4 | 2 | `seq`     | uint16, per-transmission counter |
| 6 | 2 | `len`     | uint16, payload length |
| 8 | 1 | `flags`   | reserved, 0 |
| 9 | 1 | `txid`    | uint8, random per transfer; disambiguates concurrent senders |

Two CRCs, on purpose: the header CRC lets a receiver reject a frame with a
corrupt length field immediately instead of blocking for 4 KB of garbage that
will never arrive. A frame that fails either CRC is dropped, the scanner
advances one byte, and the next preamble+sync resynchronises.

CRC parameters: polynomial `0x1021`, init `0xFFFF`, no reflection, no final
XOR (the `123456789` → `0x29B1` variant).

## 3. Frame types

| Type   | Value | Payload |
|--------|------:|---------|
| `HELLO`      | 0x01 | UTF-8 app name / version |
| `TEXT`       | 0x10 | UTF-8 message |
| `COMMAND`    | 0x11 | UTF-8 single-line command |
| `FILE_META`  | 0x20 | `txid:u32, size:u32, chunk:u16, nameLen:u16, name[nameLen], crc32:u32` |
| `FILE_CHUNK` | 0x21 | `txid:u32, offset:u32, data[...]` |
| `FILE_END`   | 0x22 | `txid:u32, crc32:u32` |
| `ACK`        | 0x30 | `txid:u32, status:u8` |
| `NACK`       | 0x31 | `txid:u32, reason:u8` |

`crc32` is the zlib/IEEE CRC-32 of the whole file. The receiver writes nothing
to disk until the declared size has been filled **and** the CRC matches, so a
truncated transfer never appears in the inbox.

## 4. A file transfer

```
sender                                    receiver
  FILE_META  seq=0   name, size, crc32
  FILE_CHUNK seq=1   offset 0
  FILE_CHUNK seq=2   offset chunk
  …
  FILE_END   seq=n   crc32
```

Chunk size comes from the profile (512 / 128 / 96 bytes) so that one frame
stays under about five seconds of air time — long enough to be efficient,
short enough that a hand slipping does not cost a minute of retransmission.

## 5. Timing budget

| Profile | frame of one max-size chunk | 10 KB file |
|---|---|---|
| `bell202`    | ~4.5 s | ~1 min 40 s |
| `bell103`    | ~4.6 s | ~6 min 20 s |
| `ultrasonic` | ~4.2 s | ~6 min 40 s |

These are physics, not implementation: 30 bytes per second is 30 bytes per
second. Airlink is for keys, coordinates, OTPs, short notes and a photo you
really need to move — not for syncing a music library.

## 6. What v1 does not do

* **No ARQ.** ACK/NACK are defined and parsed, but the app transmits
  one-way and shows you the error counter; retransmission is a human
  decision (press send again). Adding stop-and-wait ARQ is the obvious v2.
* **No encryption.** Frames are plaintext. Anyone in earshot with a
  microphone and `minimodem` can read them — that is the point of an open,
  documented format, but do not send secrets over it.
* **Half duplex.** Transmitting stops reception; two phones should take
  turns.
