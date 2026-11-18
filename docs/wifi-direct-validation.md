# Wi-Fi Direct validation gates

Implementation: WifiDirectTransfer manages Android P2P discovery, group lifecycle
and TCP sockets; DirectInvitation validates QR offers; DirectProtocol supplies
challenge authentication and AES-GCM records; DirectFileTransfer sends/reconstructs
the stream. MainActivity provides mode selection, permission gating, file confirmation,
peer selection, progress and existing automatic saving.

## Automated checks (transport v2)

- Retain optical codec, saving-route, speed and shared Python fixtures; the 10 MB
  optical cap must remain independent of Wi-Fi metadata and preparation.
- AES-GCM record round trips, nonce/key/session/index binding, modified ciphertext,
  truncated records, oversized/negative lengths and bounded invitations.
- Mutual challenge authentication and authenticated device identities precede
  file offers. Each new TCP connection derives fresh directional record keys.
- Stream 12,000,123 bytes and additional files on the same loopback connection,
  compare bytes without whole-file receive arrays, and require saved-file ACKs.
- Force a mid-file disconnect and retry from zero. Lose the final saved ACK, then
  reconnect; the in-session receipt must prevent a second save.
- Wrong hashes must never invoke saving or produce success. Reject malformed
  file metadata; permit 64-bit sizes above 2 GB without allocating a file array.
- An invalid client proof receives no identity or file records. The retry budget
  allows only two automatic retries before an explicit sender decision.

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
three-minute setup deadline (active transfers have no total deadline). Check group removal/socket closure and retry. Ensure
unrelated pre-existing groups are not removed. A failed socket/verification must
not save partial bytes. Test failed disk writes and receiver retry independently.

Measure setup time separately from payload transfer and verified-to-save time.
Use incompressible 2 MB fixtures and 30 trials per named phone pair, recording
median/p95 KB/s, failures, hash matches and saved-file equality. The live speed meter measures streamed file bytes. The final receiver rate includes
file approval, reception, verification and saving, but excludes discovery and
connection negotiation. Record user approval delay separately. Socket loopback speed is not a Wi-Fi benchmark.

Verify permission requests occur only when selecting Wi-Fi Direct operations;
Light mode should still work with Wi-Fi/mobile/Bluetooth disabled and all nearby/
location permissions denied. The APK now intentionally declares local-network and
Wi-Fi permissions, so older audit notes claiming camera-only permissions describe
historical revisions.

References: [Android P2P](https://developer.android.com/develop/connectivity/wifi/wifip2p),
[Wi-Fi permissions](https://developer.android.com/develop/connectivity/wifi/wifi-permissions).

## Saved devices, large files, session reuse and retries

These Android/radio/UI gates remain unperformed; JVM transport tests do not cover them.

1. Update both phones to Wi-Fi transport v2. Complete a QR-authorized transfer;
   confirm the profile appears on both phones, survives restart and is excluded
   from backup. Verify Keystore-encrypted storage contains no plaintext secret.
2. Sender: Saved devices > Send file to the receiver. Receiver: Saved devices >
   Receive from the sender. Verify DNS-SD discovery and authenticated reconnect
   without QR scanning, including duplicate device names and changed P2P addresses.
   Swap roles. Test unavailable peers, permissions, Location switch and DNS-SD failure.
3. Forget profiles on each side; check a forgotten device cannot be selected for
   saved reconnect. Re-pair through a new authorization QR. Test app-data removal
   and unavailable/corrupted Keystore storage without silently trusting a new peer.
4. Transfer 12 MB, 100 MB and a multi-GB fixture where storage allows. Check heap
   growth stays bounded, 64-bit progress is correct and exact file hashes match.
   Test provider read failure, sender cache exhaustion, receiver temporary/final
   storage exhaustion, save permissions and cleanup of pending public entries.
5. After success, choose Send more files; select another file and cancel the picker
   once. The existing group/session must survive the picker. Receiver approves
   each new file and saves automatically. Disconnect/Home/background must end it.
6. Interrupt TCP mid-file twice; verify automatic retries restart the current file
   and do not re-prompt for an already approved offer. After the third failure,
   verify sender Retry again / Cancel send and receiver waiting status. Simulate
   no reconnect; a sender decision must still appear after reconnect timeouts.
7. Lose the final save ACK; retries must not create duplicate output files within
   the same foreground session. Process death clears receipts and retry state;
   do not claim persistent resume or exactly-once delivery across process death.
8. Disable Wi-Fi/remove the group. Verify retries and cancellation remain usable;
   reconnect via Saved devices if the group cannot recover. Do not claim automatic
   radio/group restoration from the socket-level retry implementation.

Service discovery follows [Android Wi-Fi P2P DNS-SD](https://developer.android.com/develop/connectivity/wifi/nsd-wifi-direct).
Profile storage uses [Android Keystore](https://developer.android.com/privacy-and-security/keystore).
