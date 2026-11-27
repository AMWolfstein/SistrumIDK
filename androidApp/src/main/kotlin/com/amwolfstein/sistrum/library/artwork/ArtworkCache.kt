package com.amwolfstein.sistrum.library.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import com.amwolfstein.sistrum.library.db.ArtworkAssetEntity
import com.amwolfstein.sistrum.library.db.LibraryDao
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Content-addressed artwork storage in durable app storage.
 *
 * - the original bytes are validated by decoding them before anything is written
 * - `_md` (500 px) and `_sm` (64 px) JPEG q85 variants are generated once per key
 * - importing the same picture twice reuses the files and only refreshes `lastUsedAt`
 * - cleanup deletes least-recently-used assets that no item, album, artist or playlist references
 */
class ArtworkCache(
    private val context: Context,
    private val dao: LibraryDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val root: File
        get() = File(context.filesDir, DIRECTORY).also { it.mkdirs() }

    suspend fun store(bytes: ByteArray, kind: String, origin: String?): ArtworkAssetEntity? =
        withContext(Dispatchers.IO) {
            val extension = ArtworkKeys.extensionFor(bytes) ?: return@withContext null
            val key = ArtworkKeys.contentKey(bytes)
            dao.artwork(key)?.let { existing ->
                // A row whose files disappeared (external deletion/partial write) is repaired
                // instead of being returned broken forever.
                val extension = existing.path.substringAfterLast('.', "jpg")
                val intact = listOf(
                    ArtworkKeys.VARIANT_ORIGINAL,
                    ArtworkKeys.VARIANT_MEDIUM,
                    ArtworkKeys.VARIANT_SMALL,
                ).all { file(key, it, extension).isFile }
                if (intact) {
                    dao.touchArtwork(key, now())
                    return@withContext existing
                }
                deleteFiles(key, extension)
                dao.deleteArtwork(key)
            }
            val bitmap = decode(bytes) ?: return@withContext null
            return@withContext try {
                val original = file(key, ArtworkKeys.VARIANT_ORIGINAL, extension)
                write(bytes, original)
                writeVariant(bitmap, key, ArtworkKeys.VARIANT_MEDIUM, ArtworkKeys.MEDIUM_SIZE)
                writeVariant(bitmap, key, ArtworkKeys.VARIANT_SMALL, ArtworkKeys.SMALL_SIZE)
                val asset = ArtworkAssetEntity(
                    key = key,
                    kind = kind,
                    width = bitmap.width,
                    height = bitmap.height,
                    mime = ArtworkKeys.mimeFor(extension),
                    sizeBytes = bytes.size.toLong(),
                    path = ArtworkKeys.relativePath(key, ArtworkKeys.VARIANT_ORIGINAL, extension),
                    origin = origin,
                    createdAt = now(),
                    lastUsedAt = now(),
                    active = true,
                )
                dao.upsertArtwork(listOf(asset))
                asset
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                deleteFiles(key, extension)
                throw cancelled
            } catch (failure: Throwable) {
                deleteFiles(key, extension)
                null
            } finally {
                bitmap.recycle()
            }
        }

    suspend fun file(key: String, variant: String = ArtworkKeys.VARIANT_ORIGINAL): File? =
        withContext(Dispatchers.IO) {
            val asset = dao.artwork(key) ?: return@withContext null
            assetFile(asset, variant).takeIf { it.isFile && it.length() > 0 }
        }

    fun assetFile(asset: ArtworkAssetEntity, variant: String): File {
        val extension = asset.path.substringAfterLast('.', "jpg")
        return file(asset.key, variant, extension)
    }

    suspend fun load(key: String, variant: String = ArtworkKeys.VARIANT_MEDIUM): Bitmap? =
        withContext(Dispatchers.IO) {
            val asset = dao.artwork(key) ?: return@withContext null
            val candidate = assetFile(asset, variant).takeIf { it.isFile } ?: return@withContext null
            dao.touchArtwork(key, now())
            decode(candidate.readBytes())
        }

    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        dao.artworkAssets().sumOf { asset -> sizeOf(asset) }
    }

    private fun sizeOf(asset: ArtworkAssetEntity): Long =
        listOf(ArtworkKeys.VARIANT_ORIGINAL, ArtworkKeys.VARIANT_MEDIUM, ArtworkKeys.VARIANT_SMALL)
            .sumOf { assetFile(asset, it).length() }

    suspend fun assetCount(): Int = withContext(Dispatchers.IO) { dao.artworkAssets().size }

    /** Deletes unreferenced least-recently-used assets until the cache fits [maxBytes]. */
    suspend fun cleanup(maxBytes: Long = DEFAULT_LIMIT_BYTES): Int = withContext(Dispatchers.IO) {
        val referenced = dao.referencedArtworkKeys().toSet()
        var total = dao.artworkAssets().sumOf { sizeOf(it) }
        if (total <= maxBytes) return@withContext 0
        var removed = 0
        dao.artworkAssets()
            .sortedBy { it.lastUsedAt }
            .forEach { asset ->
                if (total <= maxBytes) return@forEach
                if (asset.key in referenced) return@forEach
                val size = sizeOf(asset)
                deleteFiles(asset.key, asset.path.substringAfterLast('.', "jpg"))
                dao.deleteArtwork(asset.key)
                total -= size
                removed += 1
            }
        removed
    }

    /** Removes every unreferenced asset, used by the owner-visible cache action. */
    suspend fun clearUnreferenced(): Int = cleanup(maxBytes = 0)

    private fun file(key: String, variant: String, extension: String): File =
        File(root, ArtworkKeys.relativePath(key, variant, extension))

    private fun write(bytes: ByteArray, target: File) {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { it.write(bytes) }
    }

    private fun writeVariant(bitmap: Bitmap, key: String, variant: String, longestSide: Int) {
        val (width, height) = ArtworkKeys.scaledSize(bitmap.width, bitmap.height, longestSide)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val target = file(key, variant, "jpg")
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { output ->
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
                throw IllegalStateException("Unable to encode $variant artwork variant")
            }
        }
        if (scaled !== bitmap) scaled.recycle()
    }

    private fun deleteFiles(key: String, extension: String) {
        listOf(ArtworkKeys.VARIANT_ORIGINAL, ArtworkKeys.VARIANT_MEDIUM, ArtworkKeys.VARIANT_SMALL)
            .forEach { variant -> file(key, variant, extension).delete() }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching {
                    val source = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }.getOrNull()
            } else {
                null
            }
    }

    private fun decode(file: File): Bitmap? =
        runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()

    private companion object {
        const val DIRECTORY = "artwork"
        const val DEFAULT_LIMIT_BYTES = 256L * 1024 * 1024
    }
}
