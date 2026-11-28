#!/usr/bin/env bash
# Builds static FFTW 3.3.11 and aubio 0.4.9 for the analysis bridge (arm64-v8a).
set -euo pipefail
FFTW_VERSION=3.3.11
FFTW_SHA=5630c24cdeb33b131612f7eb4b1a9934234754f9f388ff8617458d0be6f239a1
AUBIO_VERSION=0.4.9
AUBIO_SHA=d48282ae4dab83b3dc94c16cf011bcb63835c1c02b515490e1883049c3d1f3da
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SDK_DIR="$(awk '/^sdk[.]dir[[:blank:]]*=/{sub(/^sdk[.]dir[[:blank:]]*=[[:blank:]]*/,"");sub(/\r$/,"");print;exit}' "${REPO_ROOT}/local.properties")"
SDK_DIR="${SDK_DIR:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
NDK="${SDK_DIR}/ndk/30.0.16248370"
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"
API=31
TRIPLE="aarch64-linux-android"
BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sistrum-aubio.XXXXXXXX")"
trap 'rm -rf -- "${BUILD_DIR}"' EXIT
OUT="${REPO_ROOT}/androidApp/src/main/cpp/third_party/aubio/arm64-v8a"
mkdir -p "${OUT}/lib" "${OUT}/include"

curl -fsSL "https://fftw.org/fftw-${FFTW_VERSION}.tar.gz" -o "${BUILD_DIR}/fftw.tar.gz"
echo "${FFTW_SHA}  ${BUILD_DIR}/fftw.tar.gz" | sha256sum -c -
mkdir -p "${BUILD_DIR}/fftw" && tar -xzf "${BUILD_DIR}/fftw.tar.gz" -C "${BUILD_DIR}/fftw" --strip-components=1
cd "${BUILD_DIR}/fftw"
./configure --host="${TRIPLE}" --prefix="${BUILD_DIR}/fftw-install" \
  --enable-float --disable-shared --enable-static --disable-doc --disable-fortran \
  CC="${TOOLCHAIN}/bin/${TRIPLE}${API}-clang" AR="${TOOLCHAIN}/bin/llvm-ar" \
  RANLIB="${TOOLCHAIN}/bin/llvm-ranlib" CFLAGS="-Oz -fPIC"
make -j"$(nproc)" && make install

curl -fsSL "https://aubio.org/pub/aubio-${AUBIO_VERSION}.tar.bz2" -o "${BUILD_DIR}/aubio.tar.bz2"
echo "${AUBIO_SHA}  ${BUILD_DIR}/aubio.tar.bz2" | sha256sum -c -
mkdir -p "${BUILD_DIR}/aubio" && tar -xjf "${BUILD_DIR}/aubio.tar.bz2" -C "${BUILD_DIR}/aubio" --strip-components=1
cd "${BUILD_DIR}/aubio"
# The bundled waf predates Python 3.11/3.12 (removed `imp` module and "rU" mode).
sed -i 's/import os,re,imp,sys/import os,re,sys,types/; s/module=imp.new_module(WSCRIPT_FILE)/module=types.ModuleType(WSCRIPT_FILE)/' waflib/Context.py
sed -i "s/def readf(fname,m='r',encoding='latin-1'):/def readf(fname,m='r',encoding='latin-1'):\n\tm=m.replace('U','')/" waflib/Utils.py

CC="${TOOLCHAIN}/bin/${TRIPLE}${API}-clang" AR="${TOOLCHAIN}/bin/llvm-ar" \
RANLIB="${TOOLCHAIN}/bin/llvm-ranlib" CFLAGS="-Oz -fPIC -I${BUILD_DIR}/fftw-install/include" \
LDFLAGS="-L${BUILD_DIR}/fftw-install/lib" PKG_CONFIG_PATH="${BUILD_DIR}/fftw-install/lib/pkgconfig" \
./waf configure --with-target-platform=android --enable-fftw3f --disable-intelipp \
  --disable-accelerate --disable-complex --disable-jack --disable-sndfile --disable-samplerate \
  --disable-avcodec --disable-blas --disable-atlas --disable-wavread --disable-wavwrite \
  --disable-docs --disable-tests --disable-examples --prefix="${BUILD_DIR}/aubio-install"
./waf build -j"$(nproc)" && ./waf install

cp -L "${BUILD_DIR}/fftw-install/lib/libfftw3f.a" "${OUT}/lib/"
cp -L "${BUILD_DIR}/aubio-install/lib/libaubio.a" "${OUT}/lib/"
cp -L "${BUILD_DIR}/fftw-install/include/fftw3.h" "${OUT}/include/"
rm -rf "${OUT}/include/aubio"
cp -R "${BUILD_DIR}/aubio-install/include/aubio" "${OUT}/include/"
echo "==> aubio/FFTW written to ${OUT}"
