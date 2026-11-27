package com.amwolfstein.sistrum.library.artwork

import java.security.MessageDigest

/**
 * Content addressing for cached artwork.
 *
 * A cache entry is named after the SHA-256 of the original image bytes, so importing the same
 * picture again reuses the existing files. Variants never change the key; they only add a suffix.
 *
 * Layout: `artwork/<first two hex chars>/<key>.<ext>`, `<key>_md.jpg`, `<key>_sm.jpg`.
 */
object ArtworkKeys {
    const val VARIANT_ORIGINAL = "orig"
    const val VARIANT_MEDIUM = "md"
    const val VARIANT_SMALL = "sm"

    /** Longest side of the medium variant used by list and player surfaces. */
    const val MEDIUM_SIZE = 500

    /** Longest side of the small variant used by dense grids. */
    const val SMALL_SIZE = 64

    fun contentKey(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }

    /**
     * Detects the format from the file signature instead of trusting a provider MIME type or file
     * extension. Returns null for anything the player cannot render.
     */
    fun extensionFor(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "jpg"

        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte() -> "png"

        // RIFF....WEBP
        bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII)) -> "webp"

        bytes.size >= 6 && bytes.copyOfRange(0, 3).contentEquals("GIF".toByteArray(Charsets.US_ASCII)) -> "gif"

        bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "bmp"

        else -> null
    }

    fun mimeFor(extension: String): String = when (extension) {
        "jpg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> "application/octet-stream"
    }

    fun shard(key: String): String = key.take(2).ifBlank { "00" }

    fun fileName(key: String, variant: String, extension: String): String =
        if (variant == VARIANT_ORIGINAL) "$key.$extension" else "${key}_$variant.jpg"

    fun relativePath(key: String, variant: String, extension: String): String =
        "${shard(key)}/${fileName(key, variant, extension)}"

    /** One decoded bitmap is used for both variants; this keeps the smaller edge above 1 px. */
    fun scaledSize(width: Int, height: Int, longestSide: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val longest = maxOf(width, height)
        if (longest <= longestSide) return width to height
        val scale = longestSide.toDouble() / longest
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }
}
