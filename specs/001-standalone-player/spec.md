# Feature Specification: Complete standalone Android music player

**Feature**: `specs/001-standalone-player`
**Mission**: M001
**Created**: 2026-11-27
**Status**: Draft for independent planning review
**Input**: `alpha10-master-mission.md` (owner's final authoritative mission brief, 111 lines) plus the live `alpha10` working tree and pinned upstream sources.

## Goal

Turn SistrumIDK Android into a complete, offline-first local music player that owns its library, analysis, playback and user data on the phone. It must combine Airmedy Desktop and Airmedy Mobile capabilities natively, keep every explicit owner decision, and never require a desktop app, pairing, desktop database, LAN server, remote control or pre-generated desktop assets. Optional online services already present upstream remain optional.

## Source and environment baseline

- Target: `SistrumIDK`, branch `alpha10`, baseline `cfa44a5` (TOR003 rebrand), clean tree, workflow installed and committed.
- Upstream feature/algorithm authority: `misa198/airmedy` at `d859940c0629907e377c889fb276904b6a7a4b8c` (master tip; same revision imported by TOR001). Read-only clone at `/home/amwolfstein/Documents/GitHub/airmedy-upstream`.
- Android scanner design reference: Mornye at `af9a4d6422759541ec57a9ed696b3b47928a1bff` (LGPL-3.0; behavior reference only, no code copy). Read-only clone at `/home/amwolfstein/Documents/GitHub/mornye-upstream`.
- Forbidden reuse: commit `ea1ad18…` and the older SistrumKotlin port. `origin/main` tip `c605ad79…` is the brief's historical archive reference only, not the working baseline.
- Build environment: toolchain installed during planning — Temurin 21.0.13.1, Android SDK 37.0/build-tools 37.0.0, NDK 30.0.16248370, CMake 3.22.1, fontTools 4.69.1/brotli. (Originally missing; BL-01/BL-03 resolved.)

## User Scenarios & Testing

### User Story 1 — Local library and offline playback (Priority: P1)

A user selects one or more music folders through Android SAF, scans them into an on-device library, browses tracks/albums/artists/genres/composers, and plays any supported file offline. Re-scans are incremental, resumable and never duplicate tracks or lose user state.

**Why P1**: without a local library and playback there is no standalone product.

**Independent Test**: install a clean APK in airplane mode, grant two SAF roots (including one removable/SD root), scan, browse, search, play, kill and relaunch, rescan after adding/editing/deleting a file, and verify no duplicates, correct user state and resumable progress.

**Acceptance scenarios**:
1. Two overlapping SAF roots produce each file once.
2. A scan killed after process death resumes from a durable checkpoint without re-importing completed files.
3. A revoked/unmounted root does not delete its tracks; tracks become unavailable, then return when access is restored.
4. An edited file updates one identity, not a duplicate.
5. No broad whole-device MediaStore scan is performed; MediaStore may only be a scoped fallback for selected sources.
6. Playback continues with screen off and no network.

### User Story 2 — Metadata, artwork and tag editing (Priority: P2)

A user sees complete normalized tags and artwork, edits tags/embedded art for one or many files, and controls automatic artist artwork. Audio payload and unrelated tags survive every write.

**Why P2**: a local library must be maintainable without a desktop tag editor.

**Independent Test**: round-trip MP3, M4A, Opus, FLAC and other supported files: change title/artist/album/genre/rating/artwork/lyrics/analysis tags, verify audio bytes and unrelated tags unchanged; deny write access and verify no partial overwrite; restart and verify artwork precedence and settings.

**Acceptance scenarios**:
1. Batch edit staged, verified and atomically published; failure restores the original.
2. Artist artwork precedence: durable imported Deezer image, else latest released track's usable artwork by release date (undated last, deterministic ties), else placeholder.
3. Disabling Deezer stops new imports but keeps and shows existing images offline.
4. `Settings → Integrations → Online Artwork` is a separate page directly below Lyrics, with a Deezer toggle OFF by default.
5. Separator edits rebuild derived relationships without rereading audio or rerunning analysis.

### User Story 3 — Library views, search, playlists, favorites and lyrics (Priority: P3)

A user browses and searches a large local library, manages manual and smart playlists, favorites/ratings, and uses local then online lyrics with durable save options.

**Why P3**: these are the daily-use features that make the library usable at scale.

**Independent Test**: create/edit/reorder/delete playlists, use a smart playlist rule, favorite/rate tracks, search multilingual text, use local LRC/TXT and embedded lyrics offline, search online, save LRC and embed lyrics, and verify state across rescans and restarts.

**Acceptance scenarios**:
1. Playlist ordering and membership survive scans, missing files and process death.
2. Smart playlists use the upstream rule fields/operators/combinations/sorting/limits and refresh live.
3. Favorites/ratings are user-owned and survive scans/migrations.
4. Lyrics precedence: adjacent `.lrc`, adjacent `.txt`, configured subfolder, dedicated lyrics folder, embedded, then enabled online providers (respect `prefer_local_lyrics`).
5. Saving online lyrics offers `Save as LRC` or `Embed in Audio`; denied/read-only/unsupported paths report failure without claiming success; overwrite asks first.

### User Story 4 — Analysis, Mood Radio, listening insights and online integrations (Priority: P4)

A user analyzes tracks locally, embeds/reads versioned analysis, runs Mood Radio, sees accurate listening history/insights, and uses Last.fm and update notices without blocking offline playback.

**Why P4**: these differentiate the product but depend on a stable library and playback.

**Independent Test**: analyze a fixture album on Android and with the desktop producer, compare every field within documented tolerance, restart/reinstall and reuse tags without reanalysis, run Mood Radio, verify counts after retries/process death, and verify Last.fm failure never blocks playback.

**Acceptance scenarios**:
1. Analysis matches upstream algorithms/constants/units; no arbitrary estimates or random selection.
2. Room and embedded tags carry schema/version and audio-content fingerprint; stale/changed audio is rejected and reanalyzed.
3. ReplayGain defaults preserve existing tags; only standard gain/peak semantics are used independent of playback target LUFS.
4. Existing ReplayGain tags are not overwritten by default; user consent governs writes.
5. Mood Radio eligibility/refill/tie-breaks follow upstream and consume local analysis only.
6. Listening events are not double-counted across retries, restarts or migrations.
7. Offline/online failures are explicit and never block playback.

### User Story 5 — Desktop dependency removal and release delivery (Priority: P5)

The app has no reachable desktop pairing/sync/LAN/remote-control runtime, all replacements are tested, and a signed dev APK with a test report is produced.

**Why P5**: the final product must be genuinely standalone, not a mirror with dead desktop code.

**Independent Test**: inspect dependency/manifest/JNI/DI graph, run the final airplane-mode clean install, upgrade test with preserved state, and verify APK signature/manifest/ABI/SHA256.

**Acceptance scenarios**:
1. No MQTT/HTTP sync, QR pairing, mDNS discovery, signed sync transport, desktop asset pulling, sync service, reconciliation/acks or mirror-only schema remains reachable.
2. Independent online networking (Last.fm, lyrics, updates, Deezer artwork) remains.
3. Final APK builds debug and minified release as available, with documented version/variant/ABI/signature/SHA256.
4. Any unverified device/emulator coverage and remaining parity gaps are reported; the mission is not called complete with mandatory blockers.

## Edge Cases

- Empty library, tens of thousands of tracks, very long paths, Unicode/RTL names, duplicate filenames on different volumes.
- SAF grant revoked, SD card removed during scan/playback, URI changed after move/rename, folder inaccessible.
- Process death mid-scan, mid-tag-write, mid-analysis, mid-playlist transaction.
- Corrupt/unsupported/zero-length files; truncated audio; missing artwork; conflicting embedded tags.
- CUE sheets and folder artwork where compatible; read-only storage; denied write consent.
- Multiple analysis versions, changed audio content with unchanged tags, album membership changes.
- Playback route changes, headset unplug, Bluetooth, audio focus loss, next-track failure, gapless/crossfade/EQ/normalization interaction.
- Online provider timeout/offline/no-result; credentials missing; cached lyrics/artwork must still work.
- Upgrade from the current sync-mirror schema with existing owned playlists/favorites/listening state.
- No Android device attached; emulator vs physical coverage must be stated.

## Functional Requirements

### A. Baseline and native capability

- **FR-001**: Establish a reproducible baseline build, host tests, APK size/ABI/signature, FFmpeg version/configuration and JNI surface; record pre-existing failures separately.
- **FR-002**: Preserve all current Android features and visual identity/navigation/animations/adaptive player throughout migration.
- **FR-003**: Keep Kotlin, sharedLogic, Jetpack Compose, Room, DataStore, JNI, C++20, FFmpeg and AAudio.
- **FR-004**: TagLib (official C++) plus owner-approved aubio 0.4.9/FFTW 3.3.11 for analysis are the only new runtime/build dependencies. No Go/Wails/Vue/Bleve/miniaudio/Media3/mpv or replacement engine.
- **FR-005**: Do not upgrade toolchains/SDK/ABI/versions arbitrarily; do not modify frozen iOS targets.

### B. Local library and scanner

- **FR-010**: User-selected SAF folders are the source of truth; multiple roots (including overlapping roots, deduplicated by document identity), recursive traversal, persistable read/write grants, SD/removable support.
- **FR-011**: Support initial scan, manual incremental scan, full rescan, configurable automatic scan, exclusions, progress, pause/resume/cancel.
- **FR-012**: Durable checkpoints survive process death; ingestion is bounded, streaming/batched, transactional and battery-aware under OS limits.
- **FR-013**: Handle overlapping roots, duplicate filenames/volumes, URI changes, moved/renamed files, inaccessible folders, revoked grants, cancel and process death.
- **FR-014**: Never treat temporarily unavailable storage as deletion; deletion only after successful enumeration.
- **FR-015**: Preserve stable identities and user state across rescans, moves and renames (identity fingerprint fallback); avoid duplicate imports across folder and media index.
- **FR-016**: MediaStore is only a scoped fallback for selected sources, never an independent whole-device scan.
- **FR-017**: Room is authoritative for track, album, artist, album artist, composer, genre, playlist, favorite, rating, analysis, artwork, lyrics and listening state.
- **FR-018**: Migrate obsolete mirror semantics safely; protect user data; handle the active queue when tracks change/disappear; no destructive reset.
- **FR-019**: Separate file catalog updates from user edits.

### C. Metadata, artwork, tag editor, separators

- **FR-020**: Port upstream metadata extraction/normalization for every consumed field, multi-value tags, disc/track, year, sorting, technical metadata and lossless formats. Ratings are not upstream; see FR-063/OD-010.
- **FR-021**: Use official TagLib for reads/writes of metadata, embedded artwork, lyrics and analysis tags; static-link into the existing native library with minimal JNI.
- **FR-022**: Preserve MP3, AAC/ALAC, FLAC, WAV/AIFF, Vorbis, Opus, APE, WavPack, DSD support wherever upstream supports it; validate by content, not extension.
- **FR-023**: Implement single/batch metadata editing and embedded artwork replacement/JPEG conversion; preserve audio payload and unrelated tags.
- **FR-024**: Stage and verify before replace; recoverable writes, correct timestamps, reindex/cache invalidation; never overwrite on unsupported/failed writes.
- **FR-025**: Artist artwork precedence: imported Deezer (durable) > latest released track artwork by tag release date (undated last, deterministic ties) > placeholder; never replace cached Deezer with track art.
- **FR-026**: `Settings → Integrations → Online Artwork` directly below Lyrics; Deezer toggle OFF by default; disable stops new imports but keeps/display existing images offline.
- **FR-027**: Store imported art durably in app storage; atomic publication, content invalidation, bounded cleanup; failed lookups never erase art; user customization survives scan/process death.
- **FR-028**: Port Desktop Metadata Separators for Artist, Album Artist, Genre and Composer (the upstream fourth category, beyond the brief's three; flagged for the owner); verify defaults/matching/exceptions; add/edit/delete custom separators.
- **FR-029**: Keep raw tag values; separator changes rebuild derived relationships/indexes without rereading audio, rewriting tags or rerunning analysis; no double splitting; no duplicate tracks.

### D. Library views and search

- **FR-040**: Keep all existing mobile routes working from local data; port missing Home, library, detail, sort/filter, context actions, ratings and navigation behavior.
- **FR-041**: Implement search equivalent to upstream Bleve semantics using existing SQLite/Room/Kotlin; no Bleve.
- **FR-042**: Compare Unicode/diacritics, multilingual tokens, exact/partial matches, ordering, tie-breaks and update behavior; flag irreproducible ranking instead of claiming identity.
- **FR-043**: Optimize indexes/queries for large libraries and keep UI responsive.

### E. Playback and audio engine

- **FR-050**: Keep the existing FFmpeg/AAudio engine and native boundaries; wire local seekable files/FDs safely.
- **FR-051**: Validate every desktop-supported format/rate/channel/bit depth and output route.
- **FR-052**: Preserve queue, shuffle/repeat, restore, seek, gapless, equal-power crossfade (1–12 s when enabled; 0 = off, upstream range 0–12), 10-band EQ/presets, gain staging, normalization, audio focus, headset/Bluetooth, media keys, lockscreen/media session/notifications, background playback and route changes.
- **FR-053**: Port missing desktop controls with mobile equivalents (quick settings, sleep prevention equivalent, mini/fullscreen behavior); do not bypass OS background rules.
- **FR-054**: Verify crossfade/normalization/EQ interaction, next-track failure, pause/resume and resource lifetimes; no second engine.

### F. Playlists, favorites, smart playlists

- **FR-060**: Port create/edit/delete/reorder, membership, duplicates, artwork, favorites/rating and all playlist actions.
- **FR-061**: Port playlist import/export; fix upstream defects per owner decision: export smart/favorites correctly, support relative paths, UTF-8 BOM and `#EXTENC`, while keeping the M3U8 directives.
- **FR-062**: Direct Room transactions and observable updates; no pending desktop mutation queue or syncFailed state.
- **FR-063**: Add Sistrum 0–5 star ratings as an explicit product extension (upstream has none), with Room storage, editor/display, sorting/filtering and smart-rule eligibility.
- **FR-064**: Port the smart playlist rule engine (fields, operators, combinations, sorting, limits, live refresh, mood-based selection) without a reduced subset.
- **FR-065**: User data survives scans, missing files and migrations; ordering/owned state preserved.

### G. Lyrics

- **FR-070**: Source precedence: adjacent `.lrc`, adjacent `.txt`, configured lyrics subfolder, dedicated lyrics folder, embedded, then enabled online LRCLIB/Kugou; respect `prefer_local_lyrics`; preserve manual search/selection.
- **FR-071**: Preserve timestamps, offsets, Unicode, plain/synced content; confirm overwrite; validate round trips; refresh display immediately.
- **FR-072**: After online selection, offer `Save as LRC File` (same basename beside audio) or `Embed in Audio File` (TagLib, format-appropriate).
- **FR-073**: If embedding cannot preserve timing, offer LRC; if read-only/denied, report failure and allow retry/alternative; never claim saved.
- **FR-074**: Port parsing, matching/ranking, offsets, bilingual/word timing where implemented, cache, editing/save, offline Chinese/Korean romanization and existing glow/scroll/seek animations; keep current UI.
- **FR-075**: Handle write consent, encoding, invalid lyrics, offline/timeout and no-result separately; online requests only when enabled/needed.

### H. Analysis and Mood Radio

- **FR-080**: Trace and port the exact upstream FFmpeg analysis source: filters, PCM handling, loudness/true peak, track/album aggregation, energy, danceability, tempo, similarity, eligibility, refill, tie-breaks, flags, versions, units, coefficients, precision.
- **FR-081**: Use the owner-approved aubio 0.4.9 (tempo default + HFC onset, FFTW3F) for exact tempo/onset parity; no other DSP/ML dependency and no arbitrary estimates or random selection.
- **FR-082**: Build deterministic golden fixtures with the original desktop producer; compare per-field including silence, short tracks, formats/rates, clipping and albums; justify tolerances.
- **FR-083**: Enable only required existing FFmpeg components; document size/license impact.
- **FR-084**: Persist versioned results in Room and embed in writable files via TagLib; support `Save analysis to audio files`, `Reuse existing analysis`, `Preserve existing ReplayGain tags` (default ON, consent-aware).
- **FR-085**: Use standard ReplayGain track/album gain/peak only with correct reference/units/semantics independent of playback target LUFS; do not overwrite existing ReplayGain by default.
- **FR-086**: Store measured LUFS, true peak, BPM, energy, danceability and required fields with schema/algorithm version and audio-content fingerprint (not whole-file hash).
- **FR-087**: Validate ranges/versions/audio identity/album membership before reuse; import valid scan results; analyze only missing/stale fields; invalidate album aggregates on membership change.
- **FR-088**: Read-only/unsupported/denied files use Room only with visible status; serialize writes per file; verify audio payload/unrelated tags; avoid scan feedback loops; preserve playback.
- **FR-089**: Separate bounded discovery, metadata, analysis and write scheduling; incremental invalidation, resumable/cancellable scheduling, throttling, progress; no analysis on playback realtime thread; thermal/battery/concurrency safety.
- **FR-090**: Preserve Track/Album normalization modes, default -14 LUFS and clipping guard; replace desktop analysis gate with local setting/readiness.
- **FR-091**: Integrate all analysis consumers, smart mood playlists and genuine auto-refilling Mood Radio; stop and ask if parity is blocked.

### I. Listening history, Insights and online integrations

- **FR-100**: Port raw/aggregate listening metrics, qualified plays, durations, completed/skipped/stopped events, counts, timestamps/timezones, retention, rankings/tie-breaks and time filters.
- **FR-101**: Prevent duplicate counts across retries/restarts/migrations; preserve history.
- **FR-102**: Keep independent Last.fm auth/scrobble/love/unlove and add a bounded persistent offline retry queue (upstream has none; owner-approved fix); no credentials in logs.
- **FR-103**: Preserve update notices and other implemented online services; remove desktop identity dependency; use stable local install identity.
- **FR-104**: Ask about cross-device history scope if it cannot be resolved without desktop; offline failures never block playback.

### J. Remaining desktop features and exclusions

- **FR-110**: Audit all remaining desktop features (appearance, quick settings, shortcuts, tray equivalent, background controls, accessibility, localization, file operations, settings, context actions) and provide mobile equivalents; no silent omissions.
- **FR-111**: Do not port remote control, remote browser UI, LAN control servers, discovery or dedicated remote authentication.
- **FR-112**: Remove existing dedicated remote-control code after reference verification; preserve MediaSession, Bluetooth/headset, lockscreen and independent online services.

### K. Remove obsolete desktop dependency

- **FR-120**: Only after local replacements/tests land, remove pairing/QR, discovery/MQTT, signed sync transport, desktop asset pulling, sync service/permissions, reconciliation/acks, obsolete protocol/mirror fields, resources, dependencies and R8 rules.
- **FR-121**: Keep genuinely used networking; replace obsolete tests; no dead runtime adapters kept only for tests.
- **FR-122**: Audit generated code, manifest merger, DI, reflection, JNI, sharedLogic consumers, Gradle tree and transitive runtime references; no dummy desktop/fake snapshots/duplicated truth/destructive DB cleanup.

### Verification and delivery

- **FR-130**: Follow workflow map/review/test/commit rules per task; update traceability after each task.
- **FR-131**: Run Kotlin/shared/Room/native host tests, migrations/transactions, UI/instrumentation where possible; compare Android/desktop fixtures.
- **FR-132**: Test Android 12/current target behavior, permissions, empty/large libraries, removable storage, file mutations, corruption, duplicates, rescan cancel/restart, interrupted writes, analysis/playback concurrency, offline/online, no remote endpoints.
- **FR-133**: Final clean-install airplane-mode test; upgrade test preserves owned state.
- **FR-134**: Run workflow checks, FFmpeg build/link, debug and minified release builds as available; verify APK signature/manifest/ABI; dependency delta only approved libs and T25 build changes.
- **FR-135**: Produce dev APK in workflow-required location with SHA256, variant/version and test report; no signing secrets; no push without permission.
- **FR-136**: Final report lists implemented features, gaps, source mapping, tests actually run, unverified work, decisions, commits and APK path; never label complete with mandatory blockers.
- **FR-137**: After all feature work (TagLib, aubio, analysis, normalization) is implemented and validated, run a dedicated native-build/APK-size optimization phase as its own commit before the final audit and APK build; preserve all formats/quality/features/performance and measure before/after.
- **FR-138**: Use Looper/mpv_audio_kit/libmpv-scripts strictly as build-optimization reference; do not migrate to mpv or replace FFmpeg+AAudio.

## Explicit owner decisions (override upstream)

- **OD-001**: Remote control, remote playback clients, LAN control servers, browser control, discovery and desktop pairing/auth are out of scope and intentionally excluded, not missing parity.
- **OD-002**: TagLib plus owner-approved aubio 0.4.9/FFTW 3.3.11 are the approved new dependencies; anything else needs approval.
- **OD-003**: Mornye overrides the desktop scanner mechanism for Android scanning; Airmedy library semantics still apply.
- **OD-004**: Deezer artist artwork is OFF by default; page below Lyrics; durable imported images survive disable/offline/restart; precedence as FR-025.
- **OD-005**: Preserve existing ReplayGain tags by default; analysis writes are consent-aware.
- **OD-006**: Keep current visual identity and UI; adapt desktop mechanisms to mobile equivalents, no redesign.
- **OD-007**: Never reuse `ea1ad18…`/SistrumKotlin; upstream `d859940c` is authoritative.
- **OD-008**: iOS is frozen.
- **OD-009**: Add aubio 0.4.9 + FFTW 3.3.11 as explicitly approved analysis dependencies for exact tempo/onset parity (supersedes the earlier reimplementation decision).
- **OD-010**: Add 0–5 star ratings as a Sistrum product feature, not upstream parity.
- **OD-011**: Fix upstream M3U8/relative-path/BOM and Last.fm retry defects; document every deviation.
- **OD-012**: Run a dedicated native-build/APK-size optimization phase after all features and before the final audit/APK; Looper/mpv_audio_kit/libmpv-scripts is reference only; no mpv/Media3 migration.

## Key Entities

Track, Album, Artist, AlbumArtist, Composer, Genre, TrackArtist/credits, Playlist, PlaylistTrack, SmartPlaylistRule, Favorite/Rating, ArtworkAsset (embedded/imported/generated placeholder), Lyrics (local/embedded/online cache), AnalysisResult (versioned), AlbumAnalysisAggregate, ListeningSession, PlaybackAttempt, DailyListeningStats, LibrarySource (SAF root), ScanCheckpoint, ScanError, TagWriteJournal, AppSetting.

## Success Criteria

- **SC-001**: Clean install in airplane mode can select ≥2 SAF roots, scan 10k+ tracks incrementally, browse/search, play and survive process death with zero duplicate imports.
- **SC-002**: Every supported format round-trips tags/artwork/lyrics/analysis with unchanged audio bytes and unrelated tags; failures never partially overwrite.
- **SC-003**: Analysis fields match the desktop producer within documented tolerances on golden fixtures; stale/version-mismatched results are rejected.
- **SC-004**: Mood Radio, smart playlists, lyrics, insights and favorites produce upstream-equivalent observable behavior on identical fixtures.
- **SC-005**: No reachable desktop pairing/sync/LAN/remote-control code, permissions or endpoints remain.
- **SC-006**: Final APK builds and passes the documented test matrix; unverified areas are explicitly listed, not hidden.

## Assumptions and open items

- Owner installs JDK 21, Android SDK platform 37/build-tools, NDK 30.0.16248370 and CMake 3.22.1+ before baseline build; otherwise Task A is blocked.
- Android device testing may be unavailable; emulator/device coverage will be reported.
- Exact upstream revision is `d859940c` (master tip, no newer commits at audit time); any later upstream feature requires owner decision before scope change.
- TagLib is pinned to v2.3.2 (commit `deadc299…`); build integration is validated in T01/T05.
- Cross-device listening-history sync is not assumed; only local history unless owner requests otherwise.

## Out of scope

Desktop app packaging, Go/Wails/Vue runtime, remote control/LAN/browser control, desktop pairing/sync, iOS changes, unrelated redesign, new DSP/ML/image libraries, and any dependency beyond TagLib/aubio/FFTW without approval.
