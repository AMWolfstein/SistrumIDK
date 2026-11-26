#!/usr/bin/env bash
# Build the pinned FFTW3F and aubio versions used by Airmedy Desktop.
# The output is generated under androidApp/build/analysis.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK_DIR="$(awk '/^sdk[.]dir[[:blank:]]*=/ {
    sub(/^sdk[.]dir[[:blank:]]*=[[:blank:]]*/, "")
    sub(/\r$/, "")
    print
    exit
}' "$ROOT/local.properties" 2>/dev/null || true)"
SDK_DIR="${SDK_DIR:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
NDK="$SDK_DIR/ndk/30.0.16248370"
HOST_OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
TRIPLE=aarch64-linux-android
API=31
TOOLCHAIN=""
for candidate in "$NDK/toolchains/llvm/prebuilt/$HOST_OS-$(uname -m)" "$NDK/toolchains/llvm/prebuilt/$HOST_OS-"*; do
    if [[ -x "$candidate/bin/${TRIPLE}${API}-clang" ]]; then
        TOOLCHAIN="$candidate"
        break
    fi
done
if [[ -z "$TOOLCHAIN" ]]; then
    echo "Android NDK 30.0.16248370 with a ${HOST_OS} toolchain is required at ${NDK}." >&2
    exit 1
fi
WORK="$(mktemp -d "${TMPDIR:-/tmp}/sistrum-analysis.XXXXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT
verify_sha256() {
    if command -v sha256sum >/dev/null; then
        echo "$1  $2" | sha256sum -c -
    else
        echo "$1  $2" | shasum -a 256 -c -
    fi
}
PREFIX="$WORK/install"
mkdir -p "$WORK/fftw" "$WORK/aubio" "$WORK/shim"
curl --fail --location --retry 3 https://www.fftw.org/fftw-3.3.11.tar.gz -o "$WORK/fftw.tar.gz"
verify_sha256 "5630c24cdeb33b131612f7eb4b1a9934234754f9f388ff8617458d0be6f239a1" "$WORK/fftw.tar.gz"
curl --fail --location --retry 3 https://files.pythonhosted.org/packages/cd/80/302d89240603e5347c7f8026c8b02c59f8dfaec66c91a743d82de7c86006/aubio-0.4.9.tar.gz -o "$WORK/aubio.tar.gz"
verify_sha256 "df1244f6c4cf5bea382c8c2d35aa43bc31f4cf631fe325ae3992c219546a4202" "$WORK/aubio.tar.gz"
tar -xzf "$WORK/fftw.tar.gz" -C "$WORK/fftw" --strip-components=1
tar -xzf "$WORK/aubio.tar.gz" -C "$WORK/aubio" --strip-components=1
export CC="$TOOLCHAIN/bin/${TRIPLE}${API}-clang"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export CFLAGS="-O2 -fPIC"
(
    cd "$WORK/fftw"
    ./configure --host="$TRIPLE" --prefix="$PREFIX" --disable-shared --enable-static --with-pic --enable-single --disable-fortran --disable-doc
    make -j"${SISTRUM_BUILD_JOBS:-2}"
    make install
)
cat > "$WORK/shim/imp.py" <<'PY'
import importlib.util
import types
def new_module(name):
    return types.ModuleType(name)
def load_source(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
PY
python3 - "$WORK/aubio/waflib" <<'PYCODE'
import pathlib
import sys
for path in pathlib.Path(sys.argv[1]).rglob("*.py"):
    source = path.read_text()
    updated = source.replace("'rU'", "'r'")
    if updated != source:
        path.write_text(updated)
PYCODE
mkdir -p "$WORK/pkgconfig"
cat > "$WORK/pkgconfig/fftw3f.pc" <<PC
prefix=$PREFIX
libdir=\${prefix}/lib
includedir=\${prefix}/include
Name: fftw3f
Description: FFTW single precision
Version: 3.3.11
Libs: -L\${libdir} -lfftw3f
Cflags: -I\${includedir}
PC
(
    cd "$WORK/aubio"
    export PYTHONPATH="$WORK/shim${PYTHONPATH:+:$PYTHONPATH}"
    export CFLAGS="-O2 -fPIC -I$PREFIX/include"
    export LDFLAGS="-L$PREFIX/lib"
    export PKG_CONFIG_PATH="$WORK/pkgconfig"
    python3 ./waf configure build --enable-fftw3f --disable-intelipp --disable-accelerate \
        --disable-sndfile --disable-samplerate --disable-jack --disable-avcodec --disable-blas \
        --disable-docs --disable-tests --disable-examples --notests --prefix="$PREFIX"
)
LIB="$(find "$WORK/aubio/build" -name libaubio.a -print -quit)"
test -f "$LIB"
"$TOOLCHAIN/bin/llvm-nm" "$LIB" > "$WORK/aubio-symbols.txt"
grep -q fftwf_ "$WORK/aubio-symbols.txt"
OUT="$ROOT/androidApp/build/analysis"
mkdir -p "$OUT/include/aubio" "$OUT/lib"
cp "$PREFIX/include/fftw3.h" "$OUT/include/"
cp "$PREFIX/lib/libfftw3f.a" "$OUT/lib/"
cp "$LIB" "$OUT/lib/"
(cd "$WORK/aubio/src" && find . -name '*.h' -print0 | while IFS= read -r -d '' h; do mkdir -p "$OUT/include/aubio/$(dirname "$h")"; cp "$h" "$OUT/include/aubio/$h"; done)
echo "Analysis libraries generated under $OUT"
