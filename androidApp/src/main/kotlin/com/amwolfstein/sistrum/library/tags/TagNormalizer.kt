package com.amwolfstein.sistrum.library.tags

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Deterministic normalization shared by artist/album/genre matching and entity ids. */
object TagNormalizer {
    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
        val stripped = buildString(decomposed.length) {
            decomposed.forEach { char ->
                when (Character.getType(char)) {
                    Character.NON_SPACING_MARK.toInt(),
                    Character.COMBINING_SPACING_MARK.toInt(),
                    Character.ENCLOSING_MARK.toInt(),
                    -> Unit
                    else -> append(char)
                }
            }
        }
        return stripped
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** "The Beatles" -> "Beatles, The"; articles only move when followed by a word. */
    fun sortName(value: String): String {
        val trimmed = value.trim()
        val articles = listOf("the", "a", "an")
        val firstWord = trimmed.substringBefore(' ')
        val article = articles.firstOrNull { it.equals(firstWord, ignoreCase = true) }
        if (article != null && trimmed.length > firstWord.length) {
            val rest = trimmed.substring(firstWord.length).trimStart()
            val articleLabel = article.replaceFirstChar { it.uppercase(Locale.ROOT) }
            return "$rest, $articleLabel"
        }
        return trimmed
    }

    fun key(kind: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$kind\u0000${normalize(value)}".toByteArray(Charsets.UTF_8))
        return kind.lowercase(Locale.ROOT) + "_" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun fileTitle(relativePath: String): String =
        relativePath.substringAfterLast('/').substringBeforeLast('.', relativePath.substringAfterLast('/'))
}

/** Alias priority for tags whose canonical name differs between formats. */
object TagAliases {
    val artistKeys = listOf("ARTIST", "ARTISTS", "TPE1")
    val albumArtistKeys = listOf("ALBUMARTIST", "ALBUM ARTIST", "TPE2")
    val titleKeys = listOf("TITLE", "TIT2")
    val albumKeys = listOf("ALBUM", "TALB")
    val dateKeys = listOf("DATE", "YEAR", "TDRC", "TYER")
    val labelKeys = listOf("LABEL", "ORGANIZATION", "TPUB")
    val isrcKeys = listOf("ISRC", "TSRC")
    val copyrightKeys = listOf("COPYRIGHT", "TCOP")

    fun first(metadata: TagMetadata, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key -> metadata.first(key) }

    fun all(metadata: TagMetadata, keys: List<String>): List<String> =
        keys.flatMap { metadata.values(it) }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
