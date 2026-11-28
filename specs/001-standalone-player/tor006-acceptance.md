# M001 cumulative acceptance review (T001–T019, TOR004–TOR006)

Scope: the original alpha10 mission, `spec.md`, `plan.md`, `tasks.md`, `parity-matrix.md`,
`source-map.md`, `test-matrix.md` and the owner's product decisions. Nothing here changes a
requirement to fit the implementation; deviations are only listed where the owner approved them.

Dispositions: **VERIFIED** (production path + reproducible check + recorded evidence),
**PARTIAL** (works for the main path, a mandatory sub-requirement lacks evidence),
**MISSING**, **INCORRECT**, **UNVERIFIED** (no accepted evidence yet), **BLOCKED** (owner/device),
**OWNER_APPROVED_DEVIATION**.

## Task table

| Task | Disposition | Evidence / remaining gap |
|---|---|---|
| T001 Baseline and native input | PARTIAL | `baseline.md` records toolchain, FFmpeg 9.0.2 source/flags, ABIs and debug APK. Release signing is BL-09 (owner). Descriptor seek/lifetime covered by `SafNativeIoTest`. |
| T002 Room and legacy migration | PARTIAL | `LegacyMirrorImporter` runs at startup, `LegacyRelinker` matches unresolved refs; `LibraryMigrationTest` covers 1→5. A populated legacy-copy end-to-end run (playlists/order/favorites/artwork/lyrics/history, interruption/restart) is not recorded. |
| T003 Sources and permissions | PARTIAL | Enable/disable/remove, overlapping roots and revocation hiding are implemented (membership-based visibility). Read/write grant, SD unmount and permission-loss device runs are not recorded (BL-23/24). |
| T004 Scanning | PARTIAL | Auto-scan, exclusions UI, bounded retries, `EXTRA_LOADING`, cancel-preserves-rows, CUE-change detection, FGS-timeout pause, scoped MediaStore fallback exist. A completed 777-file UI scan was observed, but process-death/FGS recovery and MediaStore fallback lack recorded device evidence. |
| T005 Metadata mapping | PARTIAL | SAF descriptor reads fixed (previously every row had `{}`); codec/bit depth, raw pre-split values, totals, sort fields stored. Full alias/sort/totals matrix on the supplied library is not re-recorded after the fix. |
| T006 Reconciliation/identity | PARTIAL | Decoded-PCM `pcm-sha256-v1` fingerprint, content-key identity, transactional publication and separator-consistent reindexing implemented. Rename/move across roots/volumes and ambiguous-match cases are covered by publishers tests but not re-run on device in this pass. |
| T007 Safe editing | PARTIAL | Single and batch editor and artwork replace reachable; all writes (tags, artwork, lyrics, analysis) go through the journaled `TagWriter` with PCM verification and recovery. Device interrupted-write and read-only-provider runs are not recorded. |
| T008 Artwork | PARTIAL | Cover update/removal paths and size variants exist; WebP cleanup and artist-art priority/offline/deezer-off device runs are not recorded. |
| T009 Separators/album regrouping | PARTIAL | Rebuild no longer forks/deletes albums (BL-27 fix), adopts stable identity by normalization key, preserves artwork/copyright, rebuilds FTS transactionally; `RelationshipRebuilderTest` 3/3 PASS. A “fresh scan vs rebuild produce the same grouping” device check is still to be recorded. |
| T010 Search/local screens | PARTIAL | Transactional FTS4 index (tracks/albums/artists/genres/composers/playlists) with triggers; local Home/search/detail routes. `LargeLibrarySearchTest` records 10k-track latency (see its report). Unicode/CJK/prefix/phrase/ranking parity against the desktop contract is not fully re-verified. |
| T011 Local playback/restore | PARTIAL | SAF playback, gapless/crossfade natives and queue restore paths exist; `PlaybackOutputTest` renders 8 containers on device. Queue restoration after real process death is not recorded. |
| T012 Device playback evidence | PARTIAL | Automated: 8 containers via AAudio, position advance, seek < 700 ms, 0 underruns, crossfade. Audible output, focus/ducking, route changes, Bluetooth controls and process death are listed in `manual-verification-tor006.md` and remain UNVERIFIED. |
| T013 Playlists/favorites | PARTIAL | Local favorites/playlists, ordering, pin, smart guard and M3U8 round-trip implemented and unit-tested; user-facing round-trip and legacy-membership checks are not re-recorded in this pass. |
| T014 Smart playlists | PARTIAL | Editing UI loads/saves existing rules; mood/tempo rules now read local analysis values and `duration` is in seconds (`SmartRulesMoodTest`); 16-leaf cap and live/frozen behaviour unit-tested. A device run of the editor with mood rules is pending. |
| T015 Lyrics persistence | PARTIAL | Save as LRC (sidecar, staged rename) and Embed via the journaled writer; Unicode/offset round-trip and clean-install offline retrieval tests exist. A device run after reinstall is not recorded. |
| T016 Native analysis parity | VERIFIED | Upstream `d859940c` graph (ebur128/aspectralstats/astats + aubio hfc) on the descriptor path; `AnalysisParityTest` matches the producer on 17 fixtures, the CUE segment and fixture checksums; album mean from the producer values. |
| T017 Persistence/scheduling | PARTIAL | Versioned 20-tag set, fingerprint validation, `target − loudness` ReplayGain, Opus R128, preserve-by-default, journaled writes, job lifecycle with retries/cancel/recovery (`AnalysisLifecycleTest`) and clean-install restoration (`CleanInstallAnalysisRecoveryTest`) verified. Read-only/revoked-source device runs and the manual “analyze during playback” check remain. |
| T018 Normalization | PARTIAL | Album loudness = producer arithmetic mean, continuity gate, track true peak clipping, −14 LUFS default, setting preserved when analysis is missing (`AlbumAggregateTest`, `VolumeNormalizationTest`); EQ/crossfade interaction on a real output is in the manual session. |
| T019 Mood Radio | PARTIAL | Selection matches upstream constants (80/15/3, 1/√rank, album then artist relaxation), local readiness gate now comes from Room analyses instead of the legacy desktop flag, smart mood rules fixed. Offline refill and event-level device evidence are pending. |

## TOR table

| TOR | Disposition | Notes |
|---|---|---|
| TOR004 | PARTIAL | SAF ingest, identity, metadata repair, legacy import/relink and scan controls implemented; device permission-loss/SD/process-death runs and the release signature are open. |
| TOR005 | PARTIAL | Editing, artwork, regrouping, FTS, playback, playlists, smart rules and lyrics implemented with the defects above; the remaining device evidence is itemised in the T-table. |
| TOR006 | PARTIAL | Analysis parity, album parity, scheduling/persistence, clean-install recovery and automated playback output are verified; the manual listening/route session and the remaining T012/T017/T019 device checks are not. |

## Remaining mandatory evidence (not deferrals)

1. Consolidated manual playback session (`manual-verification-tor006.md`) for audible output, focus,
   routing, Bluetooth, notification controls, process death and analysis-during-playback.
2. Device runs for permission loss/SD unmount, interrupted tag writes, read-only providers, and a
   fresh-scan-vs-rebuild grouping comparison.
3. Owner decision on removing the currently playing queue row (BL-28 item) and on the lyrics glow
   default; both are product behaviours, not test bugs.
