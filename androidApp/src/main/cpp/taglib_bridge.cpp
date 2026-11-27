#include <jni.h>

#include <cstdio>
#include <string>

#include <taglib/audioproperties.h>
#include <taglib/fileref.h>
#include <taglib/tag.h>
#include <taglib/tpropertymap.h>
#include <taglib/tvariant.h>
#include <taglib/tstring.h>
#include <taglib/tstringlist.h>

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
