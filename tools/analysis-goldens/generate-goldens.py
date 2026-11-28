#!/usr/bin/env python3
"""Generates the golden reference for the analysis parity suite.

Runs the *upstream* Airmedy analyzer (its own `ffmpeg_analyzer.h`, compiled into
the `analyze` harness next to this script) over the deterministic fixture corpus
and writes `goldens.json` with producer metadata.

Album loudness is not a separate producer pass: Airmedy computes it as the
arithmetic mean of the album tracks' integrated LUFS
(internal/app/normalization/service.go, `albumLoudness`). The album case here is
derived from the same producer track values and records that reference.

Usage:
    bash tools/analysis-goldens/build-harness.sh <airmedy-source-dir>
    bash tools/analysis-goldens/make-fixtures.sh <fixtures-dir>
    python3 tools/analysis-goldens/generate-goldens.py <fixtures-dir> <harness> <out-json>
"""
import hashlib
import json
import math
import os
import re
import subprocess
import sys

AUDIO_EXTENSIONS = {"wav", "flac", "mp3", "m4a", "ogg", "opus", "wv", "aiff"}
FIELDS = [
    "loudness_lufs", "loudness_range", "true_peak", "rms", "crest",
    "spectral_centroid", "spectral_rolloff", "spectral_flatness",
    "spectral_flux", "zcr", "tempo", "onset_variance",
]


def clean(line):
    # FFmpeg prints digital-silence peaks as -inf; JSON needs null.
    return re.sub(r":[-+]?inf", ":null", line)


def run(harness, directory, files):
    output = subprocess.run(
        [harness] + [os.path.join(directory, name) for name in files],
        capture_output=True, text=True, check=True,
    ).stdout
    cases = []
    for line in output.splitlines():
        value = json.loads(clean(line))
        if "file" not in value:
            continue
        value["file"] = os.path.basename(value["file"])
        cases.append(value)
    return cases


def main():
    fixtures, harness, destination = sys.argv[1], sys.argv[2], sys.argv[3]
    names = sorted(
        name for name in os.listdir(fixtures)
        if name.rsplit(".", 1)[-1] in AUDIO_EXTENSIONS and not name.startswith("segment-")
    )
    cases = run(harness, fixtures, names)
    segment = run(harness, fixtures, ["segment-1s.flac"])[0]
    checksums = {
        name: hashlib.sha256(open(os.path.join(fixtures, name), "rb").read()).hexdigest()
        for name in sorted(os.listdir(fixtures))
        if name.rsplit(".", 1)[-1] in AUDIO_EXTENSIONS
    }

    by_name = {case["file"]: case for case in cases}
    album_tracks = [by_name[f"album-{index}.flac"] for index in (1, 2, 3)]
    album_loudness = sum(case["loudness_lufs"] for case in album_tracks) / len(album_tracks)
    album_peak = max(case["true_peak"] for case in album_tracks)

    golden = {
        "producer": {
            "repo": "https://github.com/misa198/airmedy",
            "revision": "d859940c0629907e377c889fb276904b6a7a4b8c",
            "ffmpeg": "9.0.2",
            "aubio": "0.4.9",
            "fftw": "3.3.11",
            "analyzerVersion": 4,
            "graph": "abuffer -> ebur128(metadata=1:peak=true) -> "
                     "aspectralstats(centroid+rolloff+flatness+flux) -> "
                     "astats(metadata=1:reset=0) -> abuffersink",
            "aubioConfig": "tempo=default, onset=hfc, buf=1024, hop=512, rate=44100",
            "albumLoudnessReference": "internal/app/normalization/service.go albumLoudness "
                                      "(arithmetic mean of track LUFS)",
        },
        "checksums": checksums,
        "cases": cases,
        "segmentCase": {
            "file": "album-1.flac",
            "startMs": 1000,
            "endMs": -1,
            "expected": {key: segment[key] for key in FIELDS},
        },
        "albumCase": {
            "albumArtists": ["Golden Artist"],
            "album": "Golden Album",
            "tracks": [case["file"] for case in album_tracks],
            "expected": {
                "loudnessLufs": album_loudness,
                "truePeak": album_peak,
                "trackCount": len(album_tracks),
                "replayGainAlbumGain": -18.0 - album_loudness,
                "r128AlbumGainQ78": round((-23.0 - album_loudness) * 256),
            },
        },
    }
    with open(destination, "w") as target:
        json.dump(golden, target, indent=1)
        target.write("\n")
    print(f"{len(cases)} cases, album loudness {album_loudness:.6f} LUFS")


if __name__ == "__main__":
    main()
