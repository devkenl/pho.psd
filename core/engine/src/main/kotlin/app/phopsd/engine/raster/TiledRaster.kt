package app.phopsd.engine.raster

/**
 * A premultiplied ARGB raster stored as square tiles.
 *
 * - Tiles that were never written cost nothing and read as [fill] (e.g. a white
 *   background layer of any size occupies no pixel memory).
 * - Rasters are built once and then [freeze]d. Frozen rasters are immutable and can be
 *   shared freely between layers, duplicates and undo history entries. Later milestones
 *   (brush, mask painting) will derive edited rasters copy-on-write per tile, so undo stores
 *   tile deltas rather than full-document clones.
 * - Tile size must be a power of two (shift/mask addressing in the sampling hot loop).
 */
class TiledRaster(
    val width: Int,
    val height: Int,
    val tileSize: Int = DEFAULT_TILE_SIZE,
    /** Premultiplied colour of tiles that were never written. */
    val fill: Int = Pixels.TRANSPARENT,
) {
    init {
        require(width > 0 && height > 0) { "Raster must be at least 1x1 (was ${width}x$height)" }
        require(tileSize >= 16 && (tileSize and (tileSize - 1)) == 0) { "Tile size must be a power of two >= 16" }
    }

    private val shift: Int = Integer.numberOfTrailingZeros(tileSize)
    private val mask: Int = tileSize - 1
    val tilesX: Int = (width + tileSize - 1) / tileSize
    val tilesY: Int = (height + tileSize - 1) / tileSize
    private val tiles: Array<IntArray?> = arrayOfNulls(tilesX * tilesY)

    @Volatile
    var isFrozen: Boolean = false
        private set

    fun freeze(): TiledRaster {
        isFrozen = true
        return this
    }

    /** Premultiplied pixel; coordinates outside the raster read as transparent. */
    fun pixel(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return Pixels.TRANSPARENT
        val tile = tiles[(y shr shift) * tilesX + (x shr shift)] ?: return fill
        return tile[((y and mask) shl shift) or (x and mask)]
    }

    /** Same as [pixel] but clamps coordinates to the edge (used for mip generation). */
    fun pixelClamped(x: Int, y: Int): Int =
        pixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))

    fun setPixel(x: Int, y: Int, premul: Int) {
        checkWritable()
        require(x in 0 until width && y in 0 until height) { "Pixel ($x,$y) outside raster" }
        tileForWrite(x shr shift, y shr shift)[((y and mask) shl shift) or (x and mask)] = premul
    }

    /**
     * Writes a block of rows. [src] holds [rowCount] rows of [width] pixels starting at
     * [srcOffset] with [srcStride] ints between rows. Straight input is premultiplied.
     * Fully transparent tiles stay unallocated when [fill] is transparent.
     */
    fun writeRows(
        y0: Int,
        rowCount: Int,
        src: IntArray,
        srcOffset: Int = 0,
        srcStride: Int = width,
        straightAlpha: Boolean,
    ) {
        checkWritable()
        require(y0 >= 0 && y0 + rowCount <= height) { "Rows $y0..${y0 + rowCount} outside raster" }
        for (row in 0 until rowCount) {
            val y = y0 + row
            val ty = y shr shift
            val inTileY = (y and mask) shl shift
            val rowBase = srcOffset + row * srcStride
            var x = 0
            while (x < width) {
                val tx = x shr shift
                val spanEnd = minOf(width, (tx + 1) shl shift)
                var tile = tiles[ty * tilesX + tx]
                for (xi in x until spanEnd) {
                    val s = src[rowBase + xi]
                    val p = if (straightAlpha) Pixels.premultiply(s) else s
                    if (tile == null) {
                        if (p == fill) continue
                        tile = tileForWrite(tx, ty)
                    }
                    tile[inTileY or (xi and mask)] = p
                }
                x = spanEnd
            }
        }
    }

    /** Copies a rectangular region (premultiplied) into [dst] with row stride [dstStride]. */
    fun readRegion(x0: Int, y0: Int, w: Int, h: Int, dst: IntArray, dstOffset: Int = 0, dstStride: Int = w) {
        for (j in 0 until h) {
            val base = dstOffset + j * dstStride
            for (i in 0 until w) dst[base + i] = pixel(x0 + i, y0 + j)
        }
    }

    /** Number of tiles that hold real pixel memory. */
    val allocatedTileCount: Int get() = tiles.count { it != null }

    /** Bytes of pixel memory held (excluding object overhead). */
    val allocatedBytes: Long get() = allocatedTileCount.toLong() * tileSize * tileSize * 4L

    private fun tileForWrite(tx: Int, ty: Int): IntArray {
        val idx = ty * tilesX + tx
        var t = tiles[idx]
        if (t == null) {
            t = IntArray(tileSize * tileSize)
            if (fill != 0) t.fill(fill)
            tiles[idx] = t
        }
        return t
    }

    private fun checkWritable() {
        check(!isFrozen) { "Raster is frozen; derive a new raster instead of mutating shared pixels" }
    }

    companion object {
        /**
         * Initial tile size. 256 px = 256 KiB per tile. Not yet benchmarked on device;
         * revisit with the M7 benchmark scenes (see PROJECT_STATUS.md).
         */
        const val DEFAULT_TILE_SIZE = 256

        /** Builds a frozen raster from a full premultiplied or straight buffer. */
        fun fromPixels(width: Int, height: Int, pixels: IntArray, straightAlpha: Boolean, tileSize: Int = DEFAULT_TILE_SIZE): TiledRaster {
            require(pixels.size >= width * height) { "Pixel buffer too small" }
            val r = TiledRaster(width, height, tileSize)
            r.writeRows(0, height, pixels, 0, width, straightAlpha)
            return r.freeze()
        }

        /** A frozen raster where every pixel is [premulColor], without allocating pixel memory. */
        fun solid(width: Int, height: Int, premulColor: Int, tileSize: Int = DEFAULT_TILE_SIZE): TiledRaster =
            TiledRaster(width, height, tileSize, fill = premulColor).freeze()
    }
}
