package app.phopsd.engine.render

import app.phopsd.engine.geometry.RectD
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.RasterLookup
import app.phopsd.engine.raster.MipChain
import app.phopsd.engine.raster.Pixels
import kotlin.math.floor

/** A small premultiplied ARGB image (layer and document thumbnails). */
class SmallImage(val width: Int, val height: Int, val premul: IntArray)

object Thumbnails {

    /**
     * Renders a raster's own pixels (ignoring its layer transform) into at most
     * [maxEdge] x [maxEdge], box-sampled from the closest mip level.
     */
    fun raster(mips: MipChain, maxEdge: Int): SmallImage {
        val (w, h) = Compositor.fitSize(mips.width, mips.height, maxEdge)
        val ratio = mips.width.toDouble() / w
        val level = mips.levels[mips.levelFor(ratio)]
        val sx = level.width.toDouble() / w
        val sy = level.height.toDouble() / h
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val ly = floor((y + 0.5) * sy).toInt()
            for (x in 0 until w) {
                out[y * w + x] = level.pixel(floor((x + 0.5) * sx).toInt(), ly)
            }
        }
        return SmallImage(w, h, out)
    }

    /** Composite thumbnail of the whole document (Navigator / Channels). */
    fun document(doc: Document, rasters: RasterLookup, maxEdge: Int, compositor: Compositor = Compositor()): SmallImage {
        val (w, h) = Compositor.fitSize(doc.width, doc.height, maxEdge)
        val req = RenderRequest(RectD(0.0, 0.0, doc.width.toDouble(), doc.height.toDouble()), w, h)
        return SmallImage(w, h, compositor.render(doc, rasters, req))
    }

    /** Extracts one channel of a premultiplied image as an opaque grey image (0=R, 1=G, 2=B, 3=A). */
    fun channel(src: SmallImage, channel: Int): SmallImage {
        val out = IntArray(src.premul.size)
        for (i in out.indices) {
            val straight = Pixels.unpremultiply(src.premul[i])
            val v = when (channel) {
                0 -> Pixels.red(straight)
                1 -> Pixels.green(straight)
                2 -> Pixels.blue(straight)
                else -> Pixels.alpha(straight)
            }
            out[i] = Pixels.pack(255, v, v, v)
        }
        return SmallImage(src.width, src.height, out)
    }
}
