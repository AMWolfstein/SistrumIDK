package com.amwolfstein.sistrum.ui

import com.amwolfstein.sistrum.sync.LibraryAlbum
import com.amwolfstein.sistrum.sync.LibraryArtist
import com.amwolfstein.sistrum.sync.LibraryComposer
import com.amwolfstein.sistrum.sync.LibraryTrack
import java.text.Normalizer
import java.util.Locale

internal val LibraryTrack.alphabeticalTitle: String
    // Existing MediaStore scans stored an opaque TITLE_KEY in sortTitle.
    get() = if (id.startsWith("local:")) title else sortTitle.ifBlank { title }

internal val LibraryTrack.alphabeticalArtist: String
    get() = sortArtists.ifBlank { artists }

internal val LibraryAlbum.alphabeticalTitle: String
    get() = sortTitle.ifBlank { title }

internal val LibraryAlbum.alphabeticalArtist: String
    get() = sortArtist.ifBlank { artist }

internal val LibraryArtist.alphabeticalName: String
    get() = sortName.ifBlank { name }

internal val LibraryComposer.alphabeticalName: String
    get() = sortName.ifBlank { name }

internal fun libraryAlphabeticalIndexLabel(value: String): String? {
    val initial = libraryAlphabeticalInitial(value) ?: return null
    return if (initial in 'a'..'z' || initial.isArabicLetter()) initial.uppercaseChar().toString() else "#"
}

internal val libraryAlphabeticalComparator = Comparator<String> { first, second ->
    val group = libraryAlphabeticalGroup(first).compareTo(libraryAlphabeticalGroup(second))
    if (group != 0) group else normalizedLibraryAlphabeticalText(first).compareTo(normalizedLibraryAlphabeticalText(second))
}

private fun libraryAlphabeticalGroup(value: String): Int {
    val initial = libraryAlphabeticalInitial(value)
    return when {
        initial in 'a'..'z' -> 0
        initial?.isArabicLetter() == true -> 1
        initial in '0'..'9' -> 2
        else -> 3
    }
}

private fun Char.isArabicLetter(): Boolean =
    isLetter() && Character.UnicodeScript.of(code) == Character.UnicodeScript.ARABIC

private fun libraryAlphabeticalInitial(value: String): Char? {
    val initial = value.trimStart().firstOrNull() ?: return null
    return when (initial) {
        'đ', 'Đ' -> 'd'
        in 'a'..'z', in 'A'..'Z', in '0'..'9' -> initial.lowercaseChar()
        else -> Normalizer.normalize(initial.toString(), Normalizer.Form.NFD).firstOrNull()?.lowercaseChar()
    }
}

private val LibraryAlphabeticalMarks = Regex("\\p{M}+")

private fun normalizedLibraryAlphabeticalText(value: String): String {
    val text = value.trim()
    val folded = if (text.all { it.code < 128 }) text else Normalizer
        .normalize(text, Normalizer.Form.NFD)
        .replace(LibraryAlphabeticalMarks, "")
        .replace('Đ', 'D')
        .replace('đ', 'd')
    return folded.lowercase(Locale.ROOT)
}
