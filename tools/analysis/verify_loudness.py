#!/usr/bin/env python3
"""Compare the bundled EBU meter with the host FFmpeg ebur128 filter.

Requires Python 3, a C compiler, and FFmpeg. Fixtures are generated in memory.
The Android decoder is covered by separate device instrumentation tests.
"""
import array
import math
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LIBRARY = ROOT / "androidApp/src/main/cpp/third_party/libebur128/ebur128.c"
HARNESS = ROOT / "tools/analysis/ebur_meter.c"
RATE = 48_000
SECONDS = 8


def fixture(name):
    samples = array.array("f")
    for index in range(RATE * SECONDS):
        signal = 0.0625 * math.sin(2 * math.pi * 1000 * index / RATE)
        if name == "mono_downmix":
            signal /= math.sqrt(2)
        elif name == "gated":
            signal *= 1 if index < RATE * 3 else 0.01 if index < RATE * 6 else 0
        elif name == "transients":
            signal += 0.35 if index % 12_000 == 0 else 0
        elif name == "silence":
            signal = 0
        samples.extend((signal, signal))
    if samples.itemsize != 4:
        raise RuntimeError("A 32-bit native float is required")
    return samples.tobytes()


def run():
    with tempfile.TemporaryDirectory(prefix="sistrum-ebur-") as directory:
        meter = Path(directory) / "meter"
        subprocess.run(["cc", "-O2", str(HARNESS), str(LIBRARY), "-lm", "-o", str(meter)], check=True)
        for name in ("stereo_sine", "mono_downmix", "gated", "transients", "silence"):
            data = fixture(name)
            measured = subprocess.run([str(meter)], input=data, capture_output=True, check=True)
            lufs, peak, _ = map(float, measured.stdout.decode().split())
            reference = subprocess.run(
                ["ffmpeg", "-hide_banner", "-nostats", "-f", "f32le", "-ar", str(RATE), "-ac", "2",
                 "-i", "pipe:0", "-filter_complex", "ebur128=peak=true", "-f", "null", "-"],
                input=data, capture_output=True, check=True,
            ).stderr.decode().rsplit("Summary:", 1)[-1]
            reference_lufs = float(re.search(r"I:\s*([-\d.]+) LUFS", reference).group(1))
            if name == "silence":
                assert not math.isfinite(lufs) and not math.isfinite(peak)
                print(f"{name}: silence rejected, FFmpeg display floor {reference_lufs:.1f} LUFS")
                continue
            reference_peak = float(re.search(r"Peak:\s*([-\d.]+) dBFS", reference).group(1))
            assert abs(lufs - reference_lufs) <= 0.2, (name, lufs, reference_lufs)
            assert abs(peak - reference_peak) <= 0.2, (name, peak, reference_peak)
            print(f"{name}: {lufs:.2f} LUFS / {peak:.2f} dBTP, FFmpeg {reference_lufs:.1f} / {reference_peak:.1f}")


if __name__ == "__main__":
    run()
