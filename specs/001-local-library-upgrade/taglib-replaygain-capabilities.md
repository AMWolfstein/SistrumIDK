# TagLib and ReplayGain capabilities

This table describes the implementation's current guards. The published Kyant0 TagLib 1.0.6 AAR contains `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`. Its manifest declares Android API 23. The ARM64 `libtaglib.so` uses 16 KiB ELF LOAD alignment and depends on Android platform libraries, with no separate `libc++_shared.so` dependency. Sistrum currently targets arm64-v8a and min SDK 31. Android package alignment, runtime loading, provider permissions and actual read/write round trips remain deferred.

| Container | Embedded reading | Safe replacement | Multiple pictures / lyrics | ReplayGain persistence |
| --- | --- | --- | --- | --- |
| MP3 | TagLib, with existing reader fallback | ID3v2.3/2.4 files passing opaque-frame and audio-tail verification | TagLib API exposed; untouched data must pass verification | Track tags and removal guarded by TXXX description verification |
| FLAC | TagLib, with existing reader fallback | Native FLAC files passing metadata-block and audio-tail verification | TagLib API exposed; untouched data must pass verification | Track tags and removal |
| M4A/AAC/ALAC | TagLib read attempt and existing fallback | Disabled pending container-preservation verification | Write disabled | Write disabled |
| Opus | TagLib read attempt and existing fallback | Disabled pending Ogg/Opus preservation verification | Write disabled | R128 write and playback disabled pending header-gain verification |
| Ogg Vorbis | TagLib read attempt and existing fallback | Disabled pending container-preservation verification | Write disabled | Write disabled |
| WAV | TagLib read attempt and existing fallback | Disabled pending container-preservation verification | Write disabled | Write disabled |
| AIFF, APE, WavPack, ASF/WMA, DSF, DSDIFF, Matroska | TagLib read attempt and existing fallback | Disabled pending format and preservation verification | Write disabled | Write disabled |

TagLib PropertyMap cannot represent every opaque native structure. The application reopens the staged file, compares complete exposed properties and pictures, and verifies untouched native frames or blocks and the encoded audio tail for the two enabled formats before replacement. Unsupported cases fail before changing the original. Those verification rules are conservative and cannot guarantee provider or filesystem behavior until device tests run.

ReplayGain track measurement uses the existing FFmpeg decoder, ebur128 integrated LUFS and astats sample peak, with true peak returned separately. Album-wide gating has not been verified and no album gains are calculated or written. Opus gain conventions and playback interaction need device/reference verification before enabling R128 writes. Existing album gain tags can be read for supported playback formats.

A completed file edit updates the MediaStore and leaves a visible pending library refresh. Existing scan-based recovery retains track identity. A confirmed unchanged audio payload permits migration of cached analysis to the new file revision in the library transaction. Unsupported storage locations and operations report failure; a batch can have partial success.
