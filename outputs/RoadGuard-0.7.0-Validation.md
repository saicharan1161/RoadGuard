# RoadGuard 0.7.0-alpha validation

Built on 1 November 2026. APK version code 21, minimum Android API 23. This is a test release, not a guarantee that every Android stereo or USB camera works.

## Passed

- Android debug APK assembly and lint: zero lint errors, 149 warnings remain. Windows companion compilation succeeded.
- APK signature verified with the existing development signing certificate. APK inside the Windows ZIP matches the separate APK exactly. Required server, dashboard, player, transcoder, probe and release files are present.
- Fragmented MP4 writer: 150 decoded frames, 5 seconds, unchanged decoded pixels. Simulated power cuts at index/header/payload boundaries recovered the completed 120 frames and correct 4-second duration. Complete indexed files were unchanged by recovery.
- Storage policy tests: capacity-based reserves, one eligible deletion per check, oldest-first ordering, exclusions for active/protected/unrelated/large legacy files. Tests used isolated fixtures. Symlink creation was unavailable on this host, so that guard was source-reviewed rather than exercised.
- Offline scene analysis: bounded dark-scene adjustment and black/neutral/native-night bypass rules. Car-clock date selection, midnight overlap and invalid/empty time ranges passed.
- Windows export through the production timeline processor: full-day and time-range selections across two recordings with a gap produced the expected 240 and 120 frames. Playback acceleration does not change export speed.
- Local TLS integration tests: pin rejection, authentication, pairing, matching-version checks, single-use codes, live relay, byte-range reads, interrupted backup resume, offline copies, saved reconnection and revocation. Background backups use the pacing header. A real 640×480 MP4 returned correct 5-second duration through the offline backup metadata route and its cache.
- Existing live-transport regression tests passed for 15/30 FPS synthetic inputs. This validates transport behavior, not the achieved frame rate on the car.

## Still requires the actual devices

No Android device was listed by ADB and no recording USB drive was mounted during final packaging. No app was installed and no existing user recordings were deleted.

Car cold boot/wake, late USB detection, OEM USB permission behavior, hotspot/VPN restoration, GPU timestamp appearance, real low-light output, Android Media3 playback/export and music playback alongside recording still need a parked hardware test. No Android emulator or car GPU was used for visual verification. Real-world lag and recovery from hardware faults cannot be guaranteed by host tests.

Automatic full USB-hub or Android reboot is not implemented. The app can reset its own camera connection and monitor; it cannot safely promise privileged hardware resets on an ordinary unrooted stereo. Software shadow adjustment is not infrared night vision. Large legacy recordings over 64 MiB remain excluded from incremental cleanup; space may need to be freed manually before recording resumes.

## SHA-256

- RoadGuardAI-0.7.0-alpha.apk: `2D67EA081E37B2AE517D689970CAFE70578D444754B6DB6B7995FE7EE6F1FCF0`
- RoadGuard-Windows-0.7.0-alpha.zip: `8114C4400FE3B729E8C17494B9FFB31DF4D9D9A000C360B34CFA29225C0E3EB2`
