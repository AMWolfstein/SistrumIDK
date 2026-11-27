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
