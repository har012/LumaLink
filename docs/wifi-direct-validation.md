# Wi-Fi Direct validation gates

Implementation: WifiDirectTransfer manages Android P2P discovery, group lifecycle
and TCP sockets; DirectInvitation validates QR offers; DirectProtocol supplies
challenge authentication and AES-GCM records; DirectFileTransfer sends/reconstructs
the stream. MainActivity provides mode selection, permission gating, file confirmation,
peer selection, progress and existing automatic saving.

## Automated checks

- Retain optical codec, saving-route, speed and shared Python fixtures.
- Record round trips, nonce/key/session/index binding, modified ciphertext,
  truncated records, oversized/negative lengths, bounded invitations.
- Actual JVM loopback sockets transfer 1/32767/32768/32769/2,000,000 byte files,
  compare exact bytes and require a verified acknowledgement.
- Incorrect hashes must never produce that acknowledgement or invoke saving.
- A final encrypted completion confirmation precedes receiver cleanup; the sender
  waits for peer closure before presenting success and removing its group.
- An unauthenticated client receives only a challenge, never file records.

## Required physical tests (not yet performed)

Use two P2P-capable Android phones; swap roles and repeat on API 26–28, 29–32 and
33+. Test permissions granted/denied, approximate-only Location on Android 12,
Location services disabled, Wi-Fi disabled, unsupported hardware and an existing
P2P group. Verify error screens and successful retry without restarting the app.

Check QR recognition, file confirmation, peer names (including duplicate names),
connection prompts and joining the sender's existing group. Neither phone needs
a shared router. Disable cellular data and disconnect infrastructure Wi-Fi to
confirm that file bytes use the Direct group. No internet server is involved.

Try a wrong peer, wrong/replayed key, modified QR hash, obsolete offer, declined
connection, sender/receiver STOP, backgrounding, rotation, radio loss and the
three-minute deadline. Check group removal/socket closure and retry. Ensure
unrelated pre-existing groups are not removed. A failed socket/verification must
not save partial bytes. Test failed disk writes and receiver retry independently.

Measure setup time separately from payload transfer and verified-to-save time.
Use incompressible 2 MB fixtures and 30 trials per named phone pair, recording
median/p95 KB/s, failures, hash matches and saved-file equality. The current speed
meter includes protocol authentication after TCP connect and excludes discovery,
connection negotiation and saving. Socket loopback speed is not a Wi-Fi benchmark.

Verify permission requests occur only when selecting Wi-Fi Direct operations;
Light mode should still work with Wi-Fi/mobile/Bluetooth disabled and all nearby/
location permissions denied. The APK now intentionally declares local-network and
Wi-Fi permissions, so older audit notes claiming camera-only permissions describe
historical revisions.

References: [Android P2P](https://developer.android.com/develop/connectivity/wifi/wifip2p),
[Wi-Fi permissions](https://developer.android.com/develop/connectivity/wifi/wifi-permissions).
