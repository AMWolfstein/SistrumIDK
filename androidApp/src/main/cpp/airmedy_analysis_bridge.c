#include "airmedy_analysis_bridge.h"
#include "airmedy_analyzer.h"
#include <stdio.h>
#include <unistd.h>

struct SistrumAnalysis {
    atomic_int cancelled;
    int fd;
};

SistrumAnalysis *sistrum_analysis_create(void) {
    SistrumAnalysis *state = calloc(1, sizeof(*state));
    if (state) {
        atomic_init(&state->cancelled, 0);
        state->fd = -1;
    }
    return state;
}

int sistrum_analysis_open(SistrumAnalysis *state, int fd) {
    if (!state || state->fd >= 0 || fd < 0) {
        if (fd >= 0) close(fd);
        return -1;
    }
    state->fd = fd;
    return 0;
}

int sistrum_analysis_run(SistrumAnalysis *state, double output[12]) {
    if (!state || state->fd < 0) return -1;
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", state->fd);
    FFAnalysisResult result;
    int status;
    do {
        status = ffmpeg_analyze(path, &result, &state->cancelled,
            FFA_COMPONENT_FFMPEG | FFA_COMPONENT_AUBIO);
        if (status == FFA_ERR_YIELDED) {
            while (sistrum_playback_probe_waiting() && !atomic_load(&state->cancelled))
                av_usleep(10000);
        }
    } while (status == FFA_ERR_YIELDED && !atomic_load(&state->cancelled));
    if (status != FFA_OK) return status;
    output[0] = result.loudness_lufs;
    output[1] = result.true_peak;
    output[2] = result.loudness_range;
    output[3] = result.rms;
    output[4] = result.crest;
    output[5] = result.spectral_centroid;
    output[6] = result.spectral_flux;
    output[7] = result.tempo;
    output[8] = result.onset_variance;
    output[9] = result.spectral_rolloff;
    output[10] = result.spectral_flatness;
    output[11] = result.zcr;
    return 0;
}

int sistrum_analysis_replaygain(SistrumAnalysis *state, double output[3]) {
    if (!state || state->fd < 0) return -1;
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", state->fd);
    FFAnalysisResult result;
    int status;
    do {
        status = ffmpeg_analyze(path, &result, &state->cancelled,
            FFA_COMPONENT_FFMPEG | FFA_COMPONENT_REPLAYGAIN);
        if (status == FFA_ERR_YIELDED) {
            while (sistrum_playback_probe_waiting() && !atomic_load(&state->cancelled))
                av_usleep(10000);
        }
    } while (status == FFA_ERR_YIELDED && !atomic_load(&state->cancelled));
    if (status != FFA_OK) return status;
    output[0] = result.loudness_lufs;
    output[1] = result.replaygain_sample_peak;
    output[2] = result.true_peak;
    return 0;
}

void sistrum_analysis_cancel(SistrumAnalysis *state) {
    if (state) atomic_store(&state->cancelled, 1);
}

void sistrum_analysis_destroy(SistrumAnalysis *state) {
    if (!state) return;
    if (state->fd >= 0) close(state->fd);
    free(state);
}
