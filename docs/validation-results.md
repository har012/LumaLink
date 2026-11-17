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
