# Quickstart (target development)

## Environment

```bash
export JAVA_HOME="$HOME/.jdks/temurin-21"
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

`local.properties` (git-ignored) points to `sdk.dir` and the fontTools Python.

## Build FFmpeg artifacts

```bash
cd /home/amwolfstein/Documents/GitHub/SistrumIDK
bash scripts/build-ffmpeg-android.sh arm64-v8a
```

Current baseline produces shared LGPL FFmpeg 9.0.2 libs for arm64-v8a. Analysis tasks rebuild with required filters; record flags/size.

## Build and test

```bash
./gradlew :androidApp:assembleDevDebug
./gradlew :androidApp:testDevDebugUnitTest :sharedLogic:testAndroidHostTest
./gradlew :androidApp:connectedDevDebugAndroidTest   # arm64 device/emulator
```

APK: `androidApp/build/outputs/apk/dev/debug/androidApp-dev-debug.apk`.

## Install

```bash
adb install -r androidApp/build/outputs/apk/dev/debug/androidApp-dev-debug.apk
```

## Workflow

```bash
python3 workflow/work.py status
python3 workflow/codegraph/code_tools.py update
python3 workflow/work.py task reserve --mission M001 --title "..."
python3 workflow/work.py graph record --task <ID> --query ... --impact ...
python3 workflow/work.py task complete <ID> --reviewer ... --round 1 --checks "..."
python3 workflow/work.py validate
```

Full CGC reindex may exceed the adapter timeout on this repo; run the pinned CLI directly and rebuild the snapshot, recording the limitation.
