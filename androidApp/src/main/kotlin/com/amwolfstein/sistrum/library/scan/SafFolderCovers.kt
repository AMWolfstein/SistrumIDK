package com.amwolfstein.sistrum.library.scan

import java.util.Locale

/** Recognises folder-level cover images by conventional file name. */
object SafFolderCovers {
    private val extensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

    /** Lower is better. Names closer to the front of this list win. */
    private val preferredNames = listOf(
        "cover", "folder", "front", "album", "albumart", "albumartsmall", "artwork", "thumb",
    )

    fun isCover(name: String): Boolean {
        val base = baseName(name)
        return base in preferredNames && extensionOf(name) in extensions
    }

    fun rank(name: String): Int {
        val base = baseName(name)
        val index = preferredNames.indexOf(base)
        return if (index >= 0) index else preferredNames.size
    }

    /** Picks the best cover among a folder's entries, or null when there is none. */
    fun pick(folderPath: String, documents: List<ScanDocument>): Pair<ScanDocument, FolderCover>? {
        val best = documents
            .filter { !it.isDirectory && isCover(it.name) }
            .minWithOrNull(compareBy({ rank(it.name) }, { it.name.lowercase(Locale.ROOT) }))
            ?: return null
        return best to FolderCover(
            folderPath = folderPath,
            documentKey = documentKeyOf(best),
            fileUri = best.uri,
            name = best.name,
        )
    }

    private fun baseName(name: String): String =
        name.substringBeforeLast('.', name).lowercase(Locale.ROOT).replace(" ", "").replace("_", "")

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}

/** Canonical, provider-scoped key for a SAF document. */
fun documentKeyOf(document: ScanDocument): String {
    val authority = runCatching { java.net.URI(document.uri).authority }.getOrNull()
    return if (authority.isNullOrBlank()) document.documentId else "$authority:${document.documentId}"
}

fun documentKeyFor(treeUri: String, documentId: String): String {
    val authority = runCatching { java.net.URI(treeUri).authority }.getOrNull()
    return if (authority.isNullOrBlank()) documentId else "$authority:$documentId"
}
