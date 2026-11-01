# RoadGuard next stages

## Delivered implementation in 0.5.7 (device acceptance pending)

- Phone recording cards, date/filename search, date headings, kept-only filter, 30-item batches, prominent save-to-phone action and dedicated download progress.
- Car USB library with playback after Stop & Save, search and clip protection.
- Authenticated POST protection endpoint; unprotect requires UI confirmation. Local protection and loop cleanup share a lock.
- New recordings in `Android/data/com.roadguard.ai/files/RoadGuardAI/Car Dash Cam Recordings` on removable storage. Legacy Recordings folder is still indexed, recovered and loop-cleaned. No old footage is moved or overwritten.
- Existing recording health, power-loss fragment recovery and loop storage safeguards retained.

## Implemented in 0.6.0-alpha (physical-device acceptance pending)

- Windows pinned-TLS relay, native dashboard, boot-task installer and dashboard at Windows sign-in. Installer/reboot not exercised on a real installed system.
- Persistent private pairing and remembered same-car read-route fallback. Cross-network access requires separately configured private VPN; two physical PCs not tested.
- Opt-in car location service; coordinates, accuracy, fix age, place when Android geocoder is available, and explicit stale/offline labels on phone and PC.
- Optional resumable PC backups with a size limit and free-disk reserve. No automatic PC backup deletion.
- Phone resume for the last interrupted download to a seekable document. Android document-provider behavior needs physical-phone testing.
- Shared release metadata and version gates; manual matching APK/Windows updates.
- Host TLS/relay/backup tests and simulation-only dashboard visual checks. Test data is explicitly labelled and is not real car evidence.

## Still pending

- VPN installation and separate-network hardware acceptance.
- Phone-to-car distance, routing, embedded map and thumbnails.
- Network-specific data budgets, bandwidth prioritization and coordinated two-PC backup leadership.
- Per-device revocation/list and access audit (existing revoke-all pairing remains).
- AI event marking.

Windows has a basic access log and revoke-all phone access. It does not yet provide per-device audit management in the interface.

## Decisions and acceptance gates

- Windows PC changes between home Wi-Fi and phone hotspot. Do not depend on a fixed public IP.
- Tailscale was proposed; the user has not yet explicitly approved installation/account setup on each device. Verify old car firmware supports the VPN before committing.
- No paid service or public port exposure without approval. Never disable pinning/authentication to obtain connectivity.
- Windows background service starts at boot; interactive dashboard can open only after user sign-in.
- Multiple PCs need explicit trusted registration and deterministic primary/backup selection. Do not duplicate large transfers automatically.
- Recording remains independent of PC/server availability. Local same-network access must continue.
- Test car and phone on separate internet networks, server/network restarts and failover before claiming remote functionality.
- Preserve original recordings on USB; backup is optional. Distinguish remotely listed from actually backed-up footage.
- Do not present cached GPS as live, server availability as recording health, or a connected camera as proof of saved video.
