# Optical calibration, longer holds and resume

Install the same current APK on both phones. v4 is incompatible with older receiving
apps. Earlier in-memory progress from versions without checkpoints cannot be recovered.

## Calibrate before selecting a large file

1. On the receiving phone: Light / QR → Calibrate optical link → Scan test QRs.
2. On the sending phone: Light / QR → choose the longer-hold setting → Calibrate
   optical link → Show test QRs. Keep the phones positioned for normal transfer.
3. Scan the entire sequence and the final summary QR (about 35 seconds with holds
   off; random repeats take longer). Candidates
   are 192 bytes at 3/6 FPS, 384 at 6 FPS, and 768/1024 at 6/15 FPS. Each has a
   stationary acquisition symbol followed by moving unique symbols.
4. The receiving phone displays the best **observed** candidate and a result QR.
5. On the sender, choose Calibrate optical link → Scan result QR; scan the receiver's
   result and confirm Apply profile. Alternatively set the displayed values manually.
6. Select the file. Review the estimated duration and choose Start Light transfer
   or the explicit Wi-Fi Direct option.

The score deduplicates sequence IDs and divides accepted test-payload bytes by
actual sender UI-stage duration, including encoder stalls and the selected extra
holds. It excludes stationary acquisition, file manifests, fountain redundancy and
final saving. It is a channel sample, not verified file throughput or proof that
the profile will remain optimal. Entirely unseen profiles cannot be recommended.
Re-run when moving either phone, changing lighting or changing hold settings.
No network feedback is used; the result QR is the optical return channel.

## Longer display holds

Options: Off / Random 2nd–4th appearance once. Default: Off. Every-10th hold is removed.
For each ID, the sender independently draws an appearance number of 2, 3 or 4.
Only that appearance is held for at least 500 ms total; the first appearance and
all other appearances use normal dwell. It never selects the first appearance.
If normal dwell is longer, it is preserved. Manifests retain a separate minimum
350 ms dwell; the initial manifest is held for at least one second.

Random mode deliberately interleaves four appearances for each group of four
IDs: 0,1,2,3,0,1,2,3,0,1,2,3,0,1,2,3, then the next group. This ensures each chosen
later appearance actually occurs, including repair IDs that otherwise might only
be sent once. Exactly one appearance is extended per ID within the bounded run.
These intentional repeats cost capacity and are included in duration estimates and
calibration. Off retains the normal systematic/repair schedule and avoids this
additional four-appearance policy. Do not enable random holds to reduce duplicates.

One dwell corresponds to one UI submission. Repeated presentations of an ID remain
the same symbol, not fresh file bytes. The camera may decode it several times. There is no optical
acknowledgement for each symbol, so no fixed hold guarantees capture. Holding every
symbol can greatly lower throughput; calibration evaluates the selected setting.
Manual Hold QR for focus/Resume remains available.

Calibration results include hold-policy version 2. Old first-appearance/each-tenth
recommendations are rejected, and stored old calibration rates are cleared. Update
both apps and run a new calibration before applying results.

## Duplicates and MB-sized files

At 30 analyzed camera images/s and 6 changing symbols/s, five camera images can
show the same QR. If all decode, four of those five are expected repeats, not 80%
packet loss. Re-reads counts consecutive identical decoded data packets; revisited
IDs counts duplicate IDs after a different decoded QR. Neither conclusively proves
a particular sender retransmission or display presentation, so the labels do not
claim to identify their cause. Watch unique accepted bytes/s and recovered KB/s.

The review page warns explicitly for files of 1,000,000 bytes or more and offers
Wi-Fi Direct. Continuing Light requires confirmation and retains the full duration
estimate, including extra repeats and holds. This does not make MB-sized optical
transfer fast or raise the 10 MB file cap. Choose Off for best throughput and
Wi-Fi Direct for large-file use. Radio performance still needs physical testing.

## Resume a Light transfer

The receiver records validated manifests and newly accepted data to a private,
length-bounded, CRC-protected journal. Duplicate manifests/data are not continually
appended. It stores no camera images. Packet replay reconstructs solved blocks,
pending equations and seen sequence IDs. An incomplete/corrupt tail is discarded
up to the last valid journal record.

- Tap Pause & keep progress, or reopen after interruption.
- Choose Scan & receive → Resume. Checkpoint replay runs in the background.
- Use exactly the same filename, file bytes and payload size on the sender.
  A new sender instance derives the same v4 session identity, allowing continuation.
- FPS and hold can change; changing payload creates different block boundaries and
  cannot reuse the old checkpoint. Start new explicitly replaces saved progress
  when the first valid new packets arrive.
- If the sender was manually paused, Resume continues from its current position.
  If restarted, its schedule begins again and the receiver ignores known sequences.
- Successful saving clears only the matching checkpoint generation; a delayed old
  save cannot delete a newer reception. Failed saving leaves the checkpoint available.

There is one checkpoint slot, bounded to 64,000,000 bytes/120,000 records, subject
to available private storage and the decoder packet limit. Uninstalling or clearing
app data deletes it. Old v2/v3 senders can be received, but their random IDs do not
support cross-restart sender identity. Wi-Fi Direct resume is not implemented.

After Resume, live speed and accepted/s count newly received data rather than
crediting restored blocks as current throughput. Progress includes restored blocks;
the diagnostic line also shows the total accepted count including restored records.

## Implementation map

| Component | Responsibility |
| --- | --- |
| OpticalProfile / OpticalPacer | Profile bounds, duration estimates, once-per-ID holds and no catch-up bursts |
| OpticalV4 | Periodic manifests, compact data records, stable identity, CRC and late joining |
| OpticalCalibration | Test QRs, deduplication, measured-duration scoring and result QR |
| OpticalCheckpoint | Private journal, bounded replay, incomplete-tail recovery and generation-safe cleanup |
| MainActivity | Profile controls, calibration roles, review/estimates, receiver resume and UI timing |

Unit and generated-image tests do not reproduce camera exposure, display transitions,
rolling shutter or device thermal behavior. Physical performance remains unverified.
