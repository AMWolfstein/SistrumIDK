#!/usr/bin/env bash
# Compiles the golden harness against the upstream Airmedy producer libraries.
#
# Prerequisites (outside this repository; nothing is committed from them):
#   1. Clone upstream at the pinned revision and build its native libs with its
#      own scripts, which produce ffmpeg_libs/, aubio_libs/ and fftw3_libs/:
#        git clone https://github.com/misa198/airmedy
#        cd airmedy && git checkout d859940c0629907e377c889fb276904b6a7a4b8c
#        bash scripts/build-fftw3-linux.sh amd64
#        bash scripts/build-aubio-linux.sh amd64
#        bash scripts/build-ffmpeg-linux.sh
#   2. gcc and the Linux host toolchain.
#
# Usage: bash tools/analysis-goldens/build-harness.sh /path/to/airmedy /path/to/output/analyze
set -euo pipefail
SRC="${1:?airmedy source dir}"
OUT="${2:?output binary path}"
here="$(cd "$(dirname "$0")" && pwd)"
AUDIO="$SRC/internal/infra/audio"

gcc -O2 -o "$OUT" "$here/analyze.c" "$AUDIO/ffmpeg_shared_mu.c" \
    -I"$AUDIO" \
    -I"$AUDIO/ffmpeg_libs/include" \
    -I"$AUDIO/aubio_libs/include" \
    -L"$AUDIO/ffmpeg_libs/linux/amd64" \
    -L"$AUDIO/aubio_libs/linux/amd64" \
    -L"$AUDIO/fftw3_libs/linux/amd64" \
    -lavfilter -lavformat -lavcodec -lswresample -lavutil -laubio -lfftw3f \
    -lz -lbz2 -llzma -lpthread -lm -ldl
echo "built $OUT"
