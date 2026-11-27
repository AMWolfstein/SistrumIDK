# Dependency inventory

**Baseline**: `cfa44a5` · **Rule**: TagLib plus owner-approved aubio/FFTW are the only new app dependencies; no Go/Wails/Vue/Bleve/miniaudio/Media3/mpv.

## 1. Existing app dependencies (keep unless stated)

| Dependency | Version | Purpose | Action |
|---|---|---|---|
| Kotlin / KMP | 2.4.10 | app + sharedLogic | Keep |
| AGP / Gradle / KSP | 9.3.1 / 9.5.0 / 2.3.11 | build | Keep; no upgrade |
| Compose Multiplatform / Material3 | 1.11.1 / 1.11.0-alpha07 | UI | Keep |
| Haze | 2.0.0-alpha03 | glass/blur | Keep |
| Room runtime/ktx/compiler | 2.8.4 | local DB | Keep; export schema |
| DataStore preferences | 1.1.7 | settings | Keep |
| Coroutines / Serialization | 1.10.2 / 1.9.0 | async/JSON | Keep |
| Reorderable | 3.1.0 | playlist reorder | Keep |
| Vico | 3.2.1 | Insight charts | Keep |
| Markwon + AndroidSVG | 4.6.2 / 1.4 | release notes | Keep |
| Window / Lifecycle / Activity | 1.5.1 / 2.11.0-beta01 / 1.13.0 | UI | Keep |
| CameraX | 1.5.1 | QR pairing only | Remove T23 |
| ML Kit barcode | 17.3.0 | QR pairing only | Remove T23 |
| HiveMQ MQTT | 1.3.14 | desktop sync transport | Remove T23 |
| EdDSA | 0.3.0 | pairing signatures | Remove T23 |
| JUnit / coroutines-test / room-testing | existing | tests | Keep |
| Compose UI test / Espresso / testExt | existing | instrumentation | Keep |

## 2. New dependency (approved)

| Dependency | Pin | License | Integration |
|---|---|---|---|
| TagLib (official C++) | v2.3.2, commit `deadc2990767dfbda0701e0ab35fdeea653db08f`; release tarball SHA-256 `cda2d6dac7f12a84e46fbb0907bb1a1ec0679e4722b5237576be5563e295244e` | LGPL-2.1 / MPL-1.1 | Static-link into `libsistrum_player.so` via existing NDK/CMake (`scripts/build-taglib-android.sh`); checksum verified at build time; zlib optional (`-DWITH_ZLIB=OFF`); notices added. Measured debug `libsistrum_player.so`: 227,928 B without TagLib vs 1,696,992 B with TagLib (+1,469,064 B), stripped; APK impact measured in T25. |
| utfcpp (TagLib requirement) | 4.0.6; release tarball SHA-256 `6920a6a5d6a04b9a89b2a89af7132f8acefd46e0c2a7b190350539e9213816c0` | Boost-1.0 | Header-only helper compiled into TagLib; TagLib's release tarball leaves the submodule empty, so the build script pins and verifies it; notice added. |

Approved analysis additions (owner revision):

| Dependency | Pin | License | Integration |
|---|---|---|---|
| aubio | 0.4.9, commit `90bd27a23123fcc524c31787c9c8fc0ae4c79378`; tarball SHA-256 `df1244f6c4cf5bea382c8c2d35aa43bc31f4cf631fe325ae3992c219546a4202` | GPL-3.0-or-later (compatible with project GPL-3.0) | Source archive: `https://files.pythonhosted.org/packages/cd/80/302d89240603e5347c7f8026c8b02c59f8dfaec66c91a743d82de7c86006/aubio-0.4.9.tar.gz` (the exact URL used by upstream `scripts/build-aubio-linux.sh`); static `libaubio.a`; build flags `--enable-fftw3f --disable-intelipp --disable-accelerate --disable-sndfile --disable-samplerate --disable-jack --disable-avcodec --disable-blas --disable-docs --disable-tests --disable-examples`. |
| FFTW (single precision) | 3.3.11; tarball SHA-256 `5630c24cdeb33b131612f7eb4b1a9934234754f9f388ff8617458d0be6f239a1` | GPL-2.0-or-later | Static `libfftw3f.a` (`--enable-float --disable-shared --enable-static`), required by aubio; notices included. |

No other runtime/build/test dependency is introduced. Build-time only: `fonttools`, `brotli` (existing font subset task), JDK 21, SDK 37, NDK 30.0.16248370, CMake 3.22.1.

## 3. Native/FFmpeg

Current: FFmpeg 9.0.2, LGPL-only, shared, `--disable-everything --disable-network --disable-avfilter --disable-swscale`, all decoders/demuxers/parsers, file protocol; links `avformat avcodec swresample avutil aaudio android log`.

Required additions for analysis:
- enable `avfilter` with exactly `--enable-filter=ebur128,aspectralstats,astats,aresample,aformat,anull,abuffer,abuffersink`;
- keep existing decoders/demuxers/parsers and file protocol;
- document size/license delta before/after;
- tempo/onset: link aubio 0.4.9 (tempo `default` + `hfc` onset) with static FFTW3F, matching the desktop analyzer (owner decision).

Do not add FFmpeg network/RTMP/HTTP protocols; local playback stays file-descriptor/content-URI based.

## 4. Removals (after replacements)

- Gradle: HiveMQ, EdDSA, CameraX (camera2/lifecycle/view/mlkit), ML Kit barcode.
- Manifest permissions: remove `CAMERA`, `ACCESS_LOCAL_NETWORK`, `CHANGE_WIFI_MULTICAST_STATE`; keep `FOREGROUND_SERVICE_DATA_SYNC` for local scans and media playback.
- Services: `LibrarySyncService` (`dataSync`); keep `PlaybackService` (`mediaPlayback`).
- R8: Netty/JCTools/ML Kit/Firebase suppression blocks tied to removed deps; keep native-method rule and Markwon GIF rule.
- SharedLogic: pairing/sync protocol packages and transport models; keep queue/listening/normalization/lyrics/mood logic.
- Room: mirror plans/assets/tracks/playlists/documents and desktop mutation tables; migrated user data preserved first.

## 5. Network endpoints retained

| Feature | Endpoint | Auth |
|---|---|---|
| Last.fm | `https://ws.audioscrobbler.com/2.0/`, `https://www.last.fm/api/auth/` | API key/secret + session |
| LRCLIB | `https://lrclib.net/api/...` | none |
| Kugou | search/download endpoints (upstream) | none |
| Deezer artwork | `https://api.deezer.com/search/artist` | none |
| GitHub updates | GitHub Releases API | none |

No LAN, MQTT, mDNS, pairing, remote-control endpoint or server is retained.

## 6. Toolchain verification

Installed and verified during planning: Temurin `21.0.13.1+2`, SDK platform `android-37.0`, build-tools `37.0.0`, NDK `30.0.16248370`, CMake `3.22.1`, fontTools `4.69.1` + brotli, Gradle wrapper 9.5.0. `local.properties` points to the SDK and fontTools Python.
