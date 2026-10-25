package app.phopsd.platform

import android.graphics.Bitmap
import app.phopsd.engine.geometry.RectD
import app.phopsd.engine.geometry.Viewport
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.RasterLookup
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.render.Compositor
import app.phopsd.engine.render.MaskDisplay
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
    val maskDisplay: MaskDisplay? = null,
    /** Bumped when [bitmap] was patched in place (a new frame object makes Compose redraw). */
    val version: Long = 0,
) {
    fun patched(doc: Document = document) = PreviewFrame(bitmap, docRect, doc, nearest, maskDisplay, version + 1)
}

/** Freshly rendered pixels (straight ARGB) for the region [x], [y], [w]×[h] of a frame's bitmap. */
class FramePatch(val frame: PreviewFrame, val x: Int, val y: Int, val w: Int, val h: Int, val pixels: IntArray) {
    /** Must run on the main thread (the bitmap may be drawing). */
    fun apply() {
        if (!frame.bitmap.isRecycled) frame.bitmap.setPixels(pixels, 0, w, x, y, w, h)
    }
}

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

    suspend fun renderBase(doc: Document, rasters: RasterLookup, maxEdge: Int, maskDisplay: MaskDisplay? = null): PreviewFrame {
        val (w, h) = Compositor.fitSize(doc.width, doc.height, maxEdge)
        val rect = RectD(0.0, 0.0, doc.width.toDouble(), doc.height.toDouble())
        val req = RenderRequest(rect, w, h, Sampling.Bilinear, maskDisplay = maskDisplay)
        return PreviewFrame(render(doc, rasters, req), rect, doc, nearest = false, maskDisplay = maskDisplay)
    }

    /**
     * Re-renders only the pixels of [frame] that cover [dirtyDoc] (live brush strokes). The
     * sub-request uses exactly the frame's pixel grid and sampling, so patches are seamless.
     * Returns null when the region does not overlap the frame. Call [FramePatch.apply] on the
     * main thread.
     */
    fun patch(frame: PreviewFrame, doc: Document, rasters: RasterLookup, dirtyDoc: RectD): FramePatch? {
        val bmp = frame.bitmap
        val r = frame.docRect
        val sx = r.width / bmp.width
        val sy = r.height / bmp.height
        val i0 = max(0, floor((dirtyDoc.left - r.left) / sx).toInt() - 1)
        val j0 = max(0, floor((dirtyDoc.top - r.top) / sy).toInt() - 1)
        val i1 = min(bmp.width, ceil((dirtyDoc.right - r.left) / sx).toInt() + 1)
        val j1 = min(bmp.height, ceil((dirtyDoc.bottom - r.top) / sy).toInt() + 1)
        if (i0 >= i1 || j0 >= j1) return null
        val w = i1 - i0
        val h = j1 - j0
        val req = RenderRequest(
            RectD(r.left + i0 * sx, r.top + j0 * sy, r.left + i1 * sx, r.top + j1 * sy), w, h,
            if (frame.nearest) Sampling.Nearest else Sampling.Bilinear,
            maskDisplay = frame.maskDisplay,
        )
        val out = IntArray(w * h)
        compositor.render(doc, rasters, req, out)
        Pixels.unpremultiplyInPlace(out)
        return FramePatch(frame, i0, j0, w, h, out)
    }

    /** Returns null when the base preview is already sharp enough for this viewport. */
    suspend fun renderDetail(doc: Document, rasters: RasterLookup, vp: Viewport, basePixelsPerDocPixel: Double, maskDisplay: MaskDisplay? = null): PreviewFrame? {
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
        val req = RenderRequest(rect, w, h, if (nearest) Sampling.Nearest else Sampling.Bilinear, maskDisplay = maskDisplay)
        return PreviewFrame(render(doc, rasters, req), rect, doc, nearest, maskDisplay)
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
