package com.amwolfstein.sistrum.metadata

import android.os.ParcelFileDescriptor
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import java.io.File

/** A missing key in changes leaves that property untouched; null explicitly removes it. */
internal fun mergeTagProperties(
    existing: Map<String, List<String>>,
    changes: Map<String, List<String>?>,
): Map<String, List<String>> {
    val result = existing.toMutableMap()
    changes.forEach { (key, values) ->
        require(key.isNotBlank() && '\u0000' !in key)
        if (values == null) {
            result.remove(key)
        } else {
            require(values.all { '\u0000' !in it })
            result[key] = values.toList()
        }
    }
    return result
}

internal data class TagSnapshot(
    val properties: Map<String, List<String>>,
    val pictures: List<Picture>,
)

/** The upstream FileStream owns and closes each detached duplicate descriptor. */
internal object TagMetadata {
    fun read(file: File, readPictures: Boolean): TagSnapshot {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val result = requireNotNull(TagLib.getMetadata(descriptor.dup().detachFd(), readPictures)) {
                "TagLib cannot read this file"
            }
            return TagSnapshot(result.propertyMap.mapValues { it.value.toList() }, result.pictures.toList())
        }
    }

    fun write(file: File, properties: Map<String, List<String>>?, pictures: List<Picture>?) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
            if (properties != null) {
                val propertyMap = HashMap(properties.mapValues { it.value.toTypedArray() })
                check(TagLib.savePropertyMap(descriptor.dup().detachFd(), propertyMap)) {
                    "TagLib could not write metadata"
                }
            }
            if (pictures != null) {
                check(TagLib.savePictures(descriptor.dup().detachFd(), pictures.toTypedArray())) {
                    "TagLib could not write artwork"
                }
            }
        }
    }
}

/** Read metadata without loading picture bytes during a library scan. */
internal fun embeddedTagsFromProperties(properties: Map<String, List<String>>): com.amwolfstein.sistrum.sync.EmbeddedTrackTags {
    fun first(name: String): String? = properties[name]?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
    val date = first("DATE") ?: first("YEAR")
    return com.amwolfstein.sistrum.sync.EmbeddedTrackTags(
        releaseDate = date?.takeIf { it.length > 4 },
        year = date?.take(4)?.toIntOrNull(),
        bpm = first("BPM")?.toDoubleOrNull()?.toInt(),
        label = first("LABEL") ?: first("PUBLISHER"),
        isrc = first("ISRC"),
        copyright = first("COPYRIGHT"),
        explicit = com.amwolfstein.sistrum.sync.parseAdvisoryText(first("ITUNESADVISORY")),
    )
}
