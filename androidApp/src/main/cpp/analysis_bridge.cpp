#include <jni.h>

#include "analysis_graph.h"
#include "fd_io.h"

#include <vector>

namespace {
constexpr int kFieldCount = 12;
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_amwolfstein_sistrum_player_AnalysisBridge_nativeTempoProbe(JNIEnv *, jobject) {
    aubio_tempo_t *tempo = new_aubio_tempo("default", 1024, 512, 44100);
    if (tempo == nullptr) return JNI_FALSE;
    fvec_t *input = new_fvec(512);
    fvec_t *output = new_fvec(2);
    for (int i = 0; i < 32; ++i) aubio_tempo_do(tempo, input, output);
    del_fvec(output);
    del_fvec(input);
    del_aubio_tempo(tempo);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_amwolfstein_sistrum_player_AnalysisBridge_nativeAnalyzeFd(
    JNIEnv *env, jobject, jint fd, jlong startMs, jlong endMs) {
    sistrum::FdMedia media;
    media.fd = fd;
    std::string error;
    if (!sistrum::fd_open_format(media, error)) {
        sistrum::fd_media_release(media);
        return env->NewFloatArray(0);
    }
    sistrum::RawAnalysis analysis;
    const bool ok = sistrum::analyze_media(media, startMs, endMs, analysis, error);
    sistrum::fd_media_release(media);
    if (!ok) return env->NewFloatArray(0);

    const float values[kFieldCount] = {
        static_cast<float>(analysis.loudnessLufs),
        static_cast<float>(analysis.loudnessRange),
        static_cast<float>(analysis.truePeak),
        static_cast<float>(analysis.rms),
        static_cast<float>(analysis.crest),
        static_cast<float>(analysis.spectralCentroid),
        static_cast<float>(analysis.spectralRolloff),
        static_cast<float>(analysis.spectralFlatness),
        static_cast<float>(analysis.spectralFlux),
        static_cast<float>(analysis.zcr),
        static_cast<float>(analysis.tempo),
        static_cast<float>(analysis.onsetVariance),
    };
    jfloatArray result = env->NewFloatArray(kFieldCount);
    env->SetFloatArrayRegion(result, 0, kFieldCount, values);
    return result;
}
