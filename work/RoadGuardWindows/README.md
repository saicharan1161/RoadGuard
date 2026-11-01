# RoadGuard 0.7.0-alpha — Windows companion + Android

Update all devices together. Timeline on Windows works from completed backups for this paired car; back up first. Choose a date, then Full day or an end-exclusive HH:mm range, and Play or Save MP4. Playback supports 1×/2×/3×; exports are normal speed. The desktop editor uses local temporary space and FFmpeg/ffprobe and skips recording gaps. The Android phone timeline reads the paired car or its PC copies. Park and stop recording before using the editor on a low-power car stereo. See the packaged 0.7.0 guide for retention, permissions and hardware checks. The older release notes below describe earlier versions; the 0.7.0 guide supersedes their storage thresholds.

Automatic live update: Windows and phone offer 'Open live automatically' (on by default for this requested feature). Windows starts one live window after its first online response; phone opens live once per library Activity session. Closing live is respected. Temporary stream failures retry with backoff capped at 30 seconds while the viewer is open. Phone live includes a location/accuracy/age overlay. Automatic viewing consumes network/mobile data; disable it on devices not intended to watch.

Phone RoadGuard still must be opened after reboot; it does not launch itself over the lock screen. Windows dashboard opens after sign-in, not before. Tailscale connection/sign-in, power, internet, permissions and manufacturer background-start restrictions remain prerequisites. No accurate GPS fix is promised without reception. Only two concurrent car live subscriptions are supported; extra viewers wait/retry.

GPS recovery update: stationary updates use a zero-metre movement threshold; provider changes trigger resubscription; stale fixes trigger bounded retries. Refresh requests a one-shot provider fix while keeping continuous updates active. Phone location dialog updates every 15 seconds while visible. Actual satellite availability and firmware behavior remain unverified on the car.

Reconnect update: the server immediately checks its saved car on startup and retries every 15 seconds. Phone library reconnects on opening and on network availability, retaining its 30-second retry timer. Pairings survive normal updates and server restarts. Tailscale must already be connected; the phone does not start background streaming while its app is closed. Host tests verify persisted car and phone pairing after a server restart.

This is an alpha test release, not a claim of reliable car operation on all hardware. No paid cloud account is required. Internet/mobile data and electricity are still your responsibility.

## What it includes

- Windows x64 dashboard: paired-car status, recordings, GPS coordinates/accuracy/fix age, place name when available, refresh and map link.
- Optional FFplay live window receiving the car's H.264 stream without PC re-encoding.
- TLS certificate-pinned car-to-PC connections and phone-to-PC connections. Codes are single use, expire in two minutes, and lock after five wrong attempts.
- Boot background server task (LocalService), dashboard at the installing user's sign-in, retry after server crashes. No commands needed after one-time setup.
- Optional PC backups with resumable partial files, size budget and 1 GB disk reserve. Existing PC backups are not automatically deleted. Backups remain OFF by default because hotspot data can cost money.
- Phone resume-download button for the last interrupted download into a seekable local document.
- Paired phone read requests can fall back to another remembered server for the SAME car identity. Security failures and writes are not automatically retried across servers.
- Shared release.json drives APK and Windows package versions. Incompatible versions show update errors; updates are installed manually, not silently downloaded.

## Install on Windows

1. Extract the complete ZIP into a folder. Do not run setup from inside the ZIP.
2. Double-click Setup.cmd. Approve the administrator prompt yourself and read the installation confirmation.
3. Setup installs under Program Files/RoadGuard and stores protected configuration and backups under ProgramData/RoadGuard.
4. It creates RoadGuardServer and RoadGuardDashboard scheduled tasks plus a TCP 8944 firewall rule scoped to LocalSubnet and 100.64.0.0/10. It does NOT open your router, disable your firewall, change sleep settings, or install/sign in to a VPN.
5. Install the bundled RoadGuardAI.apk on BOTH car and phone as an update. Stop/save recording first. Do not use Clean Uninstall.

Requires supported 64-bit Windows with PowerShell 5.1, .NET Framework 4.7.2+ and Task Scheduler. This package uses x64 Node and FFplay; not suitable for 32-bit Windows. Windows ARM emulation and other PCs have not been tested.

## Pair the car to Windows

1. First test on the same local network.
2. On car: open Settings > Connect Phone to generate an 8-digit code.
3. On Windows: Pair car > enter the car's IPv4 address (:8943 optional).
4. Compare the security fingerprint with the car's screen before approving; enter its 8-digit code.
5. Dashboard should show a real car response. With no camera/GPS, those features stay unavailable.

For separate networks, install/configure a private-network system such as Tailscale on car, PC and phone yourself. The car firmware's VPN support is UNVERIFIED. Enable car Settings > Location / Private Network > Allow paired private VPN clients. Pair Windows using the car's stable private VPN IPv4, not its home-Wi-Fi address. No internet tunneling is magically provided by this package.

## Pair phone to Windows

1. Click Pair phone in Windows. It lists PC addresses, code and security fingerprint.
2. Phone > Enter code: type PC IPv4:8944 and the code.
3. Compare the fingerprint on the phone with Windows, then approve.
4. For remote access choose the PC's private VPN address on all devices.
5. For two PCs: independently pair each PC with the SAME car; pair the phone with each PC. The latest paired route is tried first, then eligible remembered routes for read requests after transient failures.

There is no distributed backup leader election: enable automatic backup on ONLY ONE PC to avoid duplicate backups. Failover does not migrate an in-flight video stream; the player reconnects. Credentials are private per PC; do not copy one PC's config to another. Revoke phones disconnects active phone streams and invalidates phone tokens on that PC.

## GPS

On the car, enable Android Location. In RoadGuard Settings > Location / Private Network, explicitly enable GPS sharing and grant location permission. Open the car app to start the location foreground service; newer Android versions may require this again after reboot.

- Updates requested every five seconds when providers permit; Windows polls approximately every 15 seconds.
- Accuracy is an estimate, not an exact guarantee.
- Refresh requests a fresh provider fix asynchronously; the old fix stays explicitly labelled until a new one arrives. It cannot guarantee satellite reception.
- Old/no fixes are labelled clearly. Sharing OFF returns no coordinates.
- Place lookup uses Android's geocoder, which can be unavailable or contact its configured provider. Map button opens OpenStreetMap in your browser and sends the selected coordinates to that site.
- No phone-to-car distance/routing or embedded map in this build.
- Last-known PC location is in memory and does not survive server restart.

## Recording/backup behavior

Car recording to USB is independent of this PC. Same-network direct phone pairing remains available.

PC backs up completed clips only, newest first, retaining original date/day/time in filenames with a short unique prefix. Copies are in ProgramData/RoadGuard/Recordings/<car identity>/.

After interruption, matching .part files resume with HTTP Range and size checks. Existing completed copies are not copied again. This checks transferred byte counts, not full MP4 decode integrity. The car may delete an unprotected clip before backup finishes. Protect important clips.

When the car is offline, the phone can list/play/download completed PC backups while the PC is online. Live view and current location are unavailable. Metadata/Keep actions for offline PC copies are disabled.

Two PCs can independently serve the same car, but old hardware still limits concurrent connections. Start with ONE live viewer and no backup. Backups/live share network bandwidth. This alpha has no mobile-data metering or bandwidth prioritization yet.

## Updating and removal

Use the matching Windows ZIP and APK from the same release. Setup preserves existing server identity, pairings and backups. It replaces application binaries, then restarts tasks. No automatic software update service or signing infrastructure has been set up.

Uninstall.ps1 removes the two scheduled tasks and firewall rule. It deliberately RETAINS program files, credentials, shortcut and recordings; it is not a complete disk wipe. Back up files and request explicit cleanup if needed.

## Test status

Host integration tests exercise actual TLS, pin rejection, pairing/version checks, code reuse rejection, video/range forwarding, interrupted backup recovery, saved-copy serving and revocation. Dashboard was inspected with simulated data; simulated coordinates and clips are not real car evidence.

NOT verified: real car GPS, phone Android document-provider resume, separate-network VPN, two physical PCs, Windows reboot/startup task execution, or smooth physical webcam-to-PC playback. The installer has not been run on the user's machine. Keep 0.5.7 as a known prior build and preserve footage.

No guarantee of zero errors. If a real device fails, capture the error and stop testing before risking important recordings.
# Live maps — 0.6.4-alpha

Update the car APK, phone APK and Windows companion together to 0.6.4-alpha (Android code 19). Updating in place retains existing pairings; do not uninstall or clear app data to update.

- Phone: choose **Live car map**, or tap the GPS label on the live video screen. The map opens inside the app. Android Back / Back to RoadGuard returns to the previous screen.
- Windows: choose **Live map**. It opens an automatically updating map in your default browser, not a static OpenStreetMap pin.
- The map checks location about every 5 seconds after each completed request while visible. Actual GPS and network delays apply. Follow is on initially; dragging pauses following, and Follow car restores it. Zoom remains under your control.
- Green means a fresh fix (30 seconds or less), not guaranteed accuracy. Estimated accuracy is shown as a circle and in metres. Fixes above 50 m estimated error have a low-accuracy label. Network-provider locations are not labelled GPS. No extrapolated movement or invented positions are displayed.
- If the connection fails or a fix grows old, the previous position is amber and explicitly NOT LIVE. Turning location sharing off removes the marker when the next response arrives. GPS refresh requests a new fix but cannot force satellites or hardware to provide one.
- Car location sharing and Android precise-location permission must be enabled. The car needs a working GPS receiver/antenna and a good sky view. Tailscale must remain connected on the devices for different-network access.
- Map tiles are downloaded from OpenStreetMap only for interactive viewing. Internet access is needed; there is no tile availability guarantee, bulk download or offline-map feature. Your IP and viewed map area are visible to that service. Pairing secrets are never sent to the tile provider. Browser/WebView tile caching uses normal HTTP cache rules; unlike car footage, this cache is on the viewing device.
- Leaflet 1.9.4 is bundled locally with its license; no hosted JavaScript or paid map API key is used. See https://operations.osmfoundation.org/policies/tiles/ and https://leafletjs.com/.
- Windows uses a read-only map bridge bound only to 127.0.0.1 on a random port. It has separate limited map tokens, Host/Origin checks, no CORS, and no recording/admin endpoints. Up to 8 map sessions last 8 hours; reopen Live map after expiry, server restart or car re-pairing. No public map port or extra firewall rule is created. Car communication still uses pinned TLS.
- The map keeps only the latest position in memory, not a location-history database. Closing/hiding the map stops location polling (an already-started request may finish). Coordinates continue working when tiles cannot load.

Verification: Android build/lint, Windows compilation, map-controller/security tests and real-TLS simulated-car integration tests. Real car GPS, Android WebView rendering and Windows browser rendering still require device testing; automated browser access was disabled in the development session.

# Streaming and reconnect update — 0.6.5-alpha

Install matching 0.6.5-alpha (Android code 20) on car, phone and PC, over the previous version. Pairings are preserved. Live maps from 0.6.4 remain included.

- Phone: a separate decoder-output pump continues rendering while network input waits. This removes a blocking dependency; it does not manufacture extra camera frames.
- Car: live queues are bounded to eight packets / about 250 ms of queued source time and approximately 1 MB (a single oversized frame can exceed this). When overloaded, a viewer rejoins on a keyframe rather than accumulating a long delay. Socket/OS buffering and network delay are additional; there is no 250 ms end-to-end guarantee.
- Windows: camera timestamps are preserved in a small MPEG-TS wrapper for ffplay; raw AVC timing is no longer guessed. No re-encoding or recording-quality reduction. Player probing/buffering is reduced and frame dropping is enabled for late video.
- Automatic PC backups pause when this server has a live viewer, including interrupting an in-flight backup with its partial copy retained. Enabled backups resume during a later idle polling cycle. A direct phone-to-car live stream is not visible to the PC backup scheduler. Manual downloads can still compete for bandwidth.
- Pinned TLS connection/session reuse reduces repeated cryptographic setup work on the old car CPU. Certificate pins and version/authentication checks remain enforced. Timeouts still trigger retries; no code can guarantee connectivity through lost Wi-Fi or a killed VPN.
- The dashboard distinguishes recent live packets from a failed status poll. It no longer keeps saying Receiving after five seconds without video packets, or labels cached recording rows Recording now when status is unavailable. Receiving is not proof that ffplay displayed a frame.

## Tailscale on car startup

On the car: **Settings → Tailscale auto-connect setup**. Enable **Auto-connect Tailscale with RoadGuard** once. This is off by default. The car service checks network/VPN state about every 15 seconds and, when no VPN is present, sends Tailscale's exported `com.tailscale.ipn.CONNECT_VPN` request at most once per minute. It does not replace another VPN, disconnect Tailscale, change DNS/exit nodes, or force another app onscreen while recording.

Use the buttons to open Tailscale and approve/sign in once. In Android VPN settings, enable Tailscale's **Always-on VPN**, if the stereo exposes it. Allow background activity/unrestricted battery and manufacturer auto-start for Tailscale and RoadGuard. This helper cannot approve Android prompts, bypass OEM restrictions, fix expired login credentials or verify that an active VPN belongs to Tailscale. It can only request connection; verify connectivity by receiving RoadGuard data after a parked reboot test.

Official references: https://github.com/tailscale/tailscale-android/blob/main/android/src/main/java/com/tailscale/ipn/IPNReceiver.java and https://support.google.com/android/answer/9089766 .

Validation: Android compile/lint; Windows compile; 15/30 FPS MPEG-TS tests with 150 decoded frame hashes unchanged; bounded live queue tests; saved-video/power-loss regression; TLS pin/reuse, relay, backup priority/resume, pairing/restart and map tests. Physical phone decoding, hotspot smoothness, actual Tailscale startup and Windows live rendering still require device testing. These changes are not a guarantee of 30 FPS or error-free operation.

