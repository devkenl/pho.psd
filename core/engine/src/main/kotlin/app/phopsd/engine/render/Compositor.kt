package app.phopsd.engine.render

import app.phopsd.engine.geometry.Affine
import app.phopsd.engine.geometry.RectD
import app.phopsd.engine.model.BlendMode
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.Layer
import app.phopsd.engine.model.LayerId
import app.phopsd.engine.model.RasterLayer
import app.phopsd.engine.model.RasterLookup
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.raster.TiledRaster
import kotlin.math.floor
import kotlin.math.roundToInt

enum class Sampling {
    /** Bilinear on the appropriate mip level. Used for fit/zoomed-out previews and export. */
    Bilinear,

    /** Nearest neighbour on level 0. Used at high magnification for pixel-accurate display. */
    Nearest,
}

/**
 * What to render: the document rectangle [docRect] resampled into an output buffer of
 * [outWidth] x [outHeight] pixels. Output pixel (i, j) represents the document point at its
 * centre. `docRect = (0,0,w,h)` with `out = (w,h)` is a 1:1 full-resolution render (export).
 */
/** Mask visualisation for one layer (editor display only; never used for export). */
data class MaskDisplay(val layerId: LayerId, val mode: Mode) {
    enum class Mode {
        /** Show only the mask as a grayscale image (white = revealed). */
        Alone,
        /** Normal composite with concealed areas tinted red (a "rubylith" overlay). */
        Overlay,
    }
}

data class RenderRequest(
    val docRect: RectD,
    val outWidth: Int,
    val outHeight: Int,
    val sampling: Sampling = Sampling.Bilinear,
    /** Premultiplied colour beneath all layers (transparent by default). */
    val background: Int = Pixels.TRANSPARENT,
    val maskDisplay: MaskDisplay? = null,
) {
    init {
        require(outWidth > 0 && outHeight > 0) { "Output must be at least 1x1" }
        require(!docRect.isEmpty) { "Document rectangle is empty" }
    }

    val docPerOutX: Double get() = docRect.width / outWidth
    val docPerOutY: Double get() = docRect.height / outHeight
}

/**
 * CPU reference compositor.
 *
 * Correctness first: float accumulation in premultiplied space, W3C blend formulas,
 * per-layer opacity, non-destructive layer transforms sampled from original pixels.
 * The same code path renders previews and exports (only resolution and sampling differ),
 * so what the user sees is what gets exported. A GPU backend (M7) must match this output
 * within tolerance; golden tests in `CompositorTest` pin the behaviour.
 *
 * Thread-safety: stateless; callers may render disjoint row bands concurrently into the
 * same output array.
 */
class Compositor {

    /**
     * Renders rows [rowFrom, rowTo) of [request] into [out] (premultiplied ARGB, row stride
     * = outWidth). Returns false if [isCancelled] stopped the render early.
     */
    fun render(
        doc: Document,
        rasters: RasterLookup,
        request: RenderRequest,
        out: IntArray,
        rowFrom: Int = 0,
        rowTo: Int = request.outHeight,
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        require(out.size >= request.outWidth * request.outHeight) { "Output buffer too small" }
        return renderInto(doc, rasters, request, out, rowFrom, rowTo, outBaseRow = 0, isCancelled)
    }

    /**
     * Renders rows [rowFrom, rowTo) of [request] into a band buffer: row j is written at
     * index (j - rowFrom) * outWidth. Used by export to stream bands of any size.
     */
    fun renderRows(
        doc: Document,
        rasters: RasterLookup,
        request: RenderRequest,
        band: IntArray,
        rowFrom: Int,
        rowTo: Int,
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        require(rowFrom in 0..rowTo && rowTo <= request.outHeight) { "Invalid row range" }
        require(band.size >= request.outWidth * (rowTo - rowFrom)) { "Band buffer too small" }
        return renderInto(doc, rasters, request, band, rowFrom, rowTo, outBaseRow = rowFrom, isCancelled)
    }

    private fun renderInto(
        doc: Document,
        rasters: RasterLookup,
        request: RenderRequest,
        out: IntArray,
        rowFrom: Int,
        rowTo: Int,
        outBaseRow: Int,
        isCancelled: () -> Boolean,
    ): Boolean {
        val plans = planLayers(doc, rasters, request)
        val w = request.outWidth
        val acc = FloatArray(w * 4)
        val tmp = FloatArray(4)
        val blendOut = FloatArray(3)
        val bgA = Pixels.alpha(request.background) / 255f
        val bgR = Pixels.red(request.background) / 255f
        val bgG = Pixels.green(request.background) / 255f
        val bgB = Pixels.blue(request.background) / 255f
        val pxW = request.docPerOutX
        val pxH = request.docPerOutY

        for (j in rowFrom until rowTo) {
            if (isCancelled()) return false
            for (i in 0 until w) {
                val k = i * 4
                acc[k] = bgR; acc[k + 1] = bgG; acc[k + 2] = bgB; acc[k + 3] = bgA
            }
            val docY = request.docRect.top + (j + 0.5) * pxH
            val display = request.maskDisplay
            val displayPlan = display?.let { d -> plans.list.firstOrNull { it.layerId == d.layerId && it.mask != null } }
            if (display?.mode == MaskDisplay.Mode.Alone && displayPlan != null) {
                if (docY >= displayPlan.docBounds.top && docY < displayPlan.docBounds.bottom) {
                    maskAloneRow(displayPlan, request, docY, pxW, acc)
                }
            } else if (docY >= plans.minY && docY < plans.maxY) {
                for (plan in plans.list) {
                    if (docY < plan.docBounds.top || docY >= plan.docBounds.bottom) continue
                    compositeRow(plan, request, docY, pxW, acc, tmp, blendOut)
                }
                if (display?.mode == MaskDisplay.Mode.Overlay && displayPlan != null &&
                    docY >= displayPlan.docBounds.top && docY < displayPlan.docBounds.bottom
                ) {
                    overlayRow(displayPlan, request, docY, pxW, acc)
                }
            }
            val rowBase = (j - outBaseRow) * w
            for (i in 0 until w) {
                val k = i * 4
                val a = acc[k + 3].coerceIn(0f, 1f)
                val ai = (a * 255f + 0.5f).toInt()
                if (ai == 0) {
                    out[rowBase + i] = 0
                    continue
                }
                val r = (acc[k].coerceIn(0f, a) * 255f + 0.5f).toInt().coerceAtMost(ai)
                val g = (acc[k + 1].coerceIn(0f, a) * 255f + 0.5f).toInt().coerceAtMost(ai)
                val b = (acc[k + 2].coerceIn(0f, a) * 255f + 0.5f).toInt().coerceAtMost(ai)
                out[rowBase + i] = Pixels.pack(ai, r, g, b)
            }
        }
        return true
    }

    /** Convenience: renders a whole request into a new buffer on the calling thread. */
    fun render(doc: Document, rasters: RasterLookup, request: RenderRequest): IntArray {
        val out = IntArray(request.outWidth * request.outHeight)
        render(doc, rasters, request, out)
        return out
    }

    private class LayerPlan(
        val raster: TiledRaster,
        /** Document -> sampled-level pixel coordinates. */
        val docToLevel: Affine,
        val opacity: Float,
        val mode: BlendMode,
        val nearest: Boolean,
        val docBounds: RectD,
        /** Mask raster (alpha = reveal), or null. */
        val mask: TiledRaster?,
        /** Sampled-level pixel -> mask pixel scale (1 when the mask level matches). */
        val maskSx: Double,
        val maskSy: Double,
        val layerId: LayerId,
    )

    private fun maskAt(plan: LayerPlan, u: Double, v: Double): Float =
        plan.mask?.let { sampleAlpha(it, u * plan.maskSx, v * plan.maskSy, plan.nearest) } ?: 1f

    private class Plans(val list: List<LayerPlan>, val minY: Double, val maxY: Double)

    private fun planLayers(doc: Document, rasters: RasterLookup, req: RenderRequest): Plans {
        val list = ArrayList<LayerPlan>()
        val docPerOut = maxOf(req.docPerOutX, req.docPerOutY)
        for (layer in doc.layers) {
            if (!layer.visible || layer.opacity <= 0f) continue
            when (layer) {
                is RasterLayer -> {
                    val mips = rasters.mips(layer.rasterId) ?: continue
                    val layerToDoc = layer.transform.layerToDocument(layer.width, layer.height)
                    val docToLayer = layerToDoc.invert() ?: continue
                    val bounds = layerToDoc.mapRectBounds(RectD(0.0, 0.0, layer.width.toDouble(), layer.height.toDouble()))
                        .let { RectD(it.left - 1, it.top - 1, it.right + 1, it.bottom + 1) }
                        .intersect(req.docRect)
                    if (bounds.isEmpty) continue
                    val nearest = req.sampling == Sampling.Nearest
                    val sourcePerOut = docToLayer.meanScale * docPerOut
                    val level = if (nearest) 0 else mips.levelFor(sourcePerOut)
                    val raster = mips.levels[level]
                    // Level pixel = layer pixel scaled by the actual level/base size ratio.
                    val sx = raster.width.toDouble() / mips.width
                    val sy = raster.height.toDouble() / mips.height
                    val maskMips = layer.mask?.takeIf { it.enabled }?.let { rasters.mips(it.rasterId) }
                    // Prefer the mask level matching the layer level; otherwise (e.g. a live
                    // single-level mask during a stroke) sample the mask's base with scaling.
                    // A mask must never be silently ignored: that would reveal hidden pixels.
                    val maskLevel = maskMips?.let { m ->
                        m.levels.getOrNull(level)?.takeIf { it.width == raster.width && it.height == raster.height }
                            ?: m.base
                    }
                    list += LayerPlan(
                        raster = raster,
                        docToLevel = docToLayer.then(Affine.scale(sx, sy)),
                        opacity = layer.opacity,
                        mode = layer.blendMode,
                        nearest = nearest,
                        docBounds = bounds,
                        mask = maskLevel,
                        maskSx = maskLevel?.let { it.width.toDouble() / raster.width } ?: 1.0,
                        maskSy = maskLevel?.let { it.height.toDouble() / raster.height } ?: 1.0,
                        layerId = layer.id,
                    )
                }
            }
        }
        val minY = list.minOfOrNull { it.docBounds.top } ?: 0.0
        val maxY = list.maxOfOrNull { it.docBounds.bottom } ?: 0.0
        return Plans(list, minY, maxY)
    }

    private fun compositeRow(
        plan: LayerPlan,
        req: RenderRequest,
        docY: Double,
        pxW: Double,
        acc: FloatArray,
        tmp: FloatArray,
        blendOut: FloatArray,
    ) {
        val m = plan.docToLevel
        val w = req.outWidth
        val x0 = req.docRect.left + 0.5 * pxW
        // Output columns overlapping the layer's document bounds.
        val iStart = floor((plan.docBounds.left - req.docRect.left) / pxW).toInt().coerceIn(0, w)
        val iEnd = (floor((plan.docBounds.right - req.docRect.left) / pxW).toInt() + 1).coerceIn(0, w)
        if (iStart >= iEnd) return
        val firstX = x0 + iStart * pxW
        var u = m.mapX(firstX, docY)
        var v = m.mapY(firstX, docY)
        val du = m.a * pxW
        val dv = m.b * pxW
        val raster = plan.raster
        val rw = raster.width
        val rh = raster.height
        val opacity = plan.opacity
        val normal = plan.mode == BlendMode.Normal
        val separable = BlendMath.isSeparable(plan.mode)

        for (i in iStart until iEnd) {
            val uu = u
            val vv = v
            u += du
            v += dv
            if (uu < -1.0 || vv < -1.0 || uu > rw + 1.0 || vv > rh + 1.0) continue
            if (plan.nearest) sampleNearest(raster, uu, vv, tmp) else sampleBilinear(raster, uu, vv, tmp)
            if (plan.mask != null) {
                val m = maskAt(plan, uu, vv)
                tmp[0] *= m; tmp[1] *= m; tmp[2] *= m; tmp[3] *= m
            }
            val sa = tmp[3] * opacity
            if (sa <= 0f) continue
            val sr = tmp[0] * opacity
            val sg = tmp[1] * opacity
            val sb = tmp[2] * opacity
            val k = i * 4
            val ab = acc[k + 3]
            if (normal || ab <= 0f) {
                // Source-over. With a transparent backdrop every blend mode reduces to this.
                val inv = 1f - sa
                acc[k] = sr + acc[k] * inv
                acc[k + 1] = sg + acc[k + 1] * inv
                acc[k + 2] = sb + acc[k + 2] * inv
                acc[k + 3] = sa + ab * inv
                continue
            }
            val br = acc[k] / ab
            val bg = acc[k + 1] / ab
            val bb = acc[k + 2] / ab
            val csr = sr / sa
            val csg = sg / sa
            val csb = sb / sa
            val rr: Float
            val rg: Float
            val rb: Float
            if (separable) {
                rr = BlendMath.separable(plan.mode, br, csr)
                rg = BlendMath.separable(plan.mode, bg, csg)
                rb = BlendMath.separable(plan.mode, bb, csb)
            } else {
                BlendMath.nonSeparable(plan.mode, br, bg, bb, csr, csg, csb, blendOut)
                rr = blendOut[0]; rg = blendOut[1]; rb = blendOut[2]
            }
            val both = sa * ab
            acc[k] = (1f - ab) * sr + (1f - sa) * acc[k] + both * rr
            acc[k + 1] = (1f - ab) * sg + (1f - sa) * acc[k + 1] + both * rg
            acc[k + 2] = (1f - ab) * sb + (1f - sa) * acc[k + 2] + both * rb
            acc[k + 3] = sa + ab * (1f - sa)
        }
    }

    /** Iterates the output columns of one row that fall inside [plan]'s layer. */
    private inline fun forLayerColumns(plan: LayerPlan, req: RenderRequest, docY: Double, pxW: Double, f: (i: Int, u: Double, v: Double) -> Unit) {
        val m = plan.docToLevel
        val w = req.outWidth
        val iStart = floor((plan.docBounds.left - req.docRect.left) / pxW).toInt().coerceIn(0, w)
        val iEnd = (floor((plan.docBounds.right - req.docRect.left) / pxW).toInt() + 1).coerceIn(0, w)
        val firstX = req.docRect.left + (iStart + 0.5) * pxW
        var u = m.mapX(firstX, docY)
        var v = m.mapY(firstX, docY)
        val du = m.a * pxW
        val dv = m.b * pxW
        val rw = plan.raster.width
        val rh = plan.raster.height
        for (i in iStart until iEnd) {
            if (u >= 0 && v >= 0 && u < rw && v < rh) f(i, u, v)
            u += du
            v += dv
        }
    }

    private fun maskAloneRow(plan: LayerPlan, req: RenderRequest, docY: Double, pxW: Double, acc: FloatArray) {
        if (plan.mask == null) return
        forLayerColumns(plan, req, docY, pxW) { i, u, v ->
            val g = maskAt(plan, u, v)
            val k = i * 4
            acc[k] = g; acc[k + 1] = g; acc[k + 2] = g; acc[k + 3] = 1f
        }
    }

    private fun overlayRow(plan: LayerPlan, req: RenderRequest, docY: Double, pxW: Double, acc: FloatArray) {
        if (plan.mask == null) return
        forLayerColumns(plan, req, docY, pxW) { i, u, v ->
            val a = OVERLAY_ALPHA * (1f - maskAt(plan, u, v))
            if (a > 0f) {
                val k = i * 4
                val inv = 1f - a
                acc[k] = a + acc[k] * inv
                acc[k + 1] = acc[k + 1] * inv
                acc[k + 2] = acc[k + 2] * inv
                acc[k + 3] = a + acc[k + 3] * inv
            }
        }
    }

    /** Mask value 0..1 at mask pixel coordinates (alpha channel). */
    private fun sampleAlpha(r: TiledRaster, u: Double, v: Double, nearest: Boolean): Float {
        if (nearest) return Pixels.alpha(r.pixel(floor(u).toInt(), floor(v).toInt())) / 255f
        val fx = u - 0.5
        val fy = v - 0.5
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = (fx - x0).toFloat()
        val ty = (fy - y0).toFloat()
        // Clamp to the edge so masks don't fade out at the layer border.
        val xa = x0.coerceIn(0, r.width - 1); val xb = (x0 + 1).coerceIn(0, r.width - 1)
        val ya = y0.coerceIn(0, r.height - 1); val yb = (y0 + 1).coerceIn(0, r.height - 1)
        val a00 = Pixels.alpha(r.pixel(xa, ya)); val a10 = Pixels.alpha(r.pixel(xb, ya))
        val a01 = Pixels.alpha(r.pixel(xa, yb)); val a11 = Pixels.alpha(r.pixel(xb, yb))
        return ((a00 * (1 - tx) + a10 * tx) * (1 - ty) + (a01 * (1 - tx) + a11 * tx) * ty) / 255f
    }

    private fun sampleNearest(r: TiledRaster, u: Double, v: Double, out: FloatArray) {
        val p = r.pixel(floor(u).toInt(), floor(v).toInt())
        out[0] = Pixels.red(p) / 255f
        out[1] = Pixels.green(p) / 255f
        out[2] = Pixels.blue(p) / 255f
        out[3] = Pixels.alpha(p) / 255f
    }

    /** Bilinear filter in premultiplied space; pixel centres sit at (i + 0.5, j + 0.5). */
    private fun sampleBilinear(r: TiledRaster, u: Double, v: Double, out: FloatArray) {
        val fx = u - 0.5
        val fy = v - 0.5
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = (fx - x0).toFloat()
        val ty = (fy - y0).toFloat()
        if (tx == 0f && ty == 0f) {
            val p = r.pixel(x0, y0)
            out[0] = Pixels.red(p) / 255f
            out[1] = Pixels.green(p) / 255f
            out[2] = Pixels.blue(p) / 255f
            out[3] = Pixels.alpha(p) / 255f
            return
        }
        val p00 = r.pixel(x0, y0)
        val p10 = r.pixel(x0 + 1, y0)
        val p01 = r.pixel(x0, y0 + 1)
        val p11 = r.pixel(x0 + 1, y0 + 1)
        val w00 = (1f - tx) * (1f - ty)
        val w10 = tx * (1f - ty)
        val w01 = (1f - tx) * ty
        val w11 = tx * ty
        out[0] = (Pixels.red(p00) * w00 + Pixels.red(p10) * w10 + Pixels.red(p01) * w01 + Pixels.red(p11) * w11) / 255f
        out[1] = (Pixels.green(p00) * w00 + Pixels.green(p10) * w10 + Pixels.green(p01) * w01 + Pixels.green(p11) * w11) / 255f
        out[2] = (Pixels.blue(p00) * w00 + Pixels.blue(p10) * w10 + Pixels.blue(p01) * w01 + Pixels.blue(p11) * w11) / 255f
        out[3] = (Pixels.alpha(p00) * w00 + Pixels.alpha(p10) * w10 + Pixels.alpha(p01) * w01 + Pixels.alpha(p11) * w11) / 255f
    }

    /**
     * Top-most visible layer with meaningful opacity at a document point (Move tool
     * auto-select). Locked layers are still hit so the UI can explain why they won't move.
     */
    fun hitTest(doc: Document, rasters: RasterLookup, docX: Double, docY: Double, minAlpha: Int = 8): LayerId? {
        for (layer in doc.layers.asReversed()) {
            if (!layer.visible || layer.opacity <= 0f) continue
            if (alphaAt(layer, rasters, docX, docY) >= minAlpha) return layer.id
        }
        return null
    }

    private fun alphaAt(layer: Layer, rasters: RasterLookup, docX: Double, docY: Double): Int = when (layer) {
        is RasterLayer -> {
            val mips = rasters.mips(layer.rasterId)
            val inv = layer.transform.layerToDocument(layer.width, layer.height).invert()
            if (mips == null || inv == null) 0 else {
                val u = floor(inv.mapX(docX, docY)).toInt()
                val v = floor(inv.mapY(docX, docY)).toInt()
                val a = Pixels.alpha(mips.base.pixel(u, v))
                val m = layer.mask?.takeIf { it.enabled }?.let { rasters.mips(it.rasterId)?.base }
                if (m == null) a else Pixels.mulDiv255(a, Pixels.alpha(m.pixel(u, v)))
            }
        }
    }

    /**
     * The composite colour (premultiplied ARGB) of the single document pixel containing
     * ([docX], [docY]) — what the Eyedropper samples.
     */
    fun sampleColor(doc: Document, rasters: RasterLookup, docX: Double, docY: Double): Int {
        val x = floor(docX)
        val y = floor(docY)
        val req = RenderRequest(RectD(x, y, x + 1, y + 1), 1, 1, Sampling.Nearest)
        return render(doc, rasters, req)[0]
    }

    companion object {
        private const val OVERLAY_ALPHA = 0.5f

        /** Document-space axis-aligned bounds of a layer (for overlays and hit areas). */
        fun layerBounds(layer: Layer): RectD = when (layer) {
            is RasterLayer -> layer.transform.layerToDocument(layer.width, layer.height)
                .mapRectBounds(RectD(0.0, 0.0, layer.width.toDouble(), layer.height.toDouble()))
        }

        /** Output size that fits a document into [maxEdge] keeping aspect ratio. */
        fun fitSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
            if (width <= maxEdge && height <= maxEdge) return width to height
            val s = maxEdge.toDouble() / maxOf(width, height)
            return maxOf(1, (width * s).roundToInt()) to maxOf(1, (height * s).roundToInt())
        }
    }
}
