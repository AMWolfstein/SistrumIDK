#!/usr/bin/env bash
# Builds the pinned TagLib static library for androidApp's native player.
# Mirrors scripts/build-ffmpeg-android.sh: source and build tree stay in a private temp
# directory; only the install tree under androidApp/src/main/cpp/third_party is kept.
#
# Usage: bash scripts/build-taglib-android.sh [arm64-v8a]
set -euo pipefail

TAGLIB_VERSION="2.3.2"
TAGLIB_COMMIT="deadc2990767dfbda0701e0ab35fdeea653db08f"
TAGLIB_URL="https://github.com/taglib/taglib/archive/refs/tags/v${TAGLIB_VERSION}.tar.gz"
UTFCPP_VERSION="4.0.6"
UTFCPP_URL="https://github.com/nemtrif/utfcpp/archive/refs/tags/v${UTFCPP_VERSION}.tar.gz"
TAGLIB_SHA256="cda2d6dac7f12a84e46fbb0907bb1a1ec0679e4722b5237576be5563e295244e"
UTFCPP_SHA256="6920a6a5d6a04b9a89b2a89af7132f8acefd46e0c2a7b190350539e9213816c0"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ABI="${1:-arm64-v8a}"
if [[ "$#" -gt 1 || "${ABI}" != arm64-v8a ]]; then
    echo "Usage: bash scripts/build-taglib-android.sh [arm64-v8a]" >&2
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

NDK_VERSION="$(sed -n 's/^[[:space:]]*ndkVersion[[:space:]]*=[[:space:]]*"\(.*\)"/\1/p' \
    "${ANDROID_DIR}/build.gradle.kts" | head -1)"
NDK_VERSION="${NDK_VERSION:-30.0.16248370}"
NDK="${SDK_DIR}/ndk/${NDK_VERSION}"
if [[ ! -f "${NDK}/build/cmake/android.toolchain.cmake" ]]; then
    echo "Android NDK ${NDK_VERSION} with CMake support is required at ${NDK}." >&2
    exit 1
fi

BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sistrum-taglib.XXXXXXXX")"
trap 'rm -rf -- "${BUILD_DIR}"' EXIT
SRC="${BUILD_DIR}/src"
BUILD="${BUILD_DIR}/build"
INSTALL="${ANDROID_DIR}/src/main/cpp/third_party/taglib/${ABI}"
mkdir -p "${SRC}" "${BUILD}"

echo "==> Downloading TagLib ${TAGLIB_VERSION} (${TAGLIB_COMMIT})..."
curl --fail --location --retry 3 "${TAGLIB_URL}" -o "${BUILD_DIR}/taglib.tar.gz"
echo "${TAGLIB_SHA256}  ${BUILD_DIR}/taglib.tar.gz" | sha256sum --check --status || {
    echo "TagLib tarball checksum mismatch; expected ${TAGLIB_COMMIT} v${TAGLIB_VERSION}." >&2
    exit 1
}
tar -xzf "${BUILD_DIR}/taglib.tar.gz" -C "${SRC}" --strip-components=1

# TagLib's release tarball leaves its utfcpp submodule empty; pin the same helper explicitly.
echo "==> Downloading utfcpp ${UTFCPP_VERSION}..."
curl --fail --location --retry 3 "${UTFCPP_URL}" -o "${BUILD_DIR}/utfcpp.tar.gz"
echo "${UTFCPP_SHA256}  ${BUILD_DIR}/utfcpp.tar.gz" | sha256sum --check --status || {
    echo "utfcpp tarball checksum mismatch; expected v${UTFCPP_VERSION}." >&2
    exit 1
}
rm -rf "${SRC}/3rdparty/utfcpp"
mkdir -p "${SRC}/3rdparty/utfcpp"
tar -xzf "${BUILD_DIR}/utfcpp.tar.gz" -C "${SRC}/3rdparty/utfcpp" --strip-components=1

echo "==> Building TagLib ${TAGLIB_VERSION} for ${ABI}..."
export PATH="${SDK_DIR}/cmake/3.22.1/bin:${PATH}"
CMAKE="${SDK_DIR}/cmake/3.22.1/bin/cmake"
if [[ -z "${CMAKE}" ]]; then
    echo "cmake 3.22.1 (Android SDK) or a host cmake is required." >&2
    exit 1
fi
"${CMAKE}" -S "${SRC}" -B "${BUILD}" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="${NDK}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="${ABI}" \
    -DANDROID_PLATFORM=android-31 \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_FLAGS="-fPIC -ffunction-sections -fdata-sections -fvisibility=hidden -Oz" \
    -DCMAKE_C_FLAGS="-fPIC -ffunction-sections -fdata-sections -fvisibility=hidden -Oz" \
    -DCMAKE_INSTALL_PREFIX="${INSTALL}" \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_TESTING=OFF \
    -DBUILD_EXAMPLES=OFF \
    -DBUILD_BINDINGS=OFF \
    -DWITH_ZLIB=OFF \
    -DWITH_ICONV=OFF
"${CMAKE}" --build "${BUILD}" --parallel "$(nproc)"
"${CMAKE}" --install "${BUILD}"

echo "==> Stripping TagLib..."
case "$(uname -s)" in
    Linux) HOST_TAG="linux-x86_64" ;;
    Darwin) HOST_TAG="darwin-x86_64" ;;
    *) echo "Unsupported host for NDK strip." >&2; exit 1 ;;
esac
"${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}/bin/llvm-strip" --strip-unneeded "${INSTALL}/lib/libtag.a"
ls -lh "${INSTALL}/lib/libtag.a"
