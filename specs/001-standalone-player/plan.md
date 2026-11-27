# Implementation Plan: complete standalone Android music player

**Branch**: `alpha10` | **Date**: 2026-11-27 | **Spec**: [`spec.md`](spec.md)
**Mission**: M001 | **Baseline**: `cfa44a5`

## Summary

Replace the desktop-fed sync mirror with a local-first Android library and port Airmedy Desktop capabilities into the existing Kotlin/Compose/FFmpeg/AAudio app. The work is sequenced so the app remains buildable: baseline/toolchain → local Room schema → SAF scanner → metadata/TagLib → artwork/separators → library/search → playback wiring → playlists/smart rules → lyrics → analysis/Mood Radio → listening/insights → desktop-code removal → final verification/APK. UI, navigation, animations, adaptive player and existing tests are preserved; desktop-only mechanisms get mobile equivalents.

## Technical context

**Language/version**: Kotlin 2.4.10, JVM 21, C++20, Gradle 9.5.0, AGP 9.3.1, KSP 2.3.11.
**Primary dependencies**: existing Compose/Room/DataStore/Coroutines/Serialization/FFmpeg/AAudio; **TagLib v2.3.2**; **aubio 0.4.9 + FFTW 3.3.11** (owner-approved analysis additions); existing Last.fm/lyrics/update HTTP clients.
**Storage**: Room `library.db` (new, exported schema), DataStore settings, app-owned artwork/lyrics files, SAF content URIs, legacy `library-sync.db` read-only during migration.
**Testing**: JUnit host tests, Room migration tests, sharedLogic common tests, Compose instrumentation (device/emulator), native golden fixtures vs upstream desktop producer, shell/Gradle checks.
**Target**: Android 12+ (min 31), compile/target 37, arm64-v8a, NDK 30.0.16248370, CMake 3.22.1.
**Project type**: Android app + KMP sharedLogic.
**Performance**: 10k+ track scan must stream/checkpoint and stay responsive; no per-file unbounded buffering; analysis throttled away from playback; UI paginates/virtualizes.
**Constraints**: offline-first; no remote/LAN/desktop control; no new deps beyond TagLib/aubio/FFTW; preserve algorithms/constants; iOS frozen; no unrelated redesign.

## Constitution check

| Gate | Status | Evidence |
|---|---|---|
| Preserve behavior/scope | PASS | Existing UI/player/tests preserved; explicit exclusions limited to remote/desktop control. |
| Plan substantial work | PASS | Spec + research + data model + plan + tasks + matrix produced; map refreshed (3,483 symbols, 5,662 relationships, 413 inputs/138 exclusions, 5 unindexed). |
| One task/one reviewed commit | PASS (process) | Tasks are sequential, each maps/checks/reviews/commits; final cumulative task. |
| Honest verification | PASS (process) | Baseline failures separated; no device claim; analysis parity gated on golden fixtures. |
| Simple tooling/Git safety | PASS | Reuse existing UI/engine; TagLib/aubio/FFTW only; no history rewrite. |

No violations requiring complexity justification. Build/tests require the installed toolchain and FFmpeg artifacts; ownership of signing secrets remains with the owner.

## Repository evidence

- Baseline `cfa44a5`, clean tree; workflow installed; mission M001.
- Map refresh: full CGC reindex (1,222 s) + snapshot; `map.json` 3,483 symbols, 5,662 relationships; 413 inputs, 138 exclusions; 355 extracted, 5 unindexed (4 Gradle .kts + LivingArtworkShader.kt), 3 rejected cross-language calls. CGC index exceeded the adapter's 900 s timeout, so the pinned CLI ran directly; limitation recorded.
- Queries/reads: `MainActivity`, `AndroidManifest.xml`, `App`, `PlaybackService.kt`, `CMakeLists.txt`, `scripts/build-ffmpeg-android.sh`; `SyncDatabase.kt`, `LibrarySyncService.kt`, `AndroidPlaybackRuntime.kt`, `PlaybackService.kt`, `AndroidLyricsService.kt`, `LibrarySync.kt`, `MoodRadio.kt`.
- Upstream evidence: four read-only audits covering domain/SQLite/metadata/artwork/playlists/settings/analytics; analysis/playback/lyrics/Last.fm/romanization; frontend/mobile UI + exclusions; Mornye SAF scanner.
- Limitations: no device/emulator; no desktop golden fixtures yet; Bleve ranking non-reproducible; aubio/FFTW approved; ratings and upstream-defect decisions resolved; M001 is the first mission on alpha10; other remote branches are out of scope.

## Project structure

```text
specs/001-standalone-player/
  spec.md, research.md, data-model.md, plan.md, tasks.md,
  parity-matrix.md, source-map.md, dependency-inventory.md,
  test-matrix.md, blockers.md, quickstart.md, contracts/, task-log.md

androidApp/src/main/kotlin/com/amwolfstein/sistrum/
  library/        # new local data layer: db, repository, scan, metadata, artwork
  library/scan/   # SAF traversal, checkpoints, worker/service
  library/tags/   # TagLib JNI adapter + tag mapping
  library/analysis/ # analysis scheduling, persistence, tag embedding
  ui/            # existing screens; add library/tag/artwork/smart-playlist pages
  player/        # existing engine; wire local URIs
  lyrics/        # local/embedded precedence + save/embed
  lastfm/, update/ # keep independent networking
androidApp/src/main/cpp/
  ffmpeg_player.cpp, CMakeLists.txt, taglib/ (pinned source or prebuilt)
sharedLogic/src/commonMain/kotlin/com/amwolfstein/sistrum/
  library/, player/, lyrics/, analysis/ # pure rules; no Android/desktop coupling
```

## Workstreams and sequence

| Phase | Tasks (plan-local) | Outcome |
|---|---|---|
| 0. Baseline | T01 | Reproducible build, toolchain verification, FFmpeg/ABI/signature baseline, known failures recorded; fix sibling-path/font blockers without changing behavior. |
| 1. Local foundation | T02–T04 | `library.db` schema + exported migrations + legacy import; SAF source model/grants; scanner traversal/checkpoint/progress. |
| 2. Ingest | T05–T07 | TagLib metadata extraction + identity/dedup; incremental reconciliation/publication; tag editor + safe writes. |
| 3. Presentation data | T08–T10 | Artwork cache/Deezer page/precedence; separators; library queries/search/views wired to local data. |
| 4. Playback | T11–T12 | Local URI/FD resolution, missing-file handling, queue restore; playback verification. |
| 5. Playlists | T13–T14 | Manual playlists/favorites/M3U8; smart rule engine + UI. |
| 6. Lyrics | T15 | Local/embedded precedence, providers, save LRC/embed, data packaging. |
| 7. Analysis | T16–T19 | FFmpeg analysis build/JNI + golden fixtures; persistence/tags/reuse; normalization/album aggregation; Mood Radio local. |
| 8. History/services | T20–T21 | Local listening/history/insights; Last.fm/update independent networking cleanup. |
| 9. Cleanup | T22–T23 | Remaining desktop mechanisms/mobile equivalents; remove pairing/MQTT/HTTP/sync/camera/deps/permissions/R8. |
| 10. Delivery | T24–T27 | Migration/upgrade tests and docs; T25 dedicated native-build/APK-size optimization (separate commit); T26 full verification + APK; T27 [FINAL] cumulative review. |

Tasks are detailed in [`tasks.md`](tasks.md). Owner decisions resolved: aubio 0.4.9 + FFTW 3.3.11 are approved analysis dependencies; 0–5 ratings are a Sistrum extension; upstream M3U8/relative/BOM and Last.fm retry defects are fixed and documented as deviations.

## Key technical strategies

1. **Dual identity**: `document_key` for stable library identity; `item_sources` for overlapping roots; CUE virtual keys `#trackNN`. Legacy sync IDs are mapped during migration, never reused as local identity.
2. **Transactional publication**: scan delta in `scan_entries`, publish with `RoomDatabase.withTransaction`, delete only after complete enumeration; unavailable sources hide rows.
3. **TagLib boundary**: static-link v2.3.2 into `libsistrum_player.so`; JNI methods take a file descriptor/path, return typed metadata/write results; stage/verify/atomic replace; no audio payload changes.
4. **Analysis**: rebuild FFmpeg with `avfilter` and exactly the eight required filters (`ebur128,aspectralstats,astats,aresample,aformat,anull,abuffer,abuffersink`); port the exact graph/versions; link approved aubio 0.4.9 + FFTW 3.3.11 for tempo/onset; golden fixtures from the upstream desktop producer.
5. **Search**: FTS with folded text and match/prefix/phrase semantics; ranking differences documented, not hidden.
6. **Migration**: new DB + one-time importer + journal; never destructive; keep legacy DB until user confirms.
7. **Removal**: delete desktop code only after local replacements/tests; audit Gradle/manifest/R8/JNI/DI/tests.

## Verification strategy

- Per task: map refresh, targeted host tests, review, commit.
- Phase gates: baseline build; scanner edge cases; tag round trips; playlist/smart rules; lyrics round trips; analysis golden comparisons; upgrade migration; full airplane-mode test.
- Final: debug + minified release as available, APK signature/manifest/ABI/SHA256, dependency delta (TagLib/aubio/FFTW/build changes only), performance notes, explicit unverified items.

## Risks

- Analysis parity depends on the aubio/FFTW static build and golden fixtures; highest technical risk.
- SAF provider variability and Android 15 FGS limits; mitigated by checkpoints/retries.
- TagLib static build/licence/size; verify early.
- No physical device; instrumentation and final airplane-mode test may be blocked.
- Large schema migration from mirror data; preserve user state with strict tests.
- FFmpeg artifact rebuild can change size/decoders; baseline before/after measured.

## Open decisions

See [`blockers.md`](blockers.md): PiP/Android equivalents, release signing secrets, device coverage. Tempo/onset, ratings and upstream-defect decisions are resolved.
