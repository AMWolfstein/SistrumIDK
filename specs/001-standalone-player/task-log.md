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
