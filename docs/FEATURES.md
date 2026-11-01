# RoadGuard feature and architecture guide

This guide describes the checked-in **0.7.0-alpha** implementation. It covers the car application, phone viewer and Windows companion. For installation steps, start with the [README](../README.md) and [parked-test guide](../outputs/RoadGuard-0.7.0-Guide.md).

## Automatic hardware and version selection

### CPU architecture

The Android package includes native builds for `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`. Android chooses compatible libraries according to the device's supported ABIs. A 64-bit chip running a 32-bit Android system may use the 32-bit build. Installation and execution require Android API 23 or later and compatible native/runtime support.

Architecture selection happens through Android's package/runtime system. Camera quality selection happens separately inside RoadGuard. The app uses bundled native camera code and Android APIs. Manufacturer kernel drivers, USB host implementation and electrical power remain hardware/firmware requirements.

### Startup inspection

The compatibility scan reports Android API/build, supported ABIs, processor core count, RAM, advertised USB host support, connected USB interfaces, visible camera IDs, available H.264 encoders and writable removable storage.

The active recording quality planner additionally checks currently available RAM, low-memory state, hardware encoder surface input and advertised resolution/rate support. On API 29 and later it uses Android's hardware-acceleration flag and severe thermal-status reporting. On earlier APIs it uses codec-name heuristics to exclude common software encoders. Missing or inaccurate vendor capability reports can affect the decision.

The compatibility report contains an older, approximate profile label. The runtime quality explanation and actual camera dimensions shown in recording settings are the authoritative values for the current attempt.

### Initial quality candidates

| Priority | Conditions | First candidate |
| --- | --- | --- |
| 1 | Android low-memory warning, severe thermal state, or available RAM below 256 MiB | VGA MJPEG |
| 2 | Total RAM ≥3500 MiB, available RAM ≥768 MiB, ≥4 CPU cores, hardware surface-input AVC 1080p at 30 FPS advertised | 1080p MJPEG |
| 3 | Total RAM ≥1500 MiB, available RAM ≥384 MiB, ≥2 CPU cores, hardware surface-input AVC 720p at 30 FPS advertised | 720p MJPEG |
| 4 | All other cases | VGA MJPEG |

The candidate list includes lower eligible MJPEG profiles and ends with VGA YUYV. VGA here means 640 × 480. Camera negotiation chooses from the webcam's supported sizes within the requested limits. A camera that only supplies VGA continues at that supported size on a more powerful stereo.

For example, a roughly 1 GB head unit starts with VGA. A device meeting the middle tier can try 720p. A capable device meeting the highest tier can try 1080p. USB capacity alone does not raise or lower the image-quality ceiling.

### Encoder startup

The encoder tries Android's default H.264 encoder, then alternative advertised compatible codecs as needed. Each candidate attempts 30 FPS followed by 15 FPS. The 15-FPS attempt halves its selected bitrate. The encoder's default AVC profile is retained for compatibility with older vendor implementations. On API 29+, the configuration requests zero B-frames.

These values are configuration targets. The delivered frame rate also depends on exposure, actual camera output, encoder behavior, USB bandwidth and resource pressure. Encoder startup failures can trigger a lower camera profile. Storage/write failures have a separate recovery path.

### Runtime adjustment and learning

After 30 seconds, preview FPS below 12, an Android low-memory flag or severe thermal status count as pressure. Twenty consecutive pressure checks can lower a currently higher-than-VGA profile if a lower candidate is available. The app waits for recording closure before reopening at the lower profile, which can create a short gap.

After at least 60 seconds, if there is no current pressure and at least one video fragment has been saved, the app can remember the profile. Its key combines the firmware fingerprint, USB camera vendor/product IDs and candidate list. This is a saved stability heuristic. It is not a continuous benchmark or a guarantee of sustained performance in every condition.

To reassess after a change, stop recording, use **RETEST AUTO QUALITY (STOP FIRST)**, then start again. A running session primarily adapts downward. A future session or explicit retest can evaluate a different initial plan.

Source references are [QualityPlanner](../work/RoadGuardAI/app/src/main/java/com/roadguard/ai/QualityPlanner.kt), [AdaptiveQuality](../work/RoadGuardAI/app/src/main/java/com/roadguard/ai/AdaptiveQuality.java), [RecorderService](../work/RoadGuardAI/app/src/main/java/com/roadguard/ai/RecorderService.kt) and [H264EncodeProcessor](../work/RoadGuardAI/libausbc/src/main/java/com/jiangdg/ausbc/encode/H264EncodeProcessor.kt).

### Android-version behavior

| Area | Behavior |
| --- | --- |
| Minimum system | API 23 / Android 6.0 |
| Service startup | Uses foreground-service startup on API 26+ and legacy startup on older supported APIs. Android/OEM restrictions still apply. |
| Thermal adaptation | Uses severe thermal status on API 29+. Earlier devices lack this path. |
| Encoder classification | Hardware flag on API 29+, codec-name heuristics on older releases. |
| Saved Wi-Fi reconnection | The opted-in helper can request reconnection on supported API levels below 29 when Wi-Fi is already enabled. Newer Android manages connection selection. |
| Permissions | Camera, USB device, optional location and relevant system permissions depend on Android and firmware. First approval requires the user. |
| Storage | Selects writable removable app storage. Ambiguous multiple-drive selection can require disconnecting the other drive. |

## Car operation and controls

Choose **Car** once to use the recorder. The foreground service handles recording, camera monitoring and enabled sharing. Boot, app-update and USB events can request service startup. A stereo waking from sleep can behave differently from a full Android boot.

The live screen offers **START**, **STOP & SAVE**, **FIT / FILL**, **VIDEOS**, **SETTINGS** and full-screen controls. Status includes the recording state, saved-write progress, elapsed recording time, preview FPS and USB free space. The on-screen clock uses the car's clock.

Settings contain phone pairing, Tailscale setup, recording/kept-video access, optional location/private-network settings, rear-camera availability information, native night-processing bypass, recording health, quality retest, device-role selection, compatibility scan, Android settings and clean uninstall.

Clean uninstall requires recording to be stopped. Its confirmation flow deletes RoadGuard data on connected storage and then opens Android's uninstall confirmation. Review that deletion prompt before using it.

## USB detection and recovery

- Enumerates already-attached camera devices and handles later USB attach/detach events.
- Checks for USB video-class interfaces and requests access through Android's permission flow.
- Retries detection with waits of approximately 3, 7, 15, 30 and 60 seconds.
- After repeated failures, can reinitialize RoadGuard's own camera monitor when recording is inactive. Monitor resets are rate-limited.
- Checks storage roughly every five seconds as well as responding to mount/eject events.
- Detects a recording with no saved-write progress for 15 seconds and attempts a controlled close followed by camera recovery.
- Recovers the newest interrupted recording when storage becomes available, avoiding a full video-content scan at every start.
- Restores enabled sharing and makes bounded connection-helper attempts while the car service runs.

The recovery sequence depends on Android and encoder calls returning. Firmware hangs, denied permissions, missing camera support, inadequate power and failed flash storage can require user intervention. Shared USB-bus power cycling and privileged whole-device reboot are outside this implementation.

## Recording, timestamps and storage lifecycle

The recorder uses video-only H.264 fragmented MP4. Audio recording is disabled in the camera request. New encoded frames contain a day/date/time/seconds/UTC-offset stamp, so the same visible clock travels with saved footage and encoded live streams. Existing clips retain their original content.

Fragments are flushed and synced at approximately one-second intervals. A segment rotates near 32 MiB or five minutes on a keyframe. Under normal conditions the file writer switches while the encoder continues. If a keyframe fails to arrive within the fallback period, closure/restart may be necessary. A power cut may lose the final incomplete fragment or data still buffered by the drive.

| Data | Location |
| --- | --- |
| Car footage | Removable volume, `Android/data/com.roadguard.ai/files/RoadGuardAI/Car Dash Cam Recordings/` |
| Legacy footage | Older `RoadGuardAI/Recordings/` layouts remain recognised |
| Car exports | USB `RoadGuardAI/Exports/` |
| Diagnostics | USB `RoadGuardAI/Diagnostics/` |
| Settings and pairing identities | Android app storage and Keystore |
| Phone exports | Temporary external app cache followed by the user-selected destination |
| PC backups | Windows companion data folder, grouped by paired car identity |

Storage policy reserves 3 decimal GB on an 8 GB drive and 10 decimal GB on drives of at least 24 GB. Smaller drives reserve at most half capacity. A 64 MiB target margin supports incremental cleanup. At most one eligible completed recording is deleted per check, oldest first. Current, kept, unrelated and greater-than-64-MiB legacy clips are excluded. When space cannot be reclaimed, recording pauses rather than promising continued storage.

The `.nomedia` marker, grouped writes and paced background backup reads help sharing the drive with music. The OEM player can still behave differently and the physical drive's bandwidth is shared.

## Live viewing, pairing and remote access

Car and phone roles use the same application package. Pairing supports QR and code flows, private endpoint discovery and remembered credentials. Certificates are pinned, pairing codes are short-lived and single-use, and approved device access can be revoked. Matching app versions are checked on relevant pairing/request paths.

The primary stream carries encoded H.264 packets. The phone uses Android decoding, and Windows uses its bundled player. Up to two direct car subscribers are supported by the live hub. Bounded queues and keyframe resynchronisation help slow viewers return to recent video. An image-preview fallback is available at a much lower frame rate.

Live viewing works across a reachable local network. Different-network access requires a configured private network such as Tailscale. The optional helper checks periodically and requests Tailscale connection at most once per minute when suitable connectivity is present. Initial sign-in, Android VPN approval, battery exemptions and OEM auto-start permissions can require setup. An active VPN indicator alone cannot prove that the car or PC is reachable.

The phone can remember explicitly paired routes associated with the same car identity and use another route on eligible temporary failures. Security/certificate failures stop that fallback. This supports alternative reachable endpoints, but coordinated two-PC backup scheduling is not implemented.

The Windows companion offers car/phone pairing, online state, live video, recording list, refresh, device revocation, live map, timeline and optional backups. Its installer configures a server task at Windows boot and a dashboard task at user sign-in. When the PC is off, access through its server is unavailable.

## Recordings, downloads and editing

Saved-video browsing includes recording details and kept/protected footage. A protected recording is excluded from normal loop cleanup. User-requested downloads copy footage to the chosen destination. The Windows backup engine supports byte-range resume for interrupted copies and a configured storage budget. Once that PC's backup budget is reached, it retains existing copies and reports the limit.

The date/time timeline supports a whole day or a start/end minute on the chosen date. It includes overlapping footage across midnight, skips gaps and offers 1×/2×/3× playback. Selection times use the car's wall clock, and the end time is exclusive. Clock errors affect the displayed stamp and selection boundaries.

The phone reads available source footage through paired routes and prepares an MP4 before saving it. Car-side playback/export requires recording to be stopped. Windows timeline exports use completed PC copies. Preparation needs temporary storage and processing time. The saved result retains normal playback speed. Older, damaged or unindexed clips can have seeking limitations, particularly on Android.

## GPS, maps and place names

With location sharing enabled and permissions approved, the Android location service requests updates at a five-second interval and zero minimum movement. The operating system and receiver determine actual delivery. The freshness policy accepts a fix up to 30 seconds old while the location service is running. Older fixes remain labelled as last known.

Phone and Windows views display supplied coordinates, accuracy, age and available place information. The map supports following the car, manual browsing and recentering. Refresh requests an available fresh fix. Place strings combine available locality, sublocality, district-level and region fields. Availability depends on Android geocoding and network conditions.

Location updates and map refresh cadence differ, so a requested GPS interval is not a guaranteed screen-update rate. Network loss, GPS reception and antenna quality affect tracking. A full historical route archive, guaranteed street-level accuracy and a dedicated phone-to-car distance workflow are outside this release.

## Offline image adjustment

Small 8 × 8 samples are analysed about every three seconds using local brightness/colour clustering. The GPU applies bounded shadow brightening. Stronger adjustment is time-gated to 18:00–06:00 using the car clock. Daytime processing is limited to high-contrast scenes.

Near-black and neutral/monochrome scenes receive little or no adjustment. Reported camera names suggesting IR/night/starlight processing trigger bypass. A manual **Use camera's own night processing only** setting handles cameras that do not identify themselves clearly. The enhancement is disabled at measured rates of 1–9 FPS.

The module runs offline and targets modest improvement in existing visible shadows. Infrared imaging, large-model reconstruction, super-resolution and automatic accident recognition are outside this implementation. Actual night quality still requires camera testing.

## Source module inventory

All Android application files below are under `work/RoadGuardAI/app/src/main/java/com/roadguard/ai/`.

| Files | Responsibility |
| --- | --- |
| `ModeActivity`, `BootReceiver` | Device role and allowed startup events |
| `MainActivity`, `RecorderState` | Car preview, controls and shared status |
| `RecorderService` | Camera/session lifecycle, quality fallback, recovery and live publication |
| `QualityPlanner`, `AdaptiveQuality`, `CompatibilityProbe` | Runtime quality planning and diagnostics |
| `StorageHelper`, `LoopStorage`, `RecordingHealth`, `RecordingLibrary` | USB paths, reserves, cleanup, progress, protection and library operations |
| `CarLink`, `TlsServerContext`, `LinkSecurity`, `LinkPolicy` | Car HTTP/TLS service, authenticated requests and transfer rules |
| `CarPairingUi`, `PairingQr`, `LocalDiscovery` | Pairing screen, QR content and local discovery |
| `PhoneActivity`, `PhoneRoutes`, `ConnectionRecovery` | Phone workflow, saved endpoints and bounded retries |
| `LiveVideoHub`, `LivePlayerActivity`, `RemotePlayerActivity` | Encoded live distribution, live decoding and remote saved-video playback |
| `RecordingsActivity`, `LibraryStyle` | Recording browser and shared presentation |
| `TimelineActivity`, `PairedVideoSource`, `ClipClock`, `ClipDuration` | Date/range selection, authenticated media reads, durations and exports |
| `CarLocationService`, `LocationPolicy`, `LiveMapActivity` | Location collection, freshness and map display |
| `VpnAssist` | Opt-in Tailscale integration and setup links |
| `BackupReader` | Pacing explicitly tagged background backup reads |

| Additional source | Responsibility |
| --- | --- |
| Android `libausbc` | Camera orchestration, OpenGL rendering, H.264 encoding, timestamp and night adjustment, MP4 writer |
| Android `libuvc`, `libnative` | Bundled USB/native camera support and supporting native libraries |
| Android `tests/` | Host checks for recording, adaptation, storage, transport and selection logic |
| Windows `Dashboard.cs` | Companion interface and live/backup/map controls |
| Windows `LiveTransport.cs` | Live stream handling |
| Windows `Timeline.cs` | Playback preparation and export from PC copies |
| Windows `server.cjs` | Pairing, car connection, relay, backups and authenticated routes |
| Windows `map-server.cjs`, `live-map/` | Local map service and map UI assets |
| Windows install/build/uninstall scripts | Packaging, startup tasks and installation lifecycle |
| Windows test scripts and harnesses | TLS, map, transfer, live transport and export checks |

## Troubleshooting and practical boundaries

| What you see | What to check |
| --- | --- |
| No camera | USB host support, camera power, UVC support, permission and compatible mode. Inspect compatibility diagnostics. |
| Storage required | Writable removable storage and unambiguous drive selection. |
| Paused for reserve | Available free space, kept files and large legacy clips excluded from cleanup. |
| Picture but no saved-byte growth | Recording state, encoder error and USB write diagnostics. |
| Phone/PC lag | Car preview FPS, active quality, hotspot bandwidth, signal and concurrent transfers. |
| Pairing or connection failure | Matching versions, reachable endpoint, certificate/code confirmation and network/VPN status. |
| Old GPS position | Sharing permission, provider availability, GPS reception and displayed fix age. |
| Night adjustment absent | Car clock, scene brightness/colour, native-night bypass and low frame rate. |
| Missing footage in a selection | Car clock/date, gaps, active clips, deleted clips and unsupported duration/seek metadata. |

Current capture is one USB camera with video-only recording. Internal-camera fallback, factory RCA rear capture, owner lookup, automatic accident classification, parking-power management, universal driver installation and full USB-hub reboot remain outside this release. The PC runs relay, backup and export work, while live camera capture/encoding remains on the car.

Builds and host regressions passed as recorded in the [validation report](../outputs/RoadGuard-0.7.0-Validation.md). Hardware-specific startup, continuous recording, Android export, night rendering and music coexistence still need parked testing on the intended devices.
