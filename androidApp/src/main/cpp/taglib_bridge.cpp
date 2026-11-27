#include <jni.h>

#include <cstdio>
#include <string>
#include <vector>

#include <taglib/audioproperties.h>
#include <taglib/fileref.h>
#include <taglib/tfilestream.h>
#include <taglib/tag.h>
#include <taglib/tpropertymap.h>
#include <taglib/tvariant.h>
#include <taglib/tstring.h>
#include <taglib/tstringlist.h>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
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

class StreamFileRef {
public:
    explicit StreamFileRef(int fd) {
        const std::string path = "/proc/self/fd/" + std::to_string(fd);
        fileRef_ = TagLib::FileRef(path.c_str(), true, TagLib::AudioProperties::Average);
    }

    TagLib::FileRef &get() { return fileRef_; }
    bool isValid() const { return !fileRef_.isNull() && fileRef_.file() != nullptr; }

private:
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
        const std::string path = "/proc/self/fd/" + std::to_string(fd);
        TagLib::FileStream stream(path.c_str(), false);
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

        const std::string path = "/proc/self/fd/" + std::to_string(fd);
        TagLib::FileStream stream(path.c_str(), false);
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
Java_com_amwolfstein_sistrum_library_tags_TagLibBridge_nativeAudioFingerprint(JNIEnv *env, jclass, jint fd) {
    AVFormatContext *context = nullptr;
    const std::string path = "/proc/self/fd/" + std::to_string(fd);
    if (avformat_open_input(&context, path.c_str(), nullptr, nullptr) < 0) return nullptr;
    avformat_find_stream_info(context, nullptr);
    const int audio = av_find_best_stream(context, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (audio < 0) {
        avformat_close_input(&context);
        return nullptr;
    }
    uint64_t hash = 1469598103934665603ULL;
    AVPacket *packet = av_packet_alloc();
    while (packet != nullptr && av_read_frame(context, packet) >= 0) {
        if (packet->stream_index == audio && packet->data != nullptr && packet->size > 0) {
            for (int i = 0; i < packet->size; i += 1) {
                hash ^= static_cast<unsigned char>(packet->data[i]);
                hash *= 1099511628211ULL;
            }
            hash ^= static_cast<uint64_t>(packet->size);
            hash *= 1099511628211ULL;
        }
        av_packet_unref(packet);
    }
    av_packet_free(&packet);
    avformat_close_input(&context);
    char buffer[32];
    snprintf(buffer, sizeof(buffer), "%016llx", static_cast<unsigned long long>(hash));
    return env->NewStringUTF(buffer);
}
