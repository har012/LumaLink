# LumaLink

Experimental Android-to-Android optical file transfer. One phone displays QR
symbols; the other decodes them with its camera. Both phones require LumaLink.
There is no pairing, return acknowledgement, network transport or Bluetooth.
The app uses camera permission. Android 8–9 additionally requires storage permission
for automatic saving; that permission is capped at API 28 and is not used on Android 10+.

## Build

Requires JDK 17, Android SDK platform 35 and build tools 34.0.0. Set `JAVA_HOME`
and `ANDROID_HOME` (or configure `sdk.dir` in an untracked `local.properties`).

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

On Windows use `gradlew.bat`. The official Gradle 8.9 wrapper is included with
distribution checksum verification. GitHub Actions runs tests, lint and a debug
build and publishes the debug APK as a workflow artifact. Install
`app/build/outputs/apk/debug/app-debug.apk` on both phones. Minimum Android: 8.0
(API 26). Physical-device behavior is not yet validated.

## Use

The home screen has separate send/receive cards and 6/15/30/60 FPS choices.
The layout respects system bars and display cutouts; the home screen scrolls on
small displays. The adaptive QR/light-beam launcher icon also supports Android
13 themed icons.

1. Start with a local 1-10 KB file and the default 6 requested FPS.
2. Tap SEND FILE on one phone and RECEIVE FILE on the other.
3. Keep the complete QR and its white border visible, with steady focus and no glare.
4. After SHA-256 verification, the file saves automatically to the matching LumaLink
   folder: images → Pictures, videos → Movies, audio → Music, everything else → Download.
   The completion screen shows the saved path. Android 8–9 may ask for storage permission.
5. Stop the sender manually. Try higher requested rates only while measuring acceptance.

Leaving the foreground stops active work. Return HOME and restart after interruption.
Unsaved received files and transfer state do not survive process death or rotation.
Use local document providers for a fully offline workflow.

## Improvements over the supplied prototype

- Protocol v2 validates complete packets before changing receiver state, uses full
  UUID session IDs and a canonical SHA-256 packet integrity field. It rejects v1.
- Duplicates and invalid packets cannot reserve sequence IDs or initialize sessions.
  Contradictions, failed file hashes and packet exhaustion produce visible retry states.
- Decoder state is bounded: 10 MB files, 100,000 accepted packet IDs and 4,096 pending
  equations. Old unresolved equations are evicted, which can increase recovery overhead.
- Encoder uses a two-entry bounded queue and display callbacks, with 6/15/30/60
  requested FPS; actual presentation and optical acceptance require measurement.
- Module-sized QR bitmaps use a four-module quiet zone and disabled bitmap filtering.
- Receiver decodes luminance with reusable buffers/readers, respecting crop, stride
  and rotation. It reports analyzed, decoded and uniquely accepted packet rates.
- Session tokens prevent stale UI callbacks; STOP cancels encoding and unbinds camera.
- File size is bounded during background reading. Verified files save off the UI
  thread into type-specific public LumaLink folders. Android 10+ uses MediaStore;
  Android 8–9 uses permission-protected shared storage and media scanning. Duplicate
  filenames do not overwrite existing files; failed writes are cleaned up.

Automatic routing uses common filename extensions (case-insensitive), not content
inspection. Unknown types go to Download/LumaLink. There is no save-location picker.
Saving errors keep a Retry saving action; denying legacy storage permission does
not silently discard the verified file. Unsaved files are not persisted across process death.

## Performance and reliability limits

**This does not implement or demonstrate 190 KB/s or 60 distinct optically received
frames per second.** There are no measured phone-pair results yet.

420-byte payloads yield at most 25,200 bytes/s at 60 unique accepted packets/s,
before fountain overhead. A 2,000,000-byte file takes at least 79.4 seconds in this
ideal case. One standard binary QR per frame cannot meet 190,000 bytes/s at 60 FPS:
even its maximum 2,953-byte capacity is only 177,180 bytes/s before overhead.
See [DENSO WAVE capacity](https://www.qrcode.com/en/about/standards.html).

The fountain distribution remains a basic deterministic XOR scheme, not robust
soliton or RaptorQ. It may stall or recover inefficiently on large transfers.
Metadata and Base64 still repeat in every QR. Camera FPS/exposure/resolution are
not explicitly negotiated. Multiple QRs per frame and custom optical grids are
future experiments, not implemented features.

Packet checksums detect accidental corruption; they do not authenticate senders.
There is no encryption. Nearby cameras can observe transmissions. Receiver retry
creates a fresh local decoder; it is not an acknowledgement to the sender.

## Protocol v2

Each QR contains JSON fields `v=2`, `id` (UUID), `n` (filename), `s` (file length),
`k` (block count), `h` (file SHA-256), `q` (nonnegative sequence), `d` (URL-safe
Base64 of exactly 420 bytes), and `c` (packet SHA-256). The packet digest hashes
UTF-8 length-prefixed string representations of `v,id,n,s,k,h,q,d`, in that order.
Lengths count JVM UTF-16 code units. Numeric fields must be JSON integers.
The final block is zero-padded; the reconstructed file is truncated to `s`.
`q<k` selects a systematic block; subsequent sequences deterministically select
XOR block sets. The receiver peels singleton equations and saves only a file that
matches `h`. Any protocol change requires updating both phones.

## Validation and next work

The [Python optical lab](tools/README.md) adds seeded fountain simulations, exact
byte reconstruction, cross-language protocol fixtures and throughput-capacity
calculations. Python is a desktop development tool; the phone app remains Kotlin.

```sh
python -m unittest discover -s tools -v
python tools/optical_lab.py simulate --trials 30 --output .dist/simulation.json
python tools/optical_lab.py capacity
```

See [the validation protocol](docs/benchmark-protocol.md) for regression coverage,
physical-device checks, offline verification and end-to-end throughput gates.
[Local validation results](docs/validation-results.md): 10 passing tests, successful
debug build, lint with zero errors and 14 warnings; APK permissions checked.
See [implementation status](docs/implementation-plan.md) for audit-to-file mapping
and remaining priorities.
Priority: validate builds and API compatibility, quantify fountain overhead at scale,
measure the optical channel, then prototype a higher-capacity multi-QR transport.
