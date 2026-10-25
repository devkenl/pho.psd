package app.phopsd.engine

import app.phopsd.engine.raster.ImportBudget
import app.phopsd.engine.raster.MipChain
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.raster.RasterBuilder
import app.phopsd.engine.raster.TiledRaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RasterTest {

    @Test
    fun premultiplyAndBackPreservesOpaqueAndRoundsCorrectly() {
        val c = Pixels.pack(255, 12, 200, 99)
        assertEquals(c, Pixels.premultiply(c))
        assertEquals(c, Pixels.unpremultiply(c))
        val half = Pixels.pack(128, 255, 100, 0)
        val pm = Pixels.premultiply(half)
        assertEquals(128, Pixels.alpha(pm))
        assertEquals(128, Pixels.red(pm))
        assertEquals(50, Pixels.green(pm))
        assertEquals(0, Pixels.premultiply(Pixels.pack(0, 255, 255, 255)))
        val back = Pixels.unpremultiply(pm)
        assertEquals(255, Pixels.red(back))
        assertTrue("green within 1 after round trip", kotlin.math.abs(Pixels.green(back) - 100) <= 1)
    }

    @Test
    fun mulDiv255MatchesExactRounding() {
        for (x in 0..255) for (y in 0..255) {
            val exact = Math.round(x * y / 255.0).toInt()
            assertEquals("x=$x y=$y", exact, Pixels.mulDiv255(x, y))
        }
    }

    @Test
    fun writeAndReadAcrossTileBoundaries() {
        val w = 70
        val h = 37
        val src = IntArray(w * h) { Pixels.pack(255, it % 256, (it / 7) % 256, 3) }
        val r = TiledRaster.fromPixels(w, h, src, straightAlpha = false, tileSize = 16)
        assertEquals(5 * 3, r.allocatedTileCount)
        for (y in 0 until h) for (x in 0 until w) assertEquals(src[y * w + x], r.pixel(x, y))
        assertEquals(0, r.pixel(-1, 0))
        assertEquals(0, r.pixel(w, 0))
        val region = IntArray(20 * 3)
        r.readRegion(10, 14, 20, 3, region)
        assertEquals(src[15 * w + 29], region[1 * 20 + 19])
    }

    @Test
    fun transparentRowsAllocateNoTiles() {
        val w = 64
        val src = IntArray(w * 64)
        src[0] = Pixels.pack(255, 1, 2, 3)
        val r = TiledRaster.fromPixels(w, 64, src, straightAlpha = true, tileSize = 16)
        assertEquals(1, r.allocatedTileCount)
    }

    @Test
    fun solidRasterCostsNoMemory() {
        val white = Pixels.opaque(0xFFFFFF)
        val r = TiledRaster.solid(10_000, 8_000, white)
        assertEquals(0L, r.allocatedBytes)
        assertEquals(white, r.pixel(9_999, 7_999))
        assertEquals(0, r.pixel(10_000, 0))
    }

    @Test(expected = IllegalStateException::class)
    fun frozenRasterRejectsWrites() {
        TiledRaster.solid(4, 4, 0).let { it.setPixel(0, 0, 1) }
    }

    @Test
    fun mipLevelsHalveAndAverageInPremultipliedSpace() {
        // 2x2: opaque red, transparent, transparent, transparent -> 1/4 coverage red.
        val src = intArrayOf(Pixels.pack(255, 255, 0, 0), 0, 0, 0)
        val base = TiledRaster.fromPixels(2, 2, src, straightAlpha = false, tileSize = 16)
        val half = MipChain.downsample(base)
        assertEquals(1, half.width)
        val p = half.pixel(0, 0)
        assertEquals(64, Pixels.alpha(p))
        assertEquals(64, Pixels.red(p))
        // Straight colour is still pure red (no dark fringe), the point of premultiplied filtering.
        assertEquals(255, Pixels.red(Pixels.unpremultiply(p)))
    }

    @Test
    fun mipChainStopsAtMinimumEdgeAndHandlesOddSizes() {
        val base = TiledRaster.fromPixels(301, 97, IntArray(301 * 97) { Pixels.opaque(0x336699) }, straightAlpha = true, tileSize = 64)
        val chain = MipChain.build(base)
        assertEquals(listOf(301, 151, 76), chain.levels.map { it.width })
        assertEquals(listOf(97, 49, 25), chain.levels.map { it.height })
        assertEquals(Pixels.opaque(0x336699), chain.levels.last().pixel(75, 24))
        assertEquals(0, chain.levelFor(0.5))
        assertEquals(1, chain.levelFor(2.5))
        assertEquals(2, chain.levelFor(1000.0))
    }

    @Test
    fun rasterBuilderAssemblesBands() {
        val b = RasterBuilder(5, 4)
        b.appendStraightRows(IntArray(10) { Pixels.opaque(it) }, 2)
        assertFalse(b.isComplete)
        b.appendStraightRows(IntArray(10) { Pixels.opaque(100 + it) }, 2)
        val r = b.build()
        assertTrue(r.isFrozen)
        assertEquals(Pixels.opaque(7), r.pixel(2, 1))
        assertEquals(Pixels.opaque(109), r.pixel(4, 3))
    }

    @Test
    fun importBudgetScalesDownOnlyWhenNeeded() {
        assertEquals(1.0, ImportBudget.scaleToFit(4000, 3000, 1_000_000_000L), 0.0)
        val s = ImportBudget.scaleToFit(8000, 6000, 200_000_000L)
        assertTrue(s < 1.0)
        val w = (8000 * s).toInt()
        val h = (6000 * s).toInt()
        assertTrue(ImportBudget.bytesWithMips(w, h) <= 200_000_000L * 0.6)
    }
}
