#!/usr/bin/env python3
"""Compare both analyzers with the same FFmpeg 9/aubio/FFTW host build.

Usage: python3 tools/analysis/verify_airmedy_parity.py HOST_BUILD ORIGINAL_HEADER SCRATCH
HOST_BUILD is the host install prefix and must include headers and static archives.
ORIGINAL_HEADER is the unchanged pinned Airmedy ffmpeg_analyzer.h.
"""
import math
import pathlib
import struct
import subprocess
import sys
import wave

root = pathlib.Path(__file__).resolve().parents[2]
prefix, upstream, scratch = map(pathlib.Path, sys.argv[1:])
cpp = root / "androidApp/src/main/cpp"
harness = root / "tools/analysis/reference_harness.c"
scratch.mkdir(parents=True, exist_ok=True)

def run(*command):
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout.strip()

archives = [prefix / "lib" / f"lib{x}.a" for x in ("avfilter", "avformat", "avcodec", "swresample", "avutil", "aubio", "fftw3f")]
common = ["cc", "-O2", "-I" + str(prefix / "include"), "-I" + str(cpp)]
libraries = [str(p) for p in archives] + ["-lm", "-lpthread", "-lz"]
original = scratch / "original"
adapted = scratch / "adapted"
run(*common, "-I" + str(upstream.parent), str(harness), "-o", str(original), *libraries)
run(*common, "-DSISTRUM_ADAPTED", str(harness), str(cpp / "airmedy_analysis_bridge.c"), str(cpp / "ffmpeg_shared_mu.c"), "-o", str(adapted), *libraries)

fixtures = []
for index, bpm in enumerate((90, 120, 150)):
    wav = scratch / f"source-{bpm}.wav"
    rate = 44100
    with wave.open(str(wav), "wb") as out:
        out.setnchannels(2)
        out.setsampwidth(2)
        out.setframerate(rate)
        chunk = bytearray()
        for i in range(rate * 12):
            beat = i % (rate * 60 // bpm)
            envelope = max(0.0, 1.0 - beat / 1800.0)
            left = .12 * math.sin(2 * math.pi * (220 + 55 * index) * i / rate) + .5 * envelope * math.sin(2 * math.pi * 1100 * i / rate)
            right = .08 * math.sin(2 * math.pi * (330 + 55 * index) * i / rate) + .35 * envelope * math.sin(2 * math.pi * 700 * i / rate)
            chunk.extend(struct.pack("<hh", int(left * 32767), int(right * 32767)))
        out.writeframes(chunk)
    codecs = (("mp3", "libmp3lame"), ("m4a", "aac"), ("alac.m4a", "alac"),
              ("flac", "flac"), ("opus", "libopus"), ("wav", "pcm_s16le"))
    for suffix, codec in codecs:
        output = scratch / f"fixture-{bpm}.{suffix}"
        run("ffmpeg", "-nostdin", "-v", "error", "-y", "-i", str(wav), "-c:a", codec, str(output))
        fixtures.append(output)

silence = scratch / "silence.wav"
with wave.open(str(silence), "wb") as out:
    out.setnchannels(1)
    out.setsampwidth(2)
    out.setframerate(44100)
    out.writeframes(bytes(44100 * 3 * 2))
fixtures.append(silence)

rows = []
references = []
for fixture in fixtures:
    reference = [float(v) for v in run(str(original), str(fixture)).split(",")]
    actual = [float(v) for v in run(str(adapted), str(fixture)).split(",")]
    if len(reference) != 12 or len(actual) != 12:
        raise AssertionError(f"{fixture.name}: expected 12 raw fields")
    for index, (expected, got) in enumerate(zip(reference, actual)):
        equal = (math.isfinite(expected) and math.isfinite(got) and math.isclose(expected, got, rel_tol=1e-7, abs_tol=1e-7)) or (math.isinf(expected) and expected == got)
        if not equal:
            raise AssertionError(f"{fixture.name} field {index}: upstream={expected} android={got}")
    if all(math.isfinite(value) for value in actual):
        rows.append(actual)
        references.append(reference)
    print(f"{fixture.name}: 12 raw fields match")
def percentile(values, p):
    values = sorted(values)
    rank = p * (len(values) - 1)
    lo = int(rank)
    hi = min(lo + 1, len(values) - 1)
    return values[lo] + (rank - lo) * (values[hi] - values[lo])

def mood(row, corpus):
    def normalize(value, index):
        values = [item[index] for item in corpus]
        p1, p5, p50, p95, p99 = [percentile(values, p) for p in (.01, .05, .5, .95, .99)]
        spread = (p95 - p5) / 2
        return .5 if spread == 0 else 1 / (1 + math.exp(-2.5 * (max(p1, min(p99, value)) - p50) / spread))
    rms, crest, centroid, flux, tempo, onset, loudness_range = (row[i] for i in (3, 4, 5, 6, 7, 8, 2))
    r = normalize(rms, 3)
    c = normalize(centroid, 5)
    f = normalize(flux, 6)
    t = normalize(min(tempo, 180), 7)
    peak = normalize(crest, 4)
    interval = normalize(onset, 8)
    loudness = normalize(loudness_range, 2)
    score = 0 if tempo <= 60 or tempo >= 180 else (tempo - 60) / 55 if tempo <= 115 else (180 - tempo) / 65
    return (.32 * r + .23 * c + .17 * f + .18 * t + .10 * (1 - peak),
            .45 * score + .30 * (1 - interval) + .15 * (1 - peak) + .10 * (1 - loudness), c)

for actual, reference in zip(rows, references):
    for value, expected in zip(mood(actual, rows), mood(reference, references)):
        if not math.isclose(value, expected, rel_tol=1e-7, abs_tol=1e-7):
            raise AssertionError(f"mood mismatch: {value} != {expected}")
priority = scratch / "probe-priority"
run(*common, str(root / "tools/analysis/probe_priority_harness.c"),
    str(cpp / "airmedy_analysis_bridge.c"), str(cpp / "ffmpeg_shared_mu.c"), "-o", str(priority), *libraries)
print(run(str(priority), str(fixtures[0])))
print(f"PASS: {len(fixtures)} files; raw features and corpus mood match pinned Airmedy implementation")
