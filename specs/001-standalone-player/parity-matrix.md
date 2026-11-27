# Parity matrix

**Mission**: M001 · upstream `d859940c` · target `cfa44a5`
Status: `MIRROR` = exists only as desktop sync data; `ABSENT`; `PARTIAL`; `PRESENT`; `EXCLUDED`; `DECISION`.
Task numbers refer to [`tasks.md`](tasks.md).

## A. Library, scanner, sources

| ID | Feature | Upstream anchor | Target today | Decision / task | Status |
|---|---|---|---|---|---|
| A01 | Watched-folder add, duplicate source rejection | `internal/app/library/service.go:427-475` | None (desktop sync) | SAF roots; reject duplicate URI; overlapping trees allowed + documentKey dedup; T03 | ABSENT |
| A02 | Remove folder, keep/delete tracks choice | `service.go:477-555` | None | Source disable/remove semantics; T03 | ABSENT |
| A03 | Periodic rescan intervals 15s/15m/30m/1h/launch/manual | `service.go:328-382`; `models.go:339-367` | None | Local auto-scan policy; T03/T04 | ABSENT |
| A04 | Folder sync/reconciliation, mtime/size skip, schema-version reparse | `service.go:597-880,1321-1326` | Mirror pull only | Port to SAF scanner; T04/T05 | MIRROR |
| A05 | Supported extensions list | `service.go:47-60` | None | Curated allowlist (upstream + Mornye `.mp4/.aac/.mpc`); T04 | ABSENT |
| A06 | Deterministic IDs/entity keys | `service.go:1016-1181,1435-1437` | Mirror IDs | `document_key` adaptation; T05 | MIRROR |
| A07 | Album-artist fallback + album inheritance | `service.go:1061-1103` | Mirror projection | Port; T05 | MIRROR |
| A08 | Metadata write-back/EnsureTrack | `service.go:1472-1569` | None | TagLib; T07 | ABSENT |
| A09 | Delimiter re-split without reread | `service.go:1238-1435` | None | Port; T09 | ABSENT |
| A10 | Favorites toggle | `service.go:1530-1543` | Mirror + pending ACK | Local flag; T13 | MIRROR |
| A11 | Search reindex/self-heal | `service.go:278-292,1597-1679` | FTS from manifest | Local FTS; T10 | MIRROR |
| A12 | Orphan cleanup | `service.go:390-425,557-590` | None | Transactional cleanup; T05 | ABSENT |
| A13 | Show in file explorer | `service.go:1505-1527` | None | Android share/open-with; T22 | ABSENT |
| A14 | Recently added | `track_repository.go:628-646` | Partial Home | Port; T10 | PARTIAL |
| A15 | SAF multi-root, persistable grants, SD volumes | Mornye `MainActivitySafTreePicker.kt:18-98` | None | Port; T03 | ABSENT |
| A16 | Recursive traversal, BFS, URI dedup, provider retries | Mornye `MainActivitySafScan.kt:191-252`; `SafTreeListing.kt` | None | Port; T04 | ABSENT |
| A17 | Durable checkpoints/resume after process death | Mornye `SafScanCheckpoint.kt`; `scan.dart` | None | Room `scan_entries`; T04 | ABSENT |
| A18 | Pause/resume/cancel/progress | Mornye scan state | None | Port; T04 | ABSENT |
| A19 | CUE expansion + backing audio dedup | Mornye `SafCueScan.kt`; Rust `scan_cue` | None | Port; T04 | ABSENT |
| A20 | Folder artwork priority | Mornye `SafFolderCovers.kt` | None | Port; T08 | ABSENT |
| A21 | Availability tri-state; hide not delete | Mornye `SafTreeReadAccessPolicy.kt` | None | Port; T03/T04 | ABSENT |
| A22 | Overlapping roots duplicate fix | Mornye gap | None | `document_key` unique + memberships; T05 | ABSENT |
| A23 | MediaStore scoped fallback | Mornye `MainActivitySafIo.kt:121-175` | None | Only selected-source fallback; T04 | ABSENT |
| A24 | Background/auto scan under OS limits | Mornye WorkManager guidance | None | Foreground service + WorkManager; T04 | ABSENT |

## B. Metadata, tags, separators

| ID | Feature | Upstream anchor | Target | Decision / task | Status |
|---|---|---|---|---|---|
| B01 | Extract title/sort/album/sort | `metadata/taglib.go:29-116` | Mirror metadata | TagLib; T05 | MIRROR |
| B02 | Artists/album artists/genres/composers multi-value | same | Mirror | Port; T05 | MIRROR |
| B03 | Copyright/label/ISRC/year/track/disc/totals | same | Mirror | Port; T05 | MIRROR |
| B04 | Duration/bitrate/sample rate/bit depth/codec/format | same | Mirror | Port; T05 | MIRROR |
| B05 | Embedded artwork read | same | Mirror/synced asset | Port; T05/T08 | MIRROR |
| B06 | Lyrics tag + synced detection | `taglib.go:146-153`; `service.go:950-958` | Mirror | Port; T15 | MIRROR |
| B07 | Raw tag map `other_metadata` | `taglib.go:48-59` | Mirror raw JSON | Port; T05 | MIRROR |
| B08 | NormalizeSort (articles, fold, digit padding) | `domain/metadata_processing.go:14-42` | None | Port exactly; T05 | ABSENT |
| B09 | NormalizationKey/FoldUnicode | `metadata_processing.go:44-73` | None | Port; T05 | ABSENT |
| B10 | Default delimiters `[";","\\\\",","]`, raw `"; "` | `metadata_processing.go:75-85` | None | Port; T09 | ABSENT |
| B11 | Delimiter validation ≤5 runes, unique, empty = no split | `metadata_processing.go:87-110` | None | Port; T09 | ABSENT |
| B12 | Literal split, trim, case-insensitive dedup | `metadata_processing.go:112-146` | None | Port; T09 | ABSENT |
| B13 | Separator settings add/edit/delete + rebuild | `service.go:1192-1228`; settings | None | Port UI+engine; T09 | ABSENT |
| B14 | Multi-frame tags joined `"; "` | `taglib.go:162-175` | None | Port; T05 | ABSENT |
| B15 | Single/batch metadata editor | `MetadataEditDialog.vue` | None | Compose editor; T07 | ABSENT |
| B16 | Artwork replacement + JPEG conversion | `taglib.go:197-201` | None | TagLib; T07 | ABSENT |
| B17 | Safe staged write, preserve audio/unrelated tags | `service.go:1546-1569` | None | Journal + verify; T07 | ABSENT |
| B18 | 0–5 star ratings (Sistrum extension) | none upstream (owner) | None | Add field/editor/sort/filter/smart rule; T02/T10/T14 | NEW |

## C. Artwork

| ID | Feature | Upstream anchor | Target | Decision / task | Status |
|---|---|---|---|---|---|
| C01 | Content-hash cache, decoded validation | `artwork/cache.go:32-69` | Synced asset paths | Local cache; T08 | MIRROR |
| C02 | `_sm` 64, `_md` 500, JPEG q85 | `cache.go:72-103` | None | Port; T08 | ABSENT |
| C03 | Bounded cleanup, active keys | `cache.go:120-158` | None | Port + fix WebP variant bug; T08 | ABSENT |
| C04 | Palette (3 clusters, thresholds) | `palette.go` | Present local palette | Keep Android palette; compare; T08 | PRESENT |
| C05 | Embedded album art inheritance | `service.go:929-946,1083-1087` | Mirror | Port; T08 | MIRROR |
| C06 | Playlist custom artwork | `playlist_service.go:484-528` | Local staging + upload | Local-only; T13 | PARTIAL |
| C07 | Artist artwork sources manual/local/online | `models.go:74-144`; `artist_local_image.go` | None | Port with owner precedence; T08 | ABSENT |
| C08 | Deezer search/download (first result, 10 s) | `artist_artwork.go:91-165` | None | Port; T08 | ABSENT |
| C09 | Artist precedence override: Deezer > latest release track > placeholder | owner | None | Implement (overrides upstream); T08 | DECISION |
| C10 | Online Artwork page below Lyrics, Deezer off default | owner | None | Compose page; T08 | DECISION |
| C11 | Durable storage, disable keeps cached, failures don't erase | owner | None | Implement; T08 | DECISION |
| C12 | Artist local image ambiguity skip | `artist_local_image.go:177-182` | None | Port; T08 | ABSENT |

## D. Library views and search

| ID | Feature | Upstream anchor | Target | Decision / task | Status |
|---|---|---|---|---|---|
| D01 | Home overview/sections | `HomeOverview.vue` | Present mirror data | Wire local; T10 | PARTIAL |
| D02 | Library insights | `HomeAnalysis.vue` | Present Insight | Local history; T20 | PARTIAL |
| D03 | Tracks table/sort/filter/compact/multi-select | `TracksView.vue` | Present list/filter; no multi-select | Keep + add multi-select; T22 | PARTIAL |
| D04 | Albums grid/list/sort | `AlbumsView.vue` | Present | Wire local; T10 | PARTIAL |
| D05 | Artists explorer/detail | `ArtistsView.vue` | Present | Wire local; T10 | PARTIAL |
| D06 | Genres/composers grouped albums | `GenresView`, `ComposersView` | Present | Wire local; T10 | PARTIAL |
| D07 | Recently added | `RecentlyAddedView.vue` | Partial | Port; T10 | PARTIAL |
| D08 | Search grouped tracks/albums/artists/playlists/composers | `SearchView.vue` | Present FTS4 mirror | Local FTS; T10 | PARTIAL |
| D09 | Bleve tokenization/ranking (match OR prefix, AND, phrase boost, fold) | `infra/bleve/bleve.go:55-65,334-360` | FTS4 | Equivalent semantics; ranking gap documented; T10 | PARTIAL |
| D10 | Detail pages + context actions | views | Present mirror | Wire local; T10 | PARTIAL |
| D11 | Ratings shown/sorted | none upstream | None | Owner decision; T10 | DECISION |

## E. Playback

| ID | Feature | Upstream anchor | Target | Decision / task | Status |
|---|---|---|---|---|---|
| E01 | FFmpeg/AAudio engine, JNI | `ffmpeg_player.cpp` | Present | Keep; local URI; T11 | PRESENT |
| E02 | Queue cap 1000/allowed set, trim | `queue_service.go:45-98`; models | Present shared queue | Keep; T11 | PRESENT |
| E03 | Shuffle/repeat/restore | `queue_service.go:112-557` | Present | Keep; T11 | PRESENT |
| E04 | Gapless/preload | `player_service.go`; Android native | Present | Keep; verify local; T12 | PRESENT |
| E05 | Equal-power crossfade 0–12, effFade/remaining | `player_service.go:1982-2035`; native | Present | Keep; verify; T12 | PRESENT |
| E06 | EQ 10 bands Q1 ±12/0.5, preamp, width | `eq_service.go`; native | Present | Keep; verify; T12 | PRESENT |
| E07 | Normalization track/album −14, clip guard | `normalization/service.go:140-153` | Present mirror analysis | Local analysis; T18 | MIRROR |
| E08 | Audio focus/route/media session/notification | Android + OS | Present | Keep; T11 | PRESENT |
| E09 | Seek/prev/ff/rew semantics | `player_service.go:436-520` | Present | Verify; T12 | PRESENT |
| E10 | Missing current track clears queue | Mirror behavior | Present | Local equivalent; T11 | MIRROR |
| E11 | Sleep prevention / keep-screen-on | desktop/Android settings | Partial | Mobile equivalent; T22 | PARTIAL |
| E12 | Quick settings (long-press) | `PlayerQuickSettingsMenu.vue` | Context menu partial | Add sheet; T22 | PARTIAL |
| E13 | Mini-player PiP/floating | desktop window | In-app mini | Owner decision; T22 | DECISION |

## F. Playlists, favorites, smart rules

| ID | Feature | Upstream anchor | Target | Decision / task | Status |
|---|---|---|---|---|---|
| F01 | Manual playlist CRUD | `playlist_service.go:40-109` | Local + desktop ACK | Local-only; T13 | MIRROR |
| F02 | Favorites virtual playlist | `playlist_service.go:60-79` | Mirror + ACK | Local; T13 | MIRROR |
| F03 | Add/remove tracks; smart guard | `:334-392` | Partial | Port; T13 | PARTIAL |
| F04 | LexoRank ordering/rebalance >10 | `:334-482` | JSON order | Port; T13 | ABSENT |
| F05 | Pinned playlists | `:534-536` | None | Port; T13 | ABSENT |
| F06 | Playlist preview/mosaic | `:137-161` | Partial | Port; T13 | PARTIAL |
| F07 | Smart create/update, ≥1 ≤16 rules | `:167-258` | None | Port; T14 | ABSENT |
| F08 | Live vs frozen membership | `:260-332` | None | Port; T14 | ABSENT |
| F09 | Rule fields/operators/combination/sort/limit | `smart_rules.go`; domain | None | Port exactly; T14 | ABSENT |
| F10 | Mood smart-playlist tab | `moodPlaylistFields.ts` | Present Mood Radio only | Port builder; T14/T19 | ABSENT |
| F11 | M3U8 export (all playlists incl. smart/favorites) | `:538-591` | None | Port; fix upstream empty-export defect; T13 | ABSENT |
| F12 | M3U8 import (UTF-8/BOM, `#EXTM3U`, directives) | `m3u8.go:29-112` | None | Port; add BOM/`#EXTENC`/relative paths; T13 | ABSENT |
| F13 | Relative paths/BOM | not implemented upstream | None | Owner chose support; T13 | NEW |

## G. Lyrics

| ID | Feature | Upstream anchor | Target | Decision / task | Status |
|---|---|---|---|---|---|
| G01 | Local `.lrc`→`.txt`→subfolder→folder | `lyrics_service.go:142-220`; `local.go` | None | Port; T15 | ABSENT |
| G02 | Embedded lyrics | `taglib.go:146-153` | Mirror docs | TagLib; T15 | MIRROR |
| G03 | Provider cache and preference | `lyrics_service.go:126-140` | Present provider cache | Port; T15 | PARTIAL |
| G04 | LRCLIB exact/search/ranking | `lrclib.go`; `ranking.go` | Present mobile port | Verify parity; T15 | PRESENT |
| G05 | Kugou attempts/search/download/ranking | `kugou.go` | Present mobile port | Verify parity; T15 | PRESENT |
| G06 | Manual search/select/save LRC | `lyrics_service.go:30-86` | Partial | Add save; T15 | PARTIAL |
| G07 | Embed lyrics via TagLib | owner | None | Implement; T15 | ABSENT |
| G08 | LRC parsing, bilingual, enhanced word timing | `useLyrics.ts` | Present Android parser | Keep; compare; T15 | PRESENT |
| G09 | `[offset:]` support | **not supported upstream** | None | Flag; owner decision if add; T15 | DECISION |
| G10 | Romanization offline Chinese/Korean | `romanization` | Present but missing data | Package upstream data; T15 | PARTIAL |
| G11 | Glow/scroll/seek animations | Android/mobile | Present | Keep; T15 | PRESENT |

## H. Analysis, normalization, Mood Radio

| ID | Feature | Upstream anchor | Target | Decision / task | Status |
|---|---|---|---|---|---|
| H01 | FFmpeg filter graph (ebur128/aspectralstats/astats) | `ffmpeg_analyzer.h:304` | None local; mirror docs | Build avfilter + port; T16 | MIRROR |
| H02 | LUFS/LRA/true peak/RMS/crest | `ffmpeg_analyzer.h:427-473` | Mirror | Port exact; T16 | MIRROR |
| H03 | Spectral centroid/rolloff/flatness/flux/ZCR | `ffmpeg_analyzer.h:446-481` | Mirror | Port; T16 | MIRROR |
| H04 | Tempo/onset (aubio 0.4.9, 1024/512, HFC, FFTW3F) | `ffmpeg_analyzer.h:37-39,345-363` | Mirror | Add approved aubio/FFTW, static link; T16 | NEW |
| H05 | Mood formulas/percentiles/version 2 | `internal/app/analysis/mood/formulas.go`, `normalizer.go` | Present MoodRadio uses mirror features | Port; T17 | MIRROR |
| H06 | Analysis versions/pending masks/backfill | `analysis_service.go`; migrations | None | Port; T17 | ABSENT |
| H07 | Persist Room + TagLib tags + fingerprint | owner + upstream | Mirror docs | Implement; T17 | MIRROR |
| H08 | Reuse/reanalyze/album invalidation | `analysis_service.go`; owner | None | Implement; T17 | ABSENT |
| H09 | ReplayGain standard tags, preserve default | owner | None | Implement; T17 | ABSENT |
| H10 | Normalization modes/target/clip | `normalization/service.go` | Present mirror | Local; T18 | MIRROR |
| H11 | Album mean aggregation | `normalization/service.go:158-182` | Present shared | Local; T18 | MIRROR |
| H12 | Mood Radio similarity/selection/refill | `moodradio/service.go`; `moodRadio.ts` | Present mirror features | Local features; T19 | MIRROR |
| H13 | Analysis settings (save/reuse/preserve RG) | owner | None | Add settings; T17 | ABSENT |

## I. Listening, insights, integrations

| ID | Feature | Upstream anchor | Target | Decision / task | Status |
|---|---|---|---|---|---|
| I01 | Sessions/attempts, qualified plays | `listening_repository.go`; `player_service.go:1586` | Present local tables + merge | Local-only; T20 | PRESENT |
| I02 | Local-day split, retention 180d | `listening_repository.go:83-111`; cleanup `player_service.go:179` | Present | Keep; T20 | PRESENT |
| I03 | Daily aggregates by source device | migrations 62-64 | Present | Local identity; T20 | PRESENT |
| I04 | Insights ranges/top/quality/streak | `listening_repository.go:321-606` | Present | Keep; T20 | PRESENT |
| I05 | Device source filter | `HomeAnalysis.vue:297` | Present | Remove paired devices; T20 | PARTIAL |
| I06 | Last.fm auth/now playing/scrobble/love | `lastfm/service.go` | Present independent | Keep; verify; T21 | PRESENT |
| I07 | Last.fm offline retry | **none upstream** | None | Owner chose bounded persistent retry queue; T21 | NEW |
| I08 | Update notices/APK path | `updater` | Present GitHub check | Keep independent; T21 | PRESENT |
| I09 | i18n 12 locales | `frontend/locales`; mobile | Present | Keep; add new strings; T21/T24 | PRESENT |
| I10 | About/sponsor identity | target | Present Airmedy sponsor | Finish Sistrum cleanup; T24 | PARTIAL |

## J. Desktop-only mechanisms → Android equivalents

| ID | Desktop feature | Anchor | Android equivalent | Task |
|---|---|---|---|---|
| J01 | Tray menu / Now Playing panel | `tray.go`, macOS panel | Media notification + MediaSession; optional QS tile | T22 |
| J02 | Global keyboard shortcuts | `frontend/src/App.vue` | Media keys/hardware volume; physical-keyboard shortcuts optional | T22 |
| J03 | Custom title bar / window controls | `WindowTitleBar.vue` | System bars/immersive/adaptive layouts | T22 |
| J04 | Resizable sidebar/drawers | `MainLayout.vue` | Existing bottom sheets/fullscreen panels | Keep |
| J05 | Floating mini window/pin | `MiniPlayerFloating.vue` | In-app mini; PiP owner decision | T22 |
| J06 | Right-click quick settings | `PlayerQuickSettingsMenu.vue` | Long-press/overflow bottom sheet | T22 |
| J07 | Shift/Ctrl multi-select | `TrackTable.vue` | Long-press selection mode | T22 |
| J08 | Start at login | `GeneralSettings.vue` | Boot receiver (OS-restricted) owner decision | T22 |
| J09 | Prevent sleep | `PlaybackSettings.vue` | FGS playback; keep-screen-on only for lyrics | T22 |
| J10 | Show in Explorer | `useTrackContextMenu.ts` | Share/open-with/document provider | T22 |
| J11 | Dev overlay/15s sync | `App.vue` | Drop | T22 |
| J12 | Auto-advance notification | `PlaybackSettings.vue:134` | Update media notification; owner decision | T22 |

## K. Explicitly excluded (remote/desktop control)

| ID | Excluded | Anchor | Status |
|---|---|---|---|
| K01 | Browser remote SPA | `remote/`, `build:remote` | EXCLUDED |
| K02 | LAN HTTP/WS remote server, PIN/QR | `internal/app/remoteserver/*` | EXCLUDED |
| K03 | Mobile pairing/QR/Ed25519/MQTT | `mobilepairing/*`, sharedLogic pairing | EXCLUDED |
| K04 | mDNS discovery/broadcast | pairing services | EXCLUDED |
| K05 | Desktop library sync plans/assets/receipts | `mobilelibrarysync/*`; `SyncDatabase` | EXCLUDED |
| K06 | Playlist mutation reconciliation/acks | `PlaylistSync.kt`, `AndroidPlaylistReconciliation` | EXCLUDED |
| K07 | Listening upload/merge | `ListeningSync.kt` | EXCLUDED |
| K08 | Trusted-device UI/source filter | `MobileDevicesSettings.vue` | EXCLUDED |
| K09 | LAN/multicast/camera permissions/services | manifest | EXCLUDED; keep `FOREGROUND_SERVICE_DATA_SYNC` for local scans |
| K10 | Tray themes/title-bar customization | desktop settings | EXCLUDED |

## L. Item-level gaps/defects to decide or fix

| ID | Item | Source | Decision |
|---|---|---|---|
| L01 | M3U8 export of smart/favorites empty | `playlist_service.go:547` | Fix (owner) |
| L02 | M3U8 no relative paths/BOM/`#EXTENC` | `m3u8.go` | Fix (owner) |
| L03 | Last.fm no offline retry | `lastfm/service.go` | Add bounded retry (owner) |
| L04 | Ratings absent upstream | audit | Add Sistrum ratings (owner) |
| L05 | WebP artwork variants deleted on cleanup | `cache.go:133-137` | Fix in T08 |
| L06 | Artist artwork local case handling bug | `artist_local_image.go:27-52` | Fix in T08 |
| L07 | Track-artist GROUP_CONCAT ordering unstable | `track_repository.go:628-646` (recently added) | Make deterministic in T05 |
| L08 | `show_player_indicator`/`tray_panel_theme` default mismatch | settings | Not applicable/tray excluded |
| L09 | `lrclib_mode` dead column | migration 14 | Do not port |
| L10 | `[offset:]` ignored | `useLyrics.ts` | Flag; owner decision |
| L11 | Hidden-directory scan asymmetry | `library/service.go:641-740` | Define Android behavior in T04 |
| L12 | Album analysis aggregation absent upstream | audit | Derive at read time; T18 |
| L13 | No limiter beyond static clip guard | `normalization/service.go:149-153` | Preserve; document |

## M. Settings and controls inventory

| ID | Desktop control | Anchor | Android target / equivalent | Decision |
|---|---|---|---|---|
| M01 | Start at login | `GeneralSettings.vue:67-72` | Boot receiver or omit (OS restrictions) | Decision in T22 |
| M02 | Auto-check updates | `GeneralSettings.vue:74-79` | Keep independent update check | Port |
| M03 | Language (12 locales) | `GeneralSettings.vue:83-115` | System/per-app locale; existing 12 locales | Keep/expand |
| M04 | Theme system/light/dark/black | `GeneralSettings.vue:117-144` | Existing theme incl. OLED | Present |
| M05 | Primary/accent color | `GeneralSettings.vue:146-181` | Existing fixed Sistrum rose identity | Keep fixed (no redesign) |
| M06 | Title-bar navigation/actions | `TitleBarSettings.vue` | No title bar; system chrome/immersive | Exclude/adapt |
| M07 | Tray icon/mode/theme/panel | `GeneralSettings.vue:187-239` | Media notification + MediaSession; no tray themes | Adapt |
| M08 | Sidebar items/order/visibility | `SidebarSettings.vue` | Fixed floating destinations; no reorder | Exclude/adapt |
| M09 | Watched folders add/remove | `LibrarySettings.vue:312-370` | SAF sources page | Port |
| M10 | Sync/optimize search | `LibrarySettings.vue` | Scan/full rescan/reindex local FTS | Port |
| M11 | Sync interval | `LibrarySettings.vue:372-386` | Auto-scan policy (off/open/daily/weekly) | Port |
| M12 | Artist/album artist/genre/composer delimiters | `LibrarySettings.vue:388-409` | New separators page (four categories) | Port |
| M13 | Analysis enable/progress/retry | `LibrarySettings.vue:411-435` | Local analysis settings + progress | Port |
| M14 | Analysis worker count | `LibrarySettings.vue:437-454` | Automatic throttling; expose only if safe | Decision T17 |
| M15 | Last.fm connect/disconnect | `IntegrationsSettings.vue:137-173` | Existing | Present |
| M16 | Online artist artwork (Deezer) | `IntegrationsSettings.vue:175-182` | New page below Lyrics, OFF default | Port (owner) |
| M17 | Prefer local artist artwork | `IntegrationsSettings.vue:183-190` | Superseded by owner precedence | Owner decision |
| M18 | LRCLIB/KuGou toggles | `IntegrationsSettings.vue:193-205` | Existing | Present |
| M19 | Prefer local lyrics | `IntegrationsSettings.vue:206-212` | Default true; port toggle | Port |
| M20 | Lyrics subfolder | `IntegrationsSettings.vue:213-240` | SAF/local folder setting | Port (T15) |
| M21 | Dedicated lyrics folder | `IntegrationsSettings.vue:241-264` | SAF folder picker | Port (T15) |
| M22 | Romanization | `IntegrationsSettings.vue:265-271` | Existing | Present |
| M23 | Prevent sleep | `PlaybackSettings.vue:119-124` | FGS playback; keep-screen-on only for lyrics | Adapt |
| M24 | Player indicator dot | `PlaybackSettings.vue:125-128` | Existing? expose if missing | Port |
| M25 | Player favorite button | `PlaybackSettings.vue:129-133` | Existing player favorite | Present/verify |
| M26 | Auto-advance notification | `PlaybackSettings.vue:134-138` | Media notification update; no separate notification | Adapt/decision |
| M27 | High-contrast lyrics | `PlaybackSettings.vue:139-142` | Existing lyrics presentation | Present/verify |
| M28 | Lyrics glow | `PlaybackSettings.vue:143-146` | Existing | Present |
| M29 | Max queue size | `PlaybackSettings.vue:147-160` | Allowed set; expose setting | Port |
| M30 | Fullscreen background living/solid | `PlaybackSettings.vue:163-176` | Existing | Present |
| M31 | Mini background | `PlaybackSettings.vue:177-189` | In-app mini living/solid | Port/verify |
| M32 | EQ enable/preset/preamp/bands | `PlaybackSettings.vue:192-280`; `EQPanel.vue` | Existing | Present |
| M33 | Stereo width 0–200 | `PlaybackSettings.vue` | Native supports; expose setting | Port |
| M34 | Crossfade enable/duration/blend | `PlaybackSettings.vue:282-304` | Existing | Present |
| M35 | Normalization enable/mode/target/clip | `PlaybackSettings.vue:305-363` | Existing; local analysis gate | Present/port gate |
| M36 | Remote server/QR/PIN | `RemoteServerSettings.vue` | Excluded | Excluded |
| M37 | Mobile pairing/devices/sync | `MobileDevicesSettings.vue` | Excluded | Excluded |
| M38 | About/update/sponsors/license | `AboutSettings.vue` | Existing About; finish Sistrum branding | Port |
| M39 | Quick settings long-press | `PlayerQuickSettingsMenu.vue` | Bottom sheet | Port T22 |
| M40 | Multi-select (Ctrl/Shift) | `TrackTable.vue` | Long-press selection mode | Port T22 |

## N. Context actions and dialogs

| ID | Action | Upstream anchor | Android target | Task |
|---|---|---|---|---|
| N01 | Track: play next | `TrackContextMenu.vue` / `useTrackContextMenu.ts` | Local queue | T11 |
| N02 | Track: add to queue | same | Local queue | T11 |
| N03 | Track: track info | same; `TrackInfoDrawer.vue` | Existing bottom sheet, local data | T10 |
| N04 | Track: refresh lyrics | same | Local/provider refresh | T15 |
| N05 | Track: find lyrics | same; `FindLyricsDialog.vue` | Existing find-lyrics + save/embed | T15 |
| N06 | Track: Mood Radio | same | Local Mood Radio | T19 |
| N07 | Track: favorite/unfavorite | same | Local favorite | T13 |
| N08 | Track: add to playlist / create playlist | same | Local playlists | T13 |
| N09 | Track: go to album/artist | same | Existing navigation, local queries | T10 |
| N10 | Track: edit metadata | same; `MetadataEditDialog.vue` | Tag editor | T07 |
| N11 | Track: show in explorer | same | Share/open-with/document info | T22 |
| N12 | Track: remove from playlist | `PlaylistDetailView.vue` | Local playlist mutation | T13 |
| N13 | Multi-select track actions | `TrackTable.vue` | Long-press selection mode | T22 |
| N14 | Album: play/shuffle/play next/add queue | `useAlbumContextMenu.ts` | Local queue | T11 |
| N15 | Album: favorite all / remove favorites | same | Local favorites | T13 |
| N16 | Album: add all to playlist | same | Local playlists | T13 |
| N17 | Group: play next/add queue/add all | `useGroupContextMenu.ts` | Local queue/playlists | T11/T13 |
| N18 | Playlist: play/shuffle/play next/add queue | `usePlaylistContextMenu.ts` | Local queue | T11 |
| N19 | Playlist: pin/unpin | same | Local pinned row | T13 |
| N20 | Playlist: rename/edit smart rules | same | Local playlists/smart UI | T13/T14 |
| N21 | Playlist: export M3U8 | same | Fixed exporter | T13 |
| N22 | Playlist: delete | same | Local transaction | T13 |
| N23 | Playlist: artwork set/remove | `PlaylistDetailView.vue` | Local artwork picker | T13 |
| N24 | Artist: set/remove custom image | `ArtistDetailView.vue` | Local artist artwork override | T08 |
| N25 | Player quick settings | `PlayerQuickSettingsMenu.vue` | Long-press bottom sheet | T22 |
| N26 | Create playlist / create with tracks | dialogs | Compose dialogs | T13 |
| N27 | Smart playlist dialog | `SmartPlaylistDialog.vue` | Compose builder | T14 |
| N28 | Confirmation/restart/update dialogs | dialogs | Existing Compose components | T21/T22 |
| N29 | Device revoke / remote server / QR | desktop-only | Excluded | K03/K04 |
