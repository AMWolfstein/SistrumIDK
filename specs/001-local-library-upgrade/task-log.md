# M001 task log

Baseline: `93486b3e7a94257f8d46416f209d095ebf64f73f`.

Record the map refresh, changed paths, actual checks, reviewer/round/result,
coverage gaps, and completion for each task when implementation is authorized.

| Task | Status |
| --- | --- |
| M001-T001 | Complete |
| M001-T002 | Complete |
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

## M001-T002 execution

- Task-start map refresh succeeded on 374 indexed source files, with no parser-reported partial or unindexed sources, 10,962 symbols and 21,510 relationships. Native and JNI relationships still require runtime verification.
- The existing Android FFmpeg build omits libavfilter. Airmedy Desktop's reference analyzer uses FFmpeg ebur128/aspectralstats/astats and aubio. The implementation uses the linked FFmpeg decoder with a separate descriptor and streaming 48 kHz stereo PCM, plus bundled libebur128 v1.2.6 for gated integrated loudness, loudness range and true peak. Analysis measures the stereo downmix used by the playback engine, including for multichannel input.
- Bundled libebur128 source is unmodified at commit `67b33abe1558160ed76ada1322329b0e9e058b02`. SHA-256 for `ebur128.c` is `c2fc562f1088cacab4d21250b6e04996ef36c7694ea901e08cc4d64eb542b78d`, for `ebur128.h` is `a988fa03828bcdd6258e6c52300d709a58727272a9ce6cd3fbf351f090517111`, and for `COPYING` is `d6b4754bb67bdd08b97d5d11b2d7434997a371585a78fe77007149df3af8d09c`. MIT notice is in `NOTICES`.
- Host `python3 tools/analysis/verify_loudness.py` compared the actual bundled meter with FFmpeg 7.1.5 ebur128 on stereo sine, mono downmix, gated levels, transients and silence. Finite LUFS/true-peak differences were within 0.2 dB. libebur128 gives nonfinite silence while FFmpeg prints its -70 LUFS display floor; silence is never stored as a valid gain.
- The spectral and tempo estimator is a local Hann-window/FFT and onset-autocorrelation implementation, not aubio or FFmpeg aspectralstats. Host tests check periodic clicks at 90, 120 and 150 BPM, finite spectral features, no valid mood for silence or an unmodulated sine, and pinned Airmedy median formula vectors. It intentionally omits mood when beat evidence is insufficient.
- Local Room results are keyed to track ID, source revision and analyzer version. Before and after decoding, file path/size/mtime are checked against the scan revision. A transaction checks the current revision again before saving. Failed results wait for explicit retry. New pending work is paged and bounded, and mood percentiles are calculated on selection.
- Added Android instrumentation sources and generated synthetic fixtures for MP3, AAC/ALAC M4A, Opus, silence, cancellation, stale-result rejection and Room migration. These were compiled but not run on a device or emulator. The ARM FFmpeg decoder path, Android scheduling, scoped storage behavior, audio accuracy on hardware, load and battery effects remain unmeasured.
- `./gradlew --offline :androidApp:externalNativeBuildDevDebug :androidApp:compileDevDebugKotlin :androidApp:testDevDebugUnitTest :androidApp:compileDevDebugAndroidTestKotlin :sharedLogic:testAndroidHostTest` passed with local SDK and Gradle directories and sandbox network capability. `python3 workflow/work.py validate` and `git diff --check` passed.
- Independent review round 1: NEEDS_FIXES. Reviewer identified that repeated cancellation could queue unbounded decoder handles and that a trailing debounce could starve gain updates. The corrected dispatcher holds bounded admission until native work closes and skips opening cancelled queued work. Gain changes now use a conflated fixed cadence and check current/preloaded analyses, with album mode refreshed as needed.
- After those fixes, the full native build, Android compilation/unit tests, AndroidTest compilation and shared host tests passed again. Added host tests exercise repeated cancellation against blocked workers and sustained result events without a quiet interval. Independent round 2 review passed.
- Independent review round 2: PASS (Codex). Verified actual decoder closure bounds work admission, fixed-cadence normalization changes, Room revision checks and pending work, generated ARM64 build, 452 Android unit tests, 45 shared Android host tests, AndroidTest source compilation and the FFmpeg loudness oracle. Android device decoding and playback were not exercised.
