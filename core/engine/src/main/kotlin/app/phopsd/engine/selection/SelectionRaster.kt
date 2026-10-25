package app.phopsd.engine.selection

import app.phopsd.engine.geometry.IntRect
import app.phopsd.engine.model.SelectionOp
import app.phopsd.engine.model.SelectionShape
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.raster.SolidTiles
import app.phopsd.engine.raster.TiledRaster
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Selections are document-sized coverage rasters (alpha channel, 0..255; colour channels
 * are 0). This object rasterizes shapes with anti-aliased edges and combines selections.
 *
 * Efficiency: tiles entirely outside a shape stay unallocated, tiles entirely inside share
 * one immutable solid tile, so even a full-canvas selection of a 50 MP document costs almost
 * no memory. Only edge tiles hold real pixel data.
 */
object SelectionRaster {
    private val FULL = Pixels.pack(255, 0, 0, 0)
    private const val SUBSAMPLES = 4

    fun all(width: Int, height: Int): TiledRaster = TiledRaster.solid(width, height, FULL)

    fun empty(width: Int, height: Int): TiledRaster = TiledRaster.solid(width, height, Pixels.TRANSPARENT)

    /** Coverage (0..1) at document pixel ([x], [y]); outside the document is unselected. */
    fun coverage(sel: TiledRaster, x: Int, y: Int): Double = Pixels.alpha(sel.pixel(x, y)) / 255.0

    fun rasterize(shape: SelectionShape, width: Int, height: Int): TiledRaster =
        rasterizePolygon(polygon(shape), width, height)

    /** Converts any shape to a closed polygon (x, y pairs) in document coordinates. */
    fun polygon(shape: SelectionShape): DoubleArray = when (shape) {
        is SelectionShape.Rect -> {
            val l = min(shape.left, shape.right); val r = max(shape.left, shape.right)
            val t = min(shape.top, shape.bottom); val b = max(shape.top, shape.bottom)
            doubleArrayOf(l, t, r, t, r, b, l, b)
        }
        is SelectionShape.Ellipse -> {
            val cx = (shape.left + shape.right) / 2; val cy = (shape.top + shape.bottom) / 2
            val rx = abs(shape.right - shape.left) / 2; val ry = abs(shape.bottom - shape.top) / 2
            val perimeter = PI * (3 * (rx + ry) - sqrt((3 * rx + ry) * (rx + 3 * ry)))
            val n = ceil(perimeter / 3.0).toInt().coerceIn(24, 4096)
            DoubleArray(n * 2).also { a ->
                for (i in 0 until n) {
                    val th = 2 * PI * i / n
                    a[2 * i] = cx + rx * cos(th)
                    a[2 * i + 1] = cy + ry * sin(th)
                }
            }
        }
        is SelectionShape.Polygon -> shape.xy
    }

    /**
     * Scanline rasterization with non-zero winding, [SUBSAMPLES] sub-scanlines per pixel row
     * and exact horizontal span coverage, giving smooth anti-aliased edges.
     */
    fun rasterizePolygon(xy: DoubleArray, width: Int, height: Int): TiledRaster {
        val out = TiledRaster(width, height, fill = Pixels.TRANSPARENT)
        val n = xy.size / 2
        if (n < 3) return out.freeze()
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        for (i in 0 until n) {
            minX = min(minX, xy[2 * i]); maxX = max(maxX, xy[2 * i])
            minY = min(minY, xy[2 * i + 1]); maxY = max(maxY, xy[2 * i + 1])
        }
        val rowStart = max(0, floor(minY).toInt())
        val rowEnd = min(height, ceil(maxY).toInt())
        val colStart = max(0, floor(minX).toInt())
        val colEnd = min(width, ceil(maxX).toInt() + 1)
        if (rowStart >= rowEnd || colStart >= colEnd) return out.freeze()

        val acc = FloatArray(colEnd - colStart + 2)
        val crossX = DoubleArray(n)
        val crossW = IntArray(n)
        val rowAlpha = IntArray(width)

        for (y in rowStart until rowEnd) {
            acc.fill(0f)
            for (s in 0 until SUBSAMPLES) {
                val sy = y + (s + 0.5) / SUBSAMPLES
                var count = 0
                for (i in 0 until n) {
                    val x0 = xy[2 * i]; val y0 = xy[2 * i + 1]
                    val j = (i + 1) % n
                    val x1 = xy[2 * j]; val y1 = xy[2 * j + 1]
                    if (y0 == y1) continue
                    val up = y0 < y1
                    val lo = if (up) y0 else y1
                    val hi = if (up) y1 else y0
                    if (sy < lo || sy >= hi) continue
                    crossX[count] = x0 + (sy - y0) * (x1 - x0) / (y1 - y0)
                    crossW[count] = if (up) 1 else -1
                    count++
                }
                if (count < 2) continue
                val idx = (0 until count).sortedBy { crossX[it] }
                var wind = 0
                for (k in 0 until count - 1) {
                    wind += crossW[idx[k]]
                    if (wind != 0) addSpan(acc, crossX[idx[k]], crossX[idx[k + 1]], colStart, colEnd)
                }
            }
            // Spans were accumulated as "difference + edge" values; resolve the row.
            rowAlpha.fill(0, colStart, colEnd)
            var run = 0f
            for (x in colStart until colEnd) {
                run += acc[x - colStart]
                val a = (run / SUBSAMPLES * 255f).roundToInt().coerceIn(0, 255)
                rowAlpha[x] = a
            }
            writeRow(out, y, rowAlpha, colStart, colEnd)
        }
        compactSolidTiles(out)
        return out.freeze()
    }

    /**
     * Adds coverage of span [xa, xb) to [acc] as a difference array: full pixels contribute
     * +1 via run-length start/stop entries, partial end pixels get their exact fraction.
     */
    private fun addSpan(acc: FloatArray, xa0: Double, xb0: Double, colStart: Int, colEnd: Int) {
        val xa = xa0.coerceIn(colStart.toDouble(), colEnd.toDouble())
        val xb = xb0.coerceIn(colStart.toDouble(), colEnd.toDouble())
        if (xb <= xa) return
        val ia = floor(xa).toInt()
        val ib = floor(xb).toInt()
        if (ia == ib) {
            // Span within one pixel: add as a one-pixel bump.
            val f = (xb - xa).toFloat()
            acc[ia - colStart] += f
            acc[ia - colStart + 1] -= f
            return
        }
        val fa = (ia + 1 - xa).toFloat()
        acc[ia - colStart] += fa
        acc[ia - colStart + 1] -= fa
        // Full pixels ia+1 .. ib-1
        if (ib > ia + 1) {
            acc[ia + 1 - colStart] += 1f
            acc[ib - colStart] -= 1f
        }
        val fb = (xb - ib).toFloat()
        if (fb > 0f && ib < colEnd) {
            acc[ib - colStart] += fb
            acc[ib - colStart + 1] -= fb
        }
    }

    private fun writeRow(r: TiledRaster, y: Int, alpha: IntArray, x0: Int, x1: Int) {
        val ts = r.tileSize
        val shift = Integer.numberOfTrailingZeros(ts)
        val mask = ts - 1
        val ty = y shr shift
        var x = x0
        while (x < x1) {
            val tx = x shr shift
            val end = min(x1, (tx + 1) shl shift)
            var any = false
            for (i in x until end) if (alpha[i] != 0) { any = true; break }
            if (any) {
                val tile = r.writableTile(tx, ty)
                val base = (y and mask) shl shift
                for (i in x until end) tile[base or (i and mask)] = Pixels.pack(alpha[i], 0, 0, 0)
            }
            x = end
        }
    }

    /** Replaces uniform tiles with the shared solid tile, or nothing when equal to the fill. */
    private fun compactSolidTiles(r: TiledRaster) {
        val ts = r.tileSize
        for (ty in 0 until r.tilesY) for (tx in 0 until r.tilesX) {
            val t = r.tileOrNull(tx, ty) ?: continue
            val w = min(ts, r.width - tx * ts)
            val h = min(ts, r.height - ty * ts)
            val first = t[0]
            var uniform = true
            loop@ for (yy in 0 until h) for (xx in 0 until w) if (t[yy * ts + xx] != first) { uniform = false; break@loop }
            if (!uniform) continue
            // A cleared tile reads as the raster's fill, so only clear when that is the value.
            if (first == r.fill) r.clearTile(tx, ty) else r.putSharedTile(tx, ty, SolidTiles.of(first, ts))
        }
    }

    /** Combines [base] (null = nothing selected) with [shape] coverage using [op]. */
    fun combine(base: TiledRaster?, shape: TiledRaster, op: SelectionOp): TiledRaster {
        if (base == null || op == SelectionOp.Replace) {
            return when (op) {
                SelectionOp.Subtract, SelectionOp.Intersect -> empty(shape.width, shape.height)
                else -> shape
            }
        }
        require(base.width == shape.width && base.height == shape.height)
        return combinePixels(base, shape) { a, b ->
            when (op) {
                SelectionOp.Add -> max(a, b)
                SelectionOp.Subtract -> (a * (255 - b) + 127) / 255
                SelectionOp.Intersect -> min(a, b)
                SelectionOp.Replace -> b
            }
        }
    }

    fun invert(sel: TiledRaster): TiledRaster {
        val ts = sel.tileSize
        val out = TiledRaster(sel.width, sel.height, ts, fill = Pixels.pack(255 - Pixels.alpha(sel.fill), 0, 0, 0))
        for (ty in 0 until sel.tilesY) for (tx in 0 until sel.tilesX) {
            val t = sel.tileOrNull(tx, ty) ?: continue
            out.putTile(tx, ty, IntArray(ts * ts) { Pixels.pack(255 - Pixels.alpha(t[it]), 0, 0, 0) })
        }
        compactSolidTiles(out)
        return out.freeze()
    }

    private inline fun combinePixels(a: TiledRaster, b: TiledRaster, f: (Int, Int) -> Int): TiledRaster {
        val ts = a.tileSize
        val fillA = Pixels.alpha(a.fill)
        val fillB = Pixels.alpha(b.fill)
        val out = TiledRaster(a.width, a.height, ts, fill = Pixels.pack(f(fillA, fillB), 0, 0, 0))
        for (ty in 0 until a.tilesY) for (tx in 0 until a.tilesX) {
            val ta = a.tileOrNull(tx, ty)
            val tb = b.tileOrNull(tx, ty)
            if (ta == null && tb == null) continue
            val tile = IntArray(ts * ts)
            for (i in 0 until ts * ts) {
                val va = if (ta == null) fillA else Pixels.alpha(ta[i])
                val vb = if (tb == null) fillB else Pixels.alpha(tb[i])
                tile[i] = Pixels.pack(f(va, vb), 0, 0, 0)
            }
            out.putTile(tx, ty, tile)
        }
        compactSolidTiles(out)
        return out.freeze()
    }

    /** Bounds of selected (non-zero) pixels, or null when nothing is selected. */
    fun bounds(sel: TiledRaster): IntRect? = scanBounds(sel)

    private fun scanBounds(sel: TiledRaster): IntRect? {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
        val ts = sel.tileSize
        for (ty in 0 until sel.tilesY) for (tx in 0 until sel.tilesX) {
            val tile = sel.tileOrNull(tx, ty)
            val w = min(ts, sel.width - tx * ts)
            val h = min(ts, sel.height - ty * ts)
            if (tile == null) {
                if (Pixels.alpha(sel.fill) == 0) continue
                l = min(l, tx * ts); t = min(t, ty * ts); r = max(r, tx * ts + w); b = max(b, ty * ts + h)
                continue
            }
            for (yy in 0 until h) for (xx in 0 until w) {
                if (Pixels.alpha(tile[yy * ts + xx]) != 0) {
                    val x = tx * ts + xx; val y = ty * ts + yy
                    if (x < l) l = x; if (y < t) t = y
                    if (x + 1 > r) r = x + 1; if (y + 1 > b) b = y + 1
                }
            }
        }
        return if (l == Int.MAX_VALUE) null else IntRect(l, t, r, b)
    }

    fun isEmpty(sel: TiledRaster): Boolean = bounds(sel) == null
}
