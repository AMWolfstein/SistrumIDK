# Contract: TagLib bridge

## Native API (minimal JNI)

```text
readMetadata(fd/path) -> MetadataResult
readArtwork(fd/path) -> ByteArray? + mime
writeTags(fd/path, TagPatch, artwork?) -> WriteResult
readTag(fd/path, key) -> String?
```

- All calls are synchronous off the main thread; one file at a time.
- `fd` is a seekable descriptor or a staged temp file created by the caller; native code never opens arbitrary URIs.
- `MetadataResult` fields: title, sortTitle, album, albumSort, artists[], albumArtists[], genres[], composers[], copyright, bpm, label, isrc, year, track, disc, totalTracks, totalDiscs, durationSeconds, bitrate, sampleRate, bitDepth, codec, format, releaseDate, lyrics, lyricsSynced, artwork, otherTags.
- Alias priority follows upstream `taglib.go:29-116`; all same-named frames joined with `"; "`; raw map returned.

## Write protocol

1. Caller stages a private copy of the file (or uses a SAF writable descriptor to a temp document).
2. Native writes only requested fields; `Clear` semantics per format; artwork replacement validated/decoded/JPEG-converted.
3. Caller verifies: audio payload hash unchanged (excluding tag regions per format), unrelated tags preserved, result readable.
4. Atomic publish: rename/replace if supported; else copy-verify-replace; on failure keep original and report.
5. Journal row records stage/verify/replace/error; interrupted writes recover from the original.

## Fingerprints

After a full decode/analysis, compute `pcm-sha256-v1` over canonical decoded PCM (audio only, no tag bytes) and store it with the version. Tag-only edits must not change it. Use it for analysis reuse and move/rename `content_key` matching.

## Safety

- Serialize writes per `document_key`; no concurrent analysis/write for the same file.
- Read-only/unsupported formats fail with typed error; never partial-overwrite.
- After success: update DB metadata/artwork, invalidate caches, enqueue reindex; suppress self-triggered scan loops via content fingerprint/mtime update.
- No credentials or paths in logs.

## Formats

MP3, M4A/ALAC, FLAC, OGG/Vorbis, Opus, WAV/AIFF, APE, WavPack, DSF/DFF where TagLib supports the operation. Detect by content; extension is a hint. If a format cannot be written losslessly, return `UNSUPPORTED` and offer a sidecar/DB fallback where the product allows.
