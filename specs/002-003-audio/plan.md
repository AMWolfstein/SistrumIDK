# Android audio migration

This local draft combines the open work from Spec 002, its analysis decoder work moved to Spec 004, and Spec 003. It has **12 tasks**, including the final cumulative verification. Run them in order. Each task includes its own implementation, regression checks and review.

## Starting point and scope

SistrumIDK has already imported Spec 002 Phase A, M1 and M2. The remaining work completes folder discovery and metadata, reconnects Mood Radio to Media3 analysis, adds CUE tracks, and measures the new playback and analysis paths. Once coverage is known and the outstanding format decisions are resolved, the app can move to a single Media3 engine, remove its native build, and clean up and split the remaining playback code.

The source plans are from SistrumKotlin at `c9a4468`. This draft includes `T033a–T033g`, originally Spec 002 M4b, from Spec 004. Use the SistrumIDK workflow and existing `dev` and `prod` variants. Keep the other Spec 004 work outside this draft. [Spec 002 tasks](https://github.com/AMWolfstein/SistrumKotlin/blob/c9a4468/specs/002-kotlin-decoders/tasks.md) · [Moved M4b](https://github.com/AMWolfstein/SistrumKotlin/blob/c9a4468/specs/004-ui-extensions/NOTES.md#b-m4b--moved-from-002-task-text-unchanged) · [Spec 003 tasks](https://github.com/AMWolfstein/SistrumKotlin/blob/c9a4468/specs/003-native-removal/tasks.md)

## Tasks

- [ ] **01. Restore reproducible test data and record the baseline**
  Fix the 89 failing WaxLabel corpus preconditions in `scripts/waxlabel-oracle.py`, its generators and pinned manifests. Rebuild from a clean cache and verify hashes and metadata instead of changing assertions. Record a local test and lint baseline on the current tree and prepare report-only Detekt with Compose rules, R8 usage, lint unused-resource reporting and Kover for the Android app and all five library modules. Record known failures separately so a baseline failure cannot hide a regression.

- [ ] **02. Finish folder scanning and metadata**
  In `ScanFilterPreferences`, `MediaStoreLibraryScanner`, `LibraryScanRunner` and `ScanFilterContent`, persist read grants for selected SAF trees, enumerate with `DocumentsContract`, and reconcile with MediaStore so unindexed supported files appear once. Prompt for folders when none are chosen. Apply upstream D-31's folder-only behavior and removal of blacklist mode while preserving the existing library when a grant is lost or a scan cannot run. Make playback, artwork where needed, metadata, scan checks and `OfflineDecoder` read content URIs. Route APEv2, ASF, DSF/DFF, Matroska/WebM and AIFF-C tags via `:metadata`; feed ReplayGain and Sound Check to normalization with existing precedence. Bump metadata schema 7 to 8 for a one-time reread and add a tested Room migration only if storage changes require one. Verify deduplication, tag parity and failed-tag fallback with local tests.

- [ ] **03. Restore Mood Radio on Media3**
  Feed the loudness PCM into `:analysis` from `AnalysisMeasurer`, `TrackAnalyzer` and `LoudnessAnalysisWorker`; persist versioned energy, danceability, brightness, tempo and mood, calculate library percentiles, and retain bounded, resumable analysis. Enable the existing Mood Radio entry points when eligible tracks exist. Check oracle parity, queue start and refill for each mood, and compatibility with existing analysis rows.

- [ ] **04. Add CUE sheets from scan through playback**
  Parse external and embedded CUE sheets using `:codecs` and `:metadata`. Add the needed nullable CUE fields with the next Room version and a migration from the actual schema after task 02. Keep the source row hidden while a CUE is valid and playable as a normal track when invalid, with a skipped-files summary entry. Carry segment times into Media3 clipping, seeking, crossfade, queue identity, listening statistics and Last.fm. Use local fixtures to verify segment starts within one frame and consecutive segments join within two frames. Preserve listening data, playlists, favorites and analysis through the migration.

- [ ] **05. Port the Kotlin Opus and AAC decoders for analysis**
  Port Opus CELT, SILK, hybrid and multistream, including pre-skip, gain and channel mapping, and AAC-LC/HE-AAC v1/v2 via packet APIs under `codecs/`. Run the RFC 8251 Opus reference checks and the pinned WaxFlow AAC and Opus PCM comparisons. Use local regression assertions for steady-state buffer reuse and retain source/license attribution. Preserve the existing playback renderer selection.

- [ ] **06. Port the Kotlin MP3, FLAC and Vorbis decoders for analysis**
  Port MPEG Layer III, FLAC and Vorbis packet decoders under `codecs/`; honor container stream setup, delay and padding. Verify FLAC bit for bit and the other codecs against the pinned oracle and applicable tolerances. Keep corpora outside Git, retain attribution, and use local regression assertions for steady-state buffer reuse. Keep platform playback selection intact.

- [ ] **07. Integrate the analysis decoders and close the Spec 002 performance checkpoint**
  Wire the five new decoders into `OfflineDecoder` only, using a per-format policy and one logged platform retry on a Kotlin decode failure. Verify loudness error bounds of 0.00005 LU for lossless and 0.003 LU for lossy. Retain current analysis data unless measured changes require a version bump. Run the analysis and performance benchmarks here on the authorized test device. Until results are available, keep the platform decoder selected for these formats. Switch to Kotlin per format only when it beats the platform median on the same device and settings. Record three runs per format, at most 10 minutes each. Cover warm decode speed of at least 5× real time (2× for WMA Voice and DSD256), CPU at most 1.5× Media3 FLAC, seek within 300 ms, and two-decoder crossfade without underruns. Record memory, battery, analysis throughput and Night mode cost. Benchmark a 1,000-file scan against the Spec 001 baseline with a target of no more than 20% slower. Finish Spec 002 regression and record the checkpoint after the required evidence is available.

- [ ] **08. Verify format coverage on the test device before removal**
  Compare the shipped FFmpeg demuxers and decoders with scan admissions, Kotlin providers and device platform decoders. Run the pre-removal playback checks on the authorized test device. Record each format in a readable table and TSV with provider, fixture, playback start/advance/seek result and disposition. Cover CUE starts and joins, gapless and crossfade, and capture the final pre-removal evidence here. Cover DSD in DSF/DFF/WavPack, WMA family, and platform-dependent formats; record the DFF channel-layout decision. Keep FFmpeg until each gap has an owner decision and any approved replacement codec has passed its checks. Include owner acceptance of explicitly unsupported formats before task 09. Verify the skipped-files summary reflects accepted gaps.

- [ ] **09. Make Media3 the only playback engine**
  Pin existing behavior with characterization checks, then remove the engine switch, preference/selection types, native branches and `FfmpegDecoder`/JNI. Keep the `PlayerEngine` seam and a single registry scan gate. Retain saved normalization choices, Night mode, silence-aware crossfade, CUE, gapless and session behavior. Remove the app C++/FFmpeg files, native build script and CMake wiring. Preserve the EQ golden JSON with source provenance, and keep the incoming `audio/ffmpeg` MIME mapping. Use task 08 as the format coverage baseline. Run local playback regression tests and an app-owned native reference audit.

- [ ] **10. Remove NDK build leftovers and clean up proven dead code**
  Remove the remaining NDK/ABI and FFmpeg-only packaging/setup entries. Verify clean APKs have zero app-owned `.so` files and no `.cxx` output. Inventory third-party libraries with their owning dependencies and record decisions for any change to those dependencies. Use the baseline reports to remove only proven unreachable code and resources; check reflection, manifests, translations, generated resources and SAF paths first. Compare local lint, coverage and unused-code reports against task 01.

- [ ] **11. Split playback files and check the test suite**
  After native removal and cleanup, map responsibilities in `PlaybackCoordinator`, `Media3Engine`, `Media3PlayerFactory`, `LimiterSession` and `PlaybackService`. Close characterization gaps before moves, keep ordering, threads, locks and internal caller contracts, and move existing tests with their responsibilities. Explain files over roughly 500 lines that should remain intact. Review the test sources across modules for weak checks and dead or duplicate coverage. Use local reruns to investigate flaky host tests. Address confirmed issues without loosening existing assertions.

- [ ] **12. Verify and document the combined migration**
  Run the cumulative local tests, lint, static native source and APK checks, and local playback and scan regressions. Compare the local quality reports against task 01. Update the project's build and playback docs, regression instructions and third-party notices. Record the completed checkpoints and unresolved evidence for owner review. Reference the evidence from tasks 07 and 08. Merge to `main` is the owner's decision.

## Verification rules

- **Tasks 07 and 08 are the only tasks that run benchmarks or checks on a physical device.** Task 07 owns performance measurement and task 08 owns device playback and format coverage before removal. Use an authorized, isolated test install for those tasks. Keep native removal pending while any required result from task 07 or 08 is missing.
- **Tasks 01–06 and 09–12 use only code changes, local tests, lint and static build or artifact checks.** Use existing DevDebug host tests and compile instrumentation sources when relevant. Use the existing release variants for local APK inspection. Device, listening and performance checks stay in tasks 07 and 08.
- Preserve installed library data and existing feature behaviors. Run migration, playback and locale checks alongside the task that changes each boundary.
- Keep test fixtures and permanent notices required by implemented code. Do not import the source repo's workflow, runner, task registry or agent instructions.

## Repository evidence

- SistrumIDK baseline `5471d4c` on `alpha1`. CodeGraph mapped 840 sources, 24,982 symbols and 59,615 relationships, with zero partial or unindexed sources. Generated and external corpus assets were outside the map.
- `MediaStoreLibraryScanner` currently uses metadata schema 7 and `SyncDatabase` is Room version 14. Use the actual version when implementing CUE if the scan task adds a migration. URI work crosses scanner, playback and analysis; it cannot be validated by a picker test alone.
- Inspect `DecoderRegistry`, `OfflineDecoder` and `PlayerEngine` before selecting new renderer paths. The signed `0a8c755` baseline APK contains the Sistrum JNI library and four FFmpeg libraries, plus `libandroidx.graphics.path.so` and `libdatastore_shared_counter.so` from dependencies. Spec 003's native-removal check distinguishes app-owned libraries from documented dependencies.
