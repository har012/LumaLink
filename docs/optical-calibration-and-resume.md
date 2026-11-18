# Optical calibration, longer holds and resume

Install the same current APK on both phones. v4 is incompatible with older receiving
apps. Earlier in-memory progress from versions without checkpoints cannot be recovered.

## Calibrate before selecting a large file

1. On the receiving phone: Light / QR → Calibrate optical link → Scan test QRs.
2. On the sending phone: Light / QR → choose the longer-hold setting → Calibrate
   optical link → Show test QRs. Keep the phones positioned for normal transfer.
3. Scan the entire roughly 35-second sequence and the final summary QR. Candidates
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

Options: Off / Every new symbol once / Every 10th new symbol once. Default: every
10th. An anchor symbol is held for at least 500 ms total (not an extra 500 ms on
top of its normal dwell). If normal dwell is longer, it is preserved. The same
symbol ID gets this additional dwell only on its first presentation in that run;
scheduled source retransmissions use normal dwell. Manifests have a separate
minimum 350 ms dwell; the initial manifest is held for at least one second.

One dwell corresponds to one UI submission, so the hold does not fabricate new
symbols. The camera may legitimately decode it several times. There is no optical
acknowledgement for each symbol, so no fixed hold guarantees capture. Holding every
symbol can greatly lower throughput; calibration evaluates the selected setting.
Manual Hold QR for focus/Resume remains available.

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
