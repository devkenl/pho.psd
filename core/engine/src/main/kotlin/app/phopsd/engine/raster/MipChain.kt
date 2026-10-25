package app.phopsd.engine.raster

import kotlin.math.floor
import kotlin.math.ln

/**
 * Image pyramid for one raster: level 0 is full resolution, each next level is a 2x
 * box-filtered reduction (averaged in premultiplied space, which is the correct space for
 * filtering images with alpha). Used so zoomed-out previews and thumbnails never touch
 * every full-resolution pixel.
 */
class MipChain private constructor(val levels: List<TiledRaster>) {
    val base: TiledRaster get() = levels[0]
    val width: Int get() = base.width
    val height: Int get() = base.height

    /**
     * Picks the level for a given minification: [sourcePixelsPerOutputPixel] > 1 means the
     * raster is shown smaller than 1:1. Returns the level index; level scale is 2^index.
     */
    fun levelFor(sourcePixelsPerOutputPixel: Double): Int {
        if (sourcePixelsPerOutputPixel <= 1.0) return 0
        val l = floor(ln(sourcePixelsPerOutputPixel) / ln(2.0)).toInt()
        return l.coerceIn(0, levels.size - 1)
    }

    val allocatedBytes: Long get() = levels.sumOf { it.allocatedBytes }

    companion object {
        /** Smallest edge at which mip generation stops. */
        const val MIN_LEVEL_EDGE = 32

        fun build(base: TiledRaster, isCancelled: () -> Boolean = { false }): MipChain {
            require(base.isFrozen) { "Mip chains are built from frozen rasters" }
            val levels = ArrayList<TiledRaster>()
            levels.add(base)
            var current = base
            while (minOf(current.width, current.height) > MIN_LEVEL_EDGE) {
                if (isCancelled()) break
                current = downsample(current)
                levels.add(current)
            }
            return MipChain(levels)
        }

        /** Builds a chain consisting only of [base] (used for solid fills). */
        fun single(base: TiledRaster): MipChain {
            require(base.isFrozen)
            return MipChain(listOf(base))
        }

        /** 2x box reduction. Odd edges clamp, so the last column/row is averaged with itself. */
        fun downsample(src: TiledRaster): TiledRaster {
            val w = (src.width + 1) / 2
            val h = (src.height + 1) / 2
            if (src.allocatedTileCount == 0) {
                return TiledRaster.solid(w, h, src.fill, src.tileSize)
            }
            val dst = TiledRaster(w, h, src.tileSize, src.fill)
            val row = IntArray(w)
            for (y in 0 until h) {
                val sy = y * 2
                for (x in 0 until w) {
                    val sx = x * 2
                    row[x] = average4(
                        src.pixelClamped(sx, sy), src.pixelClamped(sx + 1, sy),
                        src.pixelClamped(sx, sy + 1), src.pixelClamped(sx + 1, sy + 1),
                    )
                }
                dst.writeRows(y, 1, row, 0, w, straightAlpha = false)
            }
            return dst.freeze()
        }

        private fun average4(p0: Int, p1: Int, p2: Int, p3: Int): Int {
            val a = (Pixels.alpha(p0) + Pixels.alpha(p1) + Pixels.alpha(p2) + Pixels.alpha(p3) + 2) shr 2
            val r = (Pixels.red(p0) + Pixels.red(p1) + Pixels.red(p2) + Pixels.red(p3) + 2) shr 2
            val g = (Pixels.green(p0) + Pixels.green(p1) + Pixels.green(p2) + Pixels.green(p3) + 2) shr 2
            val b = (Pixels.blue(p0) + Pixels.blue(p1) + Pixels.blue(p2) + Pixels.blue(p3) + 2) shr 2
            return Pixels.pack(a, minOf(r, a), minOf(g, a), minOf(b, a))
        }
    }
}
