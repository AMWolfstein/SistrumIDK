# Data model: local-first library

**Mission**: M001 · baseline `cfa44a5`
**Storage**: Room/SQLite `library.db` (new) + DataStore for settings; legacy `library-sync.db` retained read-only during migration.

## 1. Design rules

1. The phone is authoritative. No table may require a desktop producer; nullable "source" columns are audit/import metadata only.
2. One physical document has one canonical `document_key`; overlapping SAF roots add memberships, not duplicate tracks.
3. Raw tag values are immutable inputs; derived artists/albums/genres are rebuildable.
4. User-owned state (favorites, playlists, listening history, artwork overrides, lyrics selection) is never deleted by scans; missing files mark items unavailable.
5. Every destructive operation is transactional and backed by a journal/backup until verified.
6. Analysis and lyrics caches are versioned; stale versions are ignored, not silently reused.

## 2. Core library

### `library_sources`

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | stable hash of tree URI |
| `tree_uri` | TEXT UNIQUE NOT NULL | persisted SAF grant |
| `display_name` | TEXT | label captured at add time |
| `volume_id` | TEXT? | `primary`, UUID, … |
| `is_removable` | INTEGER | 0/1 |
| `enabled` | INTEGER | user switch |
| `available` | INTEGER | current access state, never deletion |
| `last_scanned_at` | INTEGER? | epoch ms |
| `last_seen_at` | INTEGER? | successful enumeration |
| `last_scan_error` | TEXT? | last failure |
| `created_at` | INTEGER | |

### `library_items` (tracks)

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | `lib_` + SHA-1(document_key) |
| `document_key` | TEXT UNIQUE NOT NULL | `saf-document:<authority>:<documentId>` or `cue:<...>#trackNN` |
| `file_uri` | TEXT NOT NULL | canonical content URI for reading |
| `source_id` | TEXT FK → library_sources | primary membership |
| `display_path` | TEXT? | human-readable relative path; never identity |
| `cue_key` | TEXT? | cue document key for virtual tracks |
| `track_number_in_cue` | INTEGER? | |
| `cue_start_ms` | INTEGER? | virtual track start offset |
| `cue_end_ms` | INTEGER? | virtual track end offset |
| `file_mod_time` | INTEGER | provider mtime; 0 = unknown |
| `file_size` | INTEGER? | when provider supplies |
| `metadata_version` | INTEGER | forces re-read on bump |
| `album_id` | TEXT? FK → albums | resolved album (one track → one album) |
| `title`, `sort_title`, `album_title`, `album_sort_title` | TEXT | |
| `raw_artist_names`, `raw_album_artist_names`, `raw_genre_names`, `raw_composer_names` | TEXT | exact joined raw values |
| `copyright`, `label`, `isrc` | TEXT? | |
| `year`, `track_number`, `disc_number`, `total_tracks`, `total_discs` | INTEGER? | |
| `duration_seconds` | INTEGER | |
| `bitrate`, `sample_rate`, `bit_depth` | INTEGER? | |
| `codec`, `format` | TEXT? | |
| `is_favorite` | INTEGER | user-owned |
| `rating` | INTEGER? | user-owned 0–5 stars (Sistrum extension; NULL = unrated) |
| `play_count` | INTEGER | qualified plays |
| `artwork_key` | TEXT? | content hash |
| `audio_fingerprint` | TEXT? | decoded-PCM SHA-256 + version (analysis/identity) |
| `content_key` | TEXT? | move/rename identity fallback (size+duration+fingerprint) |
| `release_date` | TEXT? | tag date used by artist artwork precedence |
| `added_at` | INTEGER | first import |
| `updated_at` | INTEGER | |
| `available` | INTEGER | file currently accessible |
| `missing_since` | INTEGER? | last failed enumeration |
| `other_metadata_json` | TEXT | raw tag map |
| `analysis_status` | TEXT | NONE/PENDING/PARTIAL/COMPLETE/FAILED |

Indexes: `document_key` unique, `(source_id, available)`, `(album_id)`, `(sort_title)`, `(is_favorite)`, `(added_at)`, `(release_date)`.

### `item_sources` (overlap memberships)

`item_id`, `source_id`, `file_uri`, PK(`item_id`,`source_id`). A document reached through two roots has one item and two memberships; effective `file_uri` is the preferred available membership.

### `artists`, `albums`, `genres`, `composers`

| Table | Key columns |
|---|---|
| `artists` | `id` PK, `name`, `sort_name`, `normalization_key` UNIQUE, `artwork_key`, `artwork_source` (`manual/online/local/fallback`), `artwork_updated_at` |
| `albums` | `id` PK, `title`, `sort_title`, `normalization_key` UNIQUE, `year`, `copyright`, `artwork_key`, `artwork_source`, `primary_album_artist_id` |
| `genres` | `id`, `name`, `normalization_key` UNIQUE |
| `composers` | `id`, `name`, `sort_name`, `normalization_key` UNIQUE |

### Junction tables

`track_artists(track_id, artist_id, position)`; `track_album_artists`; `track_genres`; `track_composers`; `album_artists(album_id, artist_id, position)`. PK on (`track/album`,`artist`); positions preserve tag/raw order. No duplicate rows.

### `artwork_assets`

| Column | Notes |
|---|---|
| `key` TEXT PK | SHA-256(content) + decoded ext |
| `kind` | embedded/imported/folder/online/generated |
| `width`, `height`, `mime`, `size` | validation |
| `path` | app-owned file path; variants derived `_sm`/`_md` |
| `origin_uri`/`source_url` | provenance |
| `created_at`, `last_used_at` | cleanup |
| `active` | 1 when referenced; cleanup never deletes active |

## 3. Playlists

### `playlists`

`id` TEXT PK, `name`, `description`, `is_smart`, `rules_json`, `live_updating`, `limit_enabled`, `limit_count`, `limit_sort`, `pinned_at`, `artwork_key`, `created_at`, `updated_at`, `system_kind` (`favorites` or null).

Favorites are virtual: one system row `system_kind='favorites'`; membership derived from `library_items.is_favorite`, no `playlist_tracks` rows.

### `playlist_tracks`

`playlist_id`, `track_id`, `position` TEXT (LexoRank), PK(`playlist_id`,`track_id`), index `(playlist_id, position, track_id)`. No duplicate track per playlist. Rebalance when rank length > 10.

### `smart_playlist_rules`

Rules are stored as JSON in `playlists.rules_json` (exact upstream shape); validation happens in the rule engine, not schema.

### `playlist_artwork_staging`

`sha256` PK, `path`, `mime`, `size`, `created_at`, `committed_at?`. Local only; no desktop upload.

## 4. Lyrics

### `lyrics`

| Column | Notes |
|---|---|
| `track_id` PK FK | |
| `content` | selected/provider cache |
| `source` | `local-lrc`, `local-txt`, `embedded`, `lrclib-synced/plain`, `kugou-synced/plain`, `manual` |
| `synced` | 0/1 |
| `offset_ms` | parsed when supported; default 0 |
| `fingerprint` | SHA-256(content) |
| `updated_at` | |
| `embedded_content` | last read from tags, separate from selected cache |
| `embedded_source` | `meta-synced`/`meta-plain` |

Local sidecar files are read live; this table caches provider/embedded selections and manual saves.

## 5. Analysis

### `track_analysis`

| Column | Notes |
|---|---|
| `track_id` PK FK | |
| `loudness_lufs`, `loudness_range`, `true_peak`, `rms`, `crest` | REAL |
| `spectral_centroid`, `spectral_rolloff`, `spectral_flatness`, `spectral_flux`, `zcr` | REAL |
| `tempo`, `onset_variance` | REAL, sample-domain semantics |
| `energy`, `danceability`, `brightness` | REAL [0,1] |
| `ffmpeg_version`, `aubio_version`, `analyzer_version`, `mood_version` | INT |
| `fingerprint` | audio-content fingerprint (not whole-file/tag hash) |
| `source` | local/tag/import |
| `status` | COMPLETE/PARTIAL/FAILED |
| `analyzed_at` | |

`album_analysis` is derived at read time (mean LUFS for normalization); no stale persisted aggregate. If a cached aggregate is added for performance, membership changes must invalidate it transactionally.

### `tag_write_journal`

`id`, `track_id`, `operation`, `staged_path`, `original_fingerprint`, `state` (STAGED/VERIFIED/REPLACED/FAILED), `error`, timestamps. Used to recover interrupted writes.

## 6. Listening

Reuse upstream semantics with local identity:

- `listening_sessions(id PK, track_id, source_device_id, started_at, ended_at, listened_seconds, local_date, play_counted)`
- `playback_attempts(id PK, track_id, started_at, ended_at, seconds, end_reason, local_date)`
- `daily_track_listening_stats(source_device_id, local_date, track_id, seconds, plays)` PK composite
- `daily_playback_attempt_stats(source_device_id, local_date, attempts, completed, skipped, stopped, seconds)` PK composite

`source_device_id` is a stable local install ID (no desktop pairing identity). No paired-device rows.

## 7. Scan state

- `scan_runs(id PK, source_id, tree_uri, mode FULL/INCREMENTAL, generation, state, started_at, finished_at, total_files, scanned_files, error_count, cancelled)`
- `scan_entries(run_id, document_key, file_uri, mod_time, size, state SEEN/EMITTED/FAILED, error)` PK(`run_id`,`document_key`)
- `scan_errors(run_id, document_key?, operation, message, occurred_at)` (cap retention per run)

Publication in one `withTransaction`: stage rows, delete `removed` keys only when traversal complete and no fatal error, upsert items, mark source scanned. A run is resumable by selecting unemitted `scan_entries` for the same `(source, generation)`.

## 8. Settings split

- **DataStore** continues for UI/appearance/playback/lyrics/analysis preferences (fast, small).
- **Room `app_settings`** only for library/scan settings shared with workers (auto-scan policy, exclusions, separator lists, analysis write consent), or a single settings repository backed by DataStore with worker snapshots. Prefer one owner: DataStore for all user settings, Room only for scan-run state.

## 9. Migration from the mirror DB

1. Ship `library.db` at version 1 with exported schema (`room.schemaLocation`) and no destructive fallback.
2. On first launch, if `library-sync.db` exists and has user-owned rows, run a one-time importer in a transaction:
   - import local playlists (name, track IDs, positions, artwork) and favorites; record unresolved legacy track IDs in `legacy_track_refs` rather than dropping them;
   - import listening sessions/attempts/daily aggregates with `source_device_id='legacy-local'` normalized to the new local install ID, preserving timestamps and play counts with idempotent IDs;
   - import cached provider lyrics keyed by legacy track ID where mapping exists;
   - never import mirror plans/assets/tracks as library rows.
3. After the first successful local scan, attempt to re-link legacy references by metadata fingerprint (title/artist/album/duration/file stem) and keep the journal for audit. Unresolved refs remain visible in a migration report, not silently deleted.
4. Keep `library-sync.db` on disk (not deleted) until the user confirms migration; a later cleanup task may remove it only with explicit consent.
5. Tests: real Room migration tests from empty and from a populated legacy fixture; interrupted import resumes; re-running is idempotent.


### `library_exclusions`

`id` TEXT PK, `source_id` TEXT FK, `relative_path` TEXT, `kind` TEXT (`folder`/`file`/`pattern`), `created_at`. Exclusions are user-owned and applied before metadata reads; they never delete existing rows automatically (a rescan marks them excluded/ignored with an explicit report).

### `legacy_track_refs`

`id` TEXT PK, `legacy_track_id` TEXT UNIQUE, `legacy_path` TEXT?, `title`, `artist`, `album`, `duration_seconds`, `playlist_refs_json`, `resolved_item_id` TEXT?, `resolution` TEXT (`unresolved`/`metadata`/`fingerprint`), `resolved_at` INTEGER?. Preserves unresolved legacy playlist/favorite references; never silently dropped.

### Fingerprints and move/rename identity

- `audio_fingerprint = "pcm-sha256-v1:" + SHA-256(decoded PCM)` where PCM is demuxed/decoded audio only (no tag/container bytes), converted to a canonical stream (float32 LE, 44.1 kHz, mono) and hashed incrementally. Version is stored with the hash.
- `content_key` (move/rename fallback) = SHA-256 of `{file_size, duration_seconds, audio_fingerprint}` when fingerprint exists; otherwise `{file_size, duration_seconds, title, artist, album}` with a `metadata-` prefix.
- On a new `document_key` with no matching item, match `content_key` globally (including internal ↔ SD moves) when the match is unique; only scope to the same source/volume when the content key is non-unique. If unique, re-key the item and preserve favorites/ratings/playlist memberships/artwork/lyrics/analysis instead of delete+insert. Ambiguous matches stay separate and are reported.

## 10. Invariants

- A track has exactly one canonical `document_key`; `item_sources` may have many memberships. A move/rename may re-key the item only through a verified `content_key` match.
- No scan deletes rows from an unavailable/revoked source.
- Deleting a track removes/updates playlist membership transactionally; active queue is repaired, never pointing at a missing row.
- `is_favorite`, playlists, ratings, lyrics selection, listening rows and artwork overrides are user-owned; scans never overwrite them.
- Raw tags are stored verbatim; derived entities are rebuildable and indexed.
- Analysis/lyrics reuse requires matching version + audio fingerprint; mismatches are `PENDING`, not silently trusted.
- All write paths are idempotent by primary key/unique key and safe to replay.


## 11. Legacy sync field mapping

The desktop manifest/mirror is removed, but each field has a local owner so migration and UI behavior can be traced:

| Legacy field/source | Local owner |
|---|---|
| `sync_tracks` metadata, IDs, play counts | `library_items` after content re-import; legacy IDs journaled for playlist/favorite relinking |
| `sync_assets.relativePath` audio/artwork | `library_items.file_uri`/`artwork_assets` after SAF scan; unresolved references kept in migration report |
| `sync_playlists`, `local_playlists`, `playlist_mutations` | `playlists` + `playlist_tracks`; ordering mapped from rank/JSON order |
| favorites pending mutations | `library_items.is_favorite` |
| `sync_documents(kind=lyric/analysis)` | `lyrics`/`track_analysis` only after version/fingerprint validation; desktop analysis without identifiable audio is not reused |
| `provider_lyrics` | `lyrics` cache with source/fingerprint |
| `listening_sessions`, `playback_attempts`, daily stats | same-shaped local tables with local install identity |
| `sync_plans`, receipts, reconciliation, acks | dropped; migration journal records what was ignored |

No field is silently lost: unresolved legacy references are retained in a migration journal/report until the first successful local scan can relink them.
