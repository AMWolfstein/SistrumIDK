# Tasks — Standalone Android Library Upgrade

Inputs: [spec.md](spec.md), [plan.md](plan.md) and [.specify/project-policy.md](../../.specify/project-policy.md). Implementation requires separate authorization.

## Execution

The first action of every task is `python3 workflow/codegraph/code_tools.py update`. Inspect coverage and stop that task if refresh fails. Execute in order, record checks and independent review in [task-log.md](task-log.md), and allow up to three review rounds. After PASS, complete through the workflow helper and make one commit including the relevant records. There is no separate map, review or test task.

## Implementation

- [x] M001-T001 Rebuild local library scanning and metadata maintenance
  - **Story** US1. **Depends on** planning commit.
  - **Paths** `androidApp/src/main/kotlin/com/amwolfstein/sistrum/sync/{MediaStoreLibraryScanner,SyncDatabase,LocalLibraryRuntime,ScanFilterPreferences,TagSeparatorPreferences}.kt`, `androidApp/src/main/kotlin/com/amwolfstein/sistrum/ui/screens/{LibraryScanContent,IntegrationContent}.kt`, and Android settings/resources and storage tests.
  - **Acceptance** Manual, launch and best-effort scheduled scanning share one job. Show progress, cancellation and a summary. Preserve the existing library on missing media, permission errors and interrupted scans. Keep stable IDs on verified moves. Store raw tags and extractor version. Apply separators and optimize search from stored data, allow targeted or forced metadata/art refresh, and set automatic artist pictures from the latest local song with usable artwork. Add a persisted Deezer switch under Settings → Integrations, default off. With Deezer on, the scan refreshes the artist’s current catalog profile picture even when an older provider image exists. Keep explicit overrides first and fall back to cached or local artwork when Deezer lookup fails. Switching off stops provider requests, invalidates in-flight results, and keeps cached Deezer pictures visible across restarts.
  - **Validation** `./gradlew :androidApp:compileDevDebugKotlin :androidApp:testDevDebugUnitTest`. Cover unchanged files, one-time backfill, re-split, duplicate/moved entries, cancel and external storage loss with fixtures. Verify supported Deezer access and use mocked responses for latest local artwork selection, default-off behavior, refresh of an older provider image, switching off during lookup and after restart, reliable matches, ambiguity, offline cache, source priority and transient errors. Add Room migration/storage instrumentation coverage and record if it cannot run.

- [x] M001-T002 Enable on-device analysis for normalization and Mood Radio
  - **Story** US2. **Depends on** M001-T001.
  - **Paths** `androidApp/src/main/cpp/{ffmpeg_player.cpp,CMakeLists.txt}`, `androidApp/src/main/kotlin/com/amwolfstein/sistrum/player/{FfmpegDecoder,NormalizationPreferences,PlaybackService}.kt`, `androidApp/src/main/kotlin/com/amwolfstein/sistrum/sync/SyncDatabase.kt`, scan/analysis UI and `sharedLogic/src/commonMain/kotlin/com/amwolfstein/sistrum/mood/MoodRadio.kt`.
  - **Acceptance** Analyze supported local MP3, AAC/ALAC M4A and Opus using independent background decoding. Validate integrated loudness, true peak and mood inputs, persist revision-keyed results, expose enable/progress/failure/retry controls and bounded workers. A missing or stale result leaves ordinary playback available. Verified results enable normalization and Mood Radio without desktop data.
  - **Validation** `./gradlew :androidApp:externalNativeBuildDevDebug :androidApp:testDevDebugUnitTest :sharedLogic:testAndroidHostTest`. Compare silence, loudness and mood fixtures with reference outputs, test cancellation, decode errors, stale results, queue refill and gain routing. Document unmeasured playback/device behavior.

- [x] M001-TOR009 Replace local audio analysis with Airmedy parity
  - **Owner request** Follow-up to M001-T002, after the already completed M001-T003.
  - **Paths** `scripts/build-ffmpeg-android.sh`, `scripts/build-analysis-deps-android.sh`, `androidApp/src/main/cpp/`, `androidApp/src/main/kotlin/com/amwolfstein/sistrum/analysis/`, `androidApp/src/main/kotlin/com/amwolfstein/sistrum/sync/SyncDatabase.kt`, `sharedLogic/src/commonMain/kotlin/com/amwolfstein/sistrum/analysis/` and relevant tests.
  - **Acceptance** Replace the T002 signal estimator and libebur128 with Airmedy's pinned FFmpeg 9.0.2 filter graph and aubio 0.4.9/FFTW3F 3.3.11 pipeline. Keep original input channel handling and Airmedy's zero-tempo eligibility. Invalidate T002 analysis by version and reanalyze safely while preserving playback and background lifecycle. Build the Android artifacts with 16 KiB alignment.
  - **Validation** Build ARM64 artifacts, run `:androidApp:externalNativeBuildDevDebug :androidApp:compileDevDebugKotlin :androidApp:testDevDebugUnitTest :androidApp:compileDevDebugAndroidTestKotlin :sharedLogic:testAndroidHostTest`. Compare the 12 raw features and corpus mood against pinned Desktop code on MP3, AAC, ALAC, FLAC, Opus and WAV with FFmpeg 9.0.2 and the same aubio/FFTW versions. Document device and real-world format coverage gaps.

- [x] M001-T003 Add portable playlists and local metadata tools
  - **Story** US3. **Depends on** M001-T002.
  - **Paths** `androidApp/src/main/kotlin/com/amwolfstein/sistrum/ui/screens/{LibraryPlaylistsViewModel,PlaylistDetailsViewModel,PlaylistWrites}.kt`, `androidApp/src/main/kotlin/com/amwolfstein/sistrum/sync/SyncDatabase.kt`, `androidApp/src/main/kotlin/com/amwolfstein/sistrum/lyrics/AndroidLyricsService.kt` and relevant Compose menus/resources.
  - **Acceptance** Smart Playlist rules evaluate local fields. M3U/M3U8 import/export preserves UTF-8 and order and reports unresolved items. File tag and embedded art edits use Android write consent and preserve media and user data if a write fails. Save lyrics as UTF-8 LRC through the system picker. Provide a separate explicit save-inside-audio action for safely supported formats, with synchronized and plain lyrics read-back verification.
  - **Validation** `./gradlew :androidApp:compileDevDebugKotlin :androidApp:testDevDebugUnitTest`. Cover relative paths, unresolved tracks, Unicode, round trips, unsupported writes, declined permission, and post-edit scan. Record SAF provider instrumentation status.

- [ ] M001-T004 Add library and playback personalization
  - **Story** US4. **Depends on** M001-T003.
  - **Paths** `androidApp/src/main/kotlin/com/amwolfstein/sistrum/ui/screens/{LibraryContent,SettingsContent,PlaybackSettingsContent}.kt`, `androidApp/src/main/kotlin/com/amwolfstein/sistrum/settings/ThemePreferences.kt`, `androidApp/src/main/kotlin/com/amwolfstein/sistrum/ui/theme/SistrumTheme.kt` and `androidApp/src/main/kotlin/com/amwolfstein/sistrum/ui/navigation/{FullScreenPlayer,FullScreenPlayerArtwork}.kt`.
  - **Acceptance** Persist section visibility and order while keeping navigation usable. Apply preset/custom hex accent and link Song Transition, Volume Normalization and Equalizer from the full-screen player menu to the existing settings screens. Preserve local Living Artwork and cover transitions.
  - **Validation** `./gradlew :androidApp:compileDevDebugKotlin :androidApp:testDevDebugUnitTest :androidApp:lintDevDebug`. Check defaults, invalid accent, restart, RTL, contrast, navigation and artwork regression scenarios.

## Final cumulative review

- [ ] M001-T005 [FINAL] Review the standalone library upgrade
  - **Depends on** M001-T001 through M001-T004 and any authorized mission extras.
  - **Paths** All cumulative mission changes, [spec.md](spec.md), [plan.md](plan.md), [task-log.md](task-log.md) and this checklist.
  - **Acceptance** Review migration, stored data, storage permission boundaries and native playback paths against the spec. Resolve actionable findings within this task.
  - **Validation** `./gradlew :androidApp:externalNativeBuildDevDebug :androidApp:compileDevDebugKotlin :androidApp:testDevDebugUnitTest :androidApp:lintDevDebug :sharedLogic:testAndroidHostTest` and `python3 workflow/work.py validate`. Review the integrated upgrade, scan/edit/rescan, local analysis/playback and UI scenarios. Record device coverage gaps. APK or physical-device work needs owner authorization.
