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
data class RenderRequest(
    val docRect: RectD,
    val outWidth: Int,
    val outHeight: Int,
    val sampling: Sampling = Sampling.Bilinear,
    /** Premultiplied colour beneath all layers (transparent by default). */
    val background: Int = Pixels.TRANSPARENT,
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
            if (docY >= plans.minY && docY < plans.maxY) {
                for (plan in plans.list) {
                    if (docY < plan.docBounds.top || docY >= plan.docBounds.bottom) continue
                    compositeRow(plan, request, docY, pxW, acc, tmp, blendOut)
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
    )

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
                    list += LayerPlan(
                        raster = raster,
                        docToLevel = docToLayer.then(Affine.scale(sx, sy)),
                        opacity = layer.opacity,
                        mode = layer.blendMode,
                        nearest = nearest,
                        docBounds = bounds,
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
                val u = inv.mapX(docX, docY)
                val v = inv.mapY(docX, docY)
                Pixels.alpha(mips.base.pixel(floor(u).toInt(), floor(v).toInt()))
            }
        }
    }

    companion object {
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
