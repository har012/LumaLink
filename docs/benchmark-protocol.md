# Validation protocol

No phone-to-phone performance results have been collected for this revision.

## Build and correctness

Run `./gradlew testDebugUnitTest lintDebug assembleDebug` with JDK 17 and SDK 35.
CI runs the same checks. Install on API 26, 28, 29 and a current Android device.
Test camera denial/unavailability, STOP during camera startup, rapid send/receive
switching, background/foreground, rotation, picker cancellation, empty and oversized
input and Unicode filenames. Test automatic image/video/audio/other routing, duplicate
filenames, unavailable/full storage, failed-write cleanup and retry. On API 26/28,
test storage permission grant/denial and retry after granting it in Settings. On API
29+, verify no storage permission is requested. Confirm gallery/music visibility for
valid media and ensure existing files are never overwritten. Unknown extensions
must go to Download/LumaLink. The completion screen must show the actual saved name.
Sessions intentionally stop when the activity leaves the foreground; restart from
HOME. A rotation/process death can lose an unsaved verified file; receive again.

Codec tests cover block boundaries, malformed first packets, session isolation,
duplicate packets, bad integrity, wrong file hashes and 30 seeded loss/reordering/
late-join trials. These are regression tests, not proof of fountain efficiency.
Extend to thousands of trials and larger generations before claiming reliability.

## Optical channel

For each phone pair record model, Android version, screen refresh rate, camera
resolution/frame timestamps, distance, angle, illumination and brightness. Run at
6, 15, 30 and 60 requested FPS. The sender's UI submissions/s measures calls to
update its drawable, not optically confirmed distinct presentation. Confirm actual
presentation with a high-speed camera or display tracing plus decoded sequence IDs.
Receiver camera/s counts analyzed images; QR/s includes duplicates; accepted/s
counts new valid packets. None is verified file throughput by itself.

Measure p50/p95 decode latency, missing/duplicate sequence fraction, peak memory,
GC, CPU and thermal behavior after 10 minutes. Include movement, glare, portrait/
landscape and display/camera refresh mismatch. Verify crop, row/pixel stride and
rotation on different camera implementations. Camera FPS is not explicitly negotiated.

## End-to-end gate

Use a 2,000,000-byte incompressible image fixture. Compare the saved file byte for
byte and by SHA-256. Report 30 trials per phone pair, all failures, median/p95
latency, and both first displayed packet-to-verification and user-start-to-save
times. For >=190,000 verified bytes/s, verification must take <=10.53 seconds.
Also test 2 MiB separately; do not interchange KB and KiB.

Disable Wi-Fi, mobile data and Bluetooth, use local document providers, and inspect
the merged release/debug manifests and APK permissions for network/Bluetooth APIs.
The system document picker may expose cloud providers; select local storage.

## Capacity decision

Current 420-byte symbols at 60 accepted symbols/s cap at 25,200 bytes/s before
fountain overhead. A standard binary QR maxes at 2,953 bytes, so one QR at 60 FPS
cannot reach 190,000 bytes/s even with no protocol overhead or losses.
Prototype multiple QRs per display frame before adopting that transport; measure
their combined unique acceptance and useful payload. A custom optical grid would
change the QR requirement. Do not claim either experimental transport is implemented.
