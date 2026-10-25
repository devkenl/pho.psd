package app.phopsd.platform

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.phopsd.editor.EditorController
import app.phopsd.editor.ExportFormat
import app.phopsd.editor.ExportSettings
import app.phopsd.engine.model.Document
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.render.ExportRenderer
import app.phopsd.engine.render.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Exports the composite through the same engine compositor used on screen, band by band,
 * into an Android bitmap that is then encoded with the platform PNG/JPEG encoder.
 *
 * The bitmap is marked *not premultiplied* so the straight-alpha values produced by the
 * engine reach the PNG encoder unchanged (no extra rounding). JPEG has no alpha, so the
 * document is composited over opaque white first.
 */
class ImageExporter(private val resolver: ContentResolver) {

    data class Result(val width: Int, val height: Int)

    suspend fun export(snapshot: EditorController.SaveSnapshot, settings: ExportSettings, destination: Uri): Result =
        withContext(Dispatchers.Default) {
            val doc = snapshot.document
            val (w, h) = ExportRenderer.outputSize(doc, settings.scale)
            val bitmap = try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                throw StorageException("not enough memory for a ${w}×$h export; try a smaller size", e)
            }
            try {
                bitmap.isPremultiplied = false
                val background = if (settings.format == ExportFormat.Jpeg) Pixels.opaque(0xFFFFFF) else Pixels.TRANSPARENT
                val job = coroutineContext[kotlinx.coroutines.Job]
                val ok = ExportRenderer.render(
                    doc, snapshot.rasters,
                    ExportRenderer.Options(scale = settings.scale, background = background),
                    isCancelled = { job?.isActive == false },
                ) { y0, rows, width, straight ->
                    bitmap.setPixels(straight, 0, width, 0, y0, width, rows)
                }
                coroutineContext.ensureActive()
                if (!ok) throw StorageException("export was cancelled")
                withContext(Dispatchers.IO) {
                    val out = (runCatching { resolver.openOutputStream(destination, "wt") }.getOrNull()
                        ?: resolver.openOutputStream(destination, "w"))
                        ?: throw IOException("the destination could not be opened")
                    BufferedOutputStream(out, 1 shl 16).use { buffered ->
                        val format = if (settings.format == ExportFormat.Jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                        if (!bitmap.compress(format, settings.quality, buffered)) throw IOException("the image encoder failed")
                    }
                    verifyDimensions(destination, w, h)
                }
                Result(w, h)
            } catch (e: SecurityException) {
                throw StorageException("permission to write this location was denied", e)
            } catch (e: IOException) {
                throw StorageException(e.message ?: "the file could not be written", e)
            } finally {
                bitmap.recycle()
            }
        }

    /** Re-reads the written file's header to confirm it decodes at the expected size. */
    private fun verifyDimensions(uri: Uri, w: Int, h: Int) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth != w || opts.outHeight != h) throw IOException("the written file could not be verified")
    }

    companion object {
        /** Small PNG preview of the composite, stored inside projects and in the recents list. */
        fun thumbnailPng(doc: Document, snapshot: EditorController.SaveSnapshot, maxEdge: Int = 512): ByteArray? = runCatching {
            val img = Thumbnails.document(doc, snapshot.rasters, maxEdge)
            val straight = img.premul.copyOf().also { Pixels.unpremultiplyInPlace(it) }
            val bmp = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
            bmp.isPremultiplied = false
            bmp.setPixels(straight, 0, img.width, 0, 0, img.width, img.height)
            val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bmp.recycle()
            bytes
        }.getOrNull()
    }
}
