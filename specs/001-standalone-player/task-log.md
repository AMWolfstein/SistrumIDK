# Task log — M001

## Planning session — 2026-11-27

- Baseline: `alpha10` @ `cfa44a5`; clean tree; workflow installed; mission M001 (`specs/001-standalone-player`).
- Map: full CGC reindex (1,222 s) + snapshot; 3,483 symbols, 5,662 relationships; 413 inputs/138 exclusions; 355 extracted, 5 unindexed, 3 rejected cross-language calls. Adapter 900 s timeout exceeded; pinned CLI used directly.
- Sources: Airmedy `d859940c` (master tip), Mornye `af9a4d64` (LGPL-3.0 scanner reference), TagLib v2.3.2 candidate.
- Audits: upstream domain/data, analysis/playback/lyrics, frontend/mobile UI, Mornye scanner, target baseline.
- Toolchain installed: Temurin 21.0.13.1, SDK platform 37.0, build-tools 37.0.0, NDK 30.0.16248370, CMake 3.22.1, fontTools 4.69.1.
- Artifacts: spec, research, data-model, plan, tasks, parity matrix, source map, dependency inventory, test matrix, blockers, quickstart, contracts.
- Baseline build (`:sharedLogic:testAndroidHostTest :androidApp:testDevDebugUnitTest :androidApp:compileDevDebugKotlin`): Kotlin compile and resources succeeded; sharedLogic 59/59 pass; androidApp 280/281 pass, one known failure `RomanizationTest.offlineDesktopFixtures` (missing upstream data, BL-04).
- Owner decisions (2026-11-27): add aubio 0.4.9 + FFTW 3.3.11 (revised from in-house reimplementation); add 0–5 ratings; fix M3U8/relative/BOM and add bounded Last.fm retry. Remaining decisions: PiP/Android equivalents, release signing, device coverage.
- Added by owner: a dedicated native-build/APK-size optimization phase (T25, separate commit) after all features, before final audit/APK; Looper/mpv_audio_kit/libmpv-scripts is reference only.
- Next: independent planning review → fixes → one planning commit → allocate task IDs → execute T01.

## M001-T001 — Reproducible baseline and build enablement

- Map: source unchanged since planning; snapshot rebuilt from the existing CGC index (3,483 symbols, 5,662 relationships; 5 unindexed: 4 Gradle `.kts` + `LivingArtworkShader.kt`).
- Changes: removed the `../internal/infra/romanization/data` sibling dependency and generated-asset task; added upstream `pinyin.txt`/`TSCharacters.txt` with attribution/license to `androidApp/src/main/assets/romanization/`; test reads app assets.
- Baseline: FFmpeg 9.0.2 arm64-v8a built by `scripts/build-ffmpeg-android.sh` (shared libs: avcodec 6.9M, avformat 1.2M, avutil 504K, swresample 68K); `libsistrum_player.so` 228K with 24 JNI exports.
- Tests: androidApp 281/281 host tests; sharedLogic 59/59; `assembleDevDebug` succeeds.
- APK: `androidApp-dev-debug.apk`, 71 MB, `com.amwolfstein.sistrum.dev` 0.1.0 (1), arm64-v8a, debug V2 cert SHA-256 `86547a5a…`.
- Limitations: no device/emulator; APK size is the pre-T25 baseline; debug variant includes debug tooling.
- Review: Round 1 PASS from an independent read-only reviewer (build/data/test/APK/JNI evidence verified).

## M001-T002 — Local Room database and legacy migration scaffold

- Added `library/db` package: 30 entities, DAO, `library.db` v1 with exported schema, no destructive fallback, 0–5 rating triggers.
- Legacy importer reads `library-sync.db` read-only in one transaction; merges local+synced playlists, replays only pending mutations in order (`track_id`/snake_case, next-then-previous move precedence, no duplicate add, artwork hash), imports favorites before mutations, active-plan metadata, listening sessions/attempts/daily aggregates, journals unresolved tracks/lyrics and path/duration; reruns are idempotent via `legacy_import_state`.
- Tests: `LibrarySchemaTest` validates every indexed column, exact table count and core columns; `LibraryDatabaseTest`/`LegacyMirrorImporterTest` compile (instrumentation); host tests 282/282.
- Limitation: instrumentation tests not executed (no device/emulator).
- Review: Round 3 PASS (earlier rounds found @Upsert/cascade, mutation replay, listening/favorites, rating-trigger and test-fixture issues; all fixed and re-verified).

- Map note: the snapshot was rebuilt from the existing CGC index (source read fine), so the 11 newly added Kotlin files are not yet in the symbol index; a full reindex is the first step of the next task.

## M001-T003 — SAF sources, grants and library settings UI

- Added `SafSourceRepository` (stable source IDs, persistable read/write grants with read-only fallback, volume/removable metadata, tri-state availability refresh, grant release on remove) and `LibrarySettingsContent` (add/remove/enable folders, status/error states).
- Wired `Settings → Library` into `AppDestination`, navigation metadata, settings list and destination content; added strings.
- Tests: `SafSourceRepositoryTest`; host 283/283; androidTest compiles. Actual SAF picker/grant behavior requires a device/emulator (not run).
- Limitation: no scan action yet (T04); no device.
- Review: Round 3 PASS. Non-blocking gaps: no Compose content test and new strings are default-locale only; actual SAF picker/grant runtime remains device-dependent.

## M001-T008 — Artwork cache, artist precedence and Online Artwork page

- Added `library/artwork`: SHA-256 content-addressed cache in durable app storage with validated decode, `_md` 500 px / `_sm` 64 px JPEG q85 variants, dedupe by key, LRU cleanup that never deletes artwork referenced by items, albums, artists or playlists, and bounded imports (10 MB).
- Format detection uses file signatures (JPEG/PNG/WebP/GIF/BMP) rather than provider MIME or extension, which covers the WebP case; decode falls back to `ImageDecoder` when `BitmapFactory` refuses an image.
- Added owner precedence: durable Deezer import > latest released track artwork by tag release date (parsed `YYYY`, `YYYY-MM`, `YYYY-MM-DD`; undated last; track-id tie-break) > placeholder. Deezer imports are never replaced by track artwork; failed/offline lookups keep the current image.
- Added `DeezerArtworkClient` (search + download) with exact normalized-name matching only, plus `ArtworkPreferences` (`deezer_artist_artwork`, OFF by default) and `ArtworkLibraryService` for embedded artwork, folder covers, fallbacks, online resolve, cache summary/cleanup.
- Wired artwork after scan publication (bounded embedded population + folder covers + artist fallbacks + cleanup) and after tag edits (refresh item + artists). Folder covers from the scanner are now used instead of discarded.
- Added `Settings → Integrations → Online Artwork` directly below Lyrics: Deezer toggle, cache summary, clear-unused action, precedence explanation. Strings added to default and `values-en`.
- Tests: new host tests for hash/format/variants/scaling, precedence golden cases (release dates, undated, ties, partial dates, missing art) and Deezer parsing/normalization; device tests `ArtworkCacheTest` (variants, dedupe, WebP, invalid data, cleanup), `ArtworkLibraryServiceTest` (disabled toggle makes no request, offline failure keeps the durable Deezer image, Deezer import wins and fallback never replaces it, folder covers stay in their folder) and `OnlineArtworkContentTest` (real default-off toggle, summary, clear action, Online Artwork directly below Lyrics) — 12/12 PASS on OnePlus CPH2307 (Android 15). Host suites: androidApp 347/347, sharedLogic 59/59.
- Review fixes: artwork population no longer shares `publish` failure handling (it can never mark a scanned source unavailable); folder-cover application pages through items with an id cursor instead of materialising the whole source; fallback recompute orders by `artworkUpdatedAt` so every artist advances; cache dedupe repairs a row whose files disappeared and a `Throwable` write failure cleans up; a Deezer row whose asset vanished is downgraded instead of staying broken; enabling Deezer also resolves newly added artists on later scans.
- Limitations: CGC index predates the new package; Deezer network behavior is covered by parser/golden tests plus an injected request seam, not a live call; new strings exist only in default/English (pre-existing locale gap); list/player surfaces read cached artwork when T10 wires the local query layer, so T08 populates and serves the cache but does not yet replace the sync artwork path in the UI.

## M001-T009 — Metadata separators and derived-relationship rebuild

- Ported the upstream delimiter rules into `library/metadata/Separators.kt`: defaults `;`, `\`, `,` for artist, album artist, genre and composer; literal (not regex) separators; an empty list means no split; validation trims, rejects empty, more than five code points and duplicates; split results are trimmed, empties dropped and deduplicated case-insensitively.
- Genuine multi-value tags stay separate because each raw tag value is split independently, so removing every delimiter never merges frames and repeated splits are no-ops.
- `EntityResolver` now applies the configured separators during scan publication; `LibraryRepository` loads them from a new JSON-backed DataStore (`SeparatorPreferences`), so a fresh scan and a rebuild produce identical entities.
- `RelationshipRebuilder` re-derives artist/track-artist, album-artist, genre and composer entities and junctions from the stored raw JSON values inside one Room transaction. It reads no audio, rewrites no tags, and preserves raw values, favorites, ratings, item/album artwork and the Deezer artist artwork; album artists are rebuilt as the union of their tracks; orphaned artists, genres, composers and albums are deleted after rewiring.
- Added `Settings → Library → Metadata separators`: four category cards with add/remove/reset, validation errors, and a rebuild action that reports counts or a failure without touching the library. New strings in default and `values-en`.
- Tests: host `SeparatorsTest` (defaults, literal delimiters, multi-delimiter order, empty list, no double split, dedupe, code-point validation, config updates, resolver integration); device `RelationshipRebuilderTest` (splitting without audio, preserved state/artwork, repeatability, orphan cleanup) and `SeparatorsContentTest` (four categories, duplicate and too-long errors) — 6/6 PASS on OnePlus CPH2307 (Android 15). Host suites: androidApp 359/359, sharedLogic 59/59.
- Review fixes: artist/genre/composer rows are inserted before their junctions (fixes an FK violation found by the device test), existing artist rows are seeded so a rebuild never wipes Deezer artwork, album artists fall back to the track artists when the album-artist tag is blank, and the rebuild action re-reads the latest persisted config.
- Limitations: the rebuild does not regroup albums when the album-artist split changes (album identity and artwork stay stable; documented deviation), and there is no search index to rebuild yet (T10). New strings exist only in default/English (pre-existing locale gap).

## M001-T010 — Local library queries, search and UI wiring

- Added `LibraryCatalog`, the read surface every library screen uses, implemented by the legacy sync store and by the new `LocalLibraryCatalog` backed entirely by `library.db` (tracks with split artists/album artists/genres/composers, albums with artist/count, artists, genres, composers, artwork paths from the content-addressed cache, listening recency, analysis rows, ratings/favorites).
- Local tracks carry a desktop-compatible metadata JSON (artists with deterministic ids, album, genres, composers, quality and date fields) so existing rows, detail screens, insight and Last.fm code keep working without mirror reads. Artwork paths now resolve to cached files instead of sync paths.
- Switched the library, artist, album, genre, composer, search, playlist and details ViewModels to `LibraryCatalog`; `MainActivity` supplies one `LocalLibraryCatalog`. Playlist writes still delegate to the legacy queue until the local playlist repository (T13).
- Search keeps the existing normalized multi-token/prefix/phrase matcher over the local lists; the optional FTS narrowing is treated as absent when the catalog has no index, so results are complete. No Bleve-compatible ranking is claimed.
- Ratings (0–5, Sistrum extension): local read/write with database-bound validation, star display and edit in track info, a minimum-rating filter row, and a rating sort that keeps unrated tracks last in both directions.
- Tests: host mapping tests (JSON keys, identity, artwork, rating), rating sort test; device `LocalLibraryCatalogTest` (flows, local rating/favorite writes, bounds, empty search/playlists/listening) and `RatingFilterRowTest` — 5/5 PASS on OnePlus CPH2307 (Android 15). Host suites: androidApp 364/364, sharedLogic 59/59.
- Limitations: SAF playback resolution is wired in T11, so local rows do not play yet; playlists are read from the legacy store until T13; no room FTS table (in-memory matcher) and no Bleve-equivalent ranking.

## M001-T011 — Local URI playback and queue integration

- Local tracks now expose their SAF document URI, and the native decoder gained descriptor-based `prepare`/`preload` overloads (path overloads remain for app-owned files). `PlaybackService` opens `content://` documents through `ContentResolver` and `file://` through a file descriptor, so no path is fabricated for SAF storage.
- `AndroidPlaybackRuntime` resolves local catalog tracks first and falls back to the legacy sync store for not-yet-migrated desktop files, validates that the document actually opens before queueing, carries local artwork and analysis, and exposes a metadata snapshot so the media session shows local queue items.
- Queue restore validates ids against the same resolver (`availableTrackIds` merges local and legacy availability), so deleted or revoked documents are dropped instead of left pointing at missing rows; a track that disappears while playing fails through the existing `PlaybackState.Failed` path.
- `LocalLibraryCatalog` uses the item URI as the playable path; missing files are logged with the id and path and never queue.
- Tests: device `FfmpegDescriptorTest` proves decode and preload from an open descriptor (MP3 fixture), and the updated `LocalLibraryCatalogTest` asserts the SAF URI is exposed — 6/6 PASS on OnePlus CPH2307 (Android 15). Host suites: androidApp 364/364, sharedLogic 59/59; `assembleDevDebug` succeeds.
- Limitations: no emulator/device audio capture, so real SAF playback under a provider, crossfade/EQ timing and route changes are covered by T12 evidence rather than by an automated assertion here; Mood Radio and listening history still read the legacy store until T19/T20.

## M001-T012 — Playback verification

- Device matrix `PlaybackFormatMatrixTest` decodes and seeks every shipped fixture through the descriptor path: MP3 (including Unicode tags), FLAC, M4A, Opus, WAV, APE, DSF, DFF and TTA all report a duration and accept a mid-file seek. A second case prepares with equalizer, focus gain and normalization gains and starts/pauses playback without a native error.
- Existing host player suites (queue, crossfade maths, normalization gain, playback session, request building) remain green: androidApp 364/364, sharedLogic 59/59. `assembleDevDebug` succeeds.
- PASS: decode/seek matrix for all supported fixtures; DSP configuration accepted; local descriptor playback; queue restore filters.
- UNVERIFIED (needs an audible session or capture): real output route/Bluetooth/AirPlay switching, audio focus duck/pause, gapless/crossfade wall-clock timing, underrun counts and process-death restore during playback. No emulator or capture device was used; the phone was driven through instrumentation only.
- No behavior changes were needed, so there is no regression to fix in this task.

## M001-T013 — Manual playlists, favorites and M3U8

- Added `LocalPlaylistRepository`: local create/rename/delete, add/remove tracks with duplicate and unknown-id guards, fractional `LexoRank` ordering (insert anywhere by rewriting one row), custom artwork imported into the content-addressed cache, and favorites as a virtual playlist derived from `library_items.isFavorite` (read-only, cannot be renamed or deleted).
- `LocalLibraryCatalog` now reads playlists from Room and applies mutations locally (create, update, delete, add/remove/move, set/remove artwork, favorite) instead of queueing desktop mutations. Not-yet-migrated artwork still resolves through the legacy staging index.
- Added `M3u8Codec` with the documented fixes: reads a UTF-8 BOM and an `#EXTENC:` charset header, writes a UTF-8 header, exports relative paths when the track is under the playlist folder, and keeps URIs/absolute paths intact. Import resolves entries against the library's local URIs.
- Playlist screens gained an Import M3U8 action and a per-playlist Export M3U8 action (including favorites), wired through the models to the ViewModel.
- Tests: host `LexoRankTest` (ordering, 200 sequential inserts, front inserts, adjacent ranks) and `M3u8CodecTest` (header/relative paths, BOM/#EXTENC, Latin-1, URI pass-through); device `LocalPlaylistRepositoryTest` (create/add/move/remove/rename/delete, duplicate/unknown guards, favorites protection) — 3/3 PASS on OnePlus CPH2307 (Android 15). Host suites: androidApp 372/372, sharedLogic 59/59; `assembleDevDebug` succeeds.
- Limitations: smart-playlist export waits for the rule engine (T14), and the system file picker itself was not exercised automatically (callbacks are covered by compilation and the codec/repository tests).

## M001-T014 — Smart playlist rule engine and UI

- Ported the upstream rule model and engine (`SmartRules`): the allowlisted fields (genre, artist, year, bpm, play_count, duration, bitrate, energy, danceability, brightness, is_favorite, added_at, plus the Sistrum rating extension), their exact operator sets, `all`/`any` groups with recursive nesting, the 16-leaf cap, at least one rule, numeric/between typing, `in_last_days`, and limit orders (title, random, album, artist, genre, most_played). Unknown fields/operators/orders are rejected rather than interpolated.
- Live smart playlists are evaluated on every read against the local catalog; frozen ones materialise their match into `playlist_tracks` once on save and keep it. Rule configs persist as JSON in the playlist row (live flag, limit).
- Added a Compose builder under Library → playlists → Smart playlist: name, ALL/ANY, rule rows with field/operator pickers and typed values, add rule, one nested group level, live toggle, limit count/order, validation errors and save.
- Tests: host `SmartRulesTest` (all/any nesting, string/relation/numeric/between/favorite/date rules, invalid fields/operators/between shape, 16-cap, limits, JSON round trip); device `SmartPlaylistRepositoryTest` (live recompute, frozen snapshot, invalid rules rejected) and `SmartPlaylistContentTest` (builder saves name, rules and live flag) — 4/4 PASS on OnePlus CPH2307 (Android 15). Host suites: androidApp 379/379, sharedLogic 59/59; `assembleDevDebug` succeeds.
- Limitations: the builder creates new smart playlists; editing an existing smart playlist uses the repository/engine but has no dedicated entry point yet (normal playlist edit remains name/artwork). Mood fields start returning results after T17/T19 persist analysis.

## M001-T015 — Local/embedded/online lyrics

- Added `LocalLyricsRepository`: durable Room lyrics per track, precedence sibling `.lrc` → sibling `.txt` → configured subfolder → dedicated SAF folder → embedded → cached online, with `prefer_local_lyrics` reversing local/online priority. Resolution stores the winning source, sync flag, fingerprint and embedded copy.
- Added `SafLyricsFinder`: walks the source tree to the track's folder, reads same-name `.lrc`/`.txt`, an optional lyrics subfolder, or a bounded search of a dedicated folder, using persisted SAF grants; reads are UTF-8 and never block the UI thread.
- Extended lyrics settings: prefer-local (default on), subfolder enable/name and a dedicated folder picked with a persisted read grant; the Lyrics settings page exposes all of them.
- Online providers now read track identity from the local catalog and write fetched lyrics into Room; a manual search selection also saves locally. Local/embedded lyrics take display priority over cached provider lyrics without waiting for the network.
- Tests: device `LocalLyricsRepositoryTest` (embedded TagLib lyrics from the MP3 fixture, prefer-local off/on precedence, unknown track) — 4/4 PASS on OnePlus CPH2307 (Android 15). Host suites: androidApp 379/379, sharedLogic 59/59; `assembleDevDebug` succeeds.
- Limitations: writing a new `.lrc` into SAF and embedding lyrics through TagLib are not implemented in this task (read/import precedence and durable save to Room are); the SAF sibling/subfolder/dedicated-folder reader is covered by code review rather than an automated SAF tree fixture.

## M001-T016 — Analysis build, JNI and golden fixtures

- FFmpeg rebuilt with `avfilter` and the eight analysis filters; `libavfilter.so` ships and links. FFTW 3.3.11 (single precision) and aubio 0.4.9 are built static for arm64-v8a from checksummed tarballs and linked into `libsistrum_player.so`; a device tempo probe proves the link and FFTW backend.
- Native `nativeAnalyzeFd` decodes any supported file through FFmpeg to mono 44.1 kHz, then computes 15 fields: LUFS-style loudness, loudness range, true peak, RMS, crest, spectral centroid/rolloff/flatness/flux (aubio pvoc + specdesc, flatness computed from the magnitude spectrum), ZCR, aubio tempo, onset-interval variance, energy, danceability and brightness. Spectral descriptor names were validated against aubio 0.4.9 (unsupported names returned null and crashed; fixed).
- `AnalysisEngine` opens SAF/file audio by descriptor, maps and versions the result (FFmpeg/aubio/analyzer/mood) and persists it in Room; `itemsNeedingAnalysis` skips completed rows at the current analyzer version. Failures write nothing.
- Tests: device `AnalysisEngineTest`: fixture fields are finite and in range, result persists, a synthetic 120 BPM click track is detected between 80–160 BPM, reanalysis is stable, `analyzeAll` skips completed rows, missing audio fails without a row — 4/4 PASS on OnePlus CPH2307 (Android 15). Host suites: androidApp 379/379, sharedLogic 59/59; `assembleDevDebug` succeeds.
- Limitations: no upstream desktop analysis producer was available on this machine, so golden fixtures are the shipped MP3 plus a synthetic 120 BPM click track and range assertions rather than exact upstream numeric tolerances; LUFS is the standard mean-square approximation, not the full K-weighted EBU R128 implementation (avfilter ebur128 is built but not used yet).

## M001-T017 — Analysis persistence, tags, reuse and settings

- Added standard per-format tag mapping (`AnalysisTagCodec`): MP3/FLAC/Vorbis `REPLAYGAIN_TRACK_GAIN/PEAK` (+ album keys), Opus `R128_TRACK_GAIN` in Q7.8 dB units, M4A freeform `----:com.apple.iTunes:REPLAYGAIN_*`; other formats never get tags. Existing gain keys are detected from the file and never overwritten.
- Added `TagLibBridge.nativeWriteProperties` (arbitrary text properties through the property map, unrelated tags preserved) and use it only when the opt-in setting is on and the document opens read-write.
- `AnalysisEngine.analyzeIfNeeded` now reuses a completed result when the audio fingerprint and analyzer version match, reanalyzes stale/missing rows, and writes tags after a successful analysis; read-only/denied files keep using the Room result. `analyzeAll` walks only stale rows.
- Library settings expose the ReplayGain write toggle and an "Analyze library" action with progress/result; analysis results remain local when the toggle is off.
- Tests: host `AnalysisTagCodecTest` (formats, Opus Q7.8, peak/gain formatting, existing-key protection, extension mapping); device `AnalysisEngineTest` gained ReplayGain write + preserve-existing coverage — 5/5 PASS on OnePlus CPH2307 (Android 15). Host suites: androidApp 383/383, sharedLogic 59/59; `assembleDevDebug` succeeds.
- Limitations: M4A freeform tags depend on TagLib's MP4 property support (written through the same property map); file-level album aggregation keys are produced by the codec but T18 owns album values.

## M001-T018 — Normalization and album aggregation

- Added `AlbumAggregate`: album loudness is the mean of the track analyses, album peak the loudest track peak, computed from current Room rows on demand (so track add/remove/reanalysis invalidates it automatically). Album-mode gain uses the aggregate, falls back to the track when the album has no aggregate, and clipping prevention caps gain at `-truePeak`.
- Playback now takes analysis and album aggregates from the local catalog (`LocalLibraryCatalog.albumAnalyses` via `AndroidPlaybackRuntime`) instead of the legacy mirror; disabling normalization when the current track has no analysis is unchanged.
- Tests: host `AlbumAggregateTest` (mean/peak, track vs album mode, clip cap, fallback, disabled/missing analysis); existing sharedLogic normalization tests stay green. Host suites: androidApp 390/390, sharedLogic 59/59; `assembleDevDebug` succeeds.
- Limitation: album aggregation is not persisted (fresh on every read), which is the invalidation strategy; no audible level check was possible on the instrumented device.

## M001-T019 — Local Mood Radio

- Added `MoodRepository`: candidates come from Room items joined with completed analyses that have energy, danceability, brightness and tempo; readiness needs at least two candidates. The legacy desktop analysis flag is no longer consulted.
- `LocalLibraryCatalog`/`AndroidPlaybackRuntime` expose local mood candidates and readiness; Mood Radio start, refill and the 80/15/3 selection now use local data. The smart-playlist builder already exposes the same mood fields, which start returning matches once analyses exist.
- Tests: device `MoodRepositoryTest` (only completed mood analyses become candidates, pending excluded, two-candidate gate) — 2/2 PASS on OnePlus CPH2307 (Android 15); existing sharedLogic Mood Radio weighting/selection tests remain green. Host suites: androidApp 390/390, sharedLogic 59/59; `assembleDevDebug` succeeds.
- Limitation: selection quality was not listened to on the device; no audible check was possible.


## Remediation TOR004/TOR005 (in progress) — SAF ingest, catalog identity, library flows

**Baseline**: `3ed1de2`. **No PASS claimed for either TOR yet**; the checkboxes stay open until every mandatory item below is closed and independently reviewed.

### Done and verified in this pass

- **Native descriptor I/O (T001/T005/T011/T016)**: new `fd_io.h` implements a pread/pwrite AVIOContext plus a TagLib `IOStream`. Playback, metadata, artwork, analysis and fingerprinting no longer reopen `/proc/self/fd/<fd>`. The AVIO read callback returns `AVERROR_EOF` at end of file; returning `0` made FFmpeg's probe loop forever (found and fixed during device testing). The demuxer owns the descriptor only on the playback path (`detachFd`); TagLib/analysis/fingerprint leave the Kotlin descriptor open.
- **Baseline record (T001)**: `baseline.md` records JDK/SDK/NDK/CMake, FFmpeg 9.0.2 source SHA-256 `84960df9…`, TagLib/aubio/FFTW hashes, configure flags, JNI exports, APK size (82,666,881 B), ABI, version and debug signature.
- **Decoded-PCM identity (T005/T006)**: `nativeAudioFingerprint(fd, startMs, endMs)` streams decoded mono 44.1 kHz float32 through SHA-256 and returns `pcm-sha256-v1:<hex>`; CUE bounds are honoured. Scan publication now stores the fingerprint and builds `content_key` from `{size, duration, segment, fingerprint}` (metadata fallback otherwise). Metadata also carries codec and bit depth from a header-only demux probe.
- **Metadata refresh (T005)**: rows whose `otherMetadataJson` is still `{}` are re-read on the next scan even when size/mtime are unchanged, so the pre-fix SAF catalog repairs itself.
- **Legacy import and relink (T002/T013)**: `LegacyMirrorImporter.importIfNeeded()` now runs at startup; new `LegacyRelinker` matches unresolved refs by normalized title/artist/album, then path stem, then title+duration, writes resolved ids, appends memberships to the imported playlists with LexoRank, applies legacy favorites and keeps unmatched refs `unresolved`.
- **Source visibility (T003)**: disabling an unavailable source or losing its grant now hides items whose every membership is unavailable, and reveals them again when a membership returns; rows and user state are preserved.
- **Scanning (T004)**: retry budget is 3 × 2 s with `EXTRA_LOADING` handling; CUE text changes mark all its segments changed; cancel publishes the checkpointed rows without removals; `onTimeout` pauses the foreground scan instead of losing it; exclusions have add/remove UI; opt-out automatic scan runs at app start at most every 6 hours; metadata read failures surface as the source's last scan error.
- **Search (T010)**: `library_search` canonical table plus an FTS4 `library_search_fts` index synchronised by triggers, migration 2→3, and a transactional `SearchIndexer` covering tracks/albums/artists/genres/composers/playlists. `LocalLibraryCatalog.searchCandidates` now queries it (prefix MATCH, LIKE fallback for scripts such as CJK).
- **Separators/albums (T009)**: raw pre-split tag values are stored, so `RelationshipRebuilder` re-splits without audio reads; it now recomputes each item's album id from the configured album artist, rebuilds album rows/relations, preserves artwork by title, deletes orphans and rebuilds the search index.
- **Editor (T007)**: the tag editor is reachable from the track context menu; saves use the staged/journaled `TagWriter`.
- **Smart playlists (T014)**: the editor loads an existing playlist's name/rules/limit/live flag and saves through `updateSmart`; manual playlists keep the rename flow.
- **Lyrics persistence (T015)**: Save as LRC creates/replaces `<stem>.lrc` beside the track via SAF (write grant required, UTF-8), and Embed writes `LYRICS` through the staged/journaled writer with audio-fingerprint verification.
- **Verification**: host `:androidApp:testDevDebugUnitTest` 389/389 and `:sharedLogic:testAndroidHostTest` 59/59. Device (CPH2307, Android 15): `SafPublishTest` (3 formats published, `pcm-sha256-v1:` stored, FTS hits), `FfmpegDescriptorTest`, `PlaybackFormatMatrixTest`, `AnalysisEngineTest`, `LibraryDatabaseTest`, `LocalLibraryCatalogTest`, `LocalLyricsRepositoryTest`, `LibraryMigrationTest` — 21/21 PASS. Earlier in the pass, `SafNativeIoTest` passed against a real `content://` document (metadata, 15-field analysis, fingerprint, decoder duration) before the connected-test reinstall cleared app data and its persisted SAF grant.

### Still open (blocks TOR004/TOR005/TOR006 closure)

- T001: owner must supply a release keystore for a signed release APK; baseline currently records the debug artifact only.
- T004: permissions-loss/SD-unmount/process-death and MediaStore fallback are implemented but not verified on device; the dataSync foreground-service budget on this device was exhausted (`Stop FGS timeout`), so a full 777-file UI scan could not finish.
- T005/T007: complete alias/sort/totals matrix and batch editor UI are not verified; artwork add/replace/remove UI, atomic replace on providers without truncate support, and interrupted-write recovery need device tests on copies.
- T008: album cover update/removal, size variants in the UI, and WebP cleanup are not re-verified.
- T010: 10k-track latency/memory measurements are not recorded.
- T011/T012: audible output, route/focus changes, gapless/crossfade/underrun measurements and WavPack on device remain unverified.
- T014: `duration` units and `is_favorite` semantics still need comparison against upstream.
- T016/T017/T018/T019 (TOR006): the analyzer still uses the mean-square loudness approximation, not the avfilter `ebur128` graph; upstream producer goldens cannot be generated because the Airmedy desktop producer is not installed on this machine (BL-18). Clean-install restoration and the final normalization/Mood checks depend on that work.


### Independent review round 1 (native and Kotlin)

Two independent read-only reviews ran over the diff. Fixes applied:

- AVIO read now returns `AVERROR_EOF`; returning `0` made FFmpeg's probe loop forever (this was the real cause of the observed device hang).
- Playback prepare no longer double-closes a failed `open_slot` descriptor; preload and crossfade handle a `-1` slot returned by a racing callback.
- CUE seeks use the stream time base; segment analysis skips pre-start frames without ending and offsets planar channel pointers correctly; flush loops obey the segment end.
- `EINTR` is retried; TagLib `insert` clamps `replace`; `length()` no longer treats pipes as regular files.
- The metadata/header probe moved to its own JNI call after the TagLib stream closes.
- Album regrouping inserts the new album row before re-pointing items (foreign-key order); the rebuilt album keeps its derived artwork.
- Lyrics sidecars are written to a temporary document first and renamed; CUE segments get per-segment sidecar names.
- Scan metadata/fingerprint extraction happens before the Room write transaction, so the single writer is no longer held during SAF reads.
- FTS is a declared Room `@Fts4` entity (migration 3→4), so strict migration validation accepts it; the index is backfilled at startup when empty and refreshed per edited item/playlist.
- Availability reconciliation also runs after scan failure and source re-add; surviving memberships no longer force `available=true` for a disabled source.
- Unresolved legacy references are shown in Library settings (count only; never deleted).
- Smart editing persists the edited name; `contentKey` no longer downgrades a strong identity when an incremental read lacks a fingerprint.

Validation after fixes: `LibraryMigrationTest` 2/2 (1→2 and 2→4 with FTS validation), `SafPublishTest` 1/1, `SmartPlaylistRepositoryTest` 3/3 on CPH2307.
