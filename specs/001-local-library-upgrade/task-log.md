# M001 task log

Baseline: `93486b3e7a94257f8d46416f209d095ebf64f73f`.

Record the map refresh, changed paths, actual checks, reviewer/round/result,
coverage gaps, and completion for each task when implementation is authorized.

| Task | Status |
| --- | --- |
| M001-T001 | Complete |
| M001-T002 | Reserved |
| M001-T003 | Reserved |
| M001-T004 | Reserved |
| M001-T005 | Reserved |

## M001-T001 execution

- Task-start map refresh succeeded with 367 indexed source files, zero parser-reported partial or unindexed sources, 10,757 symbols and 21,061 relationships. Full eligible scope selected; static relationships and JNI registrations remain subject to runtime verification.
- Added raw tag snapshots and full-file fingerprints, transactional re-splitting and search rebuild, safe move matching after fingerprint backfill, volume availability and legacy ambiguity guards, scan progress/cancellation and schedule settings, local artist artwork selection, and optional Deezer artwork refresh/cache.
- Existing libraries without prior fingerprints cannot prove a file was moved before the first successful scan with this version. When an old row without a fingerprint plausibly matches a current track, its prior track and assets are carried forward without assigning its ID to the new track. A later scan retains the ambiguous row while the match remains plausible.
- The initial Gradle attempt could not start because sandboxed networking blocked wildcard IP detection. Running Gradle offline with the sandbox network capability succeeded. The disk later filled; only regenerable Android build intermediates and temporary build files were removed before rerunning checks.
- `./gradlew --offline :androidApp:compileDevDebugKotlin :androidApp:testDevDebugUnitTest :androidApp:compileDevDebugAndroidTestKotlin :sharedLogic:testAndroidHostTest` passed with the project-local SDK and Gradle user directories. Focused coverage includes local artist priority, date fallback, raw-tag re-splitting, move ambiguity and scan safety. The Android instrumentation test sources compiled. No instrumentation or physical-device tests were run.
- Android instrumentation cases for Deezer cover default-off behavior, refresh, ambiguity/offline fallback, disabling during a request, and cached image availability after disabling/restart. Those tests require a device or emulator to execute.
- Live Deezer access could not be verified because the environment's HTTP proxy rejected the connection. The lookup endpoint and `picture_medium` field were verified in Airmedy Desktop commit `d859940c0629907e377c889fb276904b6a7a4b8c`. End-to-end network behavior, actual background scheduling, persisted folder access, large-library hashing cost and audio-device behavior remain unmeasured.
- Independent review round 1: NEEDS_FIXES. Reviewer identified overbroad legacy identity blocking, JobScheduler completion after user cancellation, and potential loss from incomplete MediaStore rows. Fixes carry forward plausible legacy rows and assets, distinguish platform stops from user cancellation, and abort on incomplete known rows. Added unit and Room instrumentation regression cases; reviewed in round 2.
- Independent review round 2: PASS (Codex). Reviewed the corrected scan, database, Android job lifecycle and cached artist-art flow. Confirmed 449 Android unit tests and 41 shared Android host tests passed, Android instrumentation sources compiled, workflow validation and whitespace checks passed. Device runs and live Deezer access remain unverified.
