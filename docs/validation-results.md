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
