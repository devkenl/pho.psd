package app.phopsd.platform

import android.graphics.Bitmap
import app.phopsd.engine.geometry.RectD
import app.phopsd.engine.geometry.Viewport
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.RasterLookup
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.render.Compositor
import app.phopsd.engine.render.RenderRequest
import app.phopsd.engine.render.Sampling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A rendered piece of the document: [bitmap] shows document rectangle [docRect].
 * The canvas maps it to the screen with the current viewport transform, so pan/zoom
 * between renders is a cheap GPU-scaled draw.
 */
class PreviewFrame(
    val bitmap: Bitmap,
    val docRect: RectD,
    val document: Document,
    val nearest: Boolean,
)

/**
 * Two-level preview strategy (M2 foundation for M7 tiling):
 *  1. **Base**: the whole document at roughly view resolution, re-rendered only when the
 *     document changes. Pan/zoom/rotate are free.
 *  2. **Detail**: when zoomed in beyond the base's pixel density, the visible region is
 *     rendered at screen resolution after the view settles.
 * Both use the engine compositor (same maths as export), rendered in parallel row bands
 * on [Dispatchers.Default] and cancelled whenever newer state arrives.
 */
class PreviewRenderer(private val compositor: Compositor = Compositor()) {

    private val bands = max(1, min(8, Runtime.getRuntime().availableProcessors()))

    suspend fun renderBase(doc: Document, rasters: RasterLookup, maxEdge: Int): PreviewFrame {
        val (w, h) = Compositor.fitSize(doc.width, doc.height, maxEdge)
        val rect = RectD(0.0, 0.0, doc.width.toDouble(), doc.height.toDouble())
        val req = RenderRequest(rect, w, h, Sampling.Bilinear)
        return PreviewFrame(render(doc, rasters, req), rect, doc, nearest = false)
    }

    /** Returns null when the base preview is already sharp enough for this viewport. */
    suspend fun renderDetail(doc: Document, rasters: RasterLookup, vp: Viewport, basePixelsPerDocPixel: Double): PreviewFrame? {
        if (vp.zoom <= basePixelsPerDocPixel * 1.15) return null
        val visible = vp.visibleDocumentRect()
        if (visible.isEmpty) return null
        // Snap to whole document pixels so nearest-neighbour patches align with pixel edges.
        val rect = RectD(floor(visible.left), floor(visible.top), ceil(visible.right), ceil(visible.bottom))
            .intersect(RectD(0.0, 0.0, doc.width.toDouble(), doc.height.toDouble()))
        var outW = rect.width * vp.zoom
        var outH = rect.height * vp.zoom
        val cap = vp.viewWidth * vp.viewHeight * 1.5
        if (outW * outH > cap) {
            val s = sqrt(cap / (outW * outH))
            outW *= s
            outH *= s
        }
        val w = max(1, outW.toInt())
        val h = max(1, outH.toInt())
        val nearest = vp.zoom >= 2.0
        val req = RenderRequest(rect, w, h, if (nearest) Sampling.Nearest else Sampling.Bilinear)
        return PreviewFrame(render(doc, rasters, req), rect, doc, nearest)
    }

    private suspend fun render(doc: Document, rasters: RasterLookup, req: RenderRequest): Bitmap =
        withContext(Dispatchers.Default) {
            val out = IntArray(req.outWidth * req.outHeight)
            val job = coroutineContext[Job]
            val cancelled = { job?.isActive == false }
            coroutineScope {
                val rowsPerBand = (req.outHeight + bands - 1) / bands
                (0 until bands).map { b ->
                    async {
                        val from = b * rowsPerBand
                        val to = min(req.outHeight, from + rowsPerBand)
                        if (from < to) {
                            compositor.render(doc, rasters, req, out, from, to, cancelled)
                            Pixels.unpremultiplyInPlace(out, from * req.outWidth, to * req.outWidth)
                        }
                    }
                }.awaitAll()
            }
            coroutineContext.ensureActive()
            Bitmap.createBitmap(req.outWidth, req.outHeight, Bitmap.Config.ARGB_8888).apply {
                setPixels(out, 0, req.outWidth, 0, 0, req.outWidth, req.outHeight)
            }
        }
}
