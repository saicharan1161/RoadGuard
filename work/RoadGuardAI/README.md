# RoadGuard AI 0.5.0 (alpha)

RoadGuard AI is an Android 6+ UVC dash-camera prototype designed for car head units.

## Encoded phone live video in 0.5.0

LIVE VIDEO — FULL SCREEN now uses the existing H.264 encoder output, not periodic JPEG readback. A certificate-pinned authenticated HTTPS connection transports bounded, framed AVC packets and codec metadata; MediaCodec decodes on the phone. The encoded resolution and compression quality match the car encoder, without another video transcode. Delivered FPS follows available encoded frames, network and phone decoding; it is no longer capped to 1–4 FPS. Normal encoder configuration targets 30 FPS with legacy 15-FPS startup fallback; actual camera FPS can be lower. The image preview remains an explicit fallback.

The production live hub limits viewers to two, bounds queues, waits for a keyframe on join, and resets slow consumers to fresh codec metadata/keyframes. Reconnect and stale-frame UI are implemented. Encoder callbacks only copy/enqueue data; they do not send network packets or read video back from USB. Viewing can keep the encoder active after STOP & SAVE while the USB camera remains connected. Closing the last live viewer stops stream-only encoding without stopping an active recording.

Full-screen live display has FIT/FILL and tap-to-show controls; saved playback also has a full-screen toggle. This is a local, video-only custom AVC transport, not WebRTC/RTSP or an internet streaming service. It has not yet been run on actual paired Android devices.

No resolution or FPS setting is based on whether the USB is 8 GB or 128 GB. Larger supported removable drives use the same recording logic. A fast drive does not remove camera, CPU/encoder, USB-host bus or Wi-Fi limits. FAT32's per-file limit is independent of drive capacity; exFAT support must be checked on the head unit before relying on larger individual files. No storage is reformatted.

## Adaptive quality in 0.4.1

Automatic starting ceilings are VGA, 720p or 1080p, based on total/available RAM, low-memory state, CPU cores, thermal status where Android exposes it, and advertised surface-input hardware AVC size/rate support. There is no model whitelist or mandatory 64-bit gate. These are conservative heuristics, not a benchmark or a guarantee of optimal quality. Camera negotiation selects the largest advertised size no larger than the ceiling. MJPEG/YUYV capability lists are refreshed on format changes. Actual resolution/FPS are visible on screen and the selection reason is in Settings/USB diagnostics.

After 30-second warmup, 20 consecutive seconds of low preview FPS (<12), low-memory state or severe thermal pressure can step down a higher-resolution profile. Save completion is awaited before reopening; this makes a new clip and can create a short gap. The app does not silently merge clips with different formats. Startup camera/encoder failures have bounded fallback. A profile with 60 seconds of stable operation is remembered for that firmware/camera/profile-plan combination; STOP & SAVE then RETEST AUTO QUALITY clears learned choices. USB write failures are not automatically retried as encoder-capability problems.

The optional image fallback scales from 480px/~1 FPS to 640px/up to 2 FPS or 960px/up to 4 FPS. The primary 0.5.0 live viewer uses encoded video instead. 4K/60 FPS recording is not enabled.

## New in 0.4.0

- Car/Phone role selection in the same APK; phone mode does not boot the recorder.
- Small local date/day/time/seconds/timezone overlay on the car screen (not burned into video).
- New clips go directly into Recordings. Encoder diagnostic events go into a rotating Diagnostics log. Old files/folders are preserved.
- Opt-in local HTTPS sharing, per-device Android Keystore identity, QR certificate pin, manually compared security check for code pairing, 2-minute single-use pairing codes, five-failure lockout and remembered/revocable phone tokens.
- Bounded server worker pool; private-network clients only. No remote deletion/control endpoint and no cloud service.
- Phone network discovery, auto-recognition of the paired certificate at a new discovered IP, QR scanner, code entry, hotspot settings and optional Android local-only hotspot request.
- Foreground phone recording list refresh, metadata details, pinned HTTPS range playback, user-selected downloads and Android share sheet.
- On-demand adaptive phone preview (0.4.0 used fixed 480px/~1 FPS). It is intentionally not a full-frame-rate stream; JPEG compression runs off the rendering thread. Recording uses the existing path.
- Guides and verification notes are supplied with the release. Two-device Android execution is still required; host tests are not proof of hotspot/driver/MediaPlayer behavior.

## Implemented in this alpha

- Foreground recorder service with boot restart.
- USB attach/detach detection and UVC permission flow.
- Sleep/wake state when no camera is attached.
- Hardware-dependent resolution with camera-library fallback to supported sizes; 640x480 on the reported 1 GB head unit.
- Continuous MP4 per uninterrupted recording session; no fixed-time splitting. Ignition state is not independently sensed. Head-unit sleep, camera detach and manual stop can alter session boundaries.
- Video-only recording in the first alpha so boot-time foreground recording remains compatible with newer Android restrictions.
- Removable USB app-storage selection; no fallback media recording to internal storage.
- Direct OpenGL live preview in a full-screen TextureView; no one-second bitmap/JPEG preview loop.
- FIT/FILL view controls, saved-byte progress, camera FPS, and STOP & SAVE / START controls.
- Video-only fragmented MP4 writer: initialization metadata at the start, video/sample tables committed and synced at approximately one-second intervals in one continuous file.
- Recovery removes incomplete final fragments from this writer's marked files at startup or storage reconnection; it does not modify legacy 0.2 files.
- USB-side recording event log and 15-second no-progress detection.
- Recording notification and diagnostics.
- Clean-uninstall workflow that stops recording, deletes connected RoadGuardAI USB data and opens Android's required uninstall confirmation.
- ARMv7, ARM64, x86 and x86-64 native packaging.
- Compatibility scanner for Android version, CPU ABI, RAM, USB host, connected USB interfaces, internal cameras, H.264 encoders and removable storage.
- Automatic camera retries: adaptive MJPEG, safe 640×480 MJPEG, then 640×480 YUYV.
- Removable-storage mount/eject monitoring with automatic recording resume.
- Compatibility report saved to `RoadGuardAI/Diagnostics/CompatibilityReport.txt` on removable storage.

## Important alpha limitations

- FAT32 has a 4 GiB-minus-one-byte file limit. This build does not implement rollover/merging; a long clip can hit that limit and fail. Use short supervised tests, not unattended long journeys. Do not reformat existing media without backup or compatibility checks.
- Pairing does not establish a network from nothing. First connect both devices to the same hotspot/Wi-Fi. Initial permissions and network selection may require user approval; normal saved Wi-Fi reconnection is firmware-dependent.
- The temporary local-only phone hotspot has no internet and closes when the phone activity is destroyed. A normal Android hotspot is recommended for repeat use. Android chooses its band/security; older stereos may need a manually configured 2.4 GHz WPA2 hotspot instead.
- The car must be powered, with sharing enabled and USB attached, to list/play its videos. Downloads are user-selected files; partial failed downloads must be deleted by the user. No offline in-app download catalog or background cloud sync is implemented.
- App settings/pairing keys necessarily use Android app storage/Keystore; media recording and diagnostics remain on USB. Phone downloads use the user-selected destination.

- A sudden power cut can still lose the current uncommitted section and anything a USB controller has not physically persisted; no software can guarantee zero loss. Old 0.2 MP4s without moov/SPS/PPS cannot be repaired by this new writer.
- Fragmented MP4 is independently decoded in the host tests; playback and live preview on the YT9216CJ must still be tested on-device. Older stock players may handle fragmented MP4 differently.
- Internal camera fallback and on-device AI models are not yet enabled.
- USB permission persistence depends on the Android firmware.
- The APK must be installed once; Android does not execute APKs directly from a USB drive.

The included AUSBC modules are derived from the Apache-2.0 licensed AndroidUSBCamera project.

Run `tests/run-media-tests.ps1 -FfmpegBin <directory-containing-ffmpeg-and-ffprobe> -Scratch <temporary-directory>` with a JDK to exercise the production writer and interruption recovery. Build with `gradlew.bat :app:assembleDebug :app:lintDebug` after configuring the Android SDK.
