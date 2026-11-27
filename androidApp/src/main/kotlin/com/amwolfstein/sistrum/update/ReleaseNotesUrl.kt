package com.amwolfstein.sistrum.update

import java.net.URI

internal fun releaseNotesUrl(destination: String, version: String, image: Boolean = false): String? =
    runCatching {
        val base = if (image) "https://raw.githubusercontent.com/AMWolfstein/SistrumIDK/v$version/"
            else "https://github.com/AMWolfstein/SistrumIDK/blob/v$version/"
        URI(base).resolve(destination).takeIf {
            it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null
        }?.toString()
    }.getOrNull()
