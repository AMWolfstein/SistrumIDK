#include <fcntl.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <taglib/audioproperties.h>
#include <taglib/fileref.h>
#include <taglib/tiostream.h>
#include <taglib/tfilestream.h>
#include <taglib/tag.h>
#include <taglib/tpropertymap.h>
#include <taglib/tvariant.h>
#include <taglib/tstring.h>
#include <taglib/tstringlist.h>

#include "fd_io.h"

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
}

namespace {

std::string jsonEscape(const std::string &value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (unsigned char c : value) {
        switch (c) {
        case '"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default:
            if (c < 0x20) {
                char buffer[7];
                snprintf(buffer, sizeof(buffer), "\\u%04x", c);
                out += buffer;
            } else {
                out += static_cast<char>(c);
            }
        }
    }
    return out;
}

std::string toUtf8(const TagLib::String &value) {
    return value.to8Bit(true);
}

std::string field(const char *name, const std::string &value) {
    return std::string("\"") + name + "\":\"" + jsonEscape(value) + "\"";
}

// NewStringUTF expects JNI modified UTF-8; TagLib produces standard UTF-8 (4-byte astral
// characters included), so decode to UTF-16 and use NewString instead.
jstring utf8ToJString(JNIEnv *env, const std::string &utf8) {
    std::u16string utf16;
    utf16.reserve(utf8.size());
    size_t i = 0;
    while (i < utf8.size()) {
        const unsigned char first = static_cast<unsigned char>(utf8[i]);
        uint32_t codePoint = 0xFFFD;
        size_t length = 1;
        if (first < 0x80) {
            codePoint = first;
        } else if ((first & 0xE0) == 0xC0 && i + 1 < utf8.size()) {
            codePoint = ((first & 0x1Fu) << 6) | (static_cast<unsigned char>(utf8[i + 1]) & 0x3Fu);
            length = 2;
        } else if ((first & 0xF0) == 0xE0 && i + 2 < utf8.size()) {
            codePoint = ((first & 0x0Fu) << 12) |
                ((static_cast<unsigned char>(utf8[i + 1]) & 0x3Fu) << 6) |
                (static_cast<unsigned char>(utf8[i + 2]) & 0x3Fu);
            length = 3;
        } else if ((first & 0xF8) == 0xF0 && i + 3 < utf8.size()) {
            codePoint = ((first & 0x07u) << 18) |
                ((static_cast<unsigned char>(utf8[i + 1]) & 0x3Fu) << 12) |
                ((static_cast<unsigned char>(utf8[i + 2]) & 0x3Fu) << 6) |
                (static_cast<unsigned char>(utf8[i + 3]) & 0x3Fu);
            length = 4;
        }
        i += length;
        if (codePoint > 0x10FFFF) codePoint = 0xFFFD;
        if (codePoint <= 0xFFFF) {
            utf16.push_back(static_cast<char16_t>(codePoint));
        } else {
            codePoint -= 0x10000;
            utf16.push_back(static_cast<char16_t>(0xD800 + (codePoint >> 10)));
            utf16.push_back(static_cast<char16_t>(0xDC00 + (codePoint & 0x3FF)));
        }
    }
    return env->NewString(reinterpret_cast<const jchar *>(utf16.data()), static_cast<jsize>(utf16.size()));
}

/**
 * TagLib stream over an authorized descriptor.
 *
 * SAF descriptors cannot be reopened by the path their /proc/self/fd symlink
 * points at, so every read/write/seek goes through pread/pwrite on the
 * descriptor itself. Sizes are queried with fstat, fallback to lseek.
 */
class FdIOStream : public TagLib::IOStream {
public:
    explicit FdIOStream(int fd) : fd_(fd), name_("fd:" + std::to_string(fd)) {
        length_ = fdLength();
    }

    ~FdIOStream() override = default;

    TagLib::FileName name() const override { return name_.c_str(); }

    TagLib::ByteVector readBlock(size_t length) override {
        if (length == 0) return {};
        TagLib::ByteVector block(static_cast<unsigned int>(length));
        size_t total = 0;
        while (total < length) {
            const ssize_t read = sistrum::fd_pread(fd_, block.data() + total, length - total, position_ + total);
            if (read <= 0) break;
            total += static_cast<size_t>(read);
        }
        position_ += total;
        block.resize(static_cast<unsigned int>(total));
        return block;
    }

    void writeBlock(const TagLib::ByteVector &data) override {
        writeAt(position_, data);
        position_ += data.size();
        if (position_ > length_) length_ = position_;
    }

    void insert(const TagLib::ByteVector &data, TagLib::offset_t start = 0, size_t replace = 0) override {
        const int64_t from = static_cast<int64_t>(start);
        const int64_t replaced = std::min<int64_t>(static_cast<int64_t>(replace), std::max<int64_t>(0, length_ - from));
        const int64_t delta = static_cast<int64_t>(data.size()) - replaced;
        if (from < 0 || from > length_) return;
        if (delta > 0) {
            // Grow the tail first, copying backwards so source and destination never overlap.
            int64_t remaining = length_ - (from + replaced);
            std::vector<char> chunk(256 * 1024);
            while (remaining > 0) {
                const size_t take = static_cast<size_t>(std::min<int64_t>(remaining, chunk.size()));
                const int64_t source = from + replaced + remaining - take;
                if (sistrum::fd_pread(fd_, chunk.data(), take, source) != static_cast<ssize_t>(take)) return;
                if (sistrum::fd_pwrite(fd_, chunk.data(), take, source + delta) != static_cast<ssize_t>(take)) return;
                remaining -= take;
            }
            length_ += delta;
        } else if (delta < 0) {
            int64_t remaining = length_ - (from + replaced);
            std::vector<char> chunk(256 * 1024);
            int64_t done = 0;
            while (done < remaining) {
                const size_t take = static_cast<size_t>(std::min<int64_t>(remaining - done, chunk.size()));
                const int64_t source = from + replaced + done;
                if (sistrum::fd_pread(fd_, chunk.data(), take, source) != static_cast<ssize_t>(take)) return;
                if (sistrum::fd_pwrite(fd_, chunk.data(), take, source + delta) != static_cast<ssize_t>(take)) return;
                done += take;
            }
            length_ += delta;
            (void) ftruncate(fd_, length_);
        }
        writeAt(from, data);
    }

    void removeBlock(TagLib::offset_t start = 0, size_t length = 0) override {
        insert({}, start, length);
    }

    bool readOnly() const override {
        const int mode = accessMode(fd_);
        return mode != O_RDWR && mode != O_WRONLY;
    }

    bool isOpen() const override { return fd_ >= 0; }

    void seek(TagLib::offset_t offset, Position p = Beginning) override {
        int64_t target = static_cast<int64_t>(offset);
        switch (p) {
        case Current: target += position_; break;
        case End: target += length_; break;
        case Beginning: break;
        }
        position_ = std::max<int64_t>(0, target);
    }

    TagLib::offset_t tell() const override { return position_; }

    TagLib::offset_t length() override { return length_; }

    void truncate(TagLib::offset_t length) override {
        if (ftruncate(fd_, static_cast<off_t>(length)) != 0) return;
        if (static_cast<int64_t>(length) > length_) {
            std::vector<char> zeros(64 * 1024, 0);
            int64_t at = length_;
            while (at < static_cast<int64_t>(length)) {
                const size_t take = static_cast<size_t>(std::min<int64_t>(length - at, zeros.size()));
                if (sistrum::fd_pwrite(fd_, zeros.data(), take, at) != static_cast<ssize_t>(take)) break;
                at += take;
            }
        }
        length_ = static_cast<int64_t>(length);
        if (position_ > length_) position_ = length_;
    }

private:
    static int accessMode(int fd) {
        // F_GETFL returns the descriptor status flags; O_ACCMODE extracts read/write mode.
        const int flags = fcntl(fd, F_GETFL);
        if (flags < 0) return O_RDONLY;
        return flags & O_ACCMODE;
    }

    int64_t fdLength() const {
        struct stat info {};
        if (fstat(fd_, &info) == 0 && S_ISREG(info.st_mode)) return static_cast<int64_t>(info.st_size);
        const off_t end = lseek(fd_, 0, SEEK_END);
        return end < 0 ? 0 : static_cast<int64_t>(end);
    }

    void writeAt(int64_t offset, const TagLib::ByteVector &data) {
        size_t total = 0;
        while (total < data.size()) {
            const ssize_t written = sistrum::fd_pwrite(fd_, data.data() + total, data.size() - total, offset + total);
            if (written <= 0) return;
            total += static_cast<size_t>(written);
        }
    }

    int fd_ = -1;
    std::string name_;
    int64_t position_ = 0;
    int64_t length_ = 0;
};

class StreamFileRef {
public:
    explicit StreamFileRef(int fd) : stream_(fd) {
        if (!stream_.isOpen()) return;
        fileRef_ = TagLib::FileRef(&stream_, true, TagLib::AudioProperties::Average);
    }

    TagLib::FileRef &get() { return fileRef_; }
    bool isValid() const { return !fileRef_.isNull() && fileRef_.file() != nullptr; }

private:
    FdIOStream stream_;
    TagLib::FileRef fileRef_;
};

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_amwolfstein_sistrum_library_tags_TagLibBridge_nativeReadMetadata(JNIEnv *env, jclass, jint fd) {
    try {
    StreamFileRef holder(fd);
    auto &ref = holder.get();
    if (!holder.isValid()) {
        return env->NewStringUTF("{}");
    }

    std::string json = "{";
    bool first = true;
    auto add = [&](const std::string &part) {
        if (!first) json += ",";
        first = false;
        json += part;
    };

    if (auto *tag = ref.tag()) {
        auto addString = [&](const char *name, const TagLib::String &value) {
            if (!value.isEmpty()) add(field(name, toUtf8(value)));
        };
        addString("title", tag->title());
        addString("artist", tag->artist());
        addString("album", tag->album());
        addString("genre", tag->genre());
        addString("comment", tag->comment());
        if (tag->year() > 0) add("\"year\":" + std::to_string(tag->year()));
        if (tag->track() > 0) add("\"track\":" + std::to_string(tag->track()));
    }

    if (auto *properties = ref.audioProperties()) {
        add("\"durationMs\":" + std::to_string(properties->lengthInMilliseconds()));
        if (properties->bitrate() > 0) add("\"bitrate\":" + std::to_string(properties->bitrate()));
        if (properties->sampleRate() > 0) add("\"sampleRate\":" + std::to_string(properties->sampleRate()));
        if (properties->channels() > 0) add("\"channels\":" + std::to_string(properties->channels()));
    }

    const TagLib::PropertyMap properties = ref.properties();
    std::string tagsJson = "{";
    bool firstTag = true;
    for (auto it = properties.begin(); it != properties.end(); ++it) {
        const std::string key = toUtf8(it->first);
        if (key.empty()) continue;
        std::string values = "[";
        bool firstValue = true;
        for (const auto &value : it->second) {
            if (!firstValue) values += ",";
            firstValue = false;
            values += "\"" + jsonEscape(toUtf8(value)) + "\"";
        }
        values += "]";
        if (!firstTag) tagsJson += ",";
        firstTag = false;
        tagsJson += "\"" + jsonEscape(key) + "\":" + values;
    }
    tagsJson += "}";
    add("\"rawTags\":" + tagsJson);

    const auto pictures = ref.complexProperties("PICTURE");
    bool hasArtwork = false;
    std::string artworkMime;
    if (!pictures.isEmpty()) {
        const auto &picture = pictures.front();
        if (picture.contains("data")) {
            hasArtwork = !picture["data"].value<TagLib::ByteVector>().isEmpty();
        }
        if (picture.contains("mimeType")) {
            artworkMime = toUtf8(picture["mimeType"].value<TagLib::String>());
        }
    }
    add(std::string("\"hasArtwork\":") + (hasArtwork ? "true" : "false"));
    if (!artworkMime.empty()) add(field("artworkMime", artworkMime));

    const auto lyrics = properties.find("LYRICS");
    if (lyrics != properties.end() && !lyrics->second.isEmpty()) {
        add(field("lyrics", toUtf8(lyrics->second.front())));
    }

    json += "}";
    return utf8ToJString(env, json);
    } catch (...) {
        return env->NewStringUTF("{}");
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_amwolfstein_sistrum_library_tags_TagLibBridge_nativeReadArtwork(JNIEnv *env, jclass, jint fd) {
    try {
    StreamFileRef holder(fd);
    if (!holder.isValid()) return nullptr;
    const auto pictures = holder.get().complexProperties("PICTURE");
    if (pictures.isEmpty() || !pictures.front().contains("data")) return nullptr;
    const TagLib::ByteVector data = pictures.front()["data"].value<TagLib::ByteVector>();
    if (data.isEmpty()) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(data.size()));
    if (result != nullptr) {
        env->SetByteArrayRegion(
            result, 0, static_cast<jsize>(data.size()),
            reinterpret_cast<const jbyte *>(data.data()));
    }
    return result;
    } catch (...) {
        return nullptr;
    }
}

namespace {

TagLib::String jstringToTagLib(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    std::string utf8;
    utf8.reserve(static_cast<size_t>(length) * 3);
    size_t i = 0;
    while (i < static_cast<size_t>(length)) {
        uint32_t codePoint = chars[i++];
        if (codePoint >= 0xD800 && codePoint <= 0xDBFF && i < static_cast<size_t>(length)) {
            const uint32_t low = chars[i];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                codePoint = 0x10000 + ((codePoint - 0xD800) << 10) + (low - 0xDC00);
                i += 1;
            }
        }
        if (codePoint < 0x80) {
            utf8 += static_cast<char>(codePoint);
        } else if (codePoint < 0x800) {
            utf8 += static_cast<char>(0xC0 | (codePoint >> 6));
            utf8 += static_cast<char>(0x80 | (codePoint & 0x3F));
        } else if (codePoint < 0x10000) {
            utf8 += static_cast<char>(0xE0 | (codePoint >> 12));
            utf8 += static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F));
            utf8 += static_cast<char>(0x80 | (codePoint & 0x3F));
        } else {
            utf8 += static_cast<char>(0xF0 | (codePoint >> 18));
            utf8 += static_cast<char>(0x80 | ((codePoint >> 12) & 0x3F));
            utf8 += static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F));
            utf8 += static_cast<char>(0x80 | (codePoint & 0x3F));
        }
    }
    env->ReleaseStringChars(value, chars);
    return TagLib::String(utf8, TagLib::String::UTF8);
}

void replaceIfPresent(
    TagLib::PropertyMap &map,
    const char *key,
    JNIEnv *env,
    jstring value) {
    if (value == nullptr) return;
    const TagLib::String text = jstringToTagLib(env, value);
    if (text.isEmpty()) {
        map.erase(key);
    } else {
        map.replace(key, TagLib::StringList(text));
    }
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_amwolfstein_sistrum_library_tags_TagLibBridge_nativeWriteTags(
    JNIEnv *env,
    jclass,
    jint fd,
    jstring title,
    jstring artist,
    jstring album,
    jstring albumArtist,
    jstring genre,
    jstring composer,
    jstring comment,
    jint year,
    jint track,
    jint disc,
    jstring isrc,
    jstring label,
    jstring copyright) {
    try {
        FdIOStream stream(fd);
        if (!stream.isOpen()) return JNI_FALSE;
        TagLib::FileRef ref(&stream, false, TagLib::AudioProperties::Fast);
        if (ref.isNull() || ref.file()->readOnly()) return JNI_FALSE;

        TagLib::PropertyMap map = ref.properties();
        replaceIfPresent(map, "TITLE", env, title);
        replaceIfPresent(map, "ARTIST", env, artist);
        replaceIfPresent(map, "ALBUM", env, album);
        replaceIfPresent(map, "ALBUMARTIST", env, albumArtist);
        replaceIfPresent(map, "GENRE", env, genre);
        replaceIfPresent(map, "COMPOSER", env, composer);
        replaceIfPresent(map, "COMMENT", env, comment);
        replaceIfPresent(map, "ISRC", env, isrc);
        replaceIfPresent(map, "LABEL", env, label);
        replaceIfPresent(map, "COPYRIGHT", env, copyright);
        if (year > 0) map.replace("DATE", TagLib::StringList(std::to_string(year)));
        if (track > 0) map.replace("TRACKNUMBER", TagLib::StringList(std::to_string(track)));
        if (disc > 0) map.replace("DISCNUMBER", TagLib::StringList(std::to_string(disc)));

        ref.setProperties(map);
        return ref.save() ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_amwolfstein_sistrum_library_tags_TagLibBridge_nativeWriteProperties(
    JNIEnv *env,
    jclass,
    jint fd,
    jobjectArray keys,
    jobjectArray values) {
    try {
        FdIOStream stream(fd);
        if (!stream.isOpen()) return JNI_FALSE;
        TagLib::FileRef ref(&stream, false, TagLib::AudioProperties::Fast);
        if (ref.isNull() || ref.file()->readOnly()) return JNI_FALSE;
        TagLib::PropertyMap map = ref.properties();
        const jsize count = keys == nullptr ? 0 : env->GetArrayLength(keys);
        for (jsize i = 0; i < count; ++i) {
            auto key = static_cast<jstring>(env->GetObjectArrayElement(keys, i));
            auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
            if (key != nullptr && value != nullptr) {
                const TagLib::String name = jstringToTagLib(env, key);
                const TagLib::String text = jstringToTagLib(env, value);
                if (!name.isEmpty()) map.replace(name, TagLib::StringList(text));
            }
            if (key != nullptr) env->DeleteLocalRef(key);
            if (value != nullptr) env->DeleteLocalRef(value);
        }
        ref.setProperties(map);
        return ref.save() ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_amwolfstein_sistrum_library_tags_TagLibBridge_nativeWriteArtwork(
    JNIEnv *env,
    jclass,
    jint fd,
    jbyteArray data,
    jstring mimeType,
    jstring description) {
    try {
        if (data == nullptr) return JNI_FALSE;
        const jsize size = env->GetArrayLength(data);
        std::vector<unsigned char> bytes(static_cast<size_t>(size));
        env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte *>(bytes.data()));

        FdIOStream stream(fd);
        if (!stream.isOpen()) return JNI_FALSE;
        TagLib::FileRef ref(&stream, false, TagLib::AudioProperties::Fast);
        if (ref.isNull() || ref.file()->readOnly()) return JNI_FALSE;

        TagLib::VariantMap picture;
        picture.insert("data", TagLib::Variant(TagLib::ByteVector(
            reinterpret_cast<const char *>(bytes.data()), static_cast<unsigned int>(bytes.size()))));
        picture.insert("mimeType", TagLib::Variant(jstringToTagLib(env, mimeType)));
        picture.insert("description", TagLib::Variant(jstringToTagLib(env, description)));
        picture.insert("pictureType", TagLib::Variant(TagLib::String("Front Cover")));
        TagLib::List<TagLib::VariantMap> pictures;
        pictures.append(picture);
        if (!ref.setComplexProperties("PICTURE", pictures)) return JNI_FALSE;
        return ref.save() ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_amwolfstein_sistrum_library_tags_TagLibBridge_nativeProbeAudio(JNIEnv *env, jclass, jint fd) {
    try {
        sistrum::FdMedia media;
        media.fd = fd;
        std::string error;
        if (!sistrum::fd_open_format(media, error)) {
            sistrum::fd_media_release(media);
            return env->NewStringUTF("{}");
        }
        std::string json = "{";
        const int stream = av_find_best_stream(media.format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
        if (stream >= 0) {
            const AVCodecParameters *parameters = media.format->streams[stream]->codecpar;
            if (parameters->codec_id != AV_CODEC_ID_NONE) {
                json += std::string("\"codec\":\"") + avcodec_get_name(parameters->codec_id) + "\"";
            }
            int depth = parameters->bits_per_raw_sample;
            if (depth <= 0) depth = parameters->bits_per_coded_sample;
            if (depth <= 0) {
                const AVSampleFormat format = static_cast<AVSampleFormat>(parameters->format);
                if (format != AV_SAMPLE_FMT_NONE && format != AV_SAMPLE_FMT_NB) {
                    depth = av_get_bytes_per_sample(format) * 8;
                }
            }
            if (depth > 0) {
                if (json.size() > 1) json += ",";
                json += "\"bitsPerSample\":" + std::to_string(depth);
            }
        }
        json += "}";
        sistrum::fd_media_release(media);
        return utf8ToJString(env, json);
    } catch (...) {
        return env->NewStringUTF("{}");
    }
}

namespace {

constexpr int kFingerprintRate = 44100;

/**
 * Versioned content fingerprint over decoded PCM only.
 *
 * The descriptor is read through the shared pread demuxer, resampled to the
 * canonical stream (mono, 44.1 kHz, float32) and hashed incrementally, so tag
 * and container edits cannot move the audio identity while a re-encode does.
 * [startMs]/[endMs] bound one CUE segment; 0 and -1 mean the whole file.
 */
bool fingerprint_pcm(int fd, int64_t start_ms, int64_t end_ms, std::string &out) {
    sistrum::FdMedia media;
    media.fd = fd;
    std::string error;
    if (!sistrum::fd_open_format(media, error)) {
        sistrum::fd_media_release(media);
        return false;
    }
    struct Cleanup {
        sistrum::FdMedia &media;
        AVCodecContext *codec = nullptr;
        SwrContext *swr = nullptr;
        AVPacket *packet = nullptr;
        AVFrame *frame = nullptr;
        ~Cleanup() {
            if (packet != nullptr) av_packet_free(&packet);
            if (frame != nullptr) av_frame_free(&frame);
            if (swr != nullptr) swr_free(&swr);
            if (codec != nullptr) avcodec_free_context(&codec);
            sistrum::fd_media_release(media);
        }
    } cleanup{media};

    const int stream = av_find_best_stream(media.format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (stream < 0) return false;
    const AVCodecParameters *parameters = media.format->streams[stream]->codecpar;
    const AVCodec *decoder = avcodec_find_decoder(parameters->codec_id);
    if (decoder == nullptr) return false;
    cleanup.codec = avcodec_alloc_context3(decoder);
    if (cleanup.codec == nullptr ||
            avcodec_parameters_to_context(cleanup.codec, parameters) < 0 ||
            avcodec_open2(cleanup.codec, decoder, nullptr) < 0) return false;

    AVChannelLayout mono = AV_CHANNEL_LAYOUT_MONO;
    if (swr_alloc_set_opts2(&cleanup.swr, &mono, AV_SAMPLE_FMT_FLT, kFingerprintRate,
            &cleanup.codec->ch_layout, cleanup.codec->sample_fmt, cleanup.codec->sample_rate,
            0, nullptr) < 0 || swr_init(cleanup.swr) < 0) return false;

    cleanup.packet = av_packet_alloc();
    cleanup.frame = av_frame_alloc();
    if (cleanup.packet == nullptr || cleanup.frame == nullptr) return false;

    const int64_t start_us = std::max<int64_t>(0, start_ms) * 1000;
    const int64_t end_us = end_ms > 0 ? end_ms * 1000 : -1;
    if (start_ms > 0) {
        const int64_t target = av_rescale_q(
            start_us, AVRational{1, AV_TIME_BASE}, media.format->streams[stream]->time_base);
        av_seek_frame(media.format, stream, target, AVSEEK_FLAG_BACKWARD);
        avcodec_flush_buffers(cleanup.codec);
    }

    sistrum::Sha256 sha;
    std::vector<float> converted(8192);
    size_t hashed = 0;
    auto consume = [&](const AVFrame *frame) -> bool {
        const int capacity = static_cast<int>(av_rescale_rnd(
            swr_get_delay(cleanup.swr, cleanup.codec->sample_rate) + frame->nb_samples,
            kFingerprintRate, cleanup.codec->sample_rate, AV_ROUND_UP));
        if (capacity <= 0) return true;
        converted.resize(static_cast<size_t>(capacity));
        uint8_t *target = reinterpret_cast<uint8_t *>(converted.data());
        const int got = swr_convert(cleanup.swr, &target, capacity,
                                    const_cast<const uint8_t **>(frame->extended_data),
                                    frame->nb_samples);
        if (got <= 0) return true;
        int begin = 0;
        int count = got;
        const int64_t pts = frame->best_effort_timestamp;
        int64_t frame_start_us = 0;
        if (pts != AV_NOPTS_VALUE) {
            frame_start_us = av_rescale_q(
                pts, media.format->streams[stream]->time_base, AVRational{1, AV_TIME_BASE});
            const int64_t frame_end_us = frame_start_us +
                av_rescale(frame->nb_samples, AV_TIME_BASE, cleanup.codec->sample_rate);
            if (start_us > 0 && frame_end_us <= start_us) return true;
            if (start_us > 0 && frame_start_us < start_us) {
                begin = static_cast<int>(std::min<int64_t>(
                    count, av_rescale(start_us - frame_start_us, kFingerprintRate, AV_TIME_BASE)));
            }
            if (end_us >= 0 && frame_end_us > end_us) {
                const int64_t keep = av_rescale(end_us - frame_start_us, kFingerprintRate, AV_TIME_BASE);
                count = static_cast<int>(std::max<int64_t>(0, std::min<int64_t>(count - begin, keep)));
            }
        }
        if (count > 0) {
            sha.update(converted.data() + begin, static_cast<size_t>(count) * sizeof(float));
            hashed += static_cast<size_t>(count);
        }
        if (end_us >= 0 && pts != AV_NOPTS_VALUE) {
            const int64_t kept_end_us = frame_start_us +
                av_rescale(begin + count, AV_TIME_BASE, kFingerprintRate);
            if (kept_end_us >= end_us) return false;
        }
        return true;
    };

    while (av_read_frame(media.format, cleanup.packet) >= 0) {
        if (cleanup.packet->stream_index == stream &&
                avcodec_send_packet(cleanup.codec, cleanup.packet) >= 0) {
            while (avcodec_receive_frame(cleanup.codec, cleanup.frame) >= 0) {
                const bool keep_going = consume(cleanup.frame);
                av_frame_unref(cleanup.frame);
                if (!keep_going) {
                    av_packet_unref(cleanup.packet);
                    out = "pcm-sha256-v1:" + sha.hex();
                    return hashed > 0;
                }
            }
        }
        av_packet_unref(cleanup.packet);
    }
    avcodec_send_packet(cleanup.codec, nullptr);
    while (avcodec_receive_frame(cleanup.codec, cleanup.frame) >= 0) {
        if (!consume(cleanup.frame)) {
            av_frame_unref(cleanup.frame);
            break;
        }
        av_frame_unref(cleanup.frame);
    }
    if (hashed == 0) return false;
    out = "pcm-sha256-v1:" + sha.hex();
    return true;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_amwolfstein_sistrum_library_tags_TagLibBridge_nativeAudioFingerprint(
    JNIEnv *env, jclass, jint fd, jlong startMs, jlong endMs) {
    try {
        std::string fingerprint;
        if (!fingerprint_pcm(fd, startMs, endMs, fingerprint)) return nullptr;
        return env->NewStringUTF(fingerprint.c_str());
    } catch (...) {
        return nullptr;
    }
}
