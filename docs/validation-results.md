# Local validation - 2026-11-17

Environment: Windows, Temurin JDK 17.0.21, Gradle 8.9, Android SDK platform 35,
Android build tools 34.0.0 (AGP default), AGP 8.7.3 and Kotlin 2.0.21.

Command: `gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug`

- Build: successful; debug APK produced.
- Unit tests: 10 passed, 0 failures, 0 errors. The fountain regression test includes
  30 seeded trials combining late joining, random/burst drops, reordering and duplicates.
- Lint: 0 errors, 14 warnings (newer dependencies, missing application icon and
  hardcoded/localization-related UI strings). Dependencies remain pinned to the
  prototype's versions for this baseline.
- APK permissions inspected with `aapt dump permissions`: camera and AndroidX's
  app-scoped dynamic receiver permission. No Internet or Bluetooth permissions.
- Official Gradle wrapper JAR SHA-256 matched the published Gradle checksum:
  `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`.

These checks do not establish physical-device operation, screen presentation rate,
camera throughput, Android-version compatibility in practice or 190 KB/s transfer
performance. Follow benchmark-protocol.md for those gates.

## Python lab addition

Python 3.11.9: eight standard-library unittest cases passed. The shared fixture
checks index selection, payloads and UTF-16-length-prefixed packet hashes in both
Python and Kotlin, including a non-BMP filename and maximum Java Int sequence.
After integration, all 11 Kotlin unit tests passed, Android lint completed and
the debug APK build succeeded. The Android runtime code was unchanged by this addition.

Default simulation: 30/30 successful 12,345-byte trials; median accepted/source
expansion 1.6167. Settings: 30% random loss, seven-of-37 periodic burst loss,
10% duplicates, nine-symbol reorder windows, seeds 0-29 and no late join.

A separate 2,000,000-byte trial (seed 0, same channel settings, 10,000 transmitted
symbols) failed within its budget: 5,624 unique accepted symbols recovered 3,846
of 4,762 source blocks. This demonstrates why small-fixture success does not
establish large-file recovery efficiency. These results are simulations only.

## UI and icon revision

Updated native home screen, selectable frame-rate chips, transfer-screen styling,
system-bar/cutout insets and a scrollable home layout. Added an adaptive vector
launcher icon with a monochrome layer for Android 13+ themed icons. Navigation-bar
theme attributes are version-qualified to retain API 26 compatibility.

`testDebugUnitTest lintDebug assembleDebug` succeeded: all 11 Kotlin tests passed;
lint reported zero errors and 12 warnings. No phone or emulator was connected,
so the rendered UI and launcher appearance still need a physical-device check.

## Automatic saving revision

Verified files now route by extension into Pictures/LumaLink, Movies/LumaLink,
Music/LumaLink or Download/LumaLink. API 29+ uses pending MediaStore entries;
API 26–28 requests legacy write permission and uses unique public file paths.
Failed writes are cleaned up and expose a retry action. File writes continue
through activity backgrounding. Process death recovery remains unsupported.

`testDebugUnitTest lintDebug assembleDebug` passed: 13 Kotlin tests, zero test
failures and zero lint errors (12 warnings). New tests cover type routing,
case-insensitive extensions, unsafe filenames and unknown-type fallback. APK
inspection confirmed WRITE_EXTERNAL_STORAGE has maxSdkVersion 28; there are no
Internet or Bluetooth permissions. Physical MediaStore/legacy storage behavior,
permission denial, low storage, collisions and gallery indexing remain device gates.

## Receiver formatting fix and dark UI

The receiver previously interpolated its percentage into a String.format template.
At zero progress, the resulting `0% | ...` text treated the percent sign as a format
conversion and threw UnknownFormatConversionException (`Conversion = '|'`). This
could happen even before a QR was detected. TransferStatus now uses a constant
template with `%d%%` and passes progress as an argument. Regression tests cover
0, 1, 50 and 100 percent, plus zero elapsed time.

Camera/analysis exceptions are logged and show a persistent retry screen. The home
and transfer screens now use a navy/lime palette with a decorative optical-link
illustration. System bar appearance is configured for the dark background.

All 15 Kotlin tests passed; lint completed with zero errors and 14 warnings, and
the debug APK built successfully. Physical receiving and the redesigned layout
still need a phone check; no phone was connected for this validation.

## Ambient motion, gradients, icons and receive speed

Added home-only ambient violet/teal lighting, a moving optical pulse, a tappable
pulse illustration and an explicit pause control. Animations use bounded 20 Hz
redraws, stop on detach/window hiding, and respect disabled system animators.
Transfer screens do not host these animations. Send/receive cards now use muted
sage/teal and violet/navy gradients and custom paper-plane/scanner vector icons.

Live speed is a rolling recovered-byte rate in decimal KB/s. It ignores duplicate
and redundant packets, excludes padding even when the final block is solved first,
and can fall to zero while the decoder waits for useful equations. The final
verified average spans first accepted packet through completed reconstruction and
hash verification, excluding acquisition and saving. Transfers under 250 ms show
an insufficient-duration message rather than a numeric rate.

All 20 Kotlin tests passed, with zero lint errors (14 warnings) and a successful
debug APK build. Emulator installation was attempted, but its package service
failed with Broken pipe and subsequently reported "Can't find service: package".
Visual interaction and physical optical throughput remain unverified for this revision.

## Optional Wi-Fi Direct transport

Added explicit Light / QR and Wi-Fi Direct selection. Wi-Fi Direct uses Android
P2P group creation/discovery/connection, a QR-shared session key, explicit receiver
confirmation and peer selection, authenticated AES-GCM TCP records, file hash
verification, completion acknowledgement, progress/speed and automatic saving.
The original optical data path remains selectable without Wi-Fi permission grants.

`testDebugUnitTest lintDebug assembleDebug` passed with 28 Kotlin tests, zero
failures, zero lint errors and 21 warnings. Production DirectFileTransfer was
tested over actual JVM loopback sockets, including a 2,000,000-byte exact round
trip, unauthenticated-client rejection and hash-failure acknowledgement rejection.
Record tests cover tampering, wrong keys, ordering, truncation and size bounds.

APK permission inspection confirms the newly intentional local-network/Wi-Fi
permissions, Location permissions capped at API 32, Nearby Wi-Fi Devices and legacy
storage capped at API 28. No Bluetooth permission is declared. Older camera-only
permission results in this document refer to earlier revisions.

No two-phone Wi-Fi Direct radio test has been performed. Discovery, OEM connection
prompts, API-specific permissions, cancellation/group cleanup and achieved KB/s
remain the physical-device gates in wifi-direct-validation.md. Loopback results
are not evidence of radio throughput or meeting the 190 KB/s target.

## Optical acquisition changes - 2026-11-18

In response to S24 reception stalling at one accepted block even at 6 FPS, the
default Light sender now uses 192-byte compact v3 packets, binary metadata/raw
payload, Base45 alphanumeric QR encoding and CRC32. Legacy v2 reception and its
Python interoperability fixtures remain supported. New v3 senders require the
updated app on the receiving phone. Integer-module rendering avoids fractional
QR scaling. First-symbol acquisition hold, a manual hold/resume control and
repeated systematic packets supplement the fountain equations.

Camera analysis requests 1280x720 with device fallback, full-preview FIT_CENTER,
an available AE FPS range containing 30, center autofocus/exposure metering and
tap-to-focus. Small negative exposure compensation is requested when supported.
The decoder uses center-crop luminance, bulk row copies and periodic full-frame
fallback; it no longer rotates all pixels before QR detection. Diagnostics expose
resolution, decode/packet-FEC latency, duplicates, rejects, foreign sessions and
the last accepted sequence. The default is now 3 FPS, with 6/15/30/60 still selectable.

`testDebugUnitTest lintDebug assembleDebug` passed: 34 Kotlin tests, zero failures,
zero lint errors and 22 warnings. Tests include known Base45 vectors/all byte
pairs, compact exact-byte reconstruction, checksum rejection, repeated-source
recovery, smaller generated QR module counts and decoding all four right-angle
rotations, plus the strided/cropped bulk-copy path. Wi-Fi stream tests still pass.

No physical phone was connected. Synthetic QR image tests do not demonstrate
Samsung S24 acquisition, true camera FPS, short exposure or improved optical
throughput. Those remain device tests in benchmark-protocol.md. Smaller symbols
trade payload capacity for readability; no high-speed claim is made.

## QR receiving unit tests - 2026-11-18

Added QrReceivingTest, which generates real QR luminance images from Sender frames,
decodes with production FrameDecoder.decodePixels and feeds production Receiver
until exact bytes and SHA-256 match. The main test uses three seeds, a 10,000-byte
file, late joining, random/burst drops, packet reordering/duplicates, all four
right-angle rotations, mild pixel noise and a luminance gradient.

Command: `gradlew.bat --no-daemon testDebugUnitTest --tests com.lumalink.app.QrReceivingTest`

Result: BUILD SUCCESSFUL; 3 tests passed, 0 failures/errors, JUnit suite time 1.363 s.

| Seed | QR images attempted | Decoded | Dropped after joining | Unique accepted | File hash |
| --- | ---: | ---: | ---: | ---: | --- |
| 0 | 110 | 110 | 67 | 93 | PASS |
| 1 | 93 | 91 | 53 | 81 | PASS |
| 2 | 113 | 112 | 59 | 95 | PASS |

The other two tests reject a visually readable QR with invalid packet CRC without
capturing the session, and verify that blank images do not prevent decoding the
next valid QR. Duplicate packets do not increment accepted counts.

This is a JVM unit/integration test of generated luminance through file reconstruction.
It does not invoke CameraX/ImageProxy cropping, physical display/camera exposure,
rolling shutter, motion blur, real lens focus or Samsung S24 hardware. Its runtime
is not phone transfer throughput. Physical-device validation is still required.

## Adaptive optical profiles, calibration, v4 manifests and resume - 2026-11-18

Added selectable 192/384/768/1024-byte profiles, a roughly 35-second stationary/
moving calibration sequence, deduplicated accepted-payload scoring over measured
sender display stages and a return result QR. Applying a result sets payload,
requested FPS and hold policy. Manual changes invalidate the stored estimate;
settings persist privately across restarts. File review shows first-pass duration
including manifests/holds and an explicitly optimistic calibration-based estimate.

Default optical protocol is now v4: a CRC-protected manifest before data and every
16 data symbols; data records carry UUID, sequence and raw payload with CRC.
Stable identity derives from filename, file SHA-256 and payload size. Legacy v2/v3
receiving is retained. Older receivers cannot decode v4.

A deadline pacer starts each dwell at presentation and never catches up after
stalls. Extra holds are off / each new ID once / every 10th new ID once, with a
500 ms minimum total anchor dwell. Existing source repeats do not get another
extra hold within the run. Initial manifest acquisition is 1 s; later manifests
have a 350 ms minimum. UI shows requested FPS and actual UI slot submissions/s.

Private bounded journals persist validated manifests and new packets, replaying
solved blocks and unresolved fountain equations. Resume supports a new receiver
and a restarted v4 sender using the same filename/bytes/payload. Incomplete tails
are truncated to a valid prefix. Checkpoint operations are serialized across
activity instances; generation checks prevent old writers/saves altering a new
checkpoint. Successful saving clears the matching journal. Progress survives
process death after journal writes; this is not an unlimited or power-loss-safe
database. Wi-Fi Direct resume is unchanged/unsupported.

`testDebugUnitTest lintDebug assembleDebug` passed: **48 Kotlin tests**, zero
failures, zero lint errors and 31 warnings. The 11 new adaptive tests cover v4
generated QR decoding at every profile, late/repeated manifests, loss recovery,
stable identity, pacing/one-time holds, full and incomplete journal restoration,
pending fountain equations, old-instance ownership rejection, journal size bounds,
calibration deduplication/scoring and unknown-run/profile handling.

No device or emulator was connected. Actual calibration recommendations, physical
optical speed, long-hold capture benefits and on-device resume still need testing
using optical-calibration-and-resume.md. No 190 KB/s or guaranteed-capture claim.

## Random later holds and repeat diagnostics - 2026-11-18

Extended holds now occur once per data-symbol ID on an independently chosen
second, third or fourth appearance, never its first. This optional mode sends
four interleaved appearances per ID so the later hold can occur. Off remains the
throughput default. The every-10th option is removed. Calibration follows the
same policy and rejects older policy reports; update both apps and recalibrate.

Receiver diagnostics distinguish consecutive re-reads from revisited IDs. These
are observed decoded-packet patterns, not measured packet loss. MB-sized optical
transfers show a Wi-Fi Direct recommendation and require confirmation to proceed.
Repeating symbols increases duration; this revision does not solve optical
capacity limits for large files.

`testDebugUnitTest lintDebug assembleDebug` passed: **52 Kotlin tests**, zero
failures, zero lint errors and 31 warnings. New coverage checks random later
holds, exactly one extended hold per ID, interleaved repetition and reconstruction,
removed-policy rejection, calibration policy versioning and repeat diagnostics.

Physical camera reception and phone-to-phone throughput remain unverified.
