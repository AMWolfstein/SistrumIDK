#include <jni.h>

extern "C" {
#include <aubio/aubio.h>
}

/**
 * Link probe for the approved analysis stack: creating and destroying a tempo object exercises
 * aubio's tempo/onset/FFT code paths and the static FFTW3f backend.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_amwolfstein_sistrum_player_AnalysisBridge_nativeTempoProbe(JNIEnv *, jobject) {
    aubio_tempo_t *tempo = new_aubio_tempo("default", 1024, 512, 44100);
    if (tempo == nullptr) return JNI_FALSE;
    fvec_t *input = new_fvec(512);
    fvec_t *output = new_fvec(2);
    for (int i = 0; i < 32; ++i) {
        aubio_tempo_do(tempo, input, output);
    }
    del_fvec(output);
    del_fvec(input);
    del_aubio_tempo(tempo);
    return JNI_TRUE;
}
