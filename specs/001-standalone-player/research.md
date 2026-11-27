# Research: standalone Airmedy parity on Android

**Mission**: M001 · `specs/001-standalone-player`
**Date**: 2026-11-27
**Sources**: Airmedy `d859940c…` (read-only clone), Mornye `af9a4d64…` (read-only clone), target `alpha10` at `cfa44a5`, live map refresh, four source audits + target audit.

## 1. Pinned sources

| Role | Repo | Pin | Notes |
|---|---|---|---|
| Feature/algorithm authority | `misa198/airmedy` | `d859940c0629907e377c889fb276904b6a7a4b8c` | Master tip at audit time; 0 newer commits. Same revision imported by TOR001. Desktop Go + `mobile/` Android app. GPL-3.0. |
| Scanner design reference | `mornyeapp/Mornye` | `af9a4d6422759541ec57a9ed696b3b47928a1bff` | LGPL-3.0, Flutter/Dart/Rust. Behavior reference only; no code copy. |
| TagLib (approved new dependency) | `taglib/taglib` | tag `v2.3.2` → commit `deadc2990767dfbda0701e0ab35fdeea653db08f` | Official C++; LGPL-2.1/MPL-1.1. Static link candidate; audit in Task A. |
| Target baseline | `AMWolfstein/SistrumIDK` | `cfa44a5` (`alpha10`) | Workflow installed; clean tree; rebranded `com.amwolfstein.sistrum`. |

## 2. Target baseline findings (source-verified)

- **The app has no local library.** All tracks/assets/playlists/lyrics/analysis enter through a desktop sync manifest. No SAF/MediaStore/DocumentFile code exists. `SyncDatabase.kt` (`library-sync.db`, version 12) is the only store; `AndroidPlaybackRuntime` resolves audio only from `sync_assets`.
- **Existing UI/player/lyrics/EQ tests are complete relative to upstream `mobile/`** (309 Kotlin files, 62 host-test files/281 tests, 54 androidTest files/276 tests, shared 58 tests). Do not rebuild UI.
- **Desktop coupling**: MQTT, pairing, mDNS, CameraX/ML Kit QR, signed HTTP pull/reconciliation, `dataSync` service, LAN permissions, mirror schema. Independent networking: Last.fm, LRCLIB, KuGou, GitHub update, release-note images.
- **Build blockers**: no toolchain at audit start (now installed), missing FFmpeg artifacts, generated font task, missing sibling romanization data, `fallbackToDestructiveMigration`, docs drift.
- **Known defects**: `SyncDatabase` destructive fallback/no schema export; M3U8 export of smart/favorites empty upstream; no ratings upstream; Last.fm has no offline retry upstream; romanization data half-packaged.

## 3. Architecture decisions

### D1 — Local library database

Use a **new Room database `library.db`** with a normalized local schema modeled on upstream SQLite (tracks/albums/artists/album artists/genres/composers/credits/playlists/lyrics/analysis/listening/artwork/sources). Keep the legacy `library-sync.db` untouched as a read-only backup. A one-time migration copies user-owned data (local playlists, favorites, playlist artwork, listening sessions/attempts, cached lyrics) and maps track references where possible; desktop-fed mirror rows are not re-created. Export Room schemas and write real migrations. Never `fallbackToDestructiveMigration`.

### D2 — Scanner (Mornye behavior, Kotlin/Room)

- SAF `ACTION_OPEN_DOCUMENT_TREE`, read+write+persistable grants; multiple roots; volume metadata; never release grants silently.
- Breadth-first traversal, URI visited set, rich `DocumentsContract` projection with `DOCUMENT_ID` fallback, `EXTRA_LOADING` retry (300/600 ms), `CancellationSignal`.
- Audio allowlist exactly: `.flac .m4a .mp4 .aac .mp3 .opus .ogg .ape .wv .mpc .wav .aiff .aif .dsf .dff`; exclude `.partial`, `.partial.*`, `.replaced`.
- CUE first: virtual paths `<cueUri>#trackNN`, backing audio excluded; folder cover priority `cover,folder,front,album,albumart` × `jpg,jpeg,png,webp`.
- Persist **both** `fileUri` and a tree-independent `documentKey` (authority + document id). Add uniqueness/dedup for overlapping roots (fixes Mornye duplicate gap).
- Change detection: mtime **and** size when available; `metadataVersion` forces re-read; `mtime == 0` is unknown, not unchanged.
- Deletion only after a fully successful traversal; unavailable/revoked/unmounted sources hide rows, never delete. Tri-state availability probe; retries.
- Checkpoint in Room (`scan_runs` + `scan_entries`), committed with rows; no NDJSON. Resume after process death; progress `StateFlow` with explicit states; pause holds position, cancel preserves completed work.
- WorkManager for periodic/auto-scan; foreground `dataSync` service for user scans; honor Android 15 FGS timeout. MediaStore only as scoped fallback for `media*` providers.

### D3 — Metadata and TagLib

- Pin TagLib v2.3.2 (`deadc299…`), official C++, static-link into `libsistrum_player.so`; minimal JNI surface; LGPL-2.1/MPL-1.1 notices in `NOTICES`; project is GPL-3.0.
- Port alias priority and fields exactly (title, sort, album, album sort, artists, album artists, genres, composers, copyright, BPM, label, ISRC, year, track/disc/total, duration seconds, bitrate, sample rate, bit depth, codec, format, artwork, lyrics, raw tag map).
- Normalization functions exactly: article stripping, Unicode fold (NFD, remove Mn, NFC, `đ/Đ`), numeric runs zero-padded to 4, default delimiters `[";", "\\\\", ","]`, raw separator `"; "`, literal split, case-insensitive dedup, empty list = no split.
- Identity: upstream uses UUID MD5 of normalized path/entity keys; Android cannot use raw paths with SAF, so use stable `documentKey`/CUE virtual key and preserve entity normalization keys. Document the adaptation; keep user playlists/favorites mapped by the old track IDs during migration where possible.
- Tag writes: stage to a temporary copy, verify audio payload/unrelated tags, atomic replace where SAF supports it; fallback to sidecar DB with visible failure. Serialize per file; no self-triggered rescans.

### D4 — Artwork

- Cache: SHA-256 content key + decoded extension (`.jpg/.png/.webp`), variants `_sm` 64×64 and `_md` 500×500 JPEG q85; validate decode; bounded cleanup; never delete active assets.
- Album art inherited from track; playlist custom art; artist precedence overridden by owner: durable Deezer import > latest *released* track artwork by tag release date (undated last, deterministic tie) > placeholder; never replace Deezer with track art; recompute fallback on library changes.
- `Settings → Integrations → Online Artwork` directly below Lyrics, Deezer toggle OFF by default; disable stops new imports but keeps/display cached images offline; failures never erase art.
- No new image library; use existing Android decoders + FFmpeg where needed.

### D5 — Separators

Port four categories (artist, album artist, genre, composer) with upstream defaults; support add/edit/delete; validate ≤5 runes, trimmed, unique. Keep raw tags; re-split derived relations + search index from stored raw values without rereading audio or analyzing. Never double-split native multi-value tags.

### D6 — Search

Room FTS4/5 (current mirror uses FTS4) with folded text (same `FoldUnicode`), term `match OR prefix`, all terms AND, multi-term phrase boost 2.0, limit 200. Bleve ranking is not reproducible exactly; implement equivalent matching semantics and document ordering/tie-break differences. Index tracks/albums/artists/composers/playlists; keep updates transactional.

### D7 — Playback

Reuse existing FFmpeg/AAudio engine and MediaSession. Replace synced-track resolution with local `documentKey`/URI resolution; preserve queue (cap 1000; allowed 100/500/1000/2000/3000), shuffle/repeat/restore, gapless, equal-power crossfade 0–12 s (`effFade=min(sec,dur/2)`, start when remaining ≤ effFade and >0.4 s), EQ 10 ISO bands Q=1 ±12 dB/0.5 dB, preamp ±12, width 0–200, normalization track/album, target −14 LUFS, clip guard. Keep Android focus/route/media notification behavior. Never load a missing file; stop/clean queue when current track disappears.

### D8 — Playlists, favorites, smart rules

Port manual CRUD, LexoRank ordering (`ORDER BY position, track_id`), pinned playlists, artwork, favorites virtual playlist, M3U8 import/export with owner-approved fixes (all playlists export, relative paths, BOM/#EXTENC). Port smart rules exactly: fields/operators, recursive all/any, `is` binary equality, `contains` LIKE, numeric typing, `between`, `added_at` Go-computed cutoff, default sort `sort_title`, limit sorts, 16-leaf cap, live vs frozen membership. Port favorites; **ratings are a Sistrum product extension** (0–5 stars) per owner decision: Room field, editor/display, sort/filter, smart-rule field; document as non-upstream.

### D9 — Lyrics

Local precedence: sibling `.lrc` → sibling `.txt` → configured subfolder → dedicated folder → embedded → cached online. Then provider behavior (LRCLIB then Kugou per settings; both-enabled race first non-null). Preserve ranking: title 0.5/artist 0.3/duration 0.2, title ≥0.7, duration diff ≤5 s, Levenshtein over runes, synced preferred. Manual search; save as LRC (global folder → subfolder → track dir) or embed via TagLib; confirm overwrite; validate timing; report denied/read-only failure. LRC parser: timestamps, bilingual `^`/`/`, enhanced `<mm:ss>`, word end ≤ next line/start+1 s. `[offset:]` is not supported upstream — flag, do not silently add unless owner approves. Romanization: port required upstream data (`pinyin.txt`, `TSCharacters.txt`) with attribution; same limits/version.

### D10 — Analysis and Mood Radio

Exact pipeline: FFmpeg filter graph
`ebur128=metadata=1:peak=true,aspectralstats=measure=centroid+rolloff+flatness+flux,astats=metadata=1:reset=0`;
mono 44.1 kHz float for tempo/onset; aubio `tempo default`/`onset hfc`, 1024/512; true peak per channel → dBFS; `onset_variance` in 44.1 kHz sample²; component versions FFmpeg 1, aubio 1, analyzer 4; mood formula version 2; normalizer P1/P99, spread (P95−P5)/2, sigmoid 2.5z; energy/danceability/brightness weights as audited; tempo score 60/115/180; percentiles linear interpolation; 100-add/30 s percentile batch; 3 min timeout; worker 2, max numCPU/2; playback throttle.

**Decision (owner)**: link aubio 0.4.9 with FFTW3F (tempo default + HFC onset, 44.1 kHz, 1024/512) exactly as the desktop analyzer. Golden fixtures validate parity.

Persist versioned results in Room and TagLib tags: standard ReplayGain track/album gain/peak; custom namespaced fields for measured LUFS, true peak, BPM, energy, danceability, brightness, onset variance, spectral features, schema/algorithm version, audio-content fingerprint (not whole-file hash). Preserve existing ReplayGain by default. Validate version/audio identity/album membership; reuse valid scan results; invalidate album aggregates when membership changes. Read-only/denied writes use Room with visible status.

Mood Radio: equal-weight squared Euclidean (`tempo/200`), 80 candidates, opening 3 from top 20, weight `1/sqrt(rank+1)`, album then artist relaxation, 3-artist cooldown, refill <3 with batch 15, exclude queue. Preserve nondeterministic weighted random (runtime randomness) and no tie-break; document.

### D10b — Ratings (Sistrum extension)

Add a 0–5 integer/float rating per track, user-owned, editable in metadata editor and rating control, sortable/filterable, available as a smart-playlist field. Survives scans/migrations; no desktop dependency. Not claimed as upstream parity.

### D11 — Listening/history

Port sessions/attempts/daily aggregates, qualified play threshold ≥30 s and (≥50% or ≥240 s), 10 s minimum interval, 1 min checkpoint, local-day splitting, 180-day retention, insights ranges 7d/30d/all, top 50, top 5 genres + Other, quality classifier (hi-res bit depth >16 or rate >48 kHz), streak. Use local install identity; no paired-device source filter. Prevent duplicates across retries/process death/migrations.

### D12 — Desktop removal

Only after local replacements: remove pairing/QR/MQTT/mDNS/HTTP sync, `LibrarySyncService`, reconciliation/acks, mirror tables/queries, CameraX/ML Kit, LAN/multicast permissions, HiveMQ/EdDSA deps, sync-only R8 rules, sync UI/routes/tests. Keep MediaSession, internet services, notifications.

### D13 — Build/verification

Toolchain installed: Temurin 21.0.13.1, SDK platform 37.0, build-tools 37.0.0, NDK 30.0.16248370, CMake 3.22.1, fontTools 4.69.1/brotli. FFmpeg artifacts must be rebuilt with `avfilter` + required filters (current build disables avfilter). Native ABI stays arm64-v8a. Build debug/minified release as available; final dev APK with SHA256. No device attached at planning time; instrumentation/physical tests remain owner/emulator-dependent.

## 4. Constants that must not drift

| Area | Constant |
|---|---|
| Analysis | filter graph above; 44.1 kHz/1024/512; versions 4/1/1/2; P1/P99, 2.5z; weights 0.32/0.23/0.17/0.18/0.10 and 0.45/0.30/0.15/0.10; tempo 60/115/180 |
| Mood | 80 candidates, 15 batch, <3 refill, opening 3/top 20, `1/sqrt(rank+1)`, tempo/200 |
| Normalization | target −14 LUFS, clip guard, album mean, track/album modes |
| Playback | crossfade 0–12, effFade min(dur/2), remaining >0.4; queue 1000/allowed set; EQ 10 bands/Q1/±12/0.5, preamp ±12, width 0–200 |
| Lyrics | ranking 0.5/0.3/0.2, title≥0.7, duration≤5 s; manual save order |
| Listening | 10 s min, 1 min checkpoint, 30 s/50%/240 s qualified, 180 days, top 50/top 5 |
| Metadata | default `[";","\\\\",","]`, `"; "`, 4-digit padding, fold rules |
| Scanner | extension allowlist, CUE `#trackNN`, cover priority, mtime+size |

## 5. Open decisions (asked)

1. Resolved (revised): add aubio 0.4.9 + FFTW 3.3.11.
2. Resolved: add Sistrum 0–5 ratings.
3. Resolved: fix M3U8/relative/BOM and add Last.fm retry.

All three decisions are resolved; no decision-gated tasks remain.

## 6. Native build / APK size optimization reference (Looper)

Owner-requested reference, inspected read-only. **Not** a migration target: Sistrum keeps FFmpeg + AAudio; no mpv.

| Piece | Pin | Relevant findings |
|---|---|---|
| Looper | `4d532b3dd869d60ab4f3d6136d20e9cc12b3ae7d` — https://github.com/sthrnilshaaa/looper (GPL-3.0) | Flutter app; `android/app/build.gradle.kts:92-99` sets `isMinifyEnabled=true`, `isShrinkResources=true`; `package.sh` builds split-per-ABI APKs. |
| mpv_audio_kit | 0.8.0 — https://github.com/ales-drnz/mpv_audio_kit (BSD-3-Clause), vendored in Looper `third_party/` | `hook/build.dart` fetches a prebuilt `libmpv` per ABI; Media3 is a controller only. No native build logic beyond fetching the library. |
| libmpv-scripts | `319c1e2542c6551334fc2bdc49a36f95c768fb15` — https://github.com/ales-drnz/libmpv-scripts (BSD-3-Clause scripts; output libmpv LGPL-2.1/licensed dependencies) | `scripts/build_libmpv_android.sh:702-830`, `scripts/shared/_audio_only.sh:703-810,1442-1752`; builds audio-only libmpv + FFmpeg; the real optimization reference. |

Key `libmpv-scripts` Android strategy (`scripts/build_libmpv_android.sh`, `scripts/shared/_audio_only.sh`):

- **FFmpeg**: static deps; `--enable-static --disable-shared --disable-programs --disable-doc --disable-debug --enable-small`, `--enable-avcodec/avfilter/avformat/avutil/swresample`, `--disable-avdevice`, `--disable-swscale` (with a strip patch), all demuxers kept, curated audio decoders, `--disable-encoders`, `--disable-muxers` except `spdif`, curated audio filters (`--disable-filters --enable-filter=...`), curated parsers/bsfs, protocol whitelist (`file,http,https,...`), `--enable-version3`, `--enable-lto=thin` on Clang.
- **Dependency flags**: `-Oz` for compression deps, `-O2` for FFmpeg/mpv, ThinLTO across static archives (`-flto=thin`), `-fvisibility=hidden -fvisibility-inlines-hidden`, `-ffunction-sections -fdata-sections`, C-only `.eh_frame` trim for FFmpeg.
- **Link**: `-Wl,--gc-sections,--exclude-libs=ALL,--no-undefined`, `-Bsymbolic`, Android `--pack-dyn-relocs=android`, version script exporting only the public API, static `libc++_static.a`/`libc++abi.a`/`libunwind.a`, `llvm-strip --strip-unneeded`.
- **Verification**: symbol-count/export checks, config audit asserting requested codecs/filters/parsers compiled, license manifest.

No source or prebuilt mpv artifact is copied into Sistrum; only build flags/measurements are used. If any script text is adapted, its notice is added.

Application to Sistrum (T25): adopt the measurable parts while preserving FFmpeg+AAudio and every supported format/feature: derive the exact codec/demuxer/parser/filter/protocol set from the format matrix + analysis graph; enable only those; evaluate static vs shared FFmpeg; ThinLTO for FFmpeg/TagLib/aubio/FFTW; `-Oz` where safe; hidden visibility + section GC; linker dead-strip/version script/strip; static libc++ if viable; APK minify/shrink/uncompressed native libs. Measure every `.so` and APK before/after, plus startup/scan/analysis/playback regressions. Do not copy Looper's network/TLS/SMB protocols; local-only file access is the target.

## 7. Remaining audit notes

- `packages/*` (60 files) are desktop TypeScript UI/util packages; no portable business logic. Not ported.
- `scripts/*` are desktop/build/release helpers; the FFmpeg/aubio/FFTW build scripts provided the exact analysis filter list and aubio 0.4.9 pins above. Desktop packaging/publish scripts are not portable.
- `catalog/*` (18 docs) were used to cross-check UI/player/analysis/database behavior; source wins over docs where they disagree (see target audit §8).
- `CHANGELOG.md` v1.4.0 contains UI/tray/desktop fixes; no unimplemented feature beyond the audited scope. Mobile changelog is `mobile-v1.3.0`.
- `build/` and release scripts are desktop packaging and are not ported.

## 8. Limitations

- No Android device/emulator attached; no instrumentation run at planning time.
- Full CGC index exceeds the adapter timeout; reindex run directly and reused for the snapshot (3,483 symbols, 5,662 relationships, 5 unindexed).
- Desktop golden fixtures still need to be produced by running upstream tooling outside the target tree.
- Bleve ranking cannot be identical; documented as semantic equivalence.
- TagLib static linking/licensing/build must be verified in Task A.
