package app.phopsd.platform

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import app.phopsd.engine.model.SourceInfo
import app.phopsd.engine.raster.ImportBudget
import app.phopsd.engine.raster.MipChain
import app.phopsd.engine.raster.RasterBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

class DecodedImage(val name: String, val mips: MipChain, val source: SourceInfo)

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Decodes user images through the platform [ImageDecoder]:
 *  - reads via the content resolver only; the source file is never modified,
 *  - applies EXIF orientation (ImageDecoder does this for JPEG/HEIF),
 *  - converts to the sRGB working space (the original colour space is recorded),
 *  - respects a heap budget, decoding at reduced size rather than crashing,
 *  - copies pixels into engine tiles in row bands, then frees the bitmap.
 * Always runs off the main thread.
 */
class ImageImporter(private val resolver: ContentResolver) {

    suspend fun decode(uri: Uri, budgetBytes: Long): DecodedImage = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Image"
        val mime = resolver.getType(uri)
        var originalW = 0
        var originalH = 0
        var sourceSpace: String? = null
        var downsampled = false

        val bitmap: Bitmap = try {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                originalW = info.size.width
                originalH = info.size.height
                sourceSpace = info.colorSpace?.name
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val scale = ImportBudget.scaleToFit(originalW, originalH, budgetBytes)
                if (scale <= 0.0) throw ImportException("Not enough free memory to open this image")
                if (scale < 1.0) {
                    downsampled = true
                    decoder.setTargetSize(
                        max(1, (originalW * scale).roundToInt()),
                        max(1, (originalH * scale).roundToInt()),
                    )
                }
            }
        } catch (e: ImportException) {
            throw e
        } catch (e: ImageDecoder.DecodeException) {
            throw ImportException("“$displayName” could not be decoded (unsupported or damaged file)", e)
        } catch (e: OutOfMemoryError) {
            throw ImportException("Not enough memory to open “$displayName”", e)
        } catch (e: SecurityException) {
            throw ImportException("Permission to read “$displayName” was denied", e)
        } catch (e: java.io.IOException) {
            throw ImportException("“$displayName” could not be read: ${e.message}", e)
        }

        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else {
            val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            bitmap.recycle()
            converted ?: throw ImportException("“$displayName” uses an unsupported pixel format")
        }

        try {
            val w = argb.width
            val h = argb.height
            val builder = RasterBuilder(w, h)
            val bandRows = max(1, minOf(h, BAND_PIXELS / w))
            val band = IntArray(w * bandRows)
            var y = 0
            while (y < h) {
                coroutineContext.ensureActive()
                val rows = minOf(bandRows, h - y)
                // getPixels returns straight (unpremultiplied) sRGB ARGB; the engine premultiplies.
                argb.getPixels(band, 0, w, 0, y, w, rows)
                builder.appendStraightRows(band, rows)
                y += rows
            }
            val raster = builder.build()
            argb.recycle()
            val mips = MipChain.build(raster)
            DecodedImage(
                name = displayName.substringBeforeLast('.', displayName),
                mips = mips,
                source = SourceInfo(
                    displayName = displayName,
                    mimeType = mime,
                    originalWidth = originalW,
                    originalHeight = originalH,
                    sourceColorSpace = sourceSpace,
                    downsampled = downsampled,
                ),
            )
        } catch (e: OutOfMemoryError) {
            argb.recycle()
            throw ImportException("Not enough memory to open “$displayName”", e)
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val BAND_PIXELS = 1 shl 20

        /**
         * Java-heap bytes we may spend on a new raster. Engine tiles live on the Java heap
         * (see ARCHITECTURE.md), so the budget is the heap headroom.
         */
        fun heapBudgetBytes(): Long {
            val rt = Runtime.getRuntime()
            val used = rt.totalMemory() - rt.freeMemory()
            return (rt.maxMemory() - used).coerceAtLeast(0L)
        }
    }
}
