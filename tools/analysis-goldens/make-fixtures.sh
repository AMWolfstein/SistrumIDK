#!/usr/bin/env bash
# Builds the deterministic analysis fixture corpus.
#
# Every input is generated from math, so the corpus is reproducible, carries no
# personal music and has no third-party licence. The album fixtures differ in
# duration, loudness and dynamics so album loudness/normalization can be
# compared with the producer.
#
# Usage: bash tools/analysis-goldens/make-fixtures.sh <output-dir>
set -euo pipefail
OUT="${1:?output dir}"
mkdir -p "$OUT"
cd "$OUT"

FF="ffmpeg -hide_banner -loglevel error -y"

# --- level/tone cases -------------------------------------------------------
$FF -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=3" \
    -af "volume=-6dB" -ac 2 -c:a pcm_s16le -flags +bitexact -fflags +bitexact tone-440-3s.wav
$FF -f lavfi -i "sine=frequency=1000:sample_rate=48000:duration=5" \
    -af "volume=-12dB" -ac 2 -c:a flac -sample_fmt s32 -bits_per_raw_sample 24 \
    -flags +bitexact -fflags +bitexact tone-1k-5s-48k-24bit.flac
$FF -f lavfi -i "sine=frequency=220:sample_rate=44100:duration=2" \
    -ac 1 -c:a pcm_s16le -flags +bitexact tone-220-2s-mono.wav

# --- silence / short / clipping --------------------------------------------
$FF -f lavfi -i "anullsrc=r=44100:cl=stereo" -t 2 \
    -c:a pcm_s16le -flags +bitexact silence-2s.wav
$FF -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=0.3" \
    -ac 2 -c:a pcm_s16le -flags +bitexact short-300ms.wav
$FF -f lavfi -i "sine=frequency=330:sample_rate=44100:duration=1" \
    -af "volume=12dB" -ac 2 -c:a pcm_s16le -flags +bitexact clipped-1s.wav

# --- tempo/onset ------------------------------------------------------------
$FF -f lavfi -i "sine=frequency=1000:sample_rate=44100:duration=12" \
    -af "volume='if(lt(mod(t,0.5),0.05),1,0)':eval=frame" \
    -ac 2 -c:a flac -flags +bitexact clicks-120bpm-12s.flac

# --- same 3 s tone in every supported container ----------------------------
BASE=(-f lavfi -i "sine=frequency=440:sample_rate=44100:duration=3" -af "volume=-6dB" -ac 2)
$FF "${BASE[@]}" -c:a libmp3lame -b:a 320k -flags +bitexact -fflags +bitexact tone.mp3
$FF "${BASE[@]}" -c:a aac -b:a 256k -flags +bitexact -fflags +bitexact tone.m4a
$FF "${BASE[@]}" -c:a libvorbis -q:a 6 -flags +bitexact -fflags +bitexact tone.ogg
$FF "${BASE[@]}" -c:a libopus -b:a 160k -flags +bitexact -fflags +bitexact tone.opus
$FF "${BASE[@]}" -c:a wavpack -flags +bitexact -fflags +bitexact tone.wv
$FF "${BASE[@]}" -c:a pcm_s16be -flags +bitexact -fflags +bitexact tone.aiff

# --- multichannel -----------------------------------------------------------
$FF -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=2" \
    -af "volume=-9dB,pan=5.1|FL=c0|FR=c0|FC=c0|LFE=c0|BL=c0|BR=c0" \
    -c:a flac -flags +bitexact 5.1-48k.flac

# --- album set: different durations, levels and dynamics -------------------
$FF -f lavfi -i "sine=frequency=300:sample_rate=44100:duration=3" \
    -af "volume=-3dB" -ac 2 -c:a flac -flags +bitexact \
    -metadata title="Album Track 1" -metadata artist="Golden Artist" \
    -metadata album="Golden Album" -metadata album_artist="Golden Artist" \
    -metadata track="1/3" album-1.flac
$FF -f lavfi -i "sine=frequency=500:sample_rate=44100:duration=5" \
    -af "volume=-15dB" -ac 2 -c:a flac -flags +bitexact \
    -metadata title="Album Track 2" -metadata artist="Golden Artist" \
    -metadata album="Golden Album" -metadata album_artist="Golden Artist" \
    -metadata track="2/3" album-2.flac
$FF -f lavfi -i "sine=frequency=700:sample_rate=44100:duration=8" \
    -af "volume='if(lt(mod(t,2),1),0.0316,0.2512)':eval=frame" -ac 2 -c:a flac -flags +bitexact \
    -metadata title="Album Track 3" -metadata artist="Golden Artist" \
    -metadata album="Golden Album" -metadata album_artist="Golden Artist" \
    -metadata track="3/3" album-3.flac

# --- CUE sheet over the first album track ----------------------------------
cat > golden.cue <<'CUE'
FILE "album-1.flac" WAVE
  TRACK 01 AUDIO
    TITLE "Album Track 1"
    PERFORMER "Golden Artist"
    INDEX 01 00:00:00
  TRACK 02 AUDIO
    TITLE "Second Segment"
    PERFORMER "Golden Artist"
    INDEX 01 00:01:00
CUE

# Segment used to verify CUE bounds: exactly the PCM from 1 s onwards.
$FF -ss 1 -i album-1.flac -c:a flac -flags +bitexact segment-1s.flac

ls -la
