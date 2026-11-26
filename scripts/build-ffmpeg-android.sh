#!/usr/bin/env bash
# Builds the Android FFmpeg decoder used by androidApp.
# Adapted from misa198/airmedy (d859940c0629907e377c889fb276904b6a7a4b8c).
# Source and intermediate files are built in a private temporary directory.
#
# Usage: bash scripts/build-ffmpeg-android.sh [arm64-v8a]
set -euo pipefail

FFMPEG_VERSION="9.0.2"
FFMPEG_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.gz"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ABI="${1:-arm64-v8a}"
if [[ "$#" -gt 1 || "${ABI}" != arm64-v8a ]]; then
    echo "Usage: bash scripts/build-ffmpeg-android.sh [arm64-v8a]" >&2
    exit 1
fi

ANDROID_DIR="${REPO_ROOT}/androidApp"
SDK_DIR=""
if [[ -f "${REPO_ROOT}/local.properties" ]]; then
    SDK_DIR="$(awk '/^sdk[.]dir[[:blank:]]*=/ {
        sub(/^sdk[.]dir[[:blank:]]*=[[:blank:]]*/, "")
        sub(/\r$/, "")
        print
        exit
    }' "${REPO_ROOT}/local.properties")"
fi
SDK_DIR="${SDK_DIR:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
if [[ -z "${SDK_DIR}" ]]; then
    echo "Set sdk.dir in root local.properties, ANDROID_HOME, or ANDROID_SDK_ROOT." >&2
    exit 1
fi

# Keep the pin aligned with androidApp/build.gradle.kts.
NDK_VERSION="30.0.16248370"
NDK="${SDK_DIR}/ndk/${NDK_VERSION}"
case "$(uname -s)" in
    Linux) HOST_OS="linux"; JOBS="${SISTRUM_BUILD_JOBS:-$(nproc)}" ;;
    Darwin) HOST_OS="darwin"; JOBS="${SISTRUM_BUILD_JOBS:-$(sysctl -n hw.ncpu)}" ;;
    *) echo "Run this script on a supported Linux or macOS Android NDK host." >&2; exit 1 ;;
esac
API=31
TRIPLE="aarch64-linux-android"
TOOLCHAIN=""
for candidate in "${NDK}/toolchains/llvm/prebuilt/${HOST_OS}-$(uname -m)" "${NDK}/toolchains/llvm/prebuilt/${HOST_OS}-"*; do
    if [[ -x "${candidate}/bin/${TRIPLE}${API}-clang" ]]; then
        TOOLCHAIN="${candidate}"
        break
    fi
done
if [[ -z "${TOOLCHAIN}" ]]; then
    echo "Android NDK ${NDK_VERSION} with a ${HOST_OS} toolchain is required at ${NDK}." >&2
    exit 1
fi

# Build the libraries needed by the analysis filter graph before replacing FFmpeg artifacts.
bash "${SCRIPT_DIR}/build-analysis-deps-android.sh"

BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sistrum-ffmpeg.XXXXXXXX")"
trap 'rm -rf -- "${BUILD_DIR}"' EXIT
JNI_OUT="${ANDROID_DIR}/src/main/jniLibs/${ABI}"
INCLUDE_OUT="${ANDROID_DIR}/build/ffmpeg/include"

# FFmpeg is the sole decoder. Enable its complete decoder, demuxer and parser
# registry so every music format supported by the pinned upstream source stays
# supported without maintaining a fragile allow-list. Programs, encoders,
# muxers, devices and network protocols remain excluded. Audio analysis enables
# the same filters used by Airmedy Desktop.

SRC="${BUILD_DIR}/src"
INSTALL="${BUILD_DIR}/install"
mkdir -p "${SRC}"
echo "==> Downloading FFmpeg ${FFMPEG_VERSION}..."
curl --fail --location --retry 3 "${FFMPEG_URL}" -o "${BUILD_DIR}/ffmpeg.tar.gz"
if command -v sha256sum >/dev/null; then
    echo "84960df915059e8754fef2cd7c9afeb614062b1b5458ec471eecee619ee04e98  ${BUILD_DIR}/ffmpeg.tar.gz" | sha256sum -c -
else
    echo "84960df915059e8754fef2cd7c9afeb614062b1b5458ec471eecee619ee04e98  ${BUILD_DIR}/ffmpeg.tar.gz" | shasum -a 256 -c -
fi
tar -xzf "${BUILD_DIR}/ffmpeg.tar.gz" -C "${SRC}" --strip-components=1

echo "==> Building FFmpeg ${FFMPEG_VERSION} for ${ABI}..."
(
    cd "${SRC}"
    ./configure \
        --prefix="${INSTALL}" \
        --target-os=android --arch=aarch64 --enable-cross-compile \
        --cc="${TOOLCHAIN}/bin/${TRIPLE}${API}-clang" \
        --cxx="${TOOLCHAIN}/bin/${TRIPLE}${API}-clang++" \
        --ar="${TOOLCHAIN}/bin/llvm-ar" --ranlib="${TOOLCHAIN}/bin/llvm-ranlib" --strip="${TOOLCHAIN}/bin/llvm-strip" \
        --disable-everything --disable-autodetect --disable-programs --disable-doc --disable-debug --disable-network \
        --enable-shared --disable-static --enable-small --enable-pic \
        --enable-avutil --enable-avcodec --enable-avformat --enable-swresample --enable-avfilter \
        --enable-protocol=file --enable-decoders --enable-demuxers --enable-parsers \
        --disable-avdevice --disable-swscale \
        --enable-filter=ebur128,aspectralstats,astats,aresample,aformat,anull,abuffer,abuffersink \
        --extra-cflags="-Oz -ffunction-sections -fdata-sections" \
        --extra-ldflags="-Wl,--gc-sections -Wl,-z,max-page-size=16384"
    make -j"${JOBS}"
    make install
)

LIBRARIES=(libavutil libswresample libavcodec libavformat libavfilter)
for lib in "${LIBRARIES[@]}"; do
    test -f "${INSTALL}/lib/${lib}.so"
done
test -f "${INSTALL}/include/libavcodec/avcodec.h"

mkdir -p "${JNI_OUT}"
for lib in "${LIBRARIES[@]}"; do
    cp -L "${INSTALL}/lib/${lib}.so" "${JNI_OUT}/${lib}.so"
done
rm -rf -- "${INCLUDE_OUT}"
mkdir -p "$(dirname "${INCLUDE_OUT}")"
cp -R "${INSTALL}/include" "${INCLUDE_OUT}"
echo "==> Done. Generated FFmpeg libraries are in ${JNI_OUT}."
