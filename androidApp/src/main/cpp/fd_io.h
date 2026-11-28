// Shared seekable descriptor I/O for provider-backed documents.
//
// Android SAF hands out authorized file descriptors whose /proc/self/fd/<n>
// symlink resolves to a path the app may not reopen. Every native consumer
// (TagLib, FFmpeg playback, analysis, fingerprinting) therefore reads through
// pread/pwrite on the descriptor itself instead of reopening a path.
#ifndef SISTRUM_FD_IO_H
#define SISTRUM_FD_IO_H

#include <errno.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/mem.h>
}

namespace sistrum {

constexpr int kIoBufferSize = 64 * 1024;

inline ssize_t fd_pread(int fd, void *data, size_t size, int64_t offset) {
    ssize_t read;
    do {
        read = pread(fd, data, size, offset);
    } while (read < 0 && errno == EINTR);
    return read;
}

inline ssize_t fd_pwrite(int fd, const void *data, size_t size, int64_t offset) {
    ssize_t written;
    do {
        written = pwrite(fd, data, size, offset);
    } while (written < 0 && errno == EINTR);
    return written;
}

inline bool fd_is_seekable(int fd) {
    const off_t length = lseek(fd, 0, SEEK_END);
    if (length < 0) return false;
    return lseek(fd, 0, SEEK_SET) >= 0;
}

inline int64_t fd_length(int fd) {
    struct stat info {};
    if (fstat(fd, &info) == 0 && S_ISREG(info.st_mode)) return static_cast<int64_t>(info.st_size);
    const off_t end = lseek(fd, 0, SEEK_END);
    return end < 0 ? 0 : static_cast<int64_t>(end);
}

/** Owns one descriptor plus the AVIO buffer that reads it. */
struct FdMedia {
    int fd = -1;
    int64_t length = 0;
    int64_t position = 0;
    unsigned char *buffer = nullptr;
    AVIOContext *io = nullptr;
    AVFormatContext *format = nullptr;
};

inline int fd_read_packet(void *opaque, uint8_t *data, int size) {
    auto *media = static_cast<FdMedia *>(opaque);
    if (size <= 0) return 0;
    const ssize_t read = fd_pread(media->fd, data, static_cast<size_t>(size), media->position);
    if (read < 0) return AVERROR(errno);
    if (read == 0) return AVERROR_EOF;
    media->position += read;
    return static_cast<int>(read);
}

inline int64_t fd_seek_packet(void *opaque, int64_t offset, int whence) {
    auto *media = static_cast<FdMedia *>(opaque);
    if (whence & AVSEEK_SIZE) return media->length;
    whence &= ~AVSEEK_FORCE;
    int64_t target = 0;
    switch (whence) {
    case SEEK_SET: target = offset; break;
    case SEEK_CUR: target = media->position + offset; break;
    case SEEK_END: target = media->length + offset; break;
    default: return AVERROR(EINVAL);
    }
    if (target < 0) return AVERROR(EINVAL);
    media->position = target;
    return target;
}

/**
 * Opens a demuxer over [fd]. On success the caller owns [media] and must call
 * fd_close_format. On failure the descriptor is left open for the caller.
 */
inline bool fd_open_format(FdMedia &media, std::string &error) {
    if (media.fd < 0) {
        error = "No file descriptor";
        return false;
    }
    const int64_t length = fd_length(media.fd);
    if (length <= 0) {
        error = "Audio source is empty or not seekable";
        return false;
    }
    media.length = length;
    media.position = 0;
    media.buffer = static_cast<unsigned char *>(av_malloc(kIoBufferSize));
    if (media.buffer == nullptr) {
        error = "Out of memory for the audio reader";
        return false;
    }
    media.io = avio_alloc_context(
        media.buffer, kIoBufferSize, 0, &media,
        fd_read_packet, nullptr, fd_seek_packet);
    if (media.io == nullptr) {
        av_free(media.buffer);
        media.buffer = nullptr;
        error = "Could not create the audio reader";
        return false;
    }
    AVFormatContext *context = avformat_alloc_context();
    if (context == nullptr) {
        error = "Could not allocate the demuxer";
        return false;
    }
    context->pb = media.io;
    context->flags |= AVFMT_FLAG_CUSTOM_IO;
    media.format = context;
    if (avformat_open_input(&media.format, nullptr, nullptr, nullptr) < 0) {
        // avformat_open_input frees the context on failure but not our AVIO.
        media.format = nullptr;
        error = "FFmpeg could not open this audio file";
        return false;
    }
    if (avformat_find_stream_info(media.format, nullptr) < 0) {
        error = "FFmpeg could not read this audio file";
        return false;
    }
    return true;
}

inline void fd_media_release(FdMedia &media) {
    if (media.format != nullptr) avformat_close_input(&media.format);
    if (media.io != nullptr) {
        av_freep(&media.io->buffer);
        avio_context_free(&media.io);
    }
    media.buffer = nullptr;
}

/** Closes the demuxer and the descriptor it owns. */
inline void fd_close_format(FdMedia &media) {
    fd_media_release(media);
    if (media.fd >= 0) {
        close(media.fd);
        media.fd = -1;
    }
}

// ---------------------------------------------------------------------------
// Minimal SHA-256 (FIPS 180-4), used for the versioned PCM fingerprint.
// ---------------------------------------------------------------------------
class Sha256 {
public:
    Sha256() { reset(); }

    void reset() {
        state_[0] = 0x6a09e667u; state_[1] = 0xbb67ae85u;
        state_[2] = 0x3c6ef372u; state_[3] = 0xa54ff53au;
        state_[4] = 0x510e527fu; state_[5] = 0x9b05688cu;
        state_[6] = 0x1f83d9abu; state_[7] = 0x5be0cd19u;
        length_ = 0;
        buffer_size_ = 0;
    }

    void update(const void *data, size_t length) {
        const auto *bytes = static_cast<const unsigned char *>(data);
        length_ += length;
        while (length > 0) {
            const size_t take = std::min(length, sizeof(buffer_) - buffer_size_);
            std::memcpy(buffer_ + buffer_size_, bytes, take);
            buffer_size_ += take;
            bytes += take;
            length -= take;
            if (buffer_size_ == sizeof(buffer_)) {
                transform(buffer_);
                buffer_size_ = 0;
            }
        }
    }

    std::string hex() {
        const uint64_t bits = length_ * 8;
        const unsigned char pad = 0x80;
        update(&pad, 1);
        const unsigned char zero = 0;
        while (buffer_size_ != 56) update(&zero, 1);
        unsigned char tail[8];
        for (int i = 0; i < 8; ++i) tail[i] = static_cast<unsigned char>(bits >> (56 - i * 8));
        // Skip padding accounting so the length update cannot re-enter the pad loop.
        std::memcpy(buffer_ + buffer_size_, tail, 8);
        transform(buffer_);
        buffer_size_ = 0;
        char out[65];
        for (int i = 0; i < 8; ++i) std::snprintf(out + i * 8, 9, "%08x", state_[i]);
        out[64] = '\0';
        return std::string(out);
    }

private:
    static uint32_t rotr(uint32_t value, int count) { return (value >> count) | (value << (32 - count)); }

    void transform(const unsigned char *block) {
        static const uint32_t k[64] = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
        };
        uint32_t w[64];
        for (int i = 0; i < 16; ++i) {
            w[i] = (static_cast<uint32_t>(block[i * 4]) << 24) |
                (static_cast<uint32_t>(block[i * 4 + 1]) << 16) |
                (static_cast<uint32_t>(block[i * 4 + 2]) << 8) |
                static_cast<uint32_t>(block[i * 4 + 3]);
        }
        for (int i = 16; i < 64; ++i) {
            const uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
            const uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = state_[0], b = state_[1], c = state_[2], d = state_[3];
        uint32_t e = state_[4], f = state_[5], g = state_[6], h = state_[7];
        for (int i = 0; i < 64; ++i) {
            const uint32_t s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            const uint32_t ch = (e & f) ^ (~e & g);
            const uint32_t temp1 = h + s1 + ch + k[i] + w[i];
            const uint32_t s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            const uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            const uint32_t temp2 = s0 + maj;
            h = g; g = f; f = e; e = d + temp1;
            d = c; c = b; b = a; a = temp1 + temp2;
        }
        state_[0] += a; state_[1] += b; state_[2] += c; state_[3] += d;
        state_[4] += e; state_[5] += f; state_[6] += g; state_[7] += h;
    }

    uint32_t state_[8];
    uint64_t length_ = 0;
    unsigned char buffer_[64]{};
    size_t buffer_size_ = 0;
};

}  // namespace sistrum

#endif  // SISTRUM_FD_IO_H
