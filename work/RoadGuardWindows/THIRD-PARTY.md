# Third-party components

0.7.0 adds the unmodified ffmpeg.exe and ffprobe.exe companions from the same FFmpeg distribution as ffplay. Android uses AndroidX Media3 1.6.1 (Apache-2.0) and Apache Commons Math 3.6.1 (see Android assets/notices for its license and third-party notices).

Node.js runtime: see NODE-LICENSE.txt (includes third-party notices). Version 24.16.0. Source: https://github.com/nodejs/node/tree/v24.16.0

FFplay: separate, unmodified FFmpeg GPLv3 executable from the existing Gyan 9.0.2 essentials build. See FFMPEG-LICENSE.txt and FFMPEG-README.txt for build configuration and dependency details. The provider identifies FFmpeg source revision https://github.com/FFmpeg/FFmpeg/commit/946fcce07b and build downloads at https://www.gyan.dev/ffmpeg/builds/ . It runs as a separate player process; no FFmpeg code is linked into the Windows dashboard.

This package is supplied for local personal testing. Before redistributing this bundle, obtain and provide the complete corresponding source/build material required by each included component's license, including FFmpeg's configured dependencies; a source link alone may not satisfy redistribution obligations.
# Live-map components

Leaflet 1.9.4 (BSD-2-Clause) is bundled in `live-map/`; its license is `live-map/LEAFLET-LICENSE.txt`. JavaScript/CSS hashes were verified against the official Leaflet download page. OpenStreetMap tiles are an external best-effort service, not bundled data. Visible map attribution links to OpenStreetMap contributors. Usage policy: https://operations.osmfoundation.org/policies/tiles/.

