# RoadGuard 0.7.1-alpha connection update

Use version **0.7.1-alpha, code 22** on the car and phone, with the matching Windows companion. The same APK supports both Android roles. This update retains the 0.7.0 recording, timestamps, storage reserve and timeline features.

## Update all three devices

1. On the car, select **STOP & SAVE** and wait for saving to complete. Install the new APK over RoadGuard.
2. Install that same APK over RoadGuard on the phone.
3. Extract the complete Windows ZIP to a folder and run **Setup.cmd**. Windows may request administrator approval. Setup replaces the installed program while retaining its pairing configuration and PC recordings.
4. Open the car app, verify **Recording** and increasing saved bytes, and keep sharing enabled. Open the phone app and Windows dashboard. Previously paired devices should use their saved credentials when the endpoints are reachable.
5. If this is a first connection, connect the car to the phone hotspot. Generate the car pairing code/QR and pair the phone. Pair the car from the Windows dashboard when the PC is on the same reachable network.
6. To access through the PC, generate **Pair phone** in Windows and use the PC's address with port **8944** on the phone. Direct car connections use port **8943**.
7. For different-network access, keep Tailscale connected on the participating devices, enable RoadGuard private-network access on the car and use the appropriate private VPN addresses.

Install over the existing application to preserve its identity and saved pairing. Versions are checked, so a partly updated setup can report a version mismatch until all endpoints are updated. Android permissions, hotspot isolation, Windows firewall policy, VPN sign-in and device sleep still affect connectivity.

## Connection faults addressed

- **PC appeared offline during long automatic backups.** The background schedule previously waited for the whole backup before checking status again. Health polling now runs independently while a backup is in progress.
- **Phone retry missed connection-opening failures.** Connection creation and response-header reading are now inside the retry handler.
- **Too few car server workers for concurrent activity.** The bounded server pool now starts up to eight workers on demand. This gives live viewers, idle keep-alive connections, downloads and control requests room to coexist.
- **Car address changed after hotspot reconnection.** The Windows server can receive the car's existing local discovery beacons. It verifies the previously paired certificate and matching app identity before updating the address. The phone live screen can also attempt verified rediscovery while reconnecting. VPN-address pairings remain dependent on VPN reachability, and blocked UDP discovery may require an address update.
- **Phone automatically discarded credentials on an authentication rejection.** It now reports the rejection while retaining saved credentials for diagnosis or explicit re-pairing. Revoked credentials still cannot authorise access.
- **Ambiguous TLS timeout handling on Windows.** The initial TLS handshake has a bounded timer that is cleared after the handshake. Request inactivity remains separately bounded.

## Live video and downloads

Both direct car live subscribers remain supported. Android live packet creation now uses fewer temporary arrays, and Windows reuses a transport packet buffer. Recording-list identifier hashing also avoids repeated formatting allocations on the car. These changes reduce allocation work, but achieved FPS and latency require testing on the camera, head unit and network.

Phone downloads automatically retry up to three temporary network interruptions from the number of bytes already written. Resume responses must report the exact expected range. A local destination-write error stops the transfer. The existing **Resume interrupted download** control remains available after retry exhaustion or manual cancellation.

On Windows, **right-click a completed car recording → Save this recording to PC**. This selected transfer can run while live viewing is open. Its progress appears in the backup status, and the completed PC copy can be opened by double-clicking it. It uses the configured PC backup budget and disk reserve. Automatic whole-library backup still pauses for PC live viewing. A selected transfer uses the car's background-read pacing while recording, and may take time on a slow connection.

## Verification

The Android debug build and lint completed successfully. Host tests passed for pinned TLS, pairing/version checks, two simultaneous relay clients, a selected transfer while both clients were connected, interrupted/resumed transfer byte equality, offline saved-video metadata, reconnection after PC restart, car LAN address rediscovery and status polling during an active automatic backup.

Synthetic live-video tests passed at 15 and 30 FPS with 150 decoded frames, expected durations and unchanged frame hashes. The Android live hub passed keyframe-join, two-viewer limit, packet framing, slow-viewer recovery and disconnect cleanup checks.

These checks use synthetic sources and host execution. Android USB camera capture, real hotspot/VPN behavior, phone download retries on a document provider and on-device visual playback still require a parked test. The build does not establish a universal zero-lag or zero-disconnection guarantee.

## Parked connection test

1. Watch live on the phone and PC simultaneously. Compare movement with the car preview and confirm saved-byte progress continues.
2. Save a completed clip from each viewer. Check that the finished video opens and has the expected duration.
3. Briefly disconnect and restore the hotspot. Observe automatic reconnect. If the car changes local IP, allow discovery and certificate verification time.
4. Restart the PC companion and confirm its saved pairing returns.
5. For VPN access, repeat with devices on separate networks and verify Tailscale reachability first.
6. If failure persists, collect the exact error, app version on all devices, whether the car preview is smooth, and USB diagnostics from the same failed session.
