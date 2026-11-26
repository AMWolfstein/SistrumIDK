# Feature Specification — Standalone Android Library Upgrade

**Branch** `alpha2`
**Status** Planned
**Mission** M001

## User scenarios and testing

### US1 — Keep the local library up to date

Choose folders and scan rules, run a scan manually or on a supported schedule, and see progress and failures. Change artist, composer or genre separators and apply them to the library. During a scan, use the artwork of each artist’s latest song in the local library as the automatic artist picture. Turning on Deezer in Settings → Integrations lets the scan refresh that picture from the artist’s Deezer catalog profile. A cancelled scan or unreadable folder leaves existing library entries and user data intact.

### US2 — Analyze music on the phone

Enable analysis and let the phone compute track loudness and the inputs needed for Mood Radio. See progress and retry failures. Analyzed music can use Volume Normalization and Mood Radio without data from Airmedy Desktop. Tracks that have no valid analysis retain ordinary playback and never receive guessed values.

### US3 — Manage and move library content

Create Smart Playlists, import and export regular playlists as M3U/M3U8, edit supported file tags and embedded artwork, and save lyrics as a UTF-8 `.lrc` file through Android storage permissions. Denied access or failed writes leave the source and app library consistent.

### US4 — Personalize the library and player

Reorder or hide library sections, choose a preset or custom accent, and open Song Transition (including crossfade), Volume Normalization and Equalizer from the full-screen player menu. Existing Living Artwork and cover transitions continue to work with local artwork.

## Edge cases

- Missing SD card, revoked permission, empty scan, file moves, and cancellation before committing the scan.
- Changed separators with older tracks that lack original tags, and files unchanged when the extractor gains a new field.
- Ambiguous artist artwork, no connectivity or no reliable Deezer match, silence, corrupt audio, edits during analysis, and the last playable item in a queue.
- M3U paths that refer to unavailable tracks, unsupported tag formats, and read-only sources.
- RTL, invalid accent input, and the default theme after an app upgrade.

## Requirements

- **FR-001** Preserve current favorites, playlists, play history, artwork overrides and track references through migration and rescans.
- **FR-002** Reuse cached metadata for unchanged files. Persist original tag fields and an extractor version. Support re-splitting, rebuilding search and refreshing tags or artwork as distinct actions.
- **FR-003** Support folder inclusion/exclusion, scan on launch and best-effort scheduled scans, progress, cancellation, and diagnostic counts for skipped and failed files.
- **FR-003a** Use the latest local song with usable artwork as the default automatic artist picture and keep folder artwork as a local fallback. Add a persistent Deezer toggle in Settings → Integrations, off by default. When enabled, the scan fetches or refreshes the artist’s current catalog profile picture. User-selected pictures retain priority. Failed or ambiguous lookups leave a usable cached or local picture visible.
- **FR-004** Analyze locally with the current FFmpeg stack and bounded background work. Expose progress, individual failures and retries. Invalidate stale results after a source or analysis-version change.
- **FR-005** Feed validated loudness and mood data into the existing playback features. Preserve fallback and crossfade behavior.
- **FR-006** Support Smart Playlists, portable M3U/M3U8, supported tag/artwork editing and save-as-LRC.
- **FR-007** Persist section order/visibility, accent and playback quick settings. Preserve local artwork animation.

## Key entities

Source folder, track identity, original tag snapshot, analysis result and status, smart playlist rule, user artwork override, and library presentation preferences.

## Success criteria

- A second scan of unchanged content reuses its cached metadata. Changing separators on current rows leaves the source files untouched and search reflects the new splits.
- An existing library keeps its user data through upgrade, cancel, and temporary loss of source access. Unambiguous moved files keep their library IDs.
- With Deezer off, a scan sends no Deezer requests and shows any cached Deezer picture, otherwise the latest local song’s artwork when available. Enabling Deezer allows the next scan to update a matched artist’s picture, including an existing cached picture. The cached picture remains visible offline and after Deezer is turned off or the app restarts. User-selected pictures retain priority.
- A locally analyzed test library can use track normalization and start and refill Mood Radio without desktop data.
- A UTF-8 playlist round trip preserves resolved entry order and reports unresolved entries. Supported file edits survive a rescan.
- Library and appearance preferences survive a restart. Local artwork animation, existing navigation and RTL pass regression checks.

## Assumptions

Android and the current FFmpeg playback engine remain the basis. Engine migration and audio conversion are outside this mission. Android can defer background scans and analysis.
