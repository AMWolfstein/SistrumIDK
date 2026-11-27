# Contract: SAF scanner

## Inputs

- `sourceId` + persisted tree URI + read grant.
- Optional `forceFull` flag.
- Existing `scan_runs`/`scan_entries` and library snapshot.

## State machine

`IDLE → PREFLIGHT → TRAVERSING → READING → PUBLISHING → COMPLETE`
with terminal `CANCELLED`, `FAILED`, `PAUSED` (paused holds position; not terminal).

- Preflight: validate tree URI, read grant, root listing with `EXTRA_LOADING` retries (300/600 ms, max 3).
- Traversing: BFS queue, URI visited set; any listing error marks the run failed and prevents publication.
- Reading: bounded workers (≤6), ordered consumption; CUE first, then audio; per-file failures recorded.
- Publishing: one Room transaction; re-check source enabled/URI; delete removed rows only when traversal completed with no fatal error.
- Progress: `StateFlow<ScanProgress>` (`state`, `total`, `scanned`, `current`, `errors`, `failures`).

## Identity

- `documentKey = saf-document:<authority>:<documentId>` (lowercased authority, preserved document id).
- CUE virtual: `documentKey = <cueKey>#trackNN`, `fileUri = <cueUri>`, with `cue_start_ms`/`cue_end_ms` persisted for seeking/queue identity.
- `library_items.document_key` unique; extra roots add `item_sources` memberships, never duplicate items.

## Change detection

- Audio changed if `modTime == 0/null || modTime != stored || (size known && size != stored) || metadataVersion < current`.
- CUE changed if cue modTime/size changed **or** backing audio changed.
- Removed = stored document keys for source − keys seen in a complete traversal.
- `mtime == 0` is unknown; force re-read or use size/version.

## Availability/deletion

- Missing read grant, provider exception, unmounted volume → source `available=0`; rows stay.
- Inconclusive probe → retain previous state and retry (2 s ×3).
- Confirmed empty root → clear only with complete traversal and zero fatal errors.
- Never delete on partial traversal, cancellation, or process death.

## Checkpoint/resume

- `scan_runs` row + `scan_entries` (`SEEN/EMITTED/FAILED`).
- Entry inserts/updates are transactional with item staging where possible.
- Restart selects unemitted entries for the same `(source, generation)`; replay is idempotent.
- Cancel preserves emitted rows; resume continues; a forced full scan starts a new generation.

## Identity continuity

When a document key disappears and a new key appears, match by `content_key`/audio fingerprint/metadata; if unique, re-key the item and preserve favorites, ratings, playlists, artwork, lyrics and analysis. Ambiguous matches remain separate and are reported.

## Exclusions

User exclusions (`library_exclusions`: folder/file/pattern) are applied after discovery and before metadata reads; excluded items are marked ignored, not silently deleted. Built-in rules:

Extensions: `.flac .m4a .mp4 .aac .mp3 .opus .ogg .ape .wv .mpc .wav .aiff .aif .dsf .dff`.
Names ending `.partial`, `.partial.<ext>`, `.replaced` are ignored.
MediaStore is a scoped fallback for `media*` providers only.
