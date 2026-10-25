package app.phopsd.engine.raster

/**
 * Memory planning for decoded images. The Android layer queries the heap and asks the
 * engine how large an import may be; the engine owns the arithmetic so it is tested.
 */
object ImportBudget {
    /** Bytes for a raster of the given size including its mip chain (~4/3 of level 0). */
    fun bytesWithMips(width: Int, height: Int): Long = width.toLong() * height * 4L * 4L / 3L

    /**
     * Returns the largest scale (<= 1.0) at which a [width] x [height] image fits
     * in [availableBytes], keeping [reserveFraction] of the budget free for previews/undo.
     */
    fun scaleToFit(width: Int, height: Int, availableBytes: Long, reserveFraction: Double = 0.4): Double {
        require(width > 0 && height > 0)
        val usable = (availableBytes * (1.0 - reserveFraction)).coerceAtLeast(0.0)
        val need = bytesWithMips(width, height).toDouble()
        if (need <= usable) return 1.0
        if (usable <= 0.0) return 0.0
        return kotlin.math.sqrt(usable / need).coerceAtMost(1.0)
    }
}

/**
 * Incrementally builds a frozen [TiledRaster] from decoder output delivered in row bands,
 * so a decoded bitmap never needs to be copied into one giant intermediate array.
 */
class RasterBuilder(val width: Int, val height: Int) {
    private val raster = TiledRaster(width, height)
    private var rowsWritten = 0

    /** Adds the next [rowCount] rows of *straight* ARGB pixels (stride = width). */
    fun appendStraightRows(rows: IntArray, rowCount: Int) {
        require(rowsWritten + rowCount <= height) { "Too many rows" }
        raster.writeRows(rowsWritten, rowCount, rows, 0, width, straightAlpha = true)
        rowsWritten += rowCount
    }

    val isComplete: Boolean get() = rowsWritten == height

    fun build(): TiledRaster {
        check(isComplete) { "Only $rowsWritten of $height rows were written" }
        return raster.freeze()
    }
}
