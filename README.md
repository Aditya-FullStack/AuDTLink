# Airlink

Peer-to-peer messaging and file transfer between Android phones over **sound
alone** — no Wi-Fi, no Bluetooth, no cellular, no pairing, no accounts, no
server.

Text, commands and small files are encoded as Binary FSK audio and played out
of one phone's speaker into another's microphone. It works across a room, in
airplane mode, with the SIM removed, and it is 100% protocol-compatible with
[`minimodem`](http://www.whence.com/minimodem/) — a Linux box with a sound
card is a first-class peer.

```
   ┌──────────┐        ~1200 Hz / 2200 Hz        ┌──────────┐
   │  phone   │  ▁▃▅▇▅▃▁▃▅▇▅▃▁▃▅▇▅▃▁▃▅▇▅▃▁▃▅▇  │  phone   │
   │ (sender) │ ──────────────────────────────► │(receiver)│
   └──────────┘        or 18.5 / 19.5 kHz       └──────────┘
        ▲                                            ▲
        └──────────── minimodem --rx / --tx ─────────┘
```

## Contents

```
android/                  Android Studio project (Kotlin + Jetpack Compose)
  app/src/main/java/ai/airlink/
    audio/                BFSK modulator, streaming demodulator, AudioTrack/Record
    protocol/             frame codec, CRC-16, stream scanner, file reassembly
    ui/                   Compose screen
tools/                    Linux/macOS companion
  airlink/                frame + modem + minimodem bridge libraries
  airlink_cli.py          the command line tool
  tests/                  loopback tests, including a port-fidelity test
docs/PROTOCOL.md          the wire format, byte by byte
```

## Android app

Open `android/` in Android Studio (Giraffe or newer) and run. Or:

```bash
cd android
gradle wrapper --gradle-version 8.7   # once, if gradle-wrapper.jar is absent
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

minSdk 24, targetSdk 34, Kotlin 2.0.21, AGP 8.5.2, Compose BOM 2024.09.03.

The manifest requests exactly two permissions — `RECORD_AUDIO` and
`MODIFY_AUDIO_SETTINGS`. There is no `INTERNET` permission, so the app
cannot phone home even in principle.

### Using it

1. Pick the same profile on both phones (Audible / Audible slow / Ultrasonic).
2. On the receiving phone tap the **mic**; on the sending phone type and tap
   **send**, or attach a file.
3. Hold them 10–30 cm apart, speaker to microphone, roughly facing.

The status strip shows frames accepted, frames rejected, bytes decoded and a
five-bar signal meter, so you can see whether the channel is working before
you blame the app.

## Companion CLI

```bash
pip install numpy                     # the only dependency

python3 tools/airlink_cli.py doctor          # profiles + whether minimodem is present
python3 tools/airlink_cli.py selftest        # modulate/demodulate every profile

# offline: message -> WAV -> message
python3 tools/airlink_cli.py wav    --text "hello from linux" --out hello.wav
python3 tools/airlink_cli.py listen --wav hello.wav

# a file, end to end
python3 tools/airlink_cli.py wav    --file photo.jpg --out photo.wav
python3 tools/airlink_cli.py listen --wav photo.wav --outdir ./inbox
```

The built-in modem needs no sound card and no minimodem; it is the same
algorithm as the phone's, so WAVs produced here decode on the phone and vice
versa.

## Talking to minimodem

Airlink's on-air format is 8N1 BFSK bytes. That is the one thing minimodem
has always done, so minimodem can be either end of a link — no custom
software, no plugins, no patched builds.

**Receive on Linux** (the phone transmits):

```bash
minimodem --rx -q 1200 -M 1200 -S 2200 | python3 tools/airlink_cli.py decode -
```

or let the CLI drive it, which also reassembles files:

```bash
python3 tools/airlink_cli.py recv --profile bell202 --outdir ./inbox
```

**Transmit from Linux** (the phone receives):

```bash
python3 tools/airlink_cli.py send --text "hello phone" --profile bell202
# equivalently, by hand:
python3 tools/airlink_cli.py encode --text "hello phone" \
  | minimodem --tx -q 1200 -M 1200 -S 2200
```

**Ultrasonic**, both directions — note `-b 1400`, which widens minimodem's
receive filter. Its 50 Hz default is narrower than this profile's 1 kHz
shift and will not hear the tones:

```bash
minimodem --rx -q 300 -M 18500 -S 19500 -b 1400 | python3 tools/airlink_cli.py decode -
python3 tools/airlink_cli.py encode --text "quiet" \
  | minimodem --tx -q 300 -M 18500 -S 19500
```

To capture to a file instead of live audio, add `-f capture.wav` to either
side; `listen` reads WAVs at whatever rate they were recorded.

## Testing

```bash
python3 tools/tests/test_airlink.py       # protocol + modem, all profiles
python3 tools/tests/test_kotlin_port.py   # the Android DSP, transliterated
```

The first suite covers CRC vectors, frame round-trips, byte-at-a-time
streaming, resync after garbage, corruption rejection, modulation and
demodulation at 6 dB SNR, band-limited (300–3400 Hz) channels, 0.4 % clock
drift, and a 700-byte file transfer with integrity checking.

The second suite exists because the Kotlin receiver cannot be executed
without a device: it re-implements `BfskDemodulator.kt` and
`FrameAssembler.kt` with identical control flow — ring buffers, running
quadrature sums, pending-edge queue, buffer compaction — and checks they
decode the same audio the reference modem produces. It is the closest thing
to a unit test the Android DSP gets, and it has already caught one
off-by-one in the window centring.

## Honest limits

* **One-way.** The app transmits and receives but does not do automatic
  repeat-request yet; a corrupted file is reported, not retried. Press send
  again.
* **Slow.** ~120 B/s audible, ~30 B/s ultrasonic. 10 KB is a minute or
  seven. That is the medium.
* **Short range.** Ultrasonic especially: most phone speakers roll off above
  18 kHz, so keep the devices close (10–30 cm) and quiet.
* **Not private.** Plaintext frames, audible to any microphone in the room.
  Anyone with minimodem can read your messages. Use it for convenience, not
  confidentiality.
* **Half duplex.** Transmitting stops reception.
