package app.phopsd.engine.raster

import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * A premultiplied ARGB raster stored as square tiles.
 *
 * - Tiles that were never written cost nothing and read as [fill] (e.g. a white
 *   background layer of any size occupies no pixel memory).
 * - Rasters are built and then [freeze]d. Frozen rasters are immutable and can be shared
 *   freely between layers, duplicates and undo history entries.
 * - **Copy-on-write**: [derive] creates an editable raster that *shares* every tile with its
 *   source. A tile is cloned only the first time it is written. A brush stroke therefore
 *   costs memory only for the tiles it touched, and undo keeps the old raster for free.
 * - Identical solid tiles (e.g. "fully selected") can share one immutable array
 *   ([putSharedTile] / [SolidTiles]).
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

    /** Per tile: true when this raster exclusively owns (may mutate) the array. */
    private val owned = BooleanArray(tilesX * tilesY)

    @Volatile
    var isFrozen: Boolean = false
        private set

    fun freeze(): TiledRaster {
        isFrozen = true
        return this
    }

    /**
     * An editable copy sharing all tiles with this raster (which must be frozen).
     * Writes clone a tile on first touch, so this raster is never modified.
     */
    fun derive(): TiledRaster {
        check(isFrozen) { "Only frozen rasters can be derived" }
        val r = TiledRaster(width, height, tileSize, fill)
        System.arraycopy(tiles, 0, r.tiles, 0, tiles.size)
        return r
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
        writableTile(x shr shift, y shr shift)[((y and mask) shl shift) or (x and mask)] = premul
    }

    /**
     * Writes a block of rows. [src] holds [rowCount] rows of [width] pixels starting at
     * [srcOffset] with [srcStride] ints between rows. Straight input is premultiplied.
     * Tiles that would only contain [fill] stay unallocated.
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
                val idx = ty * tilesX + tx
                val spanEnd = minOf(width, (tx + 1) shl shift)
                var tile: IntArray? = if (tiles[idx] != null) writableTile(tx, ty) else null
                for (xi in x until spanEnd) {
                    val s = src[rowBase + xi]
                    val p = if (straightAlpha) Pixels.premultiply(s) else s
                    if (tile == null) {
                        if (p == fill) continue
                        tile = writableTile(tx, ty)
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

    /**
     * Backing array of tile ([tx], [ty]) or null when the tile was never written (reads as
     * [fill]). Arrays are tileSize² premultiplied pixels, row-major; edge tiles are padded.
     * Callers must treat the array as read-only.
     */
    fun tileOrNull(tx: Int, ty: Int): IntArray? {
        require(tx in 0 until tilesX && ty in 0 until tilesY) { "Tile ($tx,$ty) out of range" }
        return tiles[ty * tilesX + tx]
    }

    /** Installs a tile this raster will own (deserialization, rasterization). */
    fun putTile(tx: Int, ty: Int, data: IntArray) {
        checkWritable()
        require(tx in 0 until tilesX && ty in 0 until tilesY) { "Tile ($tx,$ty) out of range" }
        require(data.size == tileSize * tileSize) { "Tile must hold ${tileSize * tileSize} pixels" }
        tiles[ty * tilesX + tx] = data
        owned[ty * tilesX + tx] = true
    }

    /** Installs an immutable shared tile (e.g. a [SolidTiles] array); cloned before any write. */
    fun putSharedTile(tx: Int, ty: Int, data: IntArray) {
        checkWritable()
        require(data.size == tileSize * tileSize)
        tiles[ty * tilesX + tx] = data
        owned[ty * tilesX + tx] = false
    }

    /** Removes a tile so it reads as [fill] again. */
    fun clearTile(tx: Int, ty: Int) {
        checkWritable()
        tiles[ty * tilesX + tx] = null
        owned[ty * tilesX + tx] = false
    }

    /**
     * The tile's array for in-place writing, cloning a shared tile or allocating a
     * [fill]-coloured one first. Only valid before [freeze].
     */
    fun writableTile(tx: Int, ty: Int): IntArray {
        checkWritable()
        val idx = ty * tilesX + tx
        val t = tiles[idx]
        if (t != null && owned[idx]) return t
        val fresh = when {
            t != null -> t.copyOf()
            else -> IntArray(tileSize * tileSize).also { if (fill != 0) it.fill(fill) }
        }
        tiles[idx] = fresh
        owned[idx] = true
        return fresh
    }

    /** Number of tiles that hold an array (shared or owned). */
    val allocatedTileCount: Int get() = tiles.count { it != null }

    /** Bytes referenced by this raster's tiles (shared arrays are counted once per raster). */
    val allocatedBytes: Long get() = allocatedTileCount.toLong() * tileSize * tileSize * 4L

    /** Adds every distinct tile array to [into] (for de-duplicated memory accounting). */
    fun collectTileArrays(into: IdentityHashMap<IntArray, Boolean>) {
        for (t in tiles) if (t != null) into[t] = true
    }

    private fun checkWritable() {
        check(!isFrozen) { "Raster is frozen; derive a new raster instead of mutating shared pixels" }
    }

    companion object {
        /**
         * Tile size. 256 px = 256 KiB per tile. Not yet benchmarked on device;
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

/** Shared, immutable solid tiles (never written: rasters clone before writing). */
object SolidTiles {
    private val cache = ConcurrentHashMap<Long, IntArray>()

    fun of(premul: Int, tileSize: Int): IntArray =
        cache.getOrPut((tileSize.toLong() shl 32) or (premul.toLong() and 0xFFFFFFFFL)) {
            IntArray(tileSize * tileSize).also { it.fill(premul) }
        }
}
