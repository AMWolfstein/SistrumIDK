# Implementation Plan — Standalone Android Library Upgrade

**Branch** `alpha2` · **Mission** M001 · **Spec** [spec.md](spec.md)

## Summary

Refactor the Android scanner and local data contracts first, generate local audio analysis next, then add mobile library tools and personalization. End with one cumulative review. The plan has exactly five sequential tasks.

## Repository evidence

- Baseline `93486b3e7a94257f8d46416f209d095ebf64f73f`. Planning began with `python3 workflow/codegraph/code_tools.py update`. The existing SQLite mirror initially failed to sync. Rebuilding the ignored mirror succeeded.
- Full eligible scope selected. The map recorded 419 inputs, 133 exclusions and 367 indexed source files, with zero parser-reported partial or unindexed files in that source set. Separate resource and JNI scans covered eligible inputs. Exclusions include docs and unsupported assets. Static relationships and JNI candidates do not establish runtime behavior or Gradle/CMake semantics.
- Queried `MediaStoreLibraryScanner`, `AndroidLibrarySyncStore`, `LibraryScanContent` and `FfmpegDecoder`. Read the MediaStore scanner, scan entry point, Room analysis queries, JNI declarations, existing full-screen cover transition and Android variant configuration. The scanner already reuses metadata for stable files. Room currently projects analysis from active legacy documents and local scans publish an empty analysis payload. The Kotlin decoder exposes playback commands, with no public offline PCM reader. The Android library already persists user-selected pictures through `stageArtistArtwork` in `SyncDatabase.kt`.
- The baseline has `dev` and `prod` flavors, JDK 21, and ARM64 native playback. Verify with the actual `DevDebug` tasks. Device, storage-provider and native timing behavior were not measured during planning.
- Upstream behavioral references are pinned to Airmedy `d859940c0629907e377c889fb276904b6a7a4b8c`. Use [library maintenance](https://github.com/misa198/airmedy/blob/d859940c0629907e377c889fb276904b6a7a4b8c/internal/app/library/service.go), [folder artist pictures](https://github.com/misa198/airmedy/blob/d859940c0629907e377c889fb276904b6a7a4b8c/internal/app/library/artist_local_image.go), [section settings](https://github.com/misa198/airmedy/blob/d859940c0629907e377c889fb276904b6a7a4b8c/frontend/src/components/settings/SidebarSettings.vue) and [accent settings](https://github.com/misa198/airmedy/blob/d859940c0629907e377c889fb276904b6a7a4b8c/frontend/src/components/settings/GeneralSettings.vue) as behavior references. Verify license and fixture parity before adapting any upstream implementation.

## Technical context and design

**Platform and storage.** Kotlin, Compose, Room, Android MediaStore and the Storage Access Framework (SAF), plus existing ARM64 FFmpeg/C++/JNI. Reuse existing settings, library projections and player logic.

**Scan and migration.** Replace scan orchestration in place. Preserve track IDs and user-owned rows with an explicit migration. Persist raw tags, a source revision and the extractor version. Stage results and apply them transactionally. Only remove missing sources after a successful, authoritative scan of their accessible scope. Retain IDs on verified one-to-one moves and preserve separate duplicates. Re-splitting and reindexing use stored data. An extractor version bump backfills old metadata once. One-time and periodic work share the same single-flight path. Android periodic work is best effort, with 15 minutes, 30 minutes and one hour as choices, plus on launch and manual. A persisted SAF grant allows local sidecar discovery. Resolve `artist.jpg`, `artist.jpeg` and `artist.png` in that priority, matching album artists first, and preserve explicit user overrides.

**Deezer artist artwork.** Add an opt-in online artwork setting to the scan controls. After the local scan commits, look up artists still missing pictures with bounded background work. Verify the supported Deezer endpoint and its usage terms during implementation. Send the artist name needed for lookup, and apply only a reliable, unambiguous artist match. Cache the downloaded image separately from the user override. Resolve sources in this order: user-selected image, folder image, cached Deezer image, then placeholder. No match, timeout, rate limiting or unavailable access leaves the successful local scan usable.

**Analysis.** Open an independent read-only file descriptor, decode PCM in bounded chunks using the currently linked FFmpeg libraries, and close the decoder and descriptor on completion or cancellation. Reuse the low-level decode code where possible. Use deterministic EBU R128 integrated loudness and true peak and the input semantics required by the existing Mood Radio selector. Validate against pinned upstream results or reference fixtures before enabling each consumer. Do not reuse playback handles for background analysis. Persist analysis with track/source/revision and algorithm version, so fallback and crossfade can use the existing gain path only when valid data is present. Start with one worker and bound user-configurable concurrency to the capacity validated in checks. Report failures separately from pending work and allow retry.

**Library tools.** Use existing local playlist projection and storage interfaces. Evaluate Smart Playlist filters deterministically using current local fields. Resolve M3U/M3U8 relative paths or locators available to the app, report unresolved entries and export UTF-8. Obtain system write consent before editing tags or embedded art, validate output before replacing the source, and refresh affected metadata only. Scope supported writes to formats/providers with a tested safe replacement path. Use the system document picker to save lyrics.

**Presentation.** Reuse navigation destinations, themes, player controls and local artwork rendering. Persist order and visibility of sections, presets or validated hex accent, and link Song Transition (including crossfade), Volume Normalization and Equalizer from the full-screen player menu to their existing screens and preferences. Check contrast, RTL and default layouts. Living Artwork and cover crossfade already exist on-device and get regression coverage.

## Constitution check

Use existing app modules and storage boundaries. Preserve native playback, persisted data and user scope. Review all tasks sequentially, with an independent review in each task's existing loop. No app source change, APK build or device execution is part of this planning delivery.

## Project structure

- `androidApp/src/main/kotlin/com/amwolfstein/sistrum/` contains scanning, settings, playback, lyrics and Compose UI.
- `androidApp/src/main/cpp/` contains the existing native decoder and build registration.
- `sharedLogic/src/commonMain/kotlin/com/amwolfstein/sistrum/mood/MoodRadio.kt` contains mood selection.
- [tasks.md](tasks.md) assigns starting files, validation and dependencies. [task-log.md](task-log.md) records execution evidence when authorized.
