#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
#include <aubio/aubio.h>
}

namespace {
constexpr int kSampleRate = 44100;
constexpr int kWin = 1024;
constexpr int kHop = 512;
constexpr int kFieldCount = 15;

struct Analysis {
    float loudnessLufs = 0;
    float loudnessRange = 0;
    float truePeakDb = -120;
    float rmsDb = -120;
    float crestDb = 0;
    float spectralCentroid = 0;
    float spectralRolloff = 0;
    float spectralFlatness = 0;
    float spectralFlux = 0;
    float zcr = 0;
    float tempo = 0;
    float onsetVariance = 0;
    float energy = 0;
    float danceability = 0;
    float brightness = 0;
};

bool decode_mono(int fd, std::vector<float> &out) {
    AVFormatContext *format = nullptr;
    if (avformat_open_input(&format, ("/proc/self/fd/" + std::to_string(fd)).c_str(), nullptr, nullptr) < 0) {
        return false;
    }
    if (avformat_find_stream_info(format, nullptr) < 0) {
        avformat_close_input(&format);
        return false;
    }
    const AVCodec *codec = nullptr;
    int stream = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
    if (stream < 0 || codec == nullptr) {
        avformat_close_input(&format);
        return false;
    }
    AVCodecContext *ctx = avcodec_alloc_context3(codec);
    avcodec_parameters_to_context(ctx, format->streams[stream]->codecpar);
    if (avcodec_open2(ctx, codec, nullptr) < 0) {
        avcodec_free_context(&ctx);
        avformat_close_input(&format);
        return false;
    }
    AVChannelLayout mono = AV_CHANNEL_LAYOUT_MONO;
    SwrContext *swr = nullptr;
    swr_alloc_set_opts2(&swr, &mono, AV_SAMPLE_FMT_FLT, kSampleRate, &ctx->ch_layout,
                        ctx->sample_fmt, ctx->sample_rate, 0, nullptr);
    if (!swr || swr_init(swr) < 0) {
        if (swr) swr_free(&swr);
        avcodec_free_context(&ctx);
        avformat_close_input(&format);
        return false;
    }
    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    std::vector<float> converted(4096);
    while (av_read_frame(format, packet) >= 0) {
        if (packet->stream_index == stream && avcodec_send_packet(ctx, packet) >= 0) {
            while (avcodec_receive_frame(ctx, frame) >= 0) {
                const int maxOut = static_cast<int>(av_rescale_rnd(
                    swr_get_delay(swr, ctx->sample_rate) + frame->nb_samples, kSampleRate,
                    ctx->sample_rate, AV_ROUND_UP));
                if (maxOut > 0) {
                    converted.resize(maxOut);
                    uint8_t *dst = reinterpret_cast<uint8_t *>(converted.data());
                    const int got = swr_convert(swr, &dst, maxOut,
                                                const_cast<const uint8_t **>(frame->extended_data),
                                                frame->nb_samples);
                    for (int i = 0; i < got; ++i) out.push_back(converted[i]);
                }
            }
        }
        av_packet_unref(packet);
    }
    // Flush the decoder.
    avcodec_send_packet(ctx, nullptr);
    while (avcodec_receive_frame(ctx, frame) >= 0) {
        const int maxOut = static_cast<int>(av_rescale_rnd(
            swr_get_delay(swr, ctx->sample_rate) + frame->nb_samples, kSampleRate,
            ctx->sample_rate, AV_ROUND_UP));
        if (maxOut > 0) {
            converted.resize(maxOut);
            uint8_t *dst = reinterpret_cast<uint8_t *>(converted.data());
            const int got = swr_convert(swr, &dst, maxOut,
                                        const_cast<const uint8_t **>(frame->extended_data),
                                        frame->nb_samples);
            for (int i = 0; i < got; ++i) out.push_back(converted[i]);
        }
    }
    av_frame_free(&frame);
    av_packet_free(&packet);
    swr_free(&swr);
    avcodec_free_context(&ctx);
    avformat_close_input(&format);
    return !out.empty();
}

Analysis analyze(const std::vector<float> &pcm) {
    Analysis result;
    if (pcm.empty()) return result;
    double sumSquares = 0;
    float peak = 0;
    size_t crossings = 0;
    for (size_t i = 0; i < pcm.size(); ++i) {
        const float value = pcm[i];
        sumSquares += static_cast<double>(value) * value;
        peak = std::max(peak, std::fabs(value));
        if (i > 0 && ((pcm[i - 1] < 0) != (value < 0))) ++crossings;
    }
    const double rms = std::sqrt(sumSquares / static_cast<double>(pcm.size()));
    result.energy = static_cast<float>(std::min(1.0, rms));
    result.rmsDb = static_cast<float>(20.0 * std::log10(std::max(1e-9, rms)));
    result.truePeakDb = static_cast<float>(20.0 * std::log10(std::max(1e-9, static_cast<double>(peak))));
    result.crestDb = result.truePeakDb - result.rmsDb;
    result.loudnessLufs = static_cast<float>(-0.691 + 10.0 * std::log10(std::max(1e-12, sumSquares / static_cast<double>(pcm.size()))));
    result.zcr = static_cast<float>(crossings) / static_cast<float>(pcm.size()) * kSampleRate;

    aubio_tempo_t *tempo = new_aubio_tempo("default", kWin, kHop, kSampleRate);
    aubio_onset_t *onset = new_aubio_onset("default", kWin, kHop, kSampleRate);
    aubio_pvoc_t *pvoc = new_aubio_pvoc(kWin, kHop);
    aubio_specdesc_t *centroid = new_aubio_specdesc("centroid", kWin);
    aubio_specdesc_t *rolloff = new_aubio_specdesc("rolloff", kWin);
    aubio_specdesc_t *flux = new_aubio_specdesc("specflux", kWin);
    fvec_t *block = new_fvec(kHop);
    fvec_t *tempoOut = new_fvec(2);
    fvec_t *onsetOut = new_fvec(1);
    fvec_t *scalar = new_fvec(1);
    cvec_t *grain = new_cvec(kWin);

    std::vector<double> loudnessWindows;
    double centroidSum = 0, rolloffSum = 0, flatnessSum = 0, fluxSum = 0;
    int frames = 0;
    double lastOnset = -1, onsetSum = 0, onsetSqSum = 0;
    int onsetCount = 0;
    for (size_t offset = 0; offset + kHop <= pcm.size(); offset += kHop) {
        for (int i = 0; i < kHop; ++i) fvec_set_sample(block, pcm[offset + i], i);
        aubio_tempo_do(tempo, block, tempoOut);
        aubio_onset_do(onset, block, onsetOut);
        aubio_pvoc_do(pvoc, block, grain);
        aubio_specdesc_do(centroid, grain, scalar); centroidSum += fvec_get_sample(scalar, 0);
        aubio_specdesc_do(rolloff, grain, scalar); rolloffSum += fvec_get_sample(scalar, 0);
        double logSum = 0, linearSum = 0;
        for (int bin = 0; bin < grain->length; ++bin) {
            const double magnitude = std::max(1e-12, static_cast<double>(grain->norm[bin]));
            logSum += std::log(magnitude);
            linearSum += magnitude;
        }
        const double geometric = std::exp(logSum / std::max(1, static_cast<int>(grain->length)));
        const double arithmetic = linearSum / std::max(1, static_cast<int>(grain->length));
        flatnessSum += std::clamp(geometric / std::max(1e-12, arithmetic), 0.0, 1.0);
        aubio_specdesc_do(flux, grain, scalar); fluxSum += fvec_get_sample(scalar, 0);
        frames++;
        if (fvec_get_sample(onsetOut, 0) > 0) {
            const double time = static_cast<double>(offset) / kSampleRate;
            if (lastOnset >= 0) {
                const double interval = time - lastOnset;
                onsetSum += interval;
                onsetSqSum += interval * interval;
                onsetCount++;
            }
            lastOnset = time;
        }
        if (frames % 86 == 0) { // ~1 s windows at hop 512 / 44.1 kHz
            loudnessWindows.push_back(20.0 * std::log10(std::max(1e-9, rms)) - 0.691);
        }
    }
    result.tempo = aubio_tempo_get_bpm(tempo);
    if (frames > 0) {
        result.spectralCentroid = static_cast<float>(centroidSum / frames);
        result.spectralRolloff = static_cast<float>(rolloffSum / frames);
        result.spectralFlatness = static_cast<float>(flatnessSum / frames);
        result.spectralFlux = static_cast<float>(fluxSum / frames);
    }
    result.brightness = result.spectralCentroid;
    result.danceability = static_cast<float>(std::clamp(
        (result.tempo / 200.0) * result.energy * 1.4, 0.0, 1.0));
    if (onsetCount > 0) {
        const double mean = onsetSum / onsetCount;
        result.onsetVariance = static_cast<float>(std::max(0.0, onsetSqSum / onsetCount - mean * mean));
    }
    if (loudnessWindows.size() > 1) {
        double mean = 0;
        for (double v : loudnessWindows) mean += v;
        mean /= loudnessWindows.size();
        double variance = 0;
        for (double v : loudnessWindows) variance += (v - mean) * (v - mean);
        result.loudnessRange = static_cast<float>(std::sqrt(variance / loudnessWindows.size()));
    }

    del_fvec(tempoOut);
    del_fvec(onsetOut);
    del_fvec(scalar);
    del_cvec(grain);
    del_fvec(block);
    del_aubio_specdesc(centroid);
    del_aubio_specdesc(rolloff);
    del_aubio_specdesc(flux);
    del_aubio_pvoc(pvoc);
    del_aubio_onset(onset);
    del_aubio_tempo(tempo);
    return result;
}
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
Java_com_amwolfstein_sistrum_player_AnalysisBridge_nativeAnalyzeFd(JNIEnv *env, jobject, jint fd) {
    std::vector<float> pcm;
    if (!decode_mono(fd, pcm) || pcm.size() < static_cast<size_t>(kHop)) {
        return env->NewFloatArray(0);
    }
    const Analysis a = analyze(pcm);
    const float values[kFieldCount] = {
        a.loudnessLufs, a.loudnessRange, a.truePeakDb, a.rmsDb, a.crestDb,
        a.spectralCentroid, a.spectralRolloff, a.spectralFlatness, a.spectralFlux,
        a.zcr, a.tempo, a.onsetVariance, a.energy, a.danceability, a.brightness,
    };
    jfloatArray result = env->NewFloatArray(kFieldCount);
    env->SetFloatArrayRegion(result, 0, kFieldCount, values);
    return result;
}
