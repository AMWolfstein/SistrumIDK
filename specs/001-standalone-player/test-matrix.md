# Test matrix

**Baseline**: no device/emulator attached at planning time; host tests and builds run after toolchain installation.

## 1. Build and static checks

| Check | Command | Gate |
|---|---|---|
| Gradle/Java | `./gradlew --version` | JDK 21, Gradle 9.5 |
| Debug build | `./gradlew :androidApp:assembleDevDebug` | succeeds |
| Minified release (as available) | `./gradlew :androidApp:assembleProdRelease` | succeeds or explicit signing blocker |
| Host tests | `./gradlew :androidApp:testDevDebugUnitTest :sharedLogic:testAndroidHostTest` | 0 failures |
| Instrumentation | `./gradlew :androidApp:connectedDevDebugAndroidTest` | arm64 device/emulator or documented block |
| Native compile | CMake via Gradle | TagLib + FFmpeg + aubio/FFTW + JNI link; symbol checks (`fftwf_*`, aubio, TagLib) |
| Shell | `bash -n scripts/build-ffmpeg-android.sh scripts/build-aubio-android.sh` | pass |
| Commit policy | `python3 workflow/work.py commit-check --base <base>` | pass |
| Workflow registry | `python3 workflow/work.py validate` | pass |

## 2. Scanner (fake DocumentsProvider + device)

| Case | Expected |
|---|---|
| Two roots, overlapping parent/child | one item per document key, memberships recorded |
| Empty confirmed root | source cleared only when traversal complete |
| Unconfirmed/EXTRA_LOADING root | retry, then fail; nothing deleted |
| Revoked read grant | source unavailable; rows kept |
| SD card unmount/remount | hidden, then visible; incremental reconnect |
| Process death mid-scan | resume from checkpoint; no duplicates |
| Pause/cancel | position held/cancel preserves completed rows |
| CUE + backing audio | virtual `#trackNN` with start/end offsets; backing excluded; playback/seek/queue restore/analysis use the segment |
| CUE read failure | old virtual rows preserved (fix upstream gap) |
| Rename/move (same root, cross-root, internal↔SD) | unique global `content_key` → re-key one item and preserve favorites/ratings/playlists/artwork/lyrics/analysis; non-unique scoped match; ambiguous → separate items and report |
| Duplicate filenames/volumes | distinct items |
| `.partial`/`.replaced` | ignored |
| Folder cover/disc inheritance | best cover applied only when no embedded art |
| mtime 0 / coarse | re-read or size/version check, never silent skip |
| Large library 10k+ | bounded memory, responsive progress |
| MediaStore provider fallback | scoped to selected `media*` source only |

## 3. TagLib round trips

Formats: MP3, M4A/ALAC, Opus, FLAC, OGG/Vorbis, WAV, AIFF, APE, WavPack, DSF/DFF (read-only if unsupported write).
Assertions: audio bytes/payload unchanged; unrelated tags/art preserved; artwork add/replace/remove; lyrics/analysis tags; JPEG conversion; batch partial failure isolation; read-only/denied write; interrupted write recovery; timestamps/reindex. ReplayGain mapping/round trips: MP3/FLAC/Vorbis `REPLAYGAIN_*`, Opus `R128_TRACK_GAIN`/`R128_ALBUM_GAIN`, M4A freeform tags; no overwrite by default.

## 4. Analysis golden fixtures

Fixtures produced by the upstream desktop producer (outside target): silence, short (<30 s), sine, 120 BPM click, clipping, 44.1/48/96 kHz, mono/stereo, MP3/FLAC/WAV/M4A, album with 3 tracks, missing analysis.
Compare: LUFS, LRA, true peak, RMS, crest, centroid, rolloff, flatness, flux, ZCR, tempo, onset variance, energy, danceability, brightness. Document tolerance per field; tempo/onset validated against aubio 0.4.9/FFTW desktop output.

## 5. Library/search/playlists

| Area | Tests |
|---|---|
| Normalization | articles/fold/digits/delimiters golden |
| Separators | default/custom/empty, multi-value, no double split, rebuild without audio |
| Search | Unicode/diacritics, CJK, prefix/partial, phrase boost, ordering documented |
| Playlists | CRUD/order/rebalance/pinned/artwork/missing files |
| Smart rules | all fields/operators/nesting/limits/16 cap/live vs frozen/mood gate |
| M3U8 | UTF-8/BOM/relative paths/absolute/content URI per decision; round trip |
| Favorites | toggle, virtual playlist, survives scans |
| Ratings | 0–5 edit/display/sort/filter/smart rule; survives scans/migration |
| Artwork | cache hash/variants/cleanup/WebP, Deezer precedence/release date/undated/ties/offline/disable |
| Lyrics | precedence, ranking, timing/offset, save LRC/embed, overwrite confirm, denied write, offline/restart |
| Listening | local-day split, qualified play, retry/restart dedup, retention, insight values |
| Mood | similarity weights, 80/15/3, diversity, exclusions, refill, gate |

## 6. Migration/upgrade

- Empty install → schema v1.
- Populated legacy `library-sync.db` fixture → playlists/favorites/listening/lyrics preserved; unresolved refs journaled; idempotent rerun; interrupted import resumes.
- Upgrade from baseline APK with user-owned rows → no destructive reset; active queue repaired; old DB retained until consent.


## 7. Native build / APK optimization gate (T25)

| Check | Gate |
|---|---|
| Component manifest | Every supported codec/container + analysis filter maps to an enabled FFmpeg component; no unapproved format loss |
| Baseline vs optimized sizes | Same variant/ABI/signing; per-`.so`, APK, installed size; PASS ≥10% APK reduction; 5–10% owner approval; <5% stop/report |
| Performance | Startup/scan/analysis/decode CPU ≤5% regression; no new playback underruns; same environment recorded |
| JNI/link safety | All 24 `Java_com_amwolfstein_sistrum_player_*` exports present; 16 KiB page alignment preserved; no unresolved symbols |
| No forbidden artifacts | APK `lib/`, Gradle tree, NEEDED/imports free of `mpv`, `libmpv`, Media3 runtime, Kyant0 TagLib |
| License | FFmpeg LGPL-only, no GPL components; `--enable-version3` recorded; NOTICES/source offer for static TagLib/aubio/FFTW/libc++ |
| Regression | Full format matrix + TagLib round trips + aubio/FFTW golden analysis + normalization/Mood Radio + playback/UI tests |
| Reproducibility | Build flags/scripts recorded; artifact hashes reproducible |

## 8. Final acceptance

- Clean install airplane mode: add SAF roots, scan, browse/search, edit tags/art, play all formats, playlists/smart, analyze, normalize, Mood Radio, local lyrics, insights.
- Optional online: Last.fm/auth/scrobble, LRCLIB/Kugou, Deezer, update check; failure never blocks playback.
- No remote/LAN/MQTT/mDNS endpoints or permissions.
- APK: dev variant, version 0.1.0, arm64-v8a, signature, manifest, SHA256, size; dependency delta only approved libs (TagLib/aubio/FFTW) and T25 build changes.
- Performance: scan/analysis time, memory, UI latency, playback underruns vs baseline; record environment/limitations.
