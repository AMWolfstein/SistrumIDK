# Tasks: complete standalone Android music player

**Input**: [`spec.md`](spec.md), [`plan.md`](plan.md), [`research.md`](research.md), [`data-model.md`](data-model.md), [`parity-matrix.md`](parity-matrix.md)
**Mission**: M001
**Status**: Planning draft — implementation starts only after planning review PASS. Analysis/rating/defect decisions are resolved; PiP/start-at-login/release-signing/device items are task-local decisions, not planning blockers.

Each task's upstream contract and target ownership are the corresponding rows in `parity-matrix.md` and `source-map.md`; T-numbers below are plan-local.

Rules: each task starts with a full map refresh, records evidence, implements, runs checks, gets independent review, fixes, then one commit. IDs `T01…` below are plan-local; actual `M001-T###` IDs are allocated with `work.py task reserve --mission M001` when the task starts (or in a prepared batch after plan approval). The final task is reserved with `--final`.

---

## Phase 0 — Baseline

### T01 — Reproducible baseline and build enablement

- **Outcome**: baseline builds/tests runnable; pre-existing failures recorded; sibling-path/font/FFmpeg blockers fixed without behavior change.
- **Paths**: `androidApp/build.gradle.kts`, `tools/font-subset/subset_font.py`, `scripts/build-ffmpeg-android.sh`, `androidApp/src/main/cpp/CMakeLists.txt`, `local.properties` (ignored), docs touched only for build commands.
- **Work**: verify JDK/SDK/NDK/CMake/fontTools; build FFmpeg 9.0.2 arm64-v8a with existing flags; fix `romanizationAssets` to use app-owned assets (copy upstream `pinyin.txt`, `TSCharacters.txt`, `characters.tsv` with notices); fix generated-font dependency; run `assembleDevDebug`, host tests; capture APK size/ABI/version/JNI exports and known failures.
- **Checks**: `./gradlew :androidApp:assembleDevDebug`, `:androidApp:testDevDebugUnitTest`, `:sharedLogic:testAndroidHostTest`, `bash -n scripts/build-ffmpeg-android.sh`, APK `aapt`/`unzip -l` verification.
- **Acceptance**: build succeeds; FFmpeg libs load; baseline evidence recorded; no UI/feature changes.
- **Rollback**: revert build/CMake/asset changes; keep generated artifacts in ignored `.work`/build dirs.
- **Depends**: none.

## Phase 1 — Local foundation

### T02 — Local Room database and legacy migration scaffold

- **Outcome**: `library.db` version 1 with exported schema, core entities/DAOs (including 0–5 rating), migration tests; legacy importer reads `library-sync.db` without deleting it.
- **Paths**: new `androidApp/src/main/kotlin/com/amwolfstein/sistrum/library/db/*`, `schemas/`, `androidApp/src/test/.../library/db/*`; `build.gradle.kts` schema location. Rating column `rating INTEGER? CHECK 0..5`.
- **Checks**: Room schema JSON committed; schema-create test for every indexed column; migration tests from empty **and** a populated legacy fixture; idempotent import; `testDevDebugUnitTest`.
- **Acceptance**: no destructive fallback; user-owned legacy rows imported/journaled; unresolved references reported.
- **Rollback**: delete `library.db` and importer; legacy DB untouched.
- **Depends**: T01.

### T03 — SAF sources, grants and library settings UI

- **Outcome**: add/remove/enable SAF roots, persist grants, volume metadata, availability; Settings → Library page.
- **Paths**: `library/scan/SafSourceRepository.kt`, `SafTreePicker.kt`, `ui/screens/LibrarySettingsContent.kt`, `AppDestination.kt`, `AppDestinationContent.kt`, `SettingsContent.kt`, `MainActivity.kt` wiring, strings/locales.
- **Checks**: unit tests for source id/URI, duplicate source rejection, overlapping roots accepted + documentKey dedup, grant persistence; Compose content tests; manual document-provider smoke.
- **Acceptance**: multiple roots, read/write grants; no broad storage permission; disabled/unavailable source hides rows without deletion.
- **Rollback**: remove page/repository; existing settings unaffected.
- **Depends**: T02.

### T04 — SAF scanner, checkpoint, progress and scan service

- **Outcome**: BFS traversal, CUE expansion, folder covers, mtime/size change detection, Room checkpoints, pause/resume/cancel, foreground scan service.
- **Paths**: `library/scan/SafScanner.kt`, `SafCueParser.kt`, `SafFolderCovers.kt`, `ScanRunRepository.kt`, `scan/ScanService.kt`, `scan/ScanWorker.kt`, `library/library_exclusions` DAO/settings, `AndroidManifest.xml` (dataSync service).
- **Checks**: fake DocumentsProvider tests (listing/retry/EXTRA_LOADING); checkpoint corruption/resume; overlapping roots dedup; CUE offsets/dedup; exclusions; cancel/process-death; dataSync timeout; progress state tests.
- **Acceptance**: scan survives process death; deletion only after complete enumeration; no duplicate imports; unavailable ≠ deleted.
- **Rollback**: keep scanner behind a disabled path; legacy sync still compiles until T23.
- **Depends**: T02, T03.

## Phase 2 — Ingest and metadata

### T05 — TagLib integration, metadata extraction and identity

- **Outcome**: TagLib v2.3.2 static-linked; JNI read API; alias priority/normalization; raw tags; deterministic IDs/entities; release date + decoded-PCM fingerprint fields.
- **Paths**: `androidApp/src/main/cpp/taglib/` (pinned source/build), `taglib_bridge.cpp`, `library/tags/TagLibBridge.kt`, `MetadataExtractor.kt`, `EntityResolver.kt`; CMake/notices.
- **Checks**: native unit fixtures for MP3/M4A/Opus/FLAC/WAV/APE/WV/DSF; normalization golden tests (articles/fold/delimiters); release date; fingerprint stability across tag edits; build/reproducibility; TagLib licence notice.
- **Acceptance**: exact field/alias behavior; audio unchanged; no extra dependency; `libsistrum_player.so` size delta recorded.
- **Rollback**: remove native bridge; scanner still indexes filenames (feature incomplete).
- **Depends**: T01, T04.

### T06 — Incremental reconciliation and publication

- **Outcome**: transaction publication, removed/seen logic, missing_since, source re-check, deterministic queries.
- **Paths**: `library/scan/ScanPublisher.kt`, `LibraryRepository.kt`, DAO queries, tests.
- **Checks**: full/incremental/idempotent/overlap/rename/move/unmounted/revoked; moved/renamed item re-key (same root, internal↔SD) preserves favorites/playlists/artwork/lyrics/analysis; non-unique scoped match; interrupted publish; transaction tests.
- **Acceptance**: no duplicates; no deletion on partial enumeration; active queue repaired later.
- **Rollback**: keep previous index (transaction aborts).
- **Depends**: T04, T05.

### T07 — Metadata editor and safe tag/artwork writes

- **Outcome**: single/batch editor, artwork replacement/JPEG conversion, staged verify/atomic replace, journal recovery.
- **Paths**: `library/tags/TagWriter.kt`, `TagWriteJournal.kt`, `ui/screens/MetadataEditorContent.kt`, `TagEditViewModel.kt`.
- **Checks**: round-trip fixtures (audio payload/unrelated tags/art), denied/read-only, interrupted write recovery, batch failure isolation; UI tests.
- **Acceptance**: never partially overwrite; failure visible/retryable; reindex after success; no scan feedback loop.
- **Rollback**: journal restores original; disable writer route.
- **Depends**: T05, T06.

## Phase 3 — Artwork, separators, library UI

### T08 — Artwork cache, artist precedence and Online Artwork page

- **Outcome**: content-addressed cache with variants/cleanup; folder/album art; artist precedence override; Deezer service; page below Lyrics.
- **Paths**: `library/artwork/ArtworkCache.kt`, `ArtistArtworkRepository.kt`, `DeezerArtworkClient.kt`, `ui/screens/OnlineArtworkContent.kt`, `AppDestination.kt`, `AppDestinationContent.kt`, `SettingsContent.kt`, settings/strings.
- **Checks**: cache hash/variants/cleanup/WebP fix; precedence golden tests (release date, undated, ties); disable/offline/restart; failed lookup preserves art; UI tests.
- **Acceptance**: Deezer OFF default; durable app storage; never replaces Deezer with track art; page order correct.
- **Rollback**: disable online provider; cache remains.
- **Depends**: T05, T06.

### T09 — Metadata separators and derived-relationship rebuild

- **Outcome**: four category settings, add/edit/delete, exact split semantics, rebuild without audio reads.
- **Paths**: `library/metadata/Separators.kt`, `RelationshipRebuilder.kt`, `ui/screens/SeparatorsContent.kt`, `AppDestination.kt`, `AppDestinationContent.kt`, DAOs.
- **Checks**: upstream golden split tests; multi-value tags; no double split; rebuild preserves raw tags/favorites; search/grouping tests.
- **Acceptance**: exact defaults/validation; no audio reanalysis; no duplicate tracks.
- **Rollback**: keep old separator config; rebuild is repeatable.
- **Depends**: T05, T06.

### T10 — Local library queries, search and UI wiring

- **Outcome**: existing screens read local data; FTS semantics; sort/filter/detail/context actions; 0–5 rating display/edit/sort/filter.
- **Paths**: `library/LibraryRepository.kt`, `LibrarySearch.kt`, `ui/screens/Library*`, `HomeContent`, `About` untouched.
- **Checks**: query/search golden tests (Unicode, multilingual, prefix, phrase), pagination/large library, UI content tests.
- **Acceptance**: all existing routes work offline; search ranking deviations documented; no mirror references.
- **Rollback**: feature flag to old mirror reader until T23.
- **Depends**: T06, T08, T09.

## Phase 4 — Playback

### T11 — Local URI playback and queue integration

- **Outcome**: playback resolves local URIs/FDs; missing-file handling; restore queue; current-track deletion policy.
- **Paths**: `player/AndroidPlaybackRuntime.kt`, `player/FfmpegDecoder.kt`, `player/PlaybackModels.kt`, `PlaybackService.kt`, `PlaybackSessionStore.kt`, `library/LibraryRepository.kt`.
- **Checks**: unit tests for resolver/queue restore/missing track; instrumentation playback smoke on arm64 device/emulator.
- **Acceptance**: all supported formats play offline; no content URI leaks; queue never points at deleted rows.
- **Rollback**: keep synced resolver until T23.
- **Depends**: T10.

### T12 — Playback verification (crossfade/EQ/normalization/route)

- **Outcome**: evidence that preserved engine behavior still holds with local files and analysis features.
- **Paths**: native/player tests/fixtures; no behavior change unless defects found.
- **Checks**: format matrix, seek/gapless/crossfade timing, EQ interactions, focus/route, process death restore; native logs.
- **Acceptance**: documented pass/fail; no regressions vs baseline.
- **Rollback**: fixes are isolated; restore previous native defaults.
- **Depends**: T11; normalization interaction is verified after T18 and before T26.

## Phase 5 — Playlists

### T13 — Manual playlists, favorites and M3U8

- **Outcome**: local CRUD/order/artwork, favorites virtual playlist, M3U8 import/export with fixes (all playlists, relative paths, BOM/#EXTENC).
- **Paths**: `library/playlist/PlaylistRepository.kt`, `LexoRank.kt`, `M3u8Codec.kt`, playlist UI/VM, `AppDestination.kt`, `AppDestinationContent.kt`, `MainActivity.kt`.
- **Checks**: ordering/rebalance/duplicates; favorites; import/export round trip (UTF-8/BOM/relative, smart/favorites export); missing files; UI tests.
- **Acceptance**: no desktop ACKs; user data survives scans; exact ordering.
- **Rollback**: keep legacy playlists read-only until migration verified.
- **Depends**: T10.

### T14 — Smart playlist rule engine and UI

- **Outcome**: exact generic fields/operators/all-any/nesting/sorts/limits/live-frozen; rating field; Compose builder. Mood fields are added in T19 once analysis is local.
- **Paths**: `library/playlist/SmartRules.kt`, `SmartPlaylistRepository.kt`, `ui/screens/SmartPlaylistContent.kt`, `AppDestination.kt`, `AppDestinationContent.kt`.
- **Checks**: upstream golden rule tests; 16-leaf cap; numeric typing; random/limit; live refresh; UI tests.
- **Acceptance**: no reduced subset; no SQL injection; mood fields gated by analysis.
- **Rollback**: disable smart editor; existing manual playlists unaffected.
- **Depends**: T13. Mood-rule fields are added by T19 and rechecked before T26.

## Phase 6 — Lyrics

### T15 — Local/embedded/online lyrics and durable save

- **Outcome**: precedence, providers, manual search, save LRC/embed, romanization data packaged.
- **Paths**: `lyrics/AndroidLyricsService.kt`, `library/lyrics/LyricsRepository.kt`, `FullScreenPlayerLyricsPanel.kt`, `lyrics/LyricsPreferences.kt`, `ui/screens/IntegrationContent.kt`, `ui/screens/LyricsSettingsContent.kt` (prefer-local toggle, subfolder, dedicated SAF folder), assets/data.
- **Checks**: local LRC/TXT, subfolder, dedicated folder, embedded; prefer-local on/off precedence; ranking; timing round trip; overwrite confirm; denied writes; offline/restart; romanization fixtures.
- **Acceptance**: local works offline; save/embed verified; failures explicit; no flattened timing.
- **Rollback**: disable embed action; cache remains.
- **Depends**: T07, T10.

## Phase 7 — Analysis

### T16 — Analysis build, JNI and golden fixtures

- **Outcome**: FFmpeg avfilter build + native analyzer, exact graph/constants; aubio 0.4.9 + FFTW 3.3.11 static-linked; golden fixtures.
- **Paths**: `scripts/build-ffmpeg-android.sh`, `scripts/build-aubio-android.sh`, `androidApp/src/main/cpp/analysis/*`, `androidApp/src/main/cpp/third_party/{aubio,fftw}/`, `library/analysis/AnalysisEngine.kt`, fixtures/tests.
- **Work**: cross-compile static FFTW 3.3.11 (`--enable-float --disable-shared --enable-static`, SHA-256 `5630c24c…`) and aubio 0.4.9 from `https://files.pythonhosted.org/packages/cd/80/302d89240603e5347c7f8026c8b02c59f8dfaec66c91a743d82de7c86006/aubio-0.4.9.tar.gz` (`--enable-fftw3f --disable-intelipp --disable-accelerate --disable-sndfile --disable-samplerate --disable-jack --disable-avcodec --disable-blas --disable-docs --disable-tests --disable-examples`, SHA-256 `df1244f6…`) with the NDK clang for arm64; link both into `libsistrum_player.so`; expose tempo/onset through the existing analysis JNI.
- **Checks**: desktop producer outputs for silence/short/clipping/rates/formats/albums and CUE-segment fixtures (start/end analyzed, not whole backing file); per-field tolerances; integration test; verify `fftwf_*` and aubio symbols, no dynamic dependency, and add aubio/FFTW licence notices.
- **Acceptance**: fields match agreed tolerances; components/versions documented; size/license measured.
- **Rollback**: ship analysis disabled; no partial values.
- **Depends**: T01, T05.

### T17 — Analysis persistence, tags, reuse and settings

- **Outcome**: Room rows + TagLib standard/custom tags; per-format ReplayGain mapping (MP3/FLAC/Vorbis `REPLAYGAIN_*`, Opus `R128_*`, M4A freeform); fingerprint/version validation; reuse/reanalyze; settings; write status.
- **Paths**: `library/analysis/AnalysisRepository.kt`, `AnalysisTagCodec.kt`, settings UI, DAOs.
- **Checks**: reinstall/transfer import; stale version/audio rejection; existing ReplayGain preserved; interrupted writes; no rescan loop.
- **Acceptance**: reuse valid; invalid reanalyzed; read-only uses Room + visible status.
- **Rollback**: disable tag writes; Room results remain.
- **Depends**: T16.

### T18 — Normalization and album aggregation

- **Outcome**: local track/album gain, target/clip, integration with playback; aggregate invalidation.
- **Paths**: `sharedLogic/player/VolumeNormalization.kt`, `library/analysis/AlbumAggregate.kt`, `NormalizationPreferences.kt`.
- **Checks**: golden gain math; album membership changes; missing features; mode switch; playback apply.
- **Acceptance**: exact semantics; no limiter introduced; target −14 default.
- **Rollback**: gain 0 when features missing.
- **Depends**: T17, T11.

### T19 — Local Mood Radio

- **Outcome**: local candidate query/selection/refill, analysis gate replaced with local readiness, and mood fields added to the smart-playlist builder.
- **Paths**: `sharedLogic/mood/MoodRadio.kt`, `library/mood/MoodRepository.kt`, `PlaybackService.kt`.
- **Checks**: similarity weights, 80/15/3, album/artist rules, exclusions, no duplicates, gate.
- **Acceptance**: works offline with local analysis; no random unrelated selection.
- **Rollback**: disable radio.
- **Depends**: T17.

## Phase 8 — History and integrations

### T20 — Local listening history and insights

- **Outcome**: local identity, metrics ranges, dedup, UI source filter adjustments.
- **Paths**: `sharedLogic/player/ListeningTracker.kt`, `library/listening/ListeningRepository.kt`, `InsightViewModel.kt`.
- **Checks**: local-day split, qualified play, retries/restart, retention, insight golden values.
- **Acceptance**: no double counts; no paired-device dependency.
- **Rollback**: keep sessions; recompute aggregates.
- **Depends**: T11.

### T21 — Last.fm, updates, i18n and branding cleanup

- **Outcome**: independent networking preserved; bounded Last.fm offline retry queue added; strings/new UI localized; changelog/version corrected.
- **Paths**: `lastfm/*`, `update/*`, `res/values*/strings.xml`, `CHANGELOG.md`, `AboutContent.kt`.
- **Checks**: auth/scrobble/love; bounded retry across offline/restart/duplicates; offline failure doesn't block playback; locale completeness; no secrets in logs.
- **Acceptance**: independent services work; no desktop identity.
- **Rollback**: disable optional network features.
- **Depends**: T10, T20.

## Phase 9 — Desktop removal and equivalents

### T22 — Remaining desktop mechanisms and mobile equivalents

- **Outcome**: multi-select, quick settings sheet, sleep/keep-screen policy, PiP decision, share/open-with, start-at-login decision.
- **Paths**: `ui/screens/Library*`, `ui/navigation/*`, settings, manifest.
- **Checks**: Compose tests, policy behavior, no OS bypass.
- **Acceptance**: documented equivalents; no silent omission.
- **Depends**: T10, T12.

### T23 — Remove desktop sync/pairing/MQTT/HTTP/camera and obsolete schema

- **Outcome**: delete pairing/sync/remote-ish code, dependencies, permissions, services, R8 rules, tests. Only desktop runtime/mirror *code* is removed; `library-sync.db` and its tables stay on disk until T24 upgrade evidence and explicit consent.
- **Paths**: `sync/*`, `pairing/*`, sharedLogic pairing/sync, `SyncViewModel`, sync UI/routes, `AndroidManifest.xml` (remove camera/LAN/cleartext; keep `FOREGROUND_SERVICE_DATA_SYNC` for local scans), `libs.versions.toml`, `build.gradle.kts`, proguard, tests.
- **Checks**: dependency tree diff, manifest merger, JNI/DI/reflection audit, no endpoints, full build/tests.
- **Acceptance**: no reachable desktop code; independent networking intact; size delta recorded.
- **Rollback**: restore removed modules from Git; feature flag not kept as dead code.
- **Depends**: T10–T22.

## Phase 10 — Delivery

### T24 — Migration/upgrade, documentation and traceability

- **Outcome**: upgrade from mirror DB preserves owned state; docs updated; parity matrix/task log current.
- **Paths**: migration importer/tests, `README.md`, `catalog/*`, `CHANGELOG.md`, specs.
- **Checks**: populated legacy fixture upgrade; interrupted import; docs commands; traceability audit.
- **Acceptance**: no data loss; docs match build; all parity rows updated.
- **Depends**: T23.

### T25 — Native build and APK size optimization (separate commit)

- **Outcome**: a dedicated optimization commit, after TagLib/aubio/analysis/normalization are implemented and validated, that reduces native/APK size without losing formats, quality, features or runtime performance.
- **Reference**: Looper `4d532b3d…` (GPL-3.0), `mpv_audio_kit` 0.8.0 (BSD-3-Clause), `libmpv-scripts` `319c1e2…` (LGPL-2.1); reference only, no mpv/Media3 migration and no prebuilt libmpv artifact.
- **Stages (gate after each; split only if the workflow permits)**:
  1. **Component manifest**: map every supported codec/container + analysis filter to FFmpeg components; record required decoders/demuxers/parsers/filters/protocols/bsfs; owner-visible report of anything not enabled. No format may be dropped without approval.
  2. **Native builds**: FFmpeg/TagLib/aubio/FFTW static where safe, `--enable-small`, ThinLTO, `-Oz` where safe, hidden visibility, `-ffunction-sections -fdata-sections`; preserve `--disable-network` and LGPL-only FFmpeg components.
  3. **Link/exports**: `--gc-sections,--exclude-libs=ALL,--no-undefined,-Bsymbolic,--pack-dyn-relocs=android`; version script exposing only JNI; static libc++ if viable; `llvm-strip --strip-unneeded`; preserve 16 KiB page alignment (`-Wl,-z,max-page-size=16384`) and all `Java_com_amwolfstein_sistrum_player_*` JNI exports.
  4. **APK packaging**: verify existing minify/shrink, resource shrink, uncompressed native libs/no legacy packaging, ABI/asset cleanup, R8 rule pruning; no locale/asset loss.
  5. **Measurement/regression**: before/after `.so`/APK/installed sizes, startup, scan/analysis time, decode CPU/memory, playback underruns; full format/TagLib/analysis/normalization/Mood Radio/playback/UI tests.
- **Numeric gates**: PASS at ≥10% APK reduction; 5–10% requires explicit owner approval; <5% means stop/report without cutting formats/features. Reject any >5% startup/scan/analysis/decode-CPU regression or new playback underruns (native `.so` total measured separately).
- **License gate**: FFmpeg remains LGPL-only (no GPL filters/`--enable-gpl`); record whether `--enable-version3` is used; update `NOTICES`/source-offer for static TagLib/FFmpeg/aubio/FFTW/libc++; no new dependency beyond approved ones.
- **Forbidden-artifact gate**: scan APK `lib/`, Gradle dependency tree, and native NEEDED/imports for `mpv`, `libmpv`, `Media3`/`androidx.media3` runtime, or Kyant0 TagLib; fail on any.
- **Checks**: `readelf --dyn-syms` JNI count/alignment, `llvm-readelf -d`, dependency tree, format matrix, TagLib round trips, aubio/FFTW golden analysis, normalization/Mood Radio, playback/crossfade/EQ, UI/instrumentation, same-environment measurements.
- **Paths**: `scripts/build-ffmpeg-android.sh`, `scripts/build-aubio-android.sh`, `androidApp/src/main/cpp/CMakeLists.txt`, native third-party build flags, `androidApp/build.gradle.kts`, `proguard-rules.pro`, packaging config, benchmark scripts/docs.
- **Acceptance**: measurable size reduction within gates; no feature/format/quality/perf regression; separate commit; report with flags/sizes/measurements; if a device/emulator is unavailable, performance items are explicitly unverified.
- **Rollback**: revert build flags/CMake/packaging in this commit; keep baseline artifacts.
- **Depends**: T01–T24 (all features implemented and validated).

### T26 — Full verification, performance and APK

- **Outcome**: full test matrix, debug/minified release APKs as available, signature/manifest/ABI/SHA256, performance comparison.
- **Paths**: build outputs, test reports, `.work` artifacts, final report.
- **Checks**: unit/shared/instrumentation where available; airplane-mode clean install; upgrade; FFmpeg build; APK inspect; dependency delta.
- **Acceptance**: final dev APK with hash/variant/version; limitations explicit.
- **Depends**: T25.

### T27 — [FINAL] cumulative review

- **Outcome**: independent cumulative review of all tasks against spec/plan; fixes and final evidence.
- **Checks**: full diff review, cross-language/data/runtime boundaries, final test/APK evidence.
- **Acceptance**: no unresolved mandatory blocker; final report path/hash/commits.
- **Depends**: T25, T26.

- [x] M001-T001 Reproducible baseline and build enablement

- [x] M001-T002 Local Room database and legacy migration scaffold

- [x] M001-T003 SAF sources, grants and library settings UI

- [x] M001-T004 SAF scanner, checkpoint and scan service

- [x] M001-T005 TagLib integration, metadata extraction and identity

- [x] M001-T006 Incremental reconciliation and publication

- [x] M001-T007 Metadata editor and safe tag/artwork writes

- [x] M001-T008 Artwork cache, artist precedence and Online Artwork page

- [x] M001-T009 Metadata separators and derived-relationship rebuild

- [x] M001-T010 Local library queries, search and UI wiring

- [x] M001-T011 Local URI playback and queue integration

- [x] M001-T012 Playback verification (crossfade/EQ/normalization/route)

- [x] M001-T013 Manual playlists, favorites and M3U8

- [x] M001-T014 Smart playlist rule engine and UI
