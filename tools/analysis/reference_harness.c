/* Host oracle for the pinned Airmedy analyzer and the Android C bridge. */
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <pthread.h>

#ifndef SISTRUM_ADAPTED
pthread_mutex_t ffmpeg_probe_mu = PTHREAD_MUTEX_INITIALIZER;
#endif

#ifdef SISTRUM_ADAPTED
#include "airmedy_analysis_bridge.h"
#include <fcntl.h>
#else
#include "ffmpeg_analyzer.h"
#endif

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    double values[12];
#ifdef SISTRUM_ADAPTED
    SistrumAnalysis *state = sistrum_analysis_create();
    int fd = open(argv[1], O_RDONLY);
    if (!state || fd < 0 || sistrum_analysis_open(state, fd)) return 3;
    int status = sistrum_analysis_run(state, values);
    sistrum_analysis_destroy(state);
#else
    FFAnalysisResult result;
    volatile int cancelled = 0;
    int status = ffmpeg_analyze(argv[1], &result, &cancelled,
        FFA_COMPONENT_FFMPEG | FFA_COMPONENT_AUBIO);
    values[0] = result.loudness_lufs;
    values[1] = result.true_peak;
    values[2] = result.loudness_range;
    values[3] = result.rms;
    values[4] = result.crest;
    values[5] = result.spectral_centroid;
    values[6] = result.spectral_flux;
    values[7] = result.tempo;
    values[8] = result.onset_variance;
    values[9] = result.spectral_rolloff;
    values[10] = result.spectral_flatness;
    values[11] = result.zcr;
#endif
    if (status) { fprintf(stderr, "analysis error %d\n", status); return 4; }
    for (int i = 0; i < 12; i++) printf(i ? ",%.17g" : "%.17g", values[i]);
    putchar('\n');
    return 0;
}
