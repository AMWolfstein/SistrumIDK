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

- [x] M001-T015 Local/embedded/online lyrics and durable save

- [x] M001-T016 Analysis build, JNI and golden fixtures

- [x] M001-T017 Analysis persistence, tags, reuse and settings

- [x] M001-T018 Normalization and album aggregation

- [x] M001-T019 Local Mood Radio

## Audit remediation before T020

**Reservation baseline**: `alpha10` at `3ed1de2bd696e316a4a6e7dcc669b8242733582a`. Execute TOR004 → TOR005 → TOR006. Start T020 only after all three TORs pass their checks and independent review and the cumulative acceptance below is met.

**Scope authority**: The owner's original alpha10 mission, the original T01–T19 entries above, [spec](spec.md), [plan](plan.md), [parity matrix](parity-matrix.md), [source map](source-map.md), and [test matrix](test-matrix.md) all apply. The checks below add specificity to those requirements. Record the original checkbox entries as historical workflow records and use the TOR evidence and blockers to show current acceptance. Leave T020/T021 listening history, Insights and Last.fm work in their original scope.

**Workflow for each TOR**: Refresh the graph before implementation and query actual callers, native bindings and impacted tests. Document the affected boundaries and obtain independent plan review for the high-risk changes. After implementation, refresh the graph and record before/after findings, coverage limits and verification through `work.py graph record`. Update [task-log](task-log.md), [blockers](blockers.md) and the affected parity/source/test matrix entries with a trace from each original requirement to production code, a reproducible check, its evidence and its disposition. Record failures and gaps explicitly. Obtain independent review of the diff and evidence within the three-result limit. Close with one task commit containing the task records and verified changes, with a checked subject, description and exactly one OpenBot co-author trailer, then push to `origin alpha10`. Preserve Git history, user state and source audio. Use copies for write tests. Build and test with the applicable host commands and connected arm64 Android device from the test matrix. A passing build or fixture-only test does not replace runtime acceptance.

- [ ] M001-TOR004 Restore SAF ingest, migration and catalog identity

**Covers T001–T006. Depends on the existing T019 revision.**

- **Baseline and native input (T001/T005)** — Recover the original baseline with JDK/SDK/NDK/CMake/font inputs, asset notices, FFmpeg 9.0.2 source SHA-256/flags/components and JNI exports, APK size/ABI/version/signature and known failures. Record the commands and reproducible comparison, including descriptor lifetime/seek. Implement actual seekable FD I/O for TagLib and FFmpeg, shared by playback and analysis, for provider-backed `content://` files. Report open and extraction failures at the scan boundary. Verify actual SAF reads on device with the supplied library, including art, metadata, decoded playback input and analysis input. Consumer behavior is verified in TOR005/TOR006.
- **Room and migration (T002)** — Verify exported schema, indexes and rating bounds. Wire the read-only legacy importer into startup and relink after the first successful scan. On a populated legacy copy verify playlist identities, memberships/order/pending operations, favorites, artwork, lyrics, listening records, unresolved-reference reporting, interruption and restart without duplication or data loss. Retain the original DB.
- **Sources and scanning (T003/T004)** — Exercise picker/settings, read/write grants, enable/disable/remove/re-add, overlapping roots, SD unmount and permission loss on device. Hide tracks when no available membership remains and preserve their data. Verify bounded retries (2 s × 3 as specified), `EXTRA_LOADING`, scoped MediaStore fallback, auto-scan and exclusions UI, bounded memory and visible per-file failures. Verify pause/resume, cancel preserving completed rows, process-death and foreground-service timeout recovery, folder/disc cover inheritance, CUE changes and segments, preservation of old CUE entries upon read failure, ambiguous timestamps, and deletion only after complete enumeration.
- **Metadata and reconciliation (T005/T006)** — Verify pinned TagLib, license and native size delta, format and complete field/alias mapping including unsplit raw values, sort fields, totals, technical fields, release dates and segment-aware decoded-PCM fingerprint. Verify fingerprint stability across metadata-only edits and rejection when audio changes. Refresh previously ingested empty metadata even when size/mtime are unchanged, with an upgrade test retaining identity and user state. Verify transactional publication and deterministic reconciliation across rename/move, roots and storage volumes, including ambiguous matches and interrupted publish. Preserve ratings, favorites, playlists, artwork, lyrics and analysis. Exercise the same metadata normalization contract in scanning and reindexing.
- **Affected paths/checks** — Build scripts and native bridges, `library/db`, `library/scan`, `library/tags`, library settings and their tests. Run native shell/build/JNI checks, host suites and arm64 device instrumentation. Compare an upgrade of the affected catalog with a fresh scan under the same inputs and settings.

- [ ] M001-TOR005 Repair local library, playback and editing flows

**Covers T007–T011 and T013–T015. Depends on TOR004. T012 playback verification and T014 mood-field verification close in TOR006.**

- **Safe editing (T007)** — Connect single/batch editors and artwork add/replace/remove with JPEG conversion. Route metadata, artwork, embedded lyrics and analysis writes through the same staging, verification, journal recovery and safe replacement contract. Verify write consent, read-only providers, interrupted replacement, isolated batch failures, unchanged audio payload and unrelated tags/art, and successful reindex without scan loops. Surface failure and preserve the original when safe replacement is unavailable.
- **Artwork and separators (T008/T009)** — Verify content-addressed durable cache, on-screen size variants, WebP repair/cleanup, folder/disc art and album cover update/removal. Verify manual/local/Deezer artist art priority, release-date/undated/tie rules, default OFF, placement below Lyrics, disable during requests, offline restart and failed requests. Verify all four editable separator categories against pinned upstream goldens, raw multi-value preservation, and album regrouping on Album Artist changes. Update derived entities, relations and search transactionally without reading audio or losing user state. A fresh scan and a rebuild with the same settings must agree.
- **Room search and local screens (T010)** — Implement transactional Room FTS for all specified entities including playlists, using bounded queries/pagination. Cover offline local Home and Recently Added, search, detail/context routes, grouping, sorting/filtering and 0–5 ratings from actual screens. Verify Unicode/CJK, prefixes, phrases, update propagation and ranking against the original contract. Measure query latency, UI responsiveness and memory with at least 10,000 tracks and retain the measured results.
- **Local playback and restore (T011)** — Resolve provider-backed SAF across all supported formats and verify audible playback, seek/preload, resource cleanup and CUE segment start/end. Verify missing/revoked files and queue repair, removing the current item or an earlier item, and restored current item/position after restart. Preserve valid user queue state and apply the specified deletion policy.
- **Playlists and favorites (T013/T014)** — Wire favorites and playlist actions to Room from all entry points, including legacy memberships after relinking. Verify CRUD, ordering/rebalance/duplicates, pin, artwork and smart membership protection. Round-trip manual, smart and favorites through user-facing M3U8 paths, including relative resolution, BOM, `#EXTENC`, missing files and exact order. Open, edit and save an existing smart playlist with its prior rules loaded. Verify upstream fields/operators, duration units, favorite booleans, nesting/all/any, numeric typing, 16-leaf cap, sort/random/limits and live/frozen refresh without SQL injection.
- **Durable lyrics (T015)** — Wire local/embedded/online precedence with `prefer_local` on/off, sidecar/subfolder/dedicated SAF folder, charset, provider ranking/manual search and romanization assets. Add **Save as LRC File** and **Embed in Audio File** from selected online results, with overwrite confirmation, read-only/denied handling and LRC fallback if embedding loses timing or is unsupported. Verify Unicode, offset and timed/word-timed round trips, immediate display and offline retrieval after reinstall with write-test copies.
- **Affected paths/checks** — `library/tags`, `artwork`, `metadata`, Room schema/DAOs/search, library/Home navigation/screens, player/session code, playlist and lyrics layers. Run host and instrumentation suites, format round trips and interrupted writes on SAF copies, real-device UI routes and the large-library search run.

- [ ] M001-TOR006 Restore analysis parity and verify device playback

**Covers T012 and T016–T019, including the T014 mood-field dependency. Depends on TOR004 and TOR005.**

- **Native analysis (T016)** — Use the pinned upstream Airmedy `d859940c` FFmpeg avfilter graph, constants and formulas, including EBU R128 measurement, actual filter execution and the upstream aubio tempo/HFC onset configuration. Verify CUE segment limits and bounded memory. Generate and retain producer goldens with revision/settings, then compare every field with justified numeric tolerances on silence, short inputs, clipping, formats, channels/sample rates, CUE segments and albums. Verify analysis versions, FFmpeg components, pinned static aubio/FFTW and any required JNI/FFT symbols, linkage, size and notices.
- **Persistence and scheduling (T017)** — Round-trip every required feature, fingerprint and version with standard/custom format tags and Room. Verify correct track/album ReplayGain and Opus R128 including the specified reference/units and preservation of existing tags by default. Use the TOR005 journaled writer and verify interruption and reindex behavior on copies. Cover all three analysis settings, visible write status, bounded scheduling during playback, cancel/resume and Room-only results on read-only sources. On a clean install without the previous Room database, recover valid metadata from transferred audio without redundant analysis. Reject stale versions, altered audio and malformed/incomplete tag sets. Verify recomputation when required and no rescan loop.
- **Normalization and Mood Radio (T018/T019)** — Verify contiguous-album semantics and fallback to track gain when continuity changes, aggregate invalidation, the default −14 LUFS target, track true-peak clipping protection, pending/missing analysis and preserved user preference. Verify local readiness gates in playback, context menus and the smart builder. Check all mood-rule fields, pinned upstream weights, 80/15/3 buckets, ordering/ties, correct artist identities, album/artist exclusions, duplicate suppression and automatic refill during offline playback.
- **Device audio evidence (T012)** — Use the supplied multi-format library through SAF on the connected Android device. Retain variant/ABI, input formats, rates/channels/bit depths, output evidence, native logs and measured seek, gapless, crossfade and underruns versus TOR004's baseline. Exercise audible output, supported formats (including WavPack), focus loss/gain, route changes including wired/Bluetooth as applicable, background/media controls, next-file failure, process death, restored queue/position, and EQ/crossfade with final track/album normalization. Record device/accessory gaps explicitly as blocked verification.
- **Affected paths/checks** — Native analysis/build/runtime, `library/analysis`, `library/mood`, shared normalization and Mood Radio, Android playback service, smart builder and analysis settings. Run host/device suites, producer parity comparisons, clean-install restoration on copies and the real-device playback matrix.
- **Cumulative acceptance before T020** — Independently review each original T001–T019 mandatory requirement against production paths and recorded test or device evidence. Use the six audit dispositions (*Fully implemented and verified*, *Implemented but unverified*, *Partially implemented*, *Not implemented*, *Incorrect implementation*, *Explicitly approved deviation*). Mandatory items need verified closure or an owner-approved deviation with the approval reference. Reconcile historic unsupported PASS claims through corrective evidence and blocker/task-log entries. Verify registry/record consistency and commit policy against the current `alpha10` base. T020 remains blocked while any mandatory finding lacks closure.

## Remediation progress (2026-11-28)

The TOR checkboxes above stay open: no TOR has passed its full independent review gate yet.

- **M001-TOR004** — native descriptor I/O, baseline record, decoded-PCM fingerprint/identity, metadata repair, legacy import+relink, source visibility and scan controls are implemented and device-checked for metadata/playback/analysis/fingerprint (`task-log.md`). Open: permission-loss/SD/process-death and a completed 777-file UI scan (foreground-service budget exhausted, `blockers.md` BL-23), release signing (BL-09).
- **M001-TOR005** — Room FTS, album regrouping, editor entry, smart-playlist editing and Save-as-LRC/Embed are implemented; device suites 21/21 PASS. Open: batch editor and artwork add/remove UI, 10k-track measurements, T011/T012 playback evidence, T014 unit review.
- **M001-TOR006** — analyzer ported to the upstream avfilter graph and parity-checked against producer goldens on device; ReplayGain/R128 values fixed, full analysis tags embed/restore, analysis settings added. Open: album-programme loudness, T012 device audio evidence, T018/T019 re-checks (BL-25 resolved; BL-27/BL-28 open).
