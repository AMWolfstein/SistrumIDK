package com.amwolfstein.sistrum.metadata

/** An update leaves unspecified frames byte-for-byte intact. */
internal data class Mp3TagUpdate(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val lyrics: String? = null,
    val artwork: ByteArray? = null,
    val artworkMime: String? = null,
    val pictures: List<com.kyant.taglib.Picture>? = null,
    val additionalProperties: Map<String, List<String>?> = emptyMap(),
)
