#include <jni.h>
#include <unistd.h>
#include "airmedy_analysis_bridge.h"

namespace {
void fail(JNIEnv *env, const char *message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_amwolfstein_sistrum_analysis_OfflineAudioDecoder_nativeCreate(JNIEnv *env, jclass) {
    auto *state = sistrum_analysis_create();
    if (!state) fail(env, "Unable to allocate audio analyzer");
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT void JNICALL
Java_com_amwolfstein_sistrum_analysis_OfflineAudioDecoder_nativeOpen(JNIEnv *env, jclass, jlong handle, jint fd) {
    if (sistrum_analysis_open(reinterpret_cast<SistrumAnalysis *>(handle), fd) != 0)
        fail(env, "Unable to open audio for analysis");
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_amwolfstein_sistrum_analysis_OfflineAudioDecoder_nativeAnalyze(JNIEnv *env, jclass, jlong handle) {
    double values[12];
    if (sistrum_analysis_run(reinterpret_cast<SistrumAnalysis *>(handle), values) != 0) {
        fail(env, "Audio analysis failed");
        return nullptr;
    }
    jdoubleArray result = env->NewDoubleArray(12);
    if (result) env->SetDoubleArrayRegion(result, 0, 12, values);
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_amwolfstein_sistrum_analysis_OfflineAudioDecoder_nativeReplayGain(JNIEnv *env, jclass, jlong handle) {
    double values[3];
    if (sistrum_analysis_replaygain(reinterpret_cast<SistrumAnalysis *>(handle), values) != 0) {
        fail(env, "ReplayGain analysis failed");
        return nullptr;
    }
    jdoubleArray result = env->NewDoubleArray(3);
    if (result) env->SetDoubleArrayRegion(result, 0, 3, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_amwolfstein_sistrum_analysis_OfflineAudioDecoder_nativeCancel(JNIEnv *, jclass, jlong handle) {
    sistrum_analysis_cancel(reinterpret_cast<SistrumAnalysis *>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_amwolfstein_sistrum_analysis_OfflineAudioDecoder_nativeClose(JNIEnv *, jclass, jlong handle) {
    sistrum_analysis_destroy(reinterpret_cast<SistrumAnalysis *>(handle));
}
