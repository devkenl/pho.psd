package app.phopsd.engine

import app.phopsd.engine.geometry.LayerTransform
import app.phopsd.engine.geometry.RectD
import app.phopsd.engine.model.BlendMode
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.LayerId
import app.phopsd.engine.model.RasterId
import app.phopsd.engine.model.RasterLayer
import app.phopsd.engine.model.RasterStore
import app.phopsd.engine.raster.MipChain
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.raster.TiledRaster
import app.phopsd.engine.render.BlendMath
import app.phopsd.engine.render.Compositor
import app.phopsd.engine.render.RenderRequest
import app.phopsd.engine.render.Sampling
import app.phopsd.engine.render.Thumbnails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Golden numerical tests for the CPU reference compositor. */
class CompositorTest {
    private val compositor = Compositor()
    private val store = RasterStore()

    private fun raster(w: Int, h: Int, tileSize: Int = 16, f: (Int, Int) -> Int): RasterId {
        val px = IntArray(w * h) { f(it % w, it / w) }
        val id = RasterId.random()
        store.put(id, MipChain.build(TiledRaster.fromPixels(w, h, px, straightAlpha = true, tileSize = tileSize)))
        return id
    }

    private fun layer(
        rasterId: RasterId, w: Int, h: Int,
        left: Double = 0.0, top: Double = 0.0,
        opacity: Float = 1f, mode: BlendMode = BlendMode.Normal, visible: Boolean = true,
        transform: LayerTransform? = null,
    ) = RasterLayer(
        id = LayerId.random(), name = "L", rasterId = rasterId, width = w, height = h,
        transform = transform ?: LayerTransform.atOffset(left, top, w, h),
        opacity = opacity, blendMode = mode, visible = visible,
    )

    private fun doc(w: Int, h: Int, vararg layers: RasterLayer) =
        Document("d", "Test", w, h, layers.toList(), layers.lastOrNull()?.id)

    private fun full(d: Document, sampling: Sampling = Sampling.Bilinear) =
        compositor.render(d, store, RenderRequest(RectD(0.0, 0.0, d.width.toDouble(), d.height.toDouble()), d.width, d.height, sampling))

    private fun assertChannel(name: String, expected: Int, actual: Int, tol: Int = 1) {
        assertTrue("$name expected $expected±$tol but was $actual", abs(expected - actual) <= tol)
    }

    private fun assertPremul(expected: Int, actual: Int, tol: Int = 1) {
        assertChannel("a", Pixels.alpha(expected), Pixels.alpha(actual), tol)
        assertChannel("r", Pixels.red(expected), Pixels.red(actual), tol)
        assertChannel("g", Pixels.green(expected), Pixels.green(actual), tol)
        assertChannel("b", Pixels.blue(expected), Pixels.blue(actual), tol)
    }

    @Test
    fun identityRenderIsExactAcrossTileSeams() {
        val w = 53
        val h = 41
        val id = raster(w, h, tileSize = 16) { x, y -> Pixels.pack(255, (x * 5) and 255, (y * 6) and 255, (x + y) and 255) }
        val d = doc(w, h, layer(id, w, h))
        val out = full(d)
        val mips = store.mips(id)!!
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals("pixel $x,$y", mips.base.pixel(x, y), out[y * w + x])
        }
        // Nearest sampling at 1:1 gives the same exact result.
        assertTrue(out.contentEquals(full(d, Sampling.Nearest)))
    }

    @Test
    fun integerOffsetLayerIsExactAndTransparentElsewhere() {
        val id = raster(4, 4) { _, _ -> Pixels.opaque(0x00FF00) }
        val d = doc(10, 10, layer(id, 4, 4, left = 3.0, top = 5.0))
        val out = full(d)
        assertEquals(Pixels.opaque(0x00FF00), out[5 * 10 + 3])
        assertEquals(Pixels.opaque(0x00FF00), out[8 * 10 + 6])
        assertEquals(0, out[4 * 10 + 3])
        assertEquals(0, out[5 * 10 + 7])
    }

    @Test
    fun normalSourceOverWithAlphaAndOpacity() {
        val bottom = raster(1, 1) { _, _ -> Pixels.opaque(0x0000FF) }
        val top = raster(1, 1) { _, _ -> Pixels.pack(128, 255, 0, 0) }
        val out = full(doc(1, 1, layer(bottom, 1, 1), layer(top, 1, 1)))[0]
        // 50.2% red over opaque blue.
        assertPremul(Pixels.pack(255, 128, 0, 127), out)

        val withOpacity = full(doc(1, 1, layer(bottom, 1, 1), layer(top, 1, 1, opacity = 0.5f)))[0]
        // Effective source alpha = 128/255 * 0.5 ≈ 0.251
        assertPremul(Pixels.pack(255, 64, 0, 191), withOpacity)
    }

    @Test
    fun translucentOverTransparentStaysTranslucent() {
        val top = raster(1, 1) { _, _ -> Pixels.pack(100, 200, 100, 50) }
        val out = full(doc(1, 1, layer(top, 1, 1, opacity = 0.5f)))[0]
        assertChannel("alpha", 50, Pixels.alpha(out))
        val straight = Pixels.unpremultiply(out)
        assertChannel("r", 200, Pixels.red(straight), 3)
        assertChannel("g", 100, Pixels.green(straight), 3)
    }

    @Test
    fun hiddenAndZeroOpacityLayersAreSkipped() {
        val bottom = raster(1, 1) { _, _ -> Pixels.opaque(0x112233) }
        val top = raster(1, 1) { _, _ -> Pixels.opaque(0xFFFFFF) }
        assertEquals(Pixels.opaque(0x112233), full(doc(1, 1, layer(bottom, 1, 1), layer(top, 1, 1, visible = false)))[0])
        assertEquals(Pixels.opaque(0x112233), full(doc(1, 1, layer(bottom, 1, 1), layer(top, 1, 1, opacity = 0f)))[0])
    }

    @Test
    fun stackingOrderIsBottomToTop() {
        val red = raster(1, 1) { _, _ -> Pixels.opaque(0xFF0000) }
        val blue = raster(1, 1) { _, _ -> Pixels.opaque(0x0000FF) }
        assertEquals(Pixels.opaque(0x0000FF), full(doc(1, 1, layer(red, 1, 1), layer(blue, 1, 1)))[0])
        assertEquals(Pixels.opaque(0xFF0000), full(doc(1, 1, layer(blue, 1, 1), layer(red, 1, 1)))[0])
    }

    @Test
    fun separableBlendModesMatchFormulas() {
        val cb = 0x806040 // backdrop (128, 96, 64)
        val cs = 0x40C0FF // source (64, 192, 255)
        val bottom = raster(1, 1) { _, _ -> Pixels.opaque(cb) }
        val top = raster(1, 1) { _, _ -> Pixels.opaque(cs) }
        val b = intArrayOf(128, 96, 64).map { it / 255f }
        val s = intArrayOf(64, 192, 255).map { it / 255f }
        for (mode in BlendMode.entries.filter { BlendMath.isSeparable(it) }) {
            val out = full(doc(1, 1, layer(bottom, 1, 1), layer(top, 1, 1, mode = mode)))[0]
            val exp = (0..2).map { Math.round(BlendMath.separable(mode, b[it], s[it]) * 255f) }
            assertPremul(Pixels.pack(255, exp[0], exp[1], exp[2]), out)
        }
    }

    @Test
    fun knownBlendValues() {
        assertEquals(0.25f, BlendMath.separable(BlendMode.Multiply, 0.5f, 0.5f), 1e-6f)
        assertEquals(0.75f, BlendMath.separable(BlendMode.Screen, 0.5f, 0.5f), 1e-6f)
        assertEquals(0.5f, BlendMath.separable(BlendMode.Difference, 0.75f, 0.25f), 1e-6f)
        assertEquals(0.5f, BlendMath.separable(BlendMode.Exclusion, 0.5f, 0.5f), 1e-6f)
        // Overlay = HardLight with operands swapped.
        assertEquals(BlendMath.separable(BlendMode.HardLight, 0.3f, 0.8f), BlendMath.separable(BlendMode.Overlay, 0.8f, 0.3f), 1e-6f)
        assertEquals(1f, BlendMath.separable(BlendMode.ColorDodge, 0.5f, 1f), 1e-6f)
        assertEquals(0f, BlendMath.separable(BlendMode.ColorBurn, 0.5f, 0f), 1e-6f)
        assertEquals(0.5f, BlendMath.separable(BlendMode.SoftLight, 0.5f, 0.5f), 1e-6f)
        // Neutral grey source in Soft/Hard light leaves backdrop unchanged.
        assertEquals(0.3f, BlendMath.separable(BlendMode.HardLight, 0.3f, 0.5f), 1e-6f)
    }

    @Test
    fun nonSeparableModesPreserveTheRightComponents() {
        val out = FloatArray(3)
        val b = floatArrayOf(0.2f, 0.5f, 0.8f)
        val s = floatArrayOf(0.9f, 0.1f, 0.3f)
        // Luminosity: result luminance equals source luminance.
        BlendMath.nonSeparable(BlendMode.Luminosity, b[0], b[1], b[2], s[0], s[1], s[2], out)
        assertEquals(BlendMath.lum(s[0], s[1], s[2]), BlendMath.lum(out[0], out[1], out[2]), 1e-5f)
        // Color: result luminance equals backdrop luminance.
        BlendMath.nonSeparable(BlendMode.Color, b[0], b[1], b[2], s[0], s[1], s[2], out)
        assertEquals(BlendMath.lum(b[0], b[1], b[2]), BlendMath.lum(out[0], out[1], out[2]), 1e-5f)
        // Saturation: result saturation equals source saturation (no clipping in this case).
        BlendMath.nonSeparable(BlendMode.Saturation, b[0], b[1], b[2], s[0], s[1], s[2], out)
        assertEquals(BlendMath.sat(s[0], s[1], s[2]), BlendMath.sat(out[0], out[1], out[2]), 1e-5f)
        // Grey source under Hue keeps luminance of backdrop and becomes grey.
        BlendMath.nonSeparable(BlendMode.Hue, b[0], b[1], b[2], 0.5f, 0.5f, 0.5f, out)
        assertEquals(out[0], out[1], 1e-6f)
        assertEquals(out[1], out[2], 1e-6f)
        assertEquals(BlendMath.lum(b[0], b[1], b[2]), out[0], 1e-5f)
    }

    @Test
    fun blendModeOverTransparentBackdropActsLikeNormal() {
        val top = raster(1, 1) { _, _ -> Pixels.pack(200, 10, 220, 90) }
        for (mode in BlendMode.entries) {
            val normal = full(doc(1, 1, layer(top, 1, 1)))[0]
            val other = full(doc(1, 1, layer(top, 1, 1, mode = mode)))[0]
            assertEquals("mode $mode", normal, other)
        }
    }

    @Test
    fun multiplyWithPartialAlphaFollowsW3cFormula() {
        val bottom = raster(1, 1) { _, _ -> Pixels.pack(128, 255, 255, 255) } // 50% white
        val top = raster(1, 1) { _, _ -> Pixels.pack(128, 0, 0, 0) } // 50% black
        val out = full(doc(1, 1, layer(bottom, 1, 1), layer(top, 1, 1, mode = BlendMode.Multiply)))[0]
        val asr = 128 / 255f
        val ab = 128 / 255f
        // co = (1-ab)*cs + (1-as)*cb + as*ab*B ; cs=0, cb=ab*1, B=0
        val co = (1 - asr) * ab
        val ao = asr + ab * (1 - asr)
        val ai = Math.round(ao * 255)
        val ci = Math.round(co * 255)
        assertPremul(Pixels.pack(ai, ci, ci, ci), out)
    }

    @Test
    fun scaledLayerSamplesOriginalPixels() {
        // 2x2 checker scaled 2x → each source pixel covers 2x2 doc pixels; nearest is exact.
        val id = raster(2, 2) { x, y -> if ((x + y) % 2 == 0) Pixels.opaque(0xFFFFFF) else Pixels.opaque(0x000000) }
        val t = LayerTransform(centerX = 2.0, centerY = 2.0, scaleX = 2.0, scaleY = 2.0)
        val d = doc(4, 4, layer(id, 2, 2, transform = t))
        val out = full(d, Sampling.Nearest)
        assertEquals(Pixels.opaque(0xFFFFFF), out[0])
        assertEquals(Pixels.opaque(0xFFFFFF), out[1 * 4 + 1])
        assertEquals(Pixels.opaque(0x000000), out[2])
        assertEquals(Pixels.opaque(0x000000), out[3 * 4 + 0])
        assertEquals(Pixels.opaque(0xFFFFFF), out[3 * 4 + 3])
    }

    @Test
    fun downscaledPreviewUsesMipsAndStaysInRange() {
        val w = 256
        val id = raster(w, w, tileSize = 64) { x, _ -> if (x < 128) Pixels.opaque(0xFF0000) else Pixels.opaque(0x0000FF) }
        val d = doc(w, w, layer(id, w, w))
        val out = compositor.render(d, store, RenderRequest(RectD(0.0, 0.0, w.toDouble(), w.toDouble()), 16, 16))
        assertEquals(Pixels.opaque(0xFF0000), out[8 * 16 + 2])
        assertEquals(Pixels.opaque(0x0000FF), out[8 * 16 + 13])
    }

    @Test
    fun bandedRenderingMatchesSinglePass() {
        val w = 40
        val h = 30
        val a = raster(w, h) { x, y -> Pixels.pack(200, x * 6, y * 8, 77) }
        val b = raster(20, 20) { x, _ -> Pixels.pack(180, 255 - x * 10, 30, 200) }
        val d = doc(w, h, layer(a, w, h), layer(b, 20, 20, transform = LayerTransform(17.3, 11.1, 0.8, 1.3, 21.0), mode = BlendMode.Overlay))
        val req = RenderRequest(RectD(0.0, 0.0, w.toDouble(), h.toDouble()), w, h)
        val single = compositor.render(d, store, req)
        val banded = IntArray(w * h)
        compositor.render(d, store, req, banded, 0, 13)
        compositor.render(d, store, req, banded, 13, h)
        assertTrue(single.contentEquals(banded))
    }

    @Test
    fun cancellationStopsRender() {
        val id = raster(8, 8) { _, _ -> Pixels.opaque(0xFFFFFF) }
        val d = doc(8, 8, layer(id, 8, 8))
        val out = IntArray(64)
        assertFalse(compositor.render(d, store, RenderRequest(RectD(0.0, 0.0, 8.0, 8.0), 8, 8), out, isCancelled = { true }))
    }

    @Test
    fun hitTestFindsTopmostOpaqueLayer() {
        val full = raster(10, 10) { _, _ -> Pixels.opaque(0x808080) }
        val dot = raster(10, 10) { x, y -> if (x < 5 && y < 5) Pixels.opaque(0xFF0000) else 0 }
        val bottom = layer(full, 10, 10)
        val top = layer(dot, 10, 10)
        val d = doc(10, 10, bottom, top)
        assertEquals(top.id, compositor.hitTest(d, store, 2.0, 2.0))
        assertEquals(bottom.id, compositor.hitTest(d, store, 8.0, 8.0))
        assertNull(compositor.hitTest(d, store, 20.0, 20.0))
    }

    @Test
    fun thumbnailsAndChannels() {
        val id = raster(100, 50) { _, _ -> Pixels.opaque(0x20A0F0) }
        val t = Thumbnails.raster(store.mips(id)!!, 32)
        assertEquals(32, t.width)
        assertEquals(16, t.height)
        val green = Thumbnails.channel(t, 1)
        assertEquals(Pixels.pack(255, 0xA0, 0xA0, 0xA0), green.premul[0])
        val docThumb = Thumbnails.document(doc(100, 50, layer(id, 100, 50)), store, 20)
        assertEquals(20, docThumb.width)
        assertEquals(10, docThumb.height)
    }
}
