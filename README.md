# LumaLink: Offline Android File Transfer via Optical QR and Wi-Fi Direct

## Project description and problem statement

LumaLink explores two ways to move files between nearby Android phones without
internet, cloud storage or Bluetooth: camera-based optical transfer and a
QR-authorized Wi-Fi Direct connection. The project began as a light-only transfer
experiment and expanded to include Wi-Fi Direct because animated QR throughput
and sustained camera alignment make large optical transfers impractical.

**Problem statement:** Design and evaluate an Android application that lets users
explicitly choose between network-free animated QR transfer for small files and
local Wi-Fi Direct transfer intended for larger files. Both modes must reconstruct
the original bytes, verify file integrity and save received files automatically,
while making transfer progress, permissions and practical limitations clear.
Wi-Fi Direct uses a local wireless network even though it needs no internet or
shared router; only Light mode meets the original no-network requirement.

The original optical goal was at least 190,000 verified file bytes/s for a 2 MB
image. The current single-QR design does not meet that goal. Wi-Fi Direct is
implemented as a separate transport, but its phone-to-phone reliability and speed
still require testing; it is not a validated performance result.

## Practical applications

These are intended applications, not deployment or reliability guarantees.

| Application | Suitable mode | Practical considerations |
| --- | --- | --- |
| Share short notes, contact files or small documents without a radio connection | Light / QR | Both phones need LumaLink; the complete QR must remain visible. |
| Move a small file where a network connection is unavailable or intentionally avoided | Light / QR | Camera/display use must be permitted; optical contents are visible and unencrypted. |
| Teach visual encoding, packet integrity, fountain recovery and camera processing | Light / QR | Diagnostics and calibration make throughput/readability trade-offs observable. |
| Share photos, PDFs or other larger files nearby without internet or a shared router | Wi-Fi Direct | Intended use pending physical-device validation; no app-imposed Wi-Fi size cap, subject to available storage. |
| Compare optical and local wireless transport in an Android research prototype | Both | Measure setup time, verified throughput, failure rate and usability separately. |

## Transfer modes and requirements

Experimental Android-to-Android file transfer with two explicitly selected modes:
**Light / QR** carries file bytes through animated QR frames; **Wi-Fi Direct**
uses a QR-authorized local Wi-Fi P2P connection. Both phones require LumaLink.
Neither mode requires internet, a cloud service or Bluetooth. Wi-Fi Direct requires
connection negotiation and may show Android's confirmation prompt; Light mode uses no network.
Camera permission is required for scanning. Wi-Fi Direct additionally requests
Nearby Wi-Fi Devices on Android 13+, or fine/coarse Location on Android 8–12.
Android requires INTERNET permission for sockets even for this local transfer;
the app does not contact external servers. Android 8–9 also requires storage permission
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

The dark home screen has lime-accented send/receive cards, an optical-link
illustration and 3/6/15/30/60 FPS choices (default: 3 for acquisition).
Muted sage/teal and violet gradients sit over a slowly moving home background.
Tap the optical illustration for a pulse or use its motion control to pause it.
Motion respects Android's animator setting and is absent from transfer screens.
The layout respects system bars and display cutouts; the home screen scrolls on
small displays. The adaptive QR/light-beam launcher icon also supports Android
13 themed icons.

Receiver status formatting handles progress percent signs safely, including while
waiting for the first packet. Unexpected camera/analysis errors remain on a retry
screen and are logged under `LumaLink` in Logcat, rather than returning home.
The receiver shows a rolling recovered-file rate in decimal KB/s, followed by an
average verified-file rate after SHA-256 verification. The live rate counts solved
file bytes, not duplicated/redundant packets. It can pause during fountain solving.
The verified average runs from the first accepted packet through reconstruction;
it excludes camera acquisition and saving. Very brief transfers show no numeric
rate rather than an unstable estimate. Frame/QR/accepted-symbol rates remain visible.

1. Install this revision on **both phones** and select **Light / QR**. Start with
   a local 1-10 KB file and the default 3 requested FPS, then test 6 FPS.
2. Tap SEND FILE on one phone and RECEIVE FILE on the other.
3. Keep the complete QR and its white border visible, with steady focus and no glare.
4. After SHA-256 verification, the file saves automatically to the matching LumaLink
   folder: images → Pictures, videos → Movies, audio → Music, everything else → Download.
   The completion screen shows the saved path. Android 8–9 may ask for storage permission.
5. Stop the sender manually. Try higher requested rates only while measuring acceptance.

The Light sender now uses optical protocol **v4** with selectable 192/384/768/1024
byte payloads. A file manifest is shown first and after every 16 data symbols;
data symbols carry only the UUID, sequence, raw equation bytes and CRC32, encoded
as Base45. This avoids repeating filenames and full hashes in every data QR.
Legacy v2/v3 receiving is retained, but old receivers cannot read v4. Update both phones.

The QR uses integer physical pixels per module. A deadline-based pacer displays
requested FPS next to measured UI slots/s (including manifests and holds). It does
not catch up with a burst after stalls. The first manifest is held for a second.
Longer display hold has two options: **Off** (default/best throughput) and **Random
later appearance**. Random mode chooses the 2nd, 3rd or 4th appearance independently
for each ID and holds it for at least 500 ms exactly once, never on its first
appearance. It deliberately sends four interleaved appearances per ID so the chosen
later appearance can occur. This adds repeats and can be substantially slower;
it does not guarantee capture or eliminate duplicates. Every-10th hold is removed;
previous every-10th preferences migrate to Off. Manual Hold QR for focus is retained.

Choose **Calibrate optical link** to measure candidate profiles before a file
transfer. The receiver scans stationary and moving test QRs, ranks unique accepted
payload bytes per actual sender-stage second, and displays a result QR. The sender
scans that result to apply the recommendation. Manual profiles remain available.
Calibration uses the same repeat/hold policy as file transmission. With holds off
it takes roughly 35 seconds; random repeats take longer. Earlier calibration
results are invalidated: update both apps and recalibrate after this policy change.
Settings persist across restarts; changing payload/FPS/hold invalidates the stored
calibration estimate. Recalibrate when phones, distance or lighting change.

Before transmission the app displays file size, source block count and a no-loss
first-pass display estimate including manifests and holds. A calibration-based
estimate is shown when available, with repair/repeat caveats. The review screen
keeps optical sending within Light mode; it offers Continue Light and Back only.
Files of 1 MB or more additionally show a prominent optical-duration warning
and require explicit confirmation before continuing with slow optical transfer.
See [calibration, hold and resume instructions](docs/optical-calibration-and-resume.md).

The receiver requests a 1280x720 analysis stream with device fallback, attempts a
centered luminance crop before periodic full-frame searches, and uses bulk row
copies instead of per-pixel buffer access when possible. QR detection handles
orientation without rotating the entire camera image. Tap the camera preview to
refocus. An available camera AE range containing 30 FPS and a small negative
exposure compensation are requested; actual behavior is device-dependent.

Receiver diagnostics show actual analysis resolution, mean copy/decode latency,
packet validation/fountain latency, duplicate/rejected/foreign-session counts and
the last accepted sequence. If QR/s is near zero, investigate capture/readability;
if QR/s is high but accepted/s is low, examine duplicate/rejection counts. A sender
held on one QR naturally produces only one accepted sequence.
Repeat diagnostics now separate consecutive **Re-reads** from **revisited IDs**
seen after a different decoded QR. These are observations, not inferred loss rates.
At 30 camera frames/s and 6 symbols/s, multiple reads of each symbol are expected.
Calibration optimizes accepted payload bytes/s; it does not promise zero duplicates.

Leaving the foreground stops active camera/network work. Light reception journals
validated packets privately; Scan & receive offers Resume or Start new when a
checkpoint exists, including after process death or rotation. Pause & keep progress
returns home without discarding it. Use the same sender filename, file bytes and
payload size; v4 derives a stable ID from those values. FPS/hold can change without
changing block boundaries. Restarted senders repeat their schedule; keeping the
sender paused preserves its current position. Wi-Fi Direct is not resumable.
Only successful automatic saving clears the matching Light checkpoint. A failed
save keeps it for retry; a checkpoint already containing a complete verified file
can be restored and saved without rescanning. Checkpoints are bounded to 64 MB/
120,000 journal records and retain the codec's 100,000 unique-packet limit.
Use local document providers for a fully offline workflow.

## Wi-Fi Direct mode

This uses Android Wi-Fi P2P, not a shared-router LAN or Wi-Fi hotspot workaround.

1. Install this revision on both physical Android phones. Select **Wi-Fi Direct** on both.
2. Enable Wi-Fi and grant the requested permissions. Peer discovery may also need
   the system Location switch enabled; Android 8–12 needs precise Location permission.
3. Sender: tap Send file and choose a local nonempty file. There is no app-imposed
   Wi-Fi size cap; preparation and reception require sufficient temporary storage. The app creates a P2P
   group and displays an authorization QR with the sender's device name.
4. Receiver: tap Scan & receive, scan that QR and confirm the displayed file.
5. Select the sender's device name from the nearby list. Accept Android connection
   prompts if shown. You do not need both phones on the same router.
6. The file transfers as encrypted TCP records; progress and KB/s appear on screen.
   SHA-256 verification precedes automatic saving. The sender receives a verification
   acknowledgement only after successful SHA-256 verification and automatic saving.
7. Sender: choose **Send more files** to reuse the authenticated connection and P2P
   group, or disconnect. The receiver stays on a waiting screen and approves each
   new offer; neither phone needs another authorization scan for that session.

Wi-Fi transport v2 is incompatible with the old Wi-Fi stream: update both phones.
The first authorization QR carries a random 256-bit pairing secret, a session ID,
TCP port and the initial file metadata. The file offer on the encrypted stream is
what the receiver approves. Both endpoints authenticate with nonce-bound HMAC;
fresh directional AES-256-GCM record keys are derived for each TCP connection.
Authenticated device IDs and ordered 64-bit record indices bind the stream.
Anyone who sees the authorization QR can obtain its shared secret; this remains a
PSK protocol, not TLS or certificate identity. Do not expose the QR to others.

After a successful transfer, both phones retain each other's profile and pairing
secret, encrypted by Android Keystore in storage excluded from backup. Open
**Saved devices** in Wi-Fi mode: the sender chooses **Send file to [device]** and
the receiver chooses **Receive from [device]**. DNS-SD discovers the current P2P
service; authentication checks the saved device ID and secret, not just a name or
Wi-Fi address. Another QR scan is unnecessary. Both apps must be open and nearby;
Android connection prompts/permissions may still be necessary. Forget device is
local: remove the profile on both phones to remove trust on both sides. Clearing
app data or reinstalling requires pairing again.

Wi-Fi files use 64-bit lengths and disk-backed 32 KB streaming, with no 10 MB
application cap or whole-file memory allocation. The sender prepares a stable
private temporary snapshot for retries. The receiver verifies a temporary file
before copying it into the type-specific public folder. Available storage and
filesystem/provider limits still apply; reception may need room for both the
complete temporary file and its final saved copy. The optical 10 MB cap remains.
Temporary files are removed on completion/cancellation; startup clears orphaned
Wi-Fi temporary files from a previous process.

Interrupted socket transfers get **two automatic sender retries**, restarting the
current file from byte zero. After those attempts the sender chooses **Retry
again** or **Cancel send**. The receiver reconnects and waits for that decision.
In-session saved-file receipts prevent a lost final acknowledgement from saving
the same transfer twice; these receipts do not survive process death. This is
retry/restart, not persistent Wi-Fi byte-offset resume. Total P2P group/radio loss
may need reconnecting from Saved devices. There is a three-minute setup deadline,
but no fixed total deadline for an active large-file transfer; idle and save-copy
heartbeats keep the session responsive. Foreground operation is required.
Cancel/backgrounding closes sockets, stops discovery and removes app-created P2P
groups. The document picker temporarily preserves an existing session for Send
more. Unrelated pre-existing P2P groups are rejected rather than removed.

Wi-Fi Direct radio discovery, saved-device reconnection, negotiation and real speed
are **not yet device-validated**. JVM loopback tests cover streaming files above
10 MB, repeated files on one connection, interruption/restart and lost save ACKs;
they do not establish Wi-Fi throughput or guarantee 190 KB/s.
See [Wi-Fi Direct validation](docs/wifi-direct-validation.md).

## Improvements over the supplied prototype

- Protocol v2 validates complete packets before changing receiver state, uses full
  UUID session IDs and a canonical SHA-256 packet integrity field. It rejects v1.
- Duplicates and invalid packets cannot reserve sequence IDs or initialize sessions.
  Contradictions, failed file hashes and packet exhaustion produce visible retry states.
- Decoder state is bounded: 10 MB files, 100,000 accepted packet IDs and 4,096 pending
  equations. Old unresolved equations are evicted, which can increase recovery overhead.
- Encoder uses a two-entry bounded queue and display callbacks, with 3/6/15/30/60
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
not silently discard the verified file. Light checkpoints persist across process
death; in-memory Wi-Fi Direct transfers do not.

## Performance and reliability limits

**190 KB/s and 60 distinct optically received frames per second have not been
demonstrated on phones.** There are no measured phone-pair results yet. The
following QR capacity limits apply to Light mode; Wi-Fi Direct has separate
physical-device validation gates.

The current 192-byte optical mode caps at 576 equation bytes/s at 3 accepted
symbols/s, or 1,152 bytes/s at 6, before loss, padding, source repeats and fountain
overhead. This trades capacity for readability; use Wi-Fi Direct for large files.
The legacy 420-byte payloads yield at most 25,200 bytes/s at 60 unique accepted packets/s,
before fountain overhead. A 2,000,000-byte file takes at least 79.4 seconds in this
ideal case. One standard binary QR per frame cannot meet 190,000 bytes/s at 60 FPS:
even its maximum 2,953-byte capacity is only 177,180 bytes/s before overhead.
See [DENSO WAVE capacity](https://www.qrcode.com/en/about/standards.html).

The fountain distribution remains a basic deterministic XOR scheme, not robust
soliton or RaptorQ. It may stall or recover inefficiently on large transfers.
In default v4, metadata repeats periodically in manifests; data frames use compact
Base45 packets. Camera resolution, AE frame range and exposure compensation are
requested, but device support and actual capture behavior require measurement.
Multiple QRs per frame and custom optical grids are future experiments, not
implemented features.

In Light mode, packet checksums detect accidental corruption; they do not authenticate senders.
Light mode has no encryption. Nearby cameras can observe transmissions. Receiver retry
creates a fresh local decoder; it is not an acknowledgement to the sender.

## Conclusion: Light / QR transfer only

LumaLink demonstrates the mechanics of offline optical file transfer: converting
file bytes into animated QR packets, recovering missing blocks with basic XOR
fountain equations, verifying SHA-256 and saving the reconstructed file. Small
KB-sized files are the most practical use of the current design. User-reported
phone trials show that MB-sized transfers require too many symbols and too much
time holding the phones aligned; these observations are not controlled benchmarks.

**768 bytes per QR symbol** (not 786) is the proposed practical upper working
profile for this project's conclusion. It means file/equation payload, excluding
packet and QR encoding overhead. Larger payloads produce denser QR patterns that
can become difficult or unreadable for a phone camera under the tested conditions.
This is not a universal QR capacity limit or a validated maximum across phones:
the app still offers an experimental 1024-byte profile.

**30 FPS** is the proposed upper experimental operating setting for this conclusion.
Faster changes can become unreadable when exposure, rolling shutter or decoding
cannot keep up. Reliable reception at 30 FPS has not been established, and even
lower rates may lose symbols. The app retains a 60 FPS experimental option; its
presence does not establish usable 60 FPS optical reception.

At 768 payload bytes and 30 unique accepted symbols/s, the ideal payload ceiling
is **23,040 bytes/s (23.04 decimal KB/s)** before manifests, padding, repeats,
losses and fountain overhead. A 2,500,000-byte file needs at least **3,256 source
blocks** and roughly **109 seconds** at that ideal rate. Actual completion takes
longer. Thousands of symbols and sustained alignment make large handheld optical
transfers inconvenient; the current implementation cannot promise 190 KB/s.

Wi-Fi Direct was added to explore a more practical path for larger files. These
optical conclusions do not establish Wi-Fi Direct performance: discovery,
connection negotiation, compatibility, throughput and successful saving still
need testing on physical phones.

### Learnings

- Optimize unique recovered file bytes per second, rather than requested FPS or
  total QR scans. Larger payloads reduce block counts but can reduce readability.
- Camera capture rate, QR decode rate and accepted-symbol rate are different
  measurements. Multiple camera reads of one QR are expected, not direct evidence
  of packet loss; calibration does not eliminate duplicates.
- Fountain coding helps with missing symbols but cannot decode blurred images;
  the current basic peeling decoder can also stall on unresolved equations.
- Longer holds add capture opportunities at the cost of throughput. Calibration
  must account for both payload density and the actual repeat/hold schedule.
- Integrity checks, resumable reception, duration estimates and explicit transport
  selection matter as much as the encoding algorithm for practical usability.

### Possible research areas

- **Adaptive optical transmission:** select payload, dwell and error correction
  from measured reception; investigate a feedback channel where requirements allow.
- **Capture and decoding:** benchmark focus/exposure, rolling shutter, scan regions
  and native decoders across phone pairs under repeatable conditions.
- **Higher-capacity visual formats:** investigate multi-QR layouts, color encoding
  and custom optical grids, including their robustness and processing costs.
- **Recovery efficiency:** compare degree distributions, bounded generations and
  indexed decoding with the current basic XOR fountain implementation.
- **Usability and resource costs:** study hands-free alignment, interruption/resume,
  battery use, thermal effects and realistic completion times.
- **Wi-Fi Direct validation:** independently test peer discovery, authorization,
  cancellation, integrity, automatic saving and measured end-to-end throughput
  before making reliability or speed claims for that mode.

## Optical protocols

Default v4 uses `LL4M:` manifests and `LL4D:` data frames. Both encode their binary
body plus CRC32 with Base45. The manifest body is UTF-8 JSON with UUID, filename,
original size, block size and SHA-256. The UUID is deterministically derived from
filename (up to 100 characters), SHA-256 and block size. Data bodies contain UUID
(two big-endian longs), sequence (int) and raw payload: only 24 bytes of overhead
including CRC, versus the previous full metadata on every packet. The decoder
must receive a matching manifest before accepting data; periodic repeats allow
late joining. Valid manifests do not reset recovered blocks. Session identity is
not sender authentication; optical contents and private checkpoint data are not
encrypted by this app.

Legacy compact v3 frames start with `LL3:` followed by Base45 alphanumeric text.
Decoded fields are: version byte (3), UUID (two big-endian longs), original length
(int), block size (unsigned short), sequence (int), SHA-256 (32 raw bytes), UTF-8
filename length (unsigned short), filename bytes, raw padded equation payload and
CRC32 (int). Block size is bounded to 128–1024 bytes; the current sender uses 192.
CRC covers every byte before the CRC. File length remains capped at 10 MB. The
receiver normalizes valid packets to the existing peeling decoder with a bound
block size. Neither optical protocol authenticates or encrypts file contents.

Legacy v2 remains supported for reception and cross-language regression fixtures:

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
[Local validation results](docs/validation-results.md): the latest implementation
revision passed 59 Kotlin tests, a debug build and lint with zero errors and 28
warnings. These software checks do not establish physical-phone throughput.
See [implementation status](docs/implementation-plan.md) for audit-to-file mapping
and remaining priorities.
Priority: validate builds and API compatibility, quantify fountain overhead at scale,
measure the optical channel, then prototype a higher-capacity multi-QR transport.
