#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <chrono>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libswresample/swresample.h>
#include "third_party/libebur128/ebur128.h"
}

namespace {
constexpr int kSampleRate = 48000;
constexpr int kChannels = 2;
std::mutex meter_mutex;

void fail(JNIEnv *env, const char *message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message);
}

struct Decoder {
    AVFormatContext *format = nullptr;
    AVCodecContext *codec = nullptr;
    AVPacket *packet = nullptr;
    AVFrame *frame = nullptr;
    SwrContext *resampler = nullptr;
    ebur128_state *meter = nullptr;
    int stream = -1;
    bool demux_finished = false;
    bool flush_sent = false;
    bool decoder_finished = false;
    bool resampler_finished = false;
    std::atomic<bool> cancelled{false};
    bool probing = true;
    std::chrono::steady_clock::time_point probe_deadline =
        std::chrono::steady_clock::now() + std::chrono::seconds(20);
    std::vector<float> pending;
    size_t pending_offset = 0;

    ~Decoder() {
        if (meter != nullptr) {
            std::lock_guard<std::mutex> guard(meter_mutex);
            ebur128_destroy(&meter);
        }
        swr_free(&resampler);
        av_frame_free(&frame);
        av_packet_free(&packet);
        avcodec_free_context(&codec);
        avformat_close_input(&format);
    }
};

int interrupt(void *opaque) {
    auto *decoder = static_cast<Decoder *>(opaque);
    return decoder->cancelled.load(std::memory_order_acquire) ||
        (decoder->probing && std::chrono::steady_clock::now() > decoder->probe_deadline) ? 1 : 0;
}

bool open_decoder(Decoder &d, int fd) {
    d.probe_deadline = std::chrono::steady_clock::now() + std::chrono::seconds(20);
    d.format = avformat_alloc_context();
    if (!d.format) return false;
    d.format->interrupt_callback = AVIOInterruptCB{interrupt, &d};
    const std::string path = "/proc/self/fd/" + std::to_string(fd);
    const int opened = avformat_open_input(&d.format, path.c_str(), nullptr, nullptr);
    if (opened < 0 || avformat_find_stream_info(d.format, nullptr) < 0) return false;
    d.probing = false;
    d.stream = av_find_best_stream(d.format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (d.stream < 0) return false;
    const AVCodecParameters *params = d.format->streams[d.stream]->codecpar;
    const AVCodec *decoder = avcodec_find_decoder(params->codec_id);
    if (!decoder) return false;
    d.codec = avcodec_alloc_context3(decoder);
    if (!d.codec || avcodec_parameters_to_context(d.codec, params) < 0) return false;
    d.codec->thread_count = 1;
    if (avcodec_open2(d.codec, decoder, nullptr) < 0) return false;
    AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO;
    if (swr_alloc_set_opts2(&d.resampler, &stereo, AV_SAMPLE_FMT_FLT, kSampleRate,
            &d.codec->ch_layout, d.codec->sample_fmt, d.codec->sample_rate, 0, nullptr) < 0 ||
            swr_init(d.resampler) < 0) return false;
    d.packet = av_packet_alloc();
    d.frame = av_frame_alloc();
    if (!d.packet || !d.frame) return false;
    {
        std::lock_guard<std::mutex> guard(meter_mutex);
        d.meter = ebur128_init(kChannels, kSampleRate,
            EBUR128_MODE_I | EBUR128_MODE_LRA | EBUR128_MODE_TRUE_PEAK | EBUR128_MODE_HISTOGRAM);
    }
    return d.meter != nullptr;
}

bool append_pcm(Decoder &d, const uint8_t **input, int input_frames, int capacity) {
    if (capacity <= 0 || capacity > 1'000'000) return false;
    d.pending.resize(static_cast<size_t>(capacity) * kChannels);
    uint8_t *out[] = {reinterpret_cast<uint8_t *>(d.pending.data())};
    const int frames = swr_convert(d.resampler, out, capacity, input, input_frames);
    if (frames < 0) return false;
    d.pending.resize(static_cast<size_t>(frames) * kChannels);
    d.pending_offset = 0;
    if (frames > 0) {
        std::lock_guard<std::mutex> guard(meter_mutex);
        if (ebur128_add_frames_float(d.meter, d.pending.data(), frames) != EBUR128_SUCCESS) return false;
    }
    return true;
}

// Return 1 when output was produced, 0 at EOF, and -1 on failure.
int produce(Decoder &d) {
    while (!d.cancelled.load(std::memory_order_acquire)) {
        if (d.decoder_finished) {
            if (d.resampler_finished) return 0;
            const int capacity = std::max(1, swr_get_out_samples(d.resampler, 0));
            if (!append_pcm(d, nullptr, 0, capacity)) return -1;
            if (d.pending.empty()) {
                d.resampler_finished = true;
                return 0;
            }
            return 1;
        }
        const int received = avcodec_receive_frame(d.codec, d.frame);
        if (received == 0) {
            const int capacity = swr_get_out_samples(d.resampler, d.frame->nb_samples);
            const bool ok = append_pcm(d, const_cast<const uint8_t **>(d.frame->extended_data),
                d.frame->nb_samples, capacity);
            av_frame_unref(d.frame);
            if (!ok) return -1;
            if (!d.pending.empty()) return 1;
            continue;
        }
        if (received == AVERROR_EOF) {
            d.decoder_finished = true;
            continue;
        }
        if (received != AVERROR(EAGAIN)) return -1;
        if (d.demux_finished) {
            if (!d.flush_sent) {
                const int sent = avcodec_send_packet(d.codec, nullptr);
                if (sent < 0 && sent != AVERROR_EOF) return -1;
                d.flush_sent = true;
                continue;
            }
            return -1;
        }
        const int read = av_read_frame(d.format, d.packet);
        if (read == AVERROR_EOF) {
            d.demux_finished = true;
            continue;
        }
        if (read < 0) return -1;
        int sent = 0;
        if (d.packet->stream_index == d.stream) sent = avcodec_send_packet(d.codec, d.packet);
        av_packet_unref(d.packet);
        if (sent < 0) return -1;
    }
    return -1;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_amwolfstein_sistrum_analysis_OfflineAudioDecoder_nativeCreate(JNIEnv *env, jclass) {
    auto *decoder = new (std::nothrow) Decoder();
    if (!decoder) fail(env, "Unable to allocate analysis decoder");
    return reinterpret_cast<jlong>(decoder);
}

extern "C" JNIEXPORT void JNICALL
Java_com_amwolfstein_sistrum_analysis_OfflineAudioDecoder_nativeOpen(JNIEnv *env, jclass, jlong handle, jint fd) {
    auto *decoder = reinterpret_cast<Decoder *>(handle);
    if (!decoder) {
        close(fd);
        fail(env, "Invalid analysis decoder");
        return;
    }
    const bool opened = open_decoder(*decoder, fd);
    close(fd);
    if (!opened) fail(env, "Unable to open audio for analysis");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_amwolfstein_sistrum_analysis_OfflineAudioDecoder_nativeRead(JNIEnv *env, jclass, jlong handle, jfloatArray output) {
    auto *d = reinterpret_cast<Decoder *>(handle);
    if (!d || !output) { fail(env, "Invalid analysis decoder"); return -1; }
    const jsize capacity = env->GetArrayLength(output) / kChannels * kChannels;
    if (capacity == 0) { fail(env, "Analysis buffer is too small"); return -1; }
    if (d->pending_offset == d->pending.size()) {
        d->pending.clear();
        d->pending_offset = 0;
        const int status = produce(*d);
        if (status <= 0) {
            if (status < 0) fail(env, "Audio analysis decoding failed");
            return status;
        }
    }
    const auto count = static_cast<jsize>(std::min<size_t>(capacity, d->pending.size() - d->pending_offset));
    env->SetFloatArrayRegion(output, 0, count, d->pending.data() + d->pending_offset);
    d->pending_offset += count;
    return count;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_amwolfstein_sistrum_analysis_OfflineAudioDecoder_nativeMetrics(JNIEnv *env, jclass, jlong handle) {
    auto *d = reinterpret_cast<Decoder *>(handle);
    if (!d || !d->resampler_finished) { fail(env, "Audio analysis has not finished"); return nullptr; }
    double loudness = 0, peak_left = 0, peak_right = 0, range = 0;
    {
        std::lock_guard<std::mutex> guard(meter_mutex);
        if (ebur128_loudness_global(d->meter, &loudness) != EBUR128_SUCCESS ||
            ebur128_true_peak(d->meter, 0, &peak_left) != EBUR128_SUCCESS ||
            ebur128_true_peak(d->meter, 1, &peak_right) != EBUR128_SUCCESS ||
            ebur128_loudness_range(d->meter, &range) != EBUR128_SUCCESS) {
            fail(env, "Unable to measure audio loudness");
            return nullptr;
        }
    }
    const double peak = std::max(peak_left, peak_right);
    const double values[] = {loudness, peak > 0 ? 20 * std::log10(peak) : -INFINITY, range};
    jdoubleArray result = env->NewDoubleArray(3);
    if (result) env->SetDoubleArrayRegion(result, 0, 3, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_amwolfstein_sistrum_analysis_OfflineAudioDecoder_nativeCancel(JNIEnv *, jclass, jlong handle) {
    auto *d = reinterpret_cast<Decoder *>(handle);
    if (d) d->cancelled.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_com_amwolfstein_sistrum_analysis_OfflineAudioDecoder_nativeClose(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<Decoder *>(handle);
}
