# Baseline and native input record (M001-T001/T005 remediation)

**Baseline revision**: `3ed1de2bd696e316a4a6e7dcc669b8242733582a` (`alpha10`, before TOR004).
Everything below is reproducible from that tree with the commands shown.

## Toolchain

| Input | Value | How to check |
| --- | --- | --- |
| JDK | Zulu 21.0.11 (`.gradle/jdks/azul_systems__inc_-21-amd64-linux.2`) | `java -version` |
| Android SDK | 37 (`local.properties` → `sdk.dir`) | `aapt2 dump badging` |
| NDK | 30.0.16248370 | `scripts/build-*.sh`, `androidApp/build.gradle.kts` |
| CMake | 3.22.1 | `ls "$ANDROID_HOME/cmake"` |
| Gradle | wrapper in repo (9.5.0) | `./gradlew --version` |

## Native sources

| Component | Pin | Integrity |
| --- | --- | --- |
| FFmpeg | 9.0.2, `https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.gz` | SHA-256 `84960df915059e8754fef2cd7c9afeb614062b1b5458ec471eecee619ee04e98` (18,129,779 B) |
| TagLib | v2.3.2, `deadc2990767dfbda0701e0ab35fdeea653db08f` | tarball SHA-256 `cda2d6dac7f12a84e46fbb0907bb1a1ec0679e4722b5237576be5563e295244e` |
| utfcpp | 4.0.6 | SHA-256 `6920a6a5d6a04b9a89b2a89af7132f8acefd46e0c2a7b190350539e9213816c0` |
| aubio | 0.4.9 | SHA-256 `df1244f6c4cf5bea382c8c2d35aa43bc31f4cf631fe325ae3992c219546a4202` |
| FFTW (float) | 3.3.11 | SHA-256 `5630c24cdeb33b131612f7eb4b1a9934234754f9f388ff8617458d0be6f239a1` |

FFmpeg configure flags (`scripts/build-ffmpeg-android.sh`):

```
--target-os=android --arch=aarch64 --enable-cross-compile
--disable-everything --disable-autodetect --disable-programs --disable-doc
--disable-debug --disable-network --enable-shared --disable-static --enable-small
--enable-pic --enable-avutil --enable-avcodec --enable-avformat --enable-swresample
--enable-avfilter --enable-protocol=file --enable-decoders --enable-demuxers
--enable-parsers
--enable-filter=ebur128,aspectralstats,astats,aresample,aformat,anull,abuffer,abuffersink
--disable-avdevice --disable-swscale
```

## APK and native output at baseline

Measured on `androidApp/build/outputs/apk/dev/debug/androidApp-dev-debug.apk`:

| Fact | Value |
| --- | --- |
| Size | 82,666,881 B |
| ABI | `arm64-v8a` only |
| Package / version | `com.amwolfstein.sistrum.dev` 0.1.0 (versionCode 1) |
| compileSdk / targetSdk | 37 / 37 |
| Signature | debug keystore (not a release artifact) |
| `libsistrum_player.so` | 2,398,576 B |
| `libavfilter.so` | 141,272 B (analysis filters linked) |

Rebuild:

```sh
bash scripts/build-ffmpeg-android.sh arm64-v8a
bash scripts/build-taglib-android.sh arm64-v8a
bash scripts/build-aubio-android.sh arm64-v8a
./gradlew :androidApp:buildCMakeDebug :androidApp:assembleDevDebug
```

## JNI surface (native exports used by Kotlin)

`libsistrum_player.so` exports, from `ffmpeg_player.cpp`, `taglib_bridge.cpp` and
`analysis_bridge.cpp`:

- playback: `nativeCreate`, `nativeDestroy`, `nativePrepare`, `nativePreload`,
  `nativeSetNormalizationGains`, `nativeSetFocusGain`, `nativeClearPreloaded`,
  `nativeHasPreloaded`, `nativeBeginCrossfade`, `nativeFinishCrossfade`,
  `nativeSnapCrossfade`, `nativeIsCrossfading`, `nativeConsumeTransition`,
  `nativeSetGlobalDspConfig`, `nativePlay`, `nativePause`, `nativeStop`,
  `nativeSeekTo`, `nativeDurationMs`, `nativePositionMs`,
  `nativePreloadedDurationMs`, `nativePreloadedPositionMs`, `nativeIsFinished`,
  `nativeIsOutputDisconnected`;
- tags: `nativeReadMetadata`, `nativeReadArtwork`, `nativeWriteTags`,
  `nativeWriteProperties`, `nativeWriteArtwork`, `nativeAudioFingerprint`;
- analysis: `nativeTempoProbe`, `nativeAnalyzeFd`.

## Known baseline failures (evidence for the remediation)

1. **Provider path reopen**: every consumer opened `"/proc/self/fd/<fd>"`. For SAF
   documents the symlink resolves to a path the app cannot reopen, so TagLib
   returned `{}`, FFmpeg playback failed with `FFmpeg could not open this audio
   file`, and analysis decoded nothing (`Analyzed 0 tracks`). Verified on the
   777-file `/sdcard/Music` library (device `CPH2307`, Android 15).
2. **Descriptor lifetime/seek**: the descriptor was detached in Kotlin and closed
   natively after a path open, so nothing owned the descriptor during decoding.
3. Empty metadata rows persisted because an incremental scan treats unchanged
   size/mtime as unchanged.

Remediation replaces path reopening with pread/pwrite I/O on the descriptor
itself and computes a versioned decoded-PCM identity; consumer checks live in
TOR005/TOR006.
