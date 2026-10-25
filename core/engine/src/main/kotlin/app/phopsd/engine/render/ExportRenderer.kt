package app.phopsd.engine.render

import app.phopsd.engine.geometry.RectD
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.RasterLookup
import app.phopsd.engine.raster.Pixels
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Full-resolution (or scaled) export through the *same* compositor used for previews,
 * delivered in row bands so a 50 MP export never needs a second full-size pixel buffer.
 */
object ExportRenderer {

    data class Options(
        /** 1.0 = document pixel size. */
        val scale: Double = 1.0,
        /** Premultiplied colour under all layers (opaque white for JPEG, transparent for PNG). */
        val background: Int = Pixels.TRANSPARENT,
        val bandRows: Int = 256,
    ) {
        init {
            require(scale > 0.0 && scale <= 1.0) { "Export scale must be in (0, 1]" }
            require(bandRows > 0)
        }
    }

    fun outputSize(doc: Document, scale: Double): Pair<Int, Int> =
        max(1, (doc.width * scale).roundToInt()) to max(1, (doc.height * scale).roundToInt())

    /**
     * Renders the document and hands each band to [sink] as *straight* (unpremultiplied)
     * ARGB, ready for `Bitmap.setPixels`. Returns false if cancelled.
     */
    fun render(
        doc: Document,
        rasters: RasterLookup,
        options: Options,
        isCancelled: () -> Boolean = { false },
        compositor: Compositor = Compositor(),
        sink: (y0: Int, rows: Int, width: Int, straightArgb: IntArray) -> Unit,
    ): Boolean {
        val (w, h) = outputSize(doc, options.scale)
        val req = RenderRequest(
            RectD(0.0, 0.0, doc.width.toDouble(), doc.height.toDouble()),
            w, h, Sampling.Bilinear, options.background,
        )
        val band = IntArray(w * minOf(options.bandRows, h))
        var y = 0
        while (y < h) {
            val rows = minOf(options.bandRows, h - y)
            // Render rows [y, y+rows) of the full request into a band-sized buffer.
            if (!compositor.renderRows(doc, rasters, req, band, y, y + rows, isCancelled)) return false
            Pixels.unpremultiplyInPlace(band, 0, w * rows)
            sink(y, rows, w, band)
            y += rows
        }
        return true
    }
}
