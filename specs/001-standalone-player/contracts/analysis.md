# Contract: local audio analysis

## Engine input/output

Input: local file descriptor/temp path, optional `cue_start_ms`/`cue_end_ms` segment (for CUE virtual tracks), cancel token, component versions.
Output (`TrackAnalysis`):

```text
loudnessLufs, loudnessRange, truePeakDbfs, rmsDbfs, crest,
spectralCentroid, spectralRolloff, spectralFlatness, spectralFlux, zcr,
tempoBpm, onsetVariance,
energy, danceability, brightness,
ffmpegVersion=1, tempoOnsetVersion=1, analyzerVersion=4, moodVersion=2,
audioFingerprint (pcm-sha256-v1 over decoded audio), contentKey, status
```

- LUFS/LRA from `ebur128=metadata=1:peak=true`.
- Spectral/ZCR/RMS/crest from `aspectralstats=measure=centroid+rolloff+flatness+flux` and `astats=metadata=1:reset=0`; avfilter filters exactly `ebur128,aspectralstats,astats,aresample,aformat,anull,abuffer,abuffersink`.
- Tempo/onset: aubio 0.4.9 tempo (`default`) and onset (`hfc`) with static FFTW3F, 44.1 kHz, window 1024, hop 512, ignore first 1024 samples for onsets, mean per-beat BPM, sample² onset variance.
- True peak: max per-channel linear peak → dBFS; fallback astats peak; document >8ch limitation.
- Analyze only the requested CUE segment (start/end) when present; no rounding before storage; doubles.
- Failure: no partial row; status FAILED at version; no features → normalization no-op.

## Mood derivation

- Percentiles: linear interpolation P1/P5/P50/P95/P99 over the valid corpus.
- `normalize(x) = sigmoid(2.5 * (clamp(x,P1,P99)-P50) / ((P95-P5)/2))`; zero spread → 0.5.
- Energy weights 0.32 rms, 0.23 centroid, 0.17 flux, 0.18 min(tempo,180), 0.10(1−crest).
- Danceability 0.45 tempoScore + 0.30(1−onsetVariance) + 0.15(1−crest) + 0.10(1−LRA).
- Brightness = normalized centroid; tempoScore 0≤60, 1 at 115, 0≥180.
- Percentile batch: 100 adds/deletes, 30 s debounce.

## Tag mapping

- ReplayGain mapping: MP3/FLAC/Vorbis use `REPLAYGAIN_TRACK_GAIN`, `REPLAYGAIN_TRACK_PEAK`, `REPLAYGAIN_ALBUM_GAIN`, `REPLAYGAIN_ALBUM_PEAK`; Opus uses `R128_TRACK_GAIN`/`R128_ALBUM_GAIN` (Q7.8 dB semantics); M4A uses the standard freeform ReplayGain fields. Correct reference/units, never overwrite by default.
- Namespaced/custom fields for measured LUFS, LRA, true peak, BPM, energy, danceability, brightness, onset variance, spectral features, versions, audio fingerprint.
- Audio fingerprint excludes tag bytes (decoded-PCM SHA-256, versioned), so tag edits do not invalidate reuse; the same value backs move/rename identity.
- Reuse requires matching versions + fingerprint; album membership changes invalidate album aggregates.
- Read-only/denied write: Room result only, visible status.

## Build

Cross-compile FFTW 3.3.11 single-precision static and aubio 0.4.9 (`--enable-fftw3f`, optional codecs/tools disabled) with the NDK clang for arm64; link `libfftw3f.a`/`libaubio.a` into `libsistrum_player.so`; add GPL notices. No dynamic `.so` dependency.

## Scheduling

- Bounded workers (default 2, max numCPU/2), playback throttle, 3 min timeout, resumable/cancellable.
- Never analyze on the playback realtime thread.
- Writes serialized per file; no scanner feedback loop.
