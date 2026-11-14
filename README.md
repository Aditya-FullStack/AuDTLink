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

The manifest requests `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS` and `INTERNET`.
`INTERNET` was added for one feature only — the update check on the About
screen, which GETs the public release JSON from GitHub. No message, file or
usage data is ever sent anywhere; if you want the absolute guarantee back,
delete that permission and the update check will fail with a clear error while
everything else keeps working.

### Using it

1. Pick the same mode on both phones — **Audible** or **Ultrasound (Beta)**.
2. On the receiving phone tap the **mic**; on the sending phone type and tap
   **send**, or attach a file.
3. Hold them 10–30 cm apart, speaker to microphone, roughly facing.

Two modes, nothing else to configure:

| Mode | Tone | Speed | Notes |
|---|---|---|---|
| **Audible** | Bell 202, 1200/2200 Hz | ~120 B/s | The tolerant default. Works at a few metres. |
| **Audible fast** | 2400 baud, 2000/4400 Hz | ~240 B/s | Twice as fast. Bell 202's 1 kHz spacing collapses at 2400 baud, so the tones move apart; needs a decent speaker and a quiet room. |
| **Ultrasound (Beta)** | 18.5/19.5 kHz | ~30 B/s | Silent to most adults. Keep phones ~10 cm apart. |

Sending can be paused with the round button in the composer, which turns into
pause/play while a transmission is running. Pausing takes effect within about
40 ms — the frame being sent is abandoned and re-sent from the top when you
resume, because half a frame is no use to the receiver.

The gear icon opens **About**: version, what the app does, its permissions, and
an update check that reads the newest GitHub release and offers the APK. That
check is the only network call the app makes — see the note on `INTERNET` below.

**Dark mode** is a switch under About → Appearance. It applies immediately and
is remembered across launches (stored in SharedPreferences), and the status bar
icon colours follow it so nothing becomes unreadable at night.

A received file shows up as a bubble — tap it and the system chooser opens it in
whatever app can handle the type, file manager included. The file is handed over
through a `FileProvider`, so the receiving app gets read access to that one file
and nothing else in the app's storage.

The app checks for a newer release **once every 24 hours, on open** (the
timestamp is stored, so relaunching does not re-check). If one exists it shows a
dialog with the version and release notes, and a Download button. A failed
background check stays silent; only the manual button reports errors.

It also carries a **"Use it from a computer"** section: the exact `minimodem`
receive and transmit commands for whichever mode is selected, with the mark and
space frequencies, plus copy buttons. No desktop app to install — a Linux,
macOS or BSD box with `minimodem` is a full peer.

The status line under the mode cards reports what the receiver is doing, and
each message shows its frame type, so a silent failure looks different from a
deaf one.

## Build types

CI builds the **release** variant: R8 minification, resource shrinking, and the
shared signing key — the APK that gets published to GitHub Releases is the one
you install. `./gradlew assembleDebug` still works locally when you want a
debuggable build with no shrinking.

## Pushing changes

`tools/push_to_github.py` sends **only the files that differ**, as one commit:

```bash
python3 tools/push_to_github.py --dry-run        # what would go up
python3 tools/push_to_github.py -m "v4.5: ..."   # push it
```

It hashes every local file as a git blob and compares that against the remote
tree, so unchanged files are never re-uploaded and never re-trigger CI. One
commit means one build.

This matters more than it sounds. Uploading files one at a time through the
Contents API creates one commit — and one workflow run — *per file*. Pushing
twelve files that way produced eleven builds of half-updated trees, every one
of which failed on a missing method or field, plus the single build that had
the whole change. Batching removes that entire class of failure.

## Versions and signing

Every build is signed with the same key (`android/keystore/airlink.p12`), so a
new version installs straight over the old one — no uninstall, no "app not
installed" error, and messages stay put.

To cut a release, run one command:

```bash
python3 tools/bump_version.py 4.1     # -> versionCode 4, versionName 4.1, label "AuDTLink v4.1"
git commit -am "v4.1" && git push
```

It rewrites the three places that have to stay in sync:

| File | Field | v2 (current) |
|---|---|---|
| `app/build.gradle.kts` | `versionCode` | `8` |
| `app/build.gradle.kts` | `versionName` | `"4.4"` |
| `res/values/strings.xml` | `app_name` | `AuDTLink v4.4` |

The launcher label carries the version, which is the quickest way to tell two
sideloaded builds apart on the same phone.

That keystore is a **sideloading key**: it is committed to the repository and
its password (`airlink`) is in the build file. That is deliberate — it makes
builds reproducible for anyone who clones the repo — but it means the key is
public. Do not publish this app to a store with it, and do not treat an APK
signed with it as proof of authorship.

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
python3 tools/tests/test_airlink.py            # protocol + modem, all profiles
python3 tools/tests/test_kotlin_port.py        # the Android DSP, transliterated
python3 tools/tests/test_minimodem_interop.py  # real minimodem, both directions
```

The interop test is the one that settles "does minimodem actually hear this?".
It writes a WAV with our modulator, hands it to `minimodem --rx`, and requires
the exact bytes back; then it has `minimodem --tx` produce a WAV and requires
our demodulator to read it. Both directions, all three profiles. It skips with
exit 0 when minimodem is absent, and CI installs minimodem and runs it on every
push, so the compatibility claim is checked rather than asserted.

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
