# Analysis golden references

Numerical regression suite for the native analyzer. The expected values come
from the **original desktop producer**, not from the Android port.

## Pinned producer

| Item | Value |
|---|---|
| Repository | https://github.com/misa198/airmedy |
| Revision | `d859940c0629907e377c889fb276904b6a7a4b8c` (HEAD of `master`, == the mission pin) |
| Analyzer version | 4 (`internal/infra/audio/analyzer.go`) |
| FFmpeg | 9.0.2, built by `scripts/build-ffmpeg-linux.sh` (`--disable-everything`, avfilter + ebur128/aspectralstats/astats/aresample/aformat/anull/abuffer/abuffersink, `--disable-network`, file protocol only) |
| aubio | 0.4.9, static, built against FFTW3F by `scripts/build-aubio-linux.sh` (`--enable-fftw3f`, Ooura disabled) |
| FFTW | 3.3.11 single precision, static (`-O2 -fPIC`, `--enable-static --with-pic --enable-single`) |
| Graph | `abuffer -> ebur128=metadata=1:peak=true -> aspectralstats=measure=centroid+rolloff+flatness+flux -> astats=metadata=1:reset=0 -> abuffersink` |
| aubio config | tempo `default`, onset `hfc`, buffer 1024, hop 512, mono rate 44100 |
| Decoder | `thread_count = 1`; probing serialized |

Android links the same FFmpeg/aubio/FFTW versions (`scripts/build-ffmpeg-android.sh`,
`scripts/build-aubio-android.sh`), so the only expected difference is the
double→float JNI boundary.

## Files

- `analyze.c` — harness that includes the upstream `ffmpeg_analyzer.h` verbatim
  and prints one JSON object per file.
- `build-harness.sh` — compiles it against the producer's static libraries.
- `make-fixtures.sh` — regenerates the deterministic fixture corpus (pure math,
  no personal music; safe to re-generate at any time).
- `generate-goldens.py` — runs the harness, writes `goldens.json` and fixture
  SHA-256 checksums.

The generated files live in the instrumentation assets:
`androidApp/src/androidTest/assets/analysis/` (`goldens.json` + the fixtures),
and `AnalysisParityTest` compares the Android output against them on a device.

## Album loudness

The producer has no separate album decode. `internal/app/normalization/service.go`
(`albumLoudness`) defines album loudness as the **arithmetic mean** of the
album tracks' integrated LUFS, and `sameAlbumChain` applies that gain only while
the queue stays on the same album; clipping uses the playing track's own true
peak (`maxGain = -f.TruePeak`). `generate-goldens.py` derives the album case
from the producer's track values and records the expected album gain and the
Opus `R128_ALBUM_GAIN` (Q7.8) value.

## Regenerate

```bash
git clone https://github.com/misa198/airmedy /tmp/airmedy
cd /tmp/airmedy && git checkout d859940c0629907e377c889fb276904b6a7a4b8c
bash scripts/build-fftw3-linux.sh amd64
bash scripts/build-aubio-linux.sh amd64
bash scripts/build-ffmpeg-linux.sh

cd <repo>
bash tools/analysis-goldens/build-harness.sh /tmp/airmedy /tmp/analyze
bash tools/analysis-goldens/make-fixtures.sh /tmp/analysis-fixtures
python3 tools/analysis-goldens/generate-goldens.py \
    /tmp/analysis-fixtures /tmp/analyze androidApp/src/androidTest/assets/analysis/goldens.json
cp /tmp/analysis-fixtures/*.{wav,flac,mp3,m4a,ogg,opus,wv,aiff} \
    androidApp/src/androidTest/assets/analysis/
```

`goldens.json` records the SHA-256 of every fixture, so a mismatch after
regeneration is visible without listening.

## Tolerances (and why)

| Field | Absolute tolerance | Justification |
|---|---|---|
| loudness LUFS / LRA / true peak / RMS | 0.01 dB | 32-bit float JNI rounding is < 1e-5 dB; the budget covers the last ebur128 gating block when a CUE segment ends mid-block |
| crest (linear ratio) | 0.001 | float ratio |
| spectral centroid / rolloff | 0.5 Hz | per-frame mean over thousands of frames in float32 |
| spectral flatness / flux | 1e-5 | small absolute values, float32 mean |
| ZCR per second | 1e-5 | float32 mean |
| tempo BPM | 0.05 | aubio beat estimate is identical for identical PCM; 0.05 leaves room for the last beat only |
| onset variance (samples²) | 0.1 % relative | variance is accumulated in double, then stored as float32; values reach 1e6 |

Digital silence is represented as `null` (the producer prints `-inf`); the app
must report a non-finite or <= -69 dB value, never an invented number.

## CUE segment

`segmentCase` analyzes `album-1.flac` from 1000 ms with `endMs = -1` and compares
against the producer analyzing the equivalent cut file. The trim is done in
whole samples, so the segment PCM is identical to the cut file (verified with
`cmp` on decoded s16le output).
