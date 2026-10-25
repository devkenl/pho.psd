package app.phopsd.engine.edit

import app.phopsd.engine.geometry.Affine
import app.phopsd.engine.geometry.IntRect
import app.phopsd.engine.geometry.RectD
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.Layer
import app.phopsd.engine.model.RasterLookup
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.raster.SolidTiles
import app.phopsd.engine.raster.TiledRaster
import app.phopsd.engine.render.Compositor
import app.phopsd.engine.render.RenderRequest
import app.phopsd.engine.render.Sampling
import app.phopsd.engine.selection.SelectionRaster
import kotlin.math.floor

/** A new raster plus the region (layer pixels) that differs from the one it was derived from. */
class RasterEdit(val raster: TiledRaster, val dirty: IntRect)

/**
 * Destructive-but-undoable pixel operations. Every function returns a *new* frozen raster
 * (copy-on-write where possible); inputs are never modified, so undo history stays valid.
 *
 * Selections are document-space coverage rasters; [layerToDoc] maps layer pixels into the
 * document so operations stay correct on moved, scaled or rotated layers.
 */
object PixelOps {

    /** Selection coverage (0..1) at the centre of layer pixel ([px], [py]). */
    private fun coverage(sel: TiledRaster?, layerToDoc: Affine, px: Int, py: Int): Double {
        if (sel == null) return 1.0
        val dx = layerToDoc.mapX(px + 0.5, py + 0.5)
        val dy = layerToDoc.mapY(px + 0.5, py + 0.5)
        return Pixels.alpha(sel.pixel(floor(dx).toInt(), floor(dy).toInt())) / 255.0
    }

    /** Layer pixels that a selection can affect (whole layer without a selection). */
    fun affectedRect(width: Int, height: Int, layerToDoc: Affine, sel: TiledRaster?): IntRect? {
        val full = IntRect(0, 0, width, height)
        if (sel == null || Pixels.alpha(sel.fill) != 0) return full
        val b = SelectionRaster.bounds(sel) ?: return null
        val inv = layerToDoc.invert() ?: return full
        val r = IntRect.enclosing(inv.mapRectBounds(b.toRectD()))
        val e = IntRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1).intersect(full)
        return if (e.isEmpty) null else e
    }

    private inline fun mapPixels(raster: TiledRaster, rect: IntRect, f: (x: Int, y: Int, p: Int) -> Int): RasterEdit {
        val out = raster.derive()
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
            val p = raster.pixel(x, y)
            val n = f(x, y, p)
            if (n != p) out.setPixel(x, y, n)
        }
        return RasterEdit(out.freeze(), rect)
    }

    private fun scale(p: Int, k: Double): Int {
        if (k >= 1.0) return p
        if (k <= 0.0) return 0
        fun c(v: Int) = (v * k + 0.5).toInt().coerceIn(0, 255)
        return Pixels.pack(c(Pixels.alpha(p)), c(Pixels.red(p)), c(Pixels.green(p)), c(Pixels.blue(p)))
    }

    /** Deletes pixels inside the selection (Edit ▸ Clear / Delete key). */
    fun clear(raster: TiledRaster, layerToDoc: Affine, sel: TiledRaster?): RasterEdit? {
        val rect = affectedRect(raster.width, raster.height, layerToDoc, sel) ?: return null
        return mapPixels(raster, rect) { x, y, p -> scale(p, 1.0 - coverage(sel, layerToDoc, x, y)) }
    }

    /** Fills the selection (or whole layer) with an opaque colour, honouring partial coverage. */
    fun fill(raster: TiledRaster, layerToDoc: Affine, sel: TiledRaster?, straightRgb: Int): RasterEdit? {
        val rect = affectedRect(raster.width, raster.height, layerToDoc, sel) ?: return null
        val r = (straightRgb shr 16) and 0xFF; val g = (straightRgb shr 8) and 0xFF; val b = straightRgb and 0xFF
        return mapPixels(raster, rect) { x, y, p ->
            val c = coverage(sel, layerToDoc, x, y)
            if (c <= 0.0) p else {
                val inv = 1.0 - c
                fun m(src: Int, dst: Int) = (src * c + dst * inv + 0.5).toInt().coerceIn(0, 255)
                Pixels.pack(m(255, Pixels.alpha(p)), m(r, Pixels.red(p)), m(g, Pixels.green(p)), m(b, Pixels.blue(p)))
            }
        }
    }

    /** Sets mask values inside the selection (or everywhere) to [value] (0 conceal … 255 reveal). */
    fun fillMask(mask: TiledRaster, layerToDoc: Affine, sel: TiledRaster?, value: Int): RasterEdit? {
        val rect = affectedRect(mask.width, mask.height, layerToDoc, sel) ?: return null
        val v = value.coerceIn(0, 255)
        return mapPixels(mask, rect) { x, y, p ->
            val c = coverage(sel, layerToDoc, x, y)
            if (c <= 0.0) p else {
                val a = Pixels.alpha(p)
                Pixels.pack((a + (v - a) * c + 0.5).toInt().coerceIn(0, 255), 0, 0, 0)
            }
        }
    }

    /** The selected part of a layer as a new raster of the same size (Copy / Layer via Copy). */
    fun extract(raster: TiledRaster, layerToDoc: Affine, sel: TiledRaster?): TiledRaster {
        val out = TiledRaster(raster.width, raster.height)
        val rect = affectedRect(raster.width, raster.height, layerToDoc, sel) ?: return out.freeze()
        val row = IntArray(raster.width)
        for (y in rect.top until rect.bottom) {
            row.fill(0)
            for (x in rect.left until rect.right) row[x] = scale(raster.pixel(x, y), coverage(sel, layerToDoc, x, y))
            out.writeRows(y, 1, row, 0, raster.width, straightAlpha = false)
        }
        return out.freeze()
    }

    /** A layer mask filled with one value (255 reveal all, 0 hide all); costs no pixel memory. */
    fun solidMask(width: Int, height: Int, value: Int): TiledRaster =
        TiledRaster.solid(width, height, Pixels.pack(value.coerceIn(0, 255), 0, 0, 0))

    /** A layer mask (layer pixel space) from a document selection. */
    fun maskFromSelection(width: Int, height: Int, layerToDoc: Affine, sel: TiledRaster, reveal: Boolean): TiledRaster {
        val out = TiledRaster(width, height, fill = Pixels.pack(if (reveal) 0 else 255, 0, 0, 0))
        val ts = out.tileSize
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = (coverage(sel, layerToDoc, x, y) * 255 + 0.5).toInt().coerceIn(0, 255)
                row[x] = Pixels.pack(if (reveal) c else 255 - c, 0, 0, 0)
            }
            out.writeRows(y, 1, row, 0, width, straightAlpha = false)
        }
        // Share uniform tiles so large solid regions cost nothing.
        for (ty in 0 until out.tilesY) for (tx in 0 until out.tilesX) {
            val t = out.tileOrNull(tx, ty) ?: continue
            val first = t[0]
            if (t.all { it == first }) out.putSharedTile(tx, ty, SolidTiles.of(first, ts))
        }
        return out.freeze()
    }

    fun invertMask(mask: TiledRaster): TiledRaster {
        val ts = mask.tileSize
        val out = TiledRaster(mask.width, mask.height, ts, Pixels.pack(255 - Pixels.alpha(mask.fill), 0, 0, 0))
        for (ty in 0 until mask.tilesY) for (tx in 0 until mask.tilesX) {
            val t = mask.tileOrNull(tx, ty) ?: continue
            out.putTile(tx, ty, IntArray(ts * ts) { Pixels.pack(255 - Pixels.alpha(t[it]), 0, 0, 0) })
        }
        return out.freeze()
    }

    /** Bakes a mask into layer pixels (Layer ▸ Layer Mask ▸ Apply). */
    fun applyMask(raster: TiledRaster, mask: TiledRaster): TiledRaster {
        require(raster.width == mask.width && raster.height == mask.height)
        val out = raster.derive()
        for (y in 0 until raster.height) for (x in 0 until raster.width) {
            val m = Pixels.alpha(mask.pixel(x, y))
            if (m == 255) continue
            out.setPixel(x, y, scale(raster.pixel(x, y), m / 255.0))
        }
        return out.freeze()
    }

    /**
     * Renders [layers] (with their opacity, blend modes and masks) into one document-sized
     * raster at full resolution — the basis of Merge Down and Flatten. Pixels outside the
     * canvas are not kept.
     */
    fun merge(doc: Document, rasters: RasterLookup, layers: List<Layer>, isCancelled: () -> Boolean = { false }): TiledRaster? {
        val temp = doc.copy(layers = layers, activeLayerId = layers.lastOrNull()?.id, selection = null)
        val w = doc.width
        val h = doc.height
        val out = TiledRaster(w, h)
        val req = RenderRequest(RectD(0.0, 0.0, w.toDouble(), h.toDouble()), w, h, Sampling.Bilinear)
        val compositor = Compositor()
        val bandRows = 64
        val band = IntArray(w * bandRows)
        var y = 0
        while (y < h) {
            val rows = minOf(bandRows, h - y)
            if (!compositor.renderRows(temp, rasters, req, band, y, y + rows, isCancelled)) return null
            out.writeRows(y, rows, band, 0, w, straightAlpha = false)
            y += rows
        }
        return out.freeze()
    }
}
