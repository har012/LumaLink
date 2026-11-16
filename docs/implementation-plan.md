# Implementation status and remaining work

The starting point was LumaLink-Android-Prototype.zip supplied by the owner.
No existing GitHub implementation was available; har012/LumaLink was empty.

## Implemented in this revision

| Audit gap | Files |
| --- | --- |
| No reproducible build/checks | Gradle wrapper, gradle.properties, .github/workflows/android.yml |
| Invalid packet mutates session or consumes sequence | Codec.kt; CodecTest.kt |
| Empty ID resets state | Codec.kt explicit receiver states and UUID validation |
| Corrupt reconstruction stalls forever | Codec.kt integrity/contradiction checks and terminal failure; MainActivity.kt retry UI |
| STOP leaves camera bound/stale callbacks | MainActivity.kt cancellation, session tokens and unbinding |
| UI-thread unbounded I/O and API-29-only save | MainActivity.kt bounded background input; ReceivedFileWriter.kt automatic MediaStore/legacy output and FileDestination.kt type routing |
| Sequential render-plus-delay pacing | MainActivity.kt two-entry encoder queue and Choreographer pacing |
| RGB conversion and per-image reader allocation | FrameDecoder.kt and LuminancePlane.kt with reusable luminance buffer |
| No regression coverage | CodecTest.kt and LuminancePlaneTest.kt |

## Remaining priorities

1. **Device baseline:** validate API 26+ save/read, lifecycle interruptions, camera
   formats/orientations and actual screen presentation. Review merged APK permissions.
2. **Fountain efficiency:** simulate thousands of seeds at 2 MB and 10 MB; log
   received/source symbol expansion, CPU, failure rates and bounded memory behavior.
   Introduce bounded generations and indexed equation dependencies; choose a proven
   erasure-code distribution/implementation based on measured results.
3. **Optical measurement:** add exportable per-frame timestamp traces and camera
   FPS/resolution negotiation, focus/exposure controls and calibration. Current UI
   rates distinguish analyzed, decoded and accepted packets, but are not full traces.
4. **Protocol density:** extract packet/manifest classes, replace JSON/Base64 with
   binary framing, periodically repeat manifests and preserve late joining. This
   requires a new protocol version and interoperability fixtures.
5. **Higher-capacity QR transport:** benchmark multiple QR symbols per display
   frame. The single-QR binary capacity at 60 FPS is insufficient for 190 KB/s.
   A custom optical grid is a separate product decision because it changes QR usage.
6. **End-to-end acceptance:** run the benchmark protocol and publish raw results
   before making throughput claims. Support resumable sessions and unsaved-file
   persistence if interruption recovery is required.

Runtime limits currently bound memory rather than guarantee recovery. The basic
fountain decoder can still stall; packet checksums are not sender authentication.
