#ifndef SISTRUM_ANALYSIS_GRAPH_H
#define SISTRUM_ANALYSIS_GRAPH_H

/*
 * In-process audio analysis matching Airmedy `d859940c`
 * (internal/infra/audio/ffmpeg_analyzer.h): one decode pass through
 *   abuffer -> ebur128 -> aspectralstats -> astats -> abuffersink
 * plus aubio tempo/onset (hfc) on a mono 44.1 kHz stream resampled with swr.
 *
 * Differences from upstream, both required by the Android player:
 *  - input comes from a seekable file descriptor (SAF) instead of a path;
 *  - decoded frames are clipped to a CUE segment before they reach the graph.
 */

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
#include <aubio/aubio.h>
}

#include "fd_io.h"

namespace sistrum {

constexpr int kTempoRate = 44100;
constexpr int kTempoBuf = 1024;
constexpr int kTempoHop = 512;

/** Raw DSP features, identical to upstream FFAnalysisResult. */
struct RawAnalysis {
    double loudnessLufs = 0;
    double loudnessRange = 0;
    double truePeak = 0;
    double rms = 0;
    double crest = 0;
    double spectralCentroid = 0;
    double spectralRolloff = 0;
    double spectralFlatness = 0;
    double spectralFlux = 0;
    double zcr = 0;
    double tempo = 0;
    double onsetVariance = 0;
};

namespace detail {

/** Probe/codec open is serialized: first-touch decoder tables are not reentrant. */
inline std::mutex &probe_mutex() {
    static std::mutex mutex;
    return mutex;
}

inline bool frame_metadata(AVFrame *frame, const char *key, double &value) {
    const AVDictionaryEntry *entry = av_dict_get(frame->metadata, key, nullptr, 0);
    if (entry == nullptr) return false;
    value = std::atof(entry->value);
    return true;
}

/** Clones [skip, skip + take) of a decoded frame for the filter graph. */
inline AVFrame *segment_frame(const AVFrame *source, int skip, int take) {
    AVFrame *frame = av_frame_alloc();
    if (frame == nullptr) return nullptr;
    frame->format = source->format;
    frame->sample_rate = source->sample_rate;
    if (av_channel_layout_copy(&frame->ch_layout, &source->ch_layout) < 0) {
        av_frame_free(&frame);
        return nullptr;
    }
    frame->nb_samples = take;
    if (av_frame_get_buffer(frame, 0) < 0) {
        av_frame_free(&frame);
        return nullptr;
    }
    const auto format = static_cast<AVSampleFormat>(source->format);
    if (av_samples_copy(frame->extended_data, source->extended_data, 0, skip, take,
                        frame->ch_layout.nb_channels, format) < 0) {
        av_frame_free(&frame);
        return nullptr;
    }
    return frame;
}

/** Mirrors upstream ffa_feed_tempo_onset for one (already segment-clipped) frame. */
struct AubioState {
    aubio_tempo_t *tempo = nullptr;
    fvec_t *tempoIn = nullptr;
    fvec_t *tempoOut = nullptr;
    float *hopBuffer = nullptr;
    int hopFill = 0;
    double bpmSum = 0;
    long bpmCount = 0;
    aubio_onset_t *onset = nullptr;
    fvec_t *onsetOut = nullptr;
    long onsetSamples = 0;
    long onsetLast = -1;
    long onsetCount = 0;
    double onsetMean = 0;
    double onsetM2 = 0;

    ~AubioState() {
        if (onset != nullptr) del_aubio_onset(onset);
        if (onsetOut != nullptr) del_fvec(onsetOut);
        if (tempo != nullptr) del_aubio_tempo(tempo);
        if (tempoIn != nullptr) del_fvec(tempoIn);
        if (tempoOut != nullptr) del_fvec(tempoOut);
        free(hopBuffer);
    }
};

/** Faithful port of upstream ffa_feed_tempo_onset (identical hop/onset math). */
inline void feed_aubio(AVFrame *frame, SwrContext *swr, float **scratch, int *scratchCap,
                       AubioState &state) {
    if (swr == nullptr || state.tempo == nullptr || state.tempoIn == nullptr ||
        state.tempoOut == nullptr || state.hopBuffer == nullptr) {
        return;
    }
    const int maxOut = swr_get_out_samples(swr, frame->nb_samples) + 256;
    if (maxOut <= 0) return;
    if (maxOut > *scratchCap) {
        float *grown = static_cast<float *>(realloc(*scratch, sizeof(float) * maxOut));
        if (grown == nullptr) return;
        *scratch = grown;
        *scratchCap = maxOut;
    }
    uint8_t *destination[1] = {reinterpret_cast<uint8_t *>(*scratch)};
    const int got = swr_convert(swr, destination, maxOut,
                                const_cast<const uint8_t **>(frame->extended_data),
                                frame->nb_samples);
    for (int i = 0; i < got; ++i) {
        state.hopBuffer[state.hopFill++] = (*scratch)[i];
        if (state.hopFill < kTempoHop) continue;
        memcpy(state.tempoIn->data, state.hopBuffer, sizeof(smpl_t) * kTempoHop);
        aubio_tempo_do(state.tempo, state.tempoIn, state.tempoOut);
        if (state.tempoOut->data[0] != 0) {
            const double bpm = aubio_tempo_get_bpm(state.tempo);
            if (bpm > 0) {
                state.bpmSum += bpm;
                state.bpmCount += 1;
            }
        }
        if (state.onset != nullptr && state.onsetOut != nullptr) {
            aubio_onset_do(state.onset, state.tempoIn, state.onsetOut);
            // HFC reports a spurious onset during warm-up; ignore hits until one window has filled.
            if (state.onsetOut->data[0] != 0 && state.onsetSamples >= kTempoBuf) {
                const long position = state.onsetSamples;
                if (state.onsetLast >= 0) {
                    const double interval = static_cast<double>(position - state.onsetLast);
                    state.onsetCount += 1;
                    const double delta = interval - state.onsetMean;
                    state.onsetMean += delta / static_cast<double>(state.onsetCount);
                    state.onsetM2 += delta * (interval - state.onsetMean);
                }
                state.onsetLast = position;
            }
            state.onsetSamples += kTempoHop;
        }
        state.hopFill = 0;
    }
}

}  // namespace detail

/**
 * Analyzes [startMs, endMs) of an already-opened descriptor. [endMs] < 0 means
 * end of file. Returns false and fills [error] when the graph cannot run.
 */
inline bool analyze_media(FdMedia &media, int64_t startMs, int64_t endMs, RawAnalysis &out,
                          std::string &error) {
    AVFormatContext *format = media.format;
    if (format == nullptr) {
        error = "No open media";
        return false;
    }
    std::unique_lock<std::mutex> probe_lock(detail::probe_mutex());

    const AVCodec *codec = nullptr;
    const int stream = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
    if (stream < 0 || codec == nullptr) {
        error = "No audio stream";
        return false;
    }
    for (unsigned i = 0; i < format->nb_streams; ++i) {
        if (format->streams[i]->codecpar->codec_type != AVMEDIA_TYPE_AUDIO) {
            format->streams[i]->discard = AVDISCARD_ALL;
        }
    }

    AVCodecContext *codecContext = avcodec_alloc_context3(codec);
    if (codecContext == nullptr) {
        error = "No decoder context";
        return false;
    }
    auto cleanup = [&]() {
        if (codecContext != nullptr) avcodec_free_context(&codecContext);
    };
    if (avcodec_parameters_to_context(codecContext, format->streams[stream]->codecpar) < 0 ||
        avcodec_open2(codecContext, codec, nullptr) < 0) {
        cleanup();
        error = "Decoder could not open the stream";
        return false;
    }
    codecContext->thread_count = 1;
    if (codecContext->sample_rate == 0) {
        cleanup();
        error = "Decoder reported no sample rate";
        return false;
    }
    // Probe/decoder lazy table init is not reentrant; the decode loop is.
    probe_lock.unlock();

    AVFilterGraph *graph = avfilter_graph_alloc();
    AVFilterContext *source = nullptr;
    AVFilterContext *sink = nullptr;
    AVFilterInOut *inputs = nullptr;
    AVFilterInOut *outputs = nullptr;
    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    AVFrame *filtered = av_frame_alloc();
    SwrContext *tempoSwr = nullptr;
    detail::AubioState aubio;
    float *scratch = nullptr;
    int scratchCap = 0;
    bool ok = false;

    auto release = [&]() {
        if (packet != nullptr) av_packet_free(&packet);
        if (frame != nullptr) av_frame_free(&frame);
        if (filtered != nullptr) av_frame_free(&filtered);
        if (inputs != nullptr) avfilter_inout_free(&inputs);
        if (outputs != nullptr) avfilter_inout_free(&outputs);
        if (graph != nullptr) avfilter_graph_free(&graph);
        if (tempoSwr != nullptr) swr_free(&tempoSwr);
        free(scratch);
        cleanup();
    };

    if (graph == nullptr || packet == nullptr || frame == nullptr || filtered == nullptr) {
        release();
        error = "Out of memory";
        return false;
    }

    {
        char layout[256];
        AVChannelLayout inLayout;
        if (codecContext->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC) {
            av_channel_layout_default(&inLayout, codecContext->ch_layout.nb_channels > 0
                                                    ? codecContext->ch_layout.nb_channels : 2);
        } else {
            av_channel_layout_copy(&inLayout, &codecContext->ch_layout);
        }
        av_channel_layout_describe(&inLayout, layout, sizeof(layout));
        av_channel_layout_uninit(&inLayout);

        char args[512];
        std::snprintf(args, sizeof(args),
                      "time_base=1/%d:sample_rate=%d:sample_fmt=%s:channel_layout=%s",
                      codecContext->sample_rate, codecContext->sample_rate,
                      av_get_sample_fmt_name(codecContext->sample_fmt), layout);

        const AVFilter *buffer = avfilter_get_by_name("abuffer");
        const AVFilter *buffersink = avfilter_get_by_name("abuffersink");
        if (buffer == nullptr || buffersink == nullptr ||
            avfilter_graph_create_filter(&source, buffer, "in", args, nullptr, graph) < 0 ||
            avfilter_graph_create_filter(&sink, buffersink, "out", nullptr, nullptr, graph) < 0) {
            release();
            error = "Analysis filters are missing from this build";
            return false;
        }
        outputs = avfilter_inout_alloc();
        inputs = avfilter_inout_alloc();
        if (outputs == nullptr || inputs == nullptr) {
            release();
            error = "Out of memory";
            return false;
        }
        outputs->name = av_strdup("in");
        outputs->filter_ctx = source;
        outputs->pad_idx = 0;
        outputs->next = nullptr;
        inputs->name = av_strdup("out");
        inputs->filter_ctx = sink;
        inputs->pad_idx = 0;
        inputs->next = nullptr;
        const char *description =
            "ebur128=metadata=1:peak=true,"
            "aspectralstats=measure=centroid+rolloff+flatness+flux,"
            "astats=metadata=1:reset=0";
        if (avfilter_graph_parse_ptr(graph, description, &inputs, &outputs, nullptr) < 0 ||
            avfilter_graph_config(graph, nullptr) < 0) {
            release();
            error = "Analysis graph could not be configured";
            return false;
        }
    }

    {
        AVChannelLayout inLayout;
        AVChannelLayout mono;
        if (codecContext->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC) {
            av_channel_layout_default(&inLayout, codecContext->ch_layout.nb_channels > 0
                                                    ? codecContext->ch_layout.nb_channels : 2);
        } else {
            av_channel_layout_copy(&inLayout, &codecContext->ch_layout);
        }
        av_channel_layout_default(&mono, 1);
        if (swr_alloc_set_opts2(&tempoSwr, &mono, AV_SAMPLE_FMT_FLT, kTempoRate, &inLayout,
                                codecContext->sample_fmt, codecContext->sample_rate, 0,
                                nullptr) < 0 ||
            swr_init(tempoSwr) < 0) {
            if (tempoSwr != nullptr) swr_free(&tempoSwr);
        }
        av_channel_layout_uninit(&inLayout);
        av_channel_layout_uninit(&mono);
        if (tempoSwr != nullptr) {
            aubio.tempo = new_aubio_tempo("default", kTempoBuf, kTempoHop, kTempoRate);
            aubio.tempoIn = new_fvec(kTempoHop);
            aubio.tempoOut = new_fvec(2);
            aubio.hopBuffer = static_cast<float *>(malloc(sizeof(float) * kTempoHop));
            aubio.onset = new_aubio_onset("hfc", kTempoBuf, kTempoHop, kTempoRate);
            aubio.onsetOut = new_fvec(1);
            if (aubio.tempo == nullptr || aubio.tempoIn == nullptr || aubio.tempoOut == nullptr ||
                aubio.hopBuffer == nullptr || aubio.onset == nullptr || aubio.onsetOut == nullptr) {
                release();
                error = "aubio could not initialize";
                return false;
            }
        }
    }

    const int64_t startUs = std::max<int64_t>(0, startMs) * 1000;
    const int64_t endUs = endMs > 0 ? endMs * 1000 : -1;
    const AVRational streamTimeBase = format->streams[stream]->time_base;
    const AVRational sampleBase{1, codecContext->sample_rate};
    // CUE bounds are trimmed in whole samples; converting via microseconds twice loses a sample.
    const int64_t startSample = av_rescale_q(startUs, AVRational{1, AV_TIME_BASE}, sampleBase);
    const int64_t endSample = endUs >= 0 ? av_rescale_q(endUs, AVRational{1, AV_TIME_BASE}, sampleBase) : -1;
    if (startMs > 0) {
        const int64_t target = av_rescale_q(startUs, AVRational{1, AV_TIME_BASE}, streamTimeBase);
        av_seek_frame(format, stream, target, AVSEEK_FLAG_BACKWARD);
        avcodec_flush_buffers(codecContext);
        if (tempoSwr != nullptr) swr_init(tempoSwr);
    }

    /**
     * Clips a decoded frame to the CUE segment in sample units. A frame without
     * timestamps is treated as contiguous with the previous one (decoders that
     * omit PTS still emit frames in order), so the running counter stays exact.
     */
    int64_t expectedSample = startSample;
    auto segment = [&](const AVFrame *value, int &skip, int &take, bool &pastEnd) {
        pastEnd = false;
        skip = 0;
        take = value->nb_samples;
        const int64_t pts = value->best_effort_timestamp;
        int64_t frameStart = expectedSample;
        if (pts != AV_NOPTS_VALUE) frameStart = av_rescale_q(pts, streamTimeBase, sampleBase);
        expectedSample = frameStart + value->nb_samples;
        const int64_t frameEnd = frameStart + value->nb_samples;
        if (endSample >= 0 && frameStart >= endSample) {
            pastEnd = true;
            return false;
        }
        if (frameEnd <= startSample) return false;
        if (startSample > 0 && frameStart < startSample) {
            skip = static_cast<int>(std::min<int64_t>(value->nb_samples, startSample - frameStart));
            take = value->nb_samples - skip;
        }
        if (endSample >= 0 && frameEnd > endSample) {
            take = static_cast<int>(std::max<int64_t>(0, std::min<int64_t>(
                value->nb_samples - skip, endSample - frameStart - skip)));
        }
        return take > 0;
    };

    double lastLufs = 0, lastLra = 0, lastRms = 0, lastCrest = 0, lastZcr = 0;
    double truePeakLinear = 0;
    double peakDb = 0;
    double centroidSum = 0, rolloffSum = 0, flatnessSum = 0, fluxSum = 0;
    long spectralFrames = 0;
    bool haveLoudness = false, haveAstats = false, haveTruePeak = false, havePeakDb = false;

    int endOfStream = 0;
    int flushed = 0;
    while (!endOfStream) {
        int received = avcodec_receive_frame(codecContext, frame);
        if (received == 0) {
            int skip = 0;
            int take = frame->nb_samples;
            bool pastEnd = false;
            if (segment(frame, skip, take, pastEnd)) {
                AVFrame *feed = (skip == 0 && take == frame->nb_samples)
                                    ? frame
                                    : detail::segment_frame(frame, skip, take);
                if (feed == nullptr) {
                    release();
                    error = "Out of memory while clipping a frame";
                    return false;
                }
                const int added = av_buffersrc_add_frame_flags(source, feed, AV_BUFFERSRC_FLAG_KEEP_REF);
                if (added < 0) {
                    if (feed != frame) av_frame_free(&feed);
                    release();
                    error = "The analysis graph rejected a frame";
                    return false;
                }
                if (tempoSwr != nullptr) {
                    detail::feed_aubio(feed, tempoSwr, &scratch, &scratchCap, aubio);
                }
                if (feed != frame) av_frame_free(&feed);
            }
            av_frame_unref(frame);
            if (pastEnd) endOfStream = 1;
        } else if (received == AVERROR(EAGAIN)) {
            if (!flushed && av_read_frame(format, packet) >= 0) {
                if (packet->stream_index == stream) {
                    const int sent = avcodec_send_packet(codecContext, packet);
                    if (sent < 0 && sent != AVERROR(EAGAIN)) {
                        av_packet_unref(packet);
                        release();
                        error = "The decoder rejected a packet";
                        return false;
                    }
                }
                av_packet_unref(packet);
            } else if (!flushed) {
                avcodec_send_packet(codecContext, nullptr);
                flushed = 1;
            } else {
                (void)av_buffersrc_add_frame_flags(source, nullptr, 0);
                endOfStream = 1;
            }
        } else if (received == AVERROR_EOF) {
            (void)av_buffersrc_add_frame_flags(source, nullptr, 0);
            endOfStream = 1;
        } else {
            release();
            error = "Decode failed";
            return false;
        }

        for (;;) {
            const int pulled = av_buffersink_get_frame(sink, filtered);
            if (pulled == AVERROR(EAGAIN) || pulled == AVERROR_EOF) break;
            if (pulled < 0) {
                release();
                error = "The analysis graph failed";
                return false;
            }
            double value = 0;
            if (detail::frame_metadata(filtered, "lavfi.r128.I", value)) {
                lastLufs = value;
                haveLoudness = true;
            }
            if (detail::frame_metadata(filtered, "lavfi.r128.LRA", value)) lastLra = value;
            for (int channel = 0; channel < 8; ++channel) {
                char key[48];
                std::snprintf(key, sizeof(key), "lavfi.r128.true_peaks_ch%d", channel);
                if (detail::frame_metadata(filtered, key, value) &&
                    (!haveTruePeak || value > truePeakLinear)) {
                    truePeakLinear = value;
                    haveTruePeak = true;
                }
            }
            if (detail::frame_metadata(filtered, "lavfi.astats.Overall.RMS_level", value)) {
                lastRms = value;
                haveAstats = true;
            }
            if (detail::frame_metadata(filtered, "lavfi.astats.1.Crest_factor", value)) lastCrest = value;
            if (detail::frame_metadata(filtered, "lavfi.astats.1.Zero_crossings_rate", value)) lastZcr = value;
            if (detail::frame_metadata(filtered, "lavfi.astats.Overall.Peak_level", value) &&
                (!havePeakDb || value > peakDb)) {
                peakDb = value;
                havePeakDb = true;
            }
            if (detail::frame_metadata(filtered, "lavfi.aspectralstats.1.centroid", value)) centroidSum += value;
            if (detail::frame_metadata(filtered, "lavfi.aspectralstats.1.rolloff", value)) rolloffSum += value;
            if (detail::frame_metadata(filtered, "lavfi.aspectralstats.1.flatness", value)) flatnessSum += value;
            if (detail::frame_metadata(filtered, "lavfi.aspectralstats.1.flux", value)) {
                fluxSum += value;
                spectralFrames += 1;
            }
            av_frame_unref(filtered);
        }
    }

    if (haveLoudness || haveAstats) {
        out.loudnessLufs = lastLufs;
        out.loudnessRange = lastLra;
        if (haveTruePeak && truePeakLinear > 0) {
            out.truePeak = 20.0 * std::log10(truePeakLinear);
        } else if (havePeakDb) {
            out.truePeak = peakDb;
        }
        out.rms = lastRms;
        out.crest = lastCrest;
        out.zcr = lastZcr;
        if (spectralFrames > 0) {
            out.spectralCentroid = centroidSum / static_cast<double>(spectralFrames);
            out.spectralRolloff = rolloffSum / static_cast<double>(spectralFrames);
            out.spectralFlatness = flatnessSum / static_cast<double>(spectralFrames);
            out.spectralFlux = fluxSum / static_cast<double>(spectralFrames);
        }
        out.tempo = aubio.bpmCount > 0 ? aubio.bpmSum / static_cast<double>(aubio.bpmCount) : 0;
        out.onsetVariance = aubio.onsetCount > 1
                                ? aubio.onsetM2 / static_cast<double>(aubio.onsetCount - 1)
                                : 0;
        ok = true;
    } else {
        error = "No loudness metadata was produced";
    }
    release();
    return ok;
}

}  // namespace sistrum

#endif  // SISTRUM_ANALYSIS_GRAPH_H
