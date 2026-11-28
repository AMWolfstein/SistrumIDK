/* Golden-output harness for Airmedy's in-process analyzer.
 * Includes the upstream header verbatim so the numbers come from the same
 * code path as the desktop producer. Emits one JSON object per file. */
#include <stdio.h>
#include <string.h>
#include <libavutil/avutil.h>
#include <libavfilter/avfilter.h>
#include <libavutil/log.h>
#include "ffmpeg_analyzer.h"

int main(int argc, char **argv) {
    av_log_set_level(AV_LOG_QUIET);
    if (argc < 2) {
        fprintf(stderr, "usage: %s <file>...\n", argv[0]);
        return 2;
    }
    printf("{\"harness\":{\"ffmpeg\":\"%s\",\"avfilter\":\"%u.%u.%u\",\"analyzer_version\":4}}\n",
           av_version_info(), LIBAVFILTER_VERSION_MAJOR, LIBAVFILTER_VERSION_MINOR,
           LIBAVFILTER_VERSION_MICRO);
    for (int i = 1; i < argc; i++) {
        volatile int cancel = 0;
        FFAnalysisResult r;
        int rc = ffmpeg_analyze(argv[i], &r, &cancel,
                                FFA_COMPONENT_FFMPEG | FFA_COMPONENT_AUBIO);
        printf("{\"file\":\"%s\",\"rc\":%d,"
               "\"loudness_lufs\":%.6f,\"loudness_range\":%.6f,\"true_peak\":%.6f,"
               "\"rms\":%.6f,\"crest\":%.6f,\"spectral_centroid\":%.6f,"
               "\"spectral_rolloff\":%.6f,\"spectral_flatness\":%.9f,"
               "\"spectral_flux\":%.6f,\"zcr\":%.6f,\"tempo\":%.6f,"
               "\"onset_variance\":%.9f}\n",
               argv[i], rc, r.loudness_lufs, r.loudness_range, r.true_peak,
               r.rms, r.crest, r.spectral_centroid, r.spectral_rolloff,
               r.spectral_flatness, r.spectral_flux, r.zcr, r.tempo,
               r.onset_variance);
    }
    return 0;
}
