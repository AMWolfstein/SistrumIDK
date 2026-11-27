# Source map: upstream → Android target

Upstream = `airmedy-upstream` @ `d859940c`. Target paths are relative to the repo root. Task numbers refer to `tasks.md`.

| Concern | Upstream source | Android target (planned/actual) | Task |
|---|---|---|---|
| Library service/folder sync | `internal/app/library/service.go` | `androidApp/.../library/scan/*`, `library/LibraryRepository.kt` | T04–T06 |
| Entity identity/resolution | `service.go:1016-1181,1435-1437` | `library/metadata/EntityResolver.kt`, DB | T05 |
| Metadata extraction | `internal/infra/metadata/taglib.go` | `cpp/taglib/*`, `library/tags/TagLibBridge.kt`, `MetadataExtractor.kt` | T05 |
| Tag writing | `taglib.go:177-204`; `service.go:1546-1569` | `library/tags/TagWriter.kt`, `TagWriteJournal.kt` | T07 |
| Normalization/sort/delimiters | `internal/domain/metadata_processing.go` | `library/metadata/Normalization.kt`, `Separators.kt` | T05, T09 |
| Artwork cache | `internal/infra/artwork/cache.go` | `library/artwork/ArtworkCache.kt` | T08 |
| Artist artwork | `internal/app/library/artist_artwork.go`, `artist_local_image.go` | `library/artwork/ArtistArtworkRepository.kt`, `DeezerArtworkClient.kt` | T08 |
| Playlists | `internal/app/playlist/*`, `internal/infra/sqlite/playlist_repository.go` | `library/playlist/*` | T13 |
| Smart rules | `internal/app/playlist/smart_rules.go` | `library/playlist/SmartRules.kt` | T14 |
| Settings/defaults | `internal/domain/models.go`, `appsettings/service.go` | DataStore repositories/UI | T09/T17/T21 |
| Search | `internal/infra/bleve/bleve.go` | `library/search/LibrarySearch.kt` (FTS) | T10 |
| Queue | `internal/app/player/queue_service.go` | existing `sharedLogic/player/PlaybackQueue.kt` | T11 |
| Playback/crossfade/EQ | `native_player_darwin.m`, `miniaudio_wrapper_*.c`, `eq_service.go` | existing `cpp/ffmpeg_player.cpp` | T11/T12 |
| Normalization | `internal/app/normalization/service.go` | `sharedLogic/player/VolumeNormalization.kt`, `library/analysis/AlbumAggregate.kt` | T18 |
| Lyrics | `internal/app/lyrics/*`, `internal/infra/lyrics/*` | `lyrics/AndroidLyricsService.kt`, `library/lyrics/*` | T15 |
| Romanization | `internal/infra/romanization/*` | `lyrics/AndroidRomanizationEngine.kt`, `sharedLogic/lyrics/*`, assets | T15 |
| Analysis (FFmpeg) | `internal/infra/audio/ffmpeg_analyzer.h`, `analyzer.go` | `cpp/analysis/*`, `library/analysis/AnalysisEngine.kt` | T16 |
| Analysis service/formulas | `internal/app/analysis/*` | `library/analysis/*` | T17 |
| Mood Radio | `internal/app/moodradio/service.go`; `frontend/src/stores/moodRadio.ts` | `sharedLogic/mood/MoodRadio.kt`, `library/mood/*` | T19 |
| Listening/analytics | `internal/infra/sqlite/listening_repository.go` | `library/listening/*`, `sharedLogic/player/ListeningTracker.kt` | T20 |
| Last.fm | `internal/app/lastfm/service.go` | `lastfm/LastFmService.kt` | T21 |
| Updater | `internal/app/updater/*` | `update/GitHubAppReleaseSource.kt` | T21 |
| UI routes/screens | `frontend/src/views/*` | existing `ui/screens/*` | T10/T22 |
| Mobile app | `mobile/androidApp/*`, `mobile/sharedLogic/*` | target app (already imported) | — |
| Scanner reference | Mornye `MainActivitySafScan.kt`, `SafTreeListing.kt`, `SafScanCheckpoint.kt`, Dart providers | `library/scan/*` | T03/T04 |
| Desktop sync/pairing | `internal/app/mobilesync`, `mobilepairing`, `mobile/.../sync`, `pairing` | remove | T23 |
| Remote control | `remote/`, `internal/app/remoteserver` | exclude | K01/K02 |
