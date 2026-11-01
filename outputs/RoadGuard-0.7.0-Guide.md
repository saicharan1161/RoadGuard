# RoadGuard 0.7.0-alpha • car, phone and Windows

Install the same APK over the app on car and phone. Extract the Windows ZIP and run Setup.cmd to update the companion. Do not uninstall or clear app data to update. This build does not install Tailscale, grant permissions or change Wi-Fi credentials.

## First parked test

1. Check the car clock and time zone, enable its saved hotspot network, pair RoadGuard and approve the UVC webcam once. If Android offers a default USB-device association, choose RoadGuard. For VPN, keep Tailscale signed in and use RoadGuard's Tailscale auto-connect setup.
2. Start with the webcam and recording USB already attached. Wait for **Recording** and increasing **saved bytes**, not just a picture. Then try startup with storage arriving late; the recorder polls for a writable drive as well as mount events. If Android cannot identify the USB among several removable disks, leave only the intended drive connected. See whether the stereo actually cold boots or wakes from sleep.
3. View Watch live on the phone and PC. The local day, date, time to seconds and UTC offset should be in the **picture**. Download a newly saved clip and compare. Old clips are not rewritten; a wrong car clock produces a wrong stamp everywhere.
4. Play songs from the drive while recording and across segment boundaries. RoadGuard groups writes, marks its library as non-media, limits background PC backup reads during recording and doesn't rescan the whole movie archive for damage at every ignition. It never selects songs or edits the music library. OEM music players can ignore .nomedia; a slow/failing stick or USB bus can still cause skips. Disable automatic PC backup for a comparison if needed.
5. Test in daylight and after dark while parked. Offline clustering meters small samples, then a conservative GPU adjustment lifts existing shadows. The stronger night setting operates only between 18:00 and 06:00 by the car clock and only on usable dark colour scenes. It avoids near-black, neutral/monochrome pictures and cameras whose reported names indicate native IR/night processing. Unlabelled colour night cameras cannot always be detected. Select **Settings → Use camera's own night processing only** for those cameras. No infrared, super-resolution or invented details.

## Shared USB space

The reserve is **3 decimal GB on an 8 GB drive** and **10 decimal GB on drives of 24 GB or larger**, including 30/32/60/64/128 GB models. On very small drives it is at most half their capacity. A safety margin starts cleanup early. When near the reserve, up to one oldest eligible finished segment is reclaimed per storage check (about five seconds). Normal rotation is around 32 MiB or five minutes, at the next keyframe; this switches the file writer without intentionally restarting the camera/encoder. A broken encoder may require a save/restart. Sudden power loss can lose an unfinished fragment. There is no guarantee of zero dropped frames.

Only timestamp-named, completed RoadGuard MP4s in the two app-owned recording folders are eligible. Kept videos, current video, music, exports and large legacy clips over 64 MiB are excluded. Legacy clips at or below that limit can be deleted whole. Completed MP4s are never shortened in place. If there is not enough eligible footage to reclaim, recording stops safely and can resume when space returns. This is not a disk quota: another app can use the reserve. If you start below reserve with only large legacy trips, export or remove those trips yourself first.

## Select footage

**Phone:** Recording timeline → Choose date and time. Select Full day or a start and end minute (end excluded), then Play selection or Download selected MP4. Phone reads via the existing pinned connection. It prepares the MP4 in external app cache before you pick the download destination; allow space for both copies. Close the screen to cancel a running export. Playback speed (1×/2×/3×) doesn't change the saved file.

**Car:** Recordings → Browse by date. Stop & Save first on this low-power unit. The finished export is on the USB in Android/data/com.roadguard.ai/files/RoadGuardAI/Exports. The originals stay in their recording folders.

**Windows:** Timeline uses completed **PC backups** of the paired car, not the active recording or car-only files. Choose Back up now before editing, then select the day/range and Play or Save MP4. Allow temporary disk space for the pieces, joined MP4 and final copy. Preparing an entire day may take time.

Dates refer to the car's clock, not the phone or PC time zone. Midnight overlaps are included; gaps are skipped. Keep important source footage first so loop cleanup does not remove it during a long export. Some older, unindexed or damaged recordings may not support accurate seeking on Android; use a backed-up copy on Windows. No missing or unrecorded time is reconstructed.

## Recovery limits

RoadGuard checks for devices that were attached before the app started, retries with backoff, and can reinitialize its own camera monitor after repeated failures. It does **not** reboot Android, reset a shared USB hub, root the stereo or disconnect the music drive. On supported older Android versions the opted-in connection helper can request reconnection to saved Wi-Fi if Wi-Fi is enabled; on newer systems Android manages that connection. Revoked permissions, blocked startup, unsupported hardware, insufficient USB power and expired VPN sign-in still require attention.
