package app.phopsd.engine

import app.phopsd.engine.edit.CanvasEdits
import app.phopsd.engine.edit.DocumentEdits
import app.phopsd.engine.edit.EditResult
import app.phopsd.engine.edit.PixelOps
import app.phopsd.engine.geometry.Affine
import app.phopsd.engine.geometry.AngleSnap
import app.phopsd.engine.geometry.CropBox
import app.phopsd.engine.geometry.IntRect
import app.phopsd.engine.geometry.LayerTransform
import app.phopsd.engine.geometry.PointD
import app.phopsd.engine.geometry.RectD
import app.phopsd.engine.geometry.TransformHandles
import app.phopsd.engine.geometry.TransformHandles.Handle
import app.phopsd.engine.io.Json
import app.phopsd.engine.io.ProjectReader
import app.phopsd.engine.io.ProjectWriter
import app.phopsd.engine.io.SaveInfo
import app.phopsd.engine.io.int
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.LayerId
import app.phopsd.engine.model.LayerMask
import app.phopsd.engine.model.RasterId
import app.phopsd.engine.model.RasterLayer
import app.phopsd.engine.model.RasterStore
import app.phopsd.engine.model.Selection
import app.phopsd.engine.model.SelectionOp
import app.phopsd.engine.model.SelectionOutline
import app.phopsd.engine.model.SelectionShape
import app.phopsd.engine.paint.BrushSettings
import app.phopsd.engine.paint.BrushStroke
import app.phopsd.engine.paint.StrokeMode
import app.phopsd.engine.raster.MipChain
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.raster.TiledRaster
import app.phopsd.engine.render.Compositor
import app.phopsd.engine.render.MaskDisplay
import app.phopsd.engine.render.RenderRequest
import app.phopsd.engine.render.Sampling
import app.phopsd.engine.selection.SelectionRaster
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs

/** M4: copy-on-write rasters, brushes, selections, masks, canvas operations, handles. */
class PaintingTest {

    private fun photo(w: Int, h: Int, seed: Long, tileSize: Int = 32): TiledRaster {
        val rnd = Random(seed)
        val px = IntArray(w * h) { Pixels.opaque(rnd.nextInt(0x1000000)) }
        return TiledRaster.fromPixels(w, h, px, straightAlpha = true, tileSize = tileSize)
    }

    private fun pixels(r: TiledRaster): IntArray = IntArray(r.width * r.height).also { r.readRegion(0, 0, r.width, r.height, it) }

    private fun Document.applied(r: EditResult): Document = (r as? EditResult.Applied)?.document ?: error("Rejected: $r")

    private fun layer(id: String, rid: RasterId, w: Int, h: Int, t: LayerTransform = LayerTransform.atOffset(0.0, 0.0, w, h), mask: LayerMask? = null) =
        RasterLayer(LayerId(id), id, rid, w, h, t, mask = mask)

    private fun render(doc: Document, store: RasterStore, sampling: Sampling = Sampling.Nearest, maskDisplay: MaskDisplay? = null): IntArray =
        Compositor().render(doc, store, RenderRequest(RectD(0.0, 0.0, doc.width.toDouble(), doc.height.toDouble()), doc.width, doc.height, sampling, maskDisplay = maskDisplay))

    // ------------------------------------------------------------------ copy-on-write

    @Test
    fun deriveSharesTilesAndClonesOnlyOnWrite() {
        val base = photo(100, 100, 1)
        val before = pixels(base)
        val d = base.derive()
        d.setPixel(5, 5, Pixels.opaque(0x123456))
        d.freeze()
        assertArrayEquals("original must never change", before, pixels(base))
        assertEquals(Pixels.opaque(0x123456), d.pixel(5, 5))
        val ids = IdentityHashMap<IntArray, Boolean>()
        base.collectTileArrays(ids)
        d.collectTileArrays(ids)
        // 16 shared tiles + 1 cloned.
        assertEquals(base.allocatedTileCount + 1, ids.size)
        assertSame(base.tileOrNull(3, 3), d.tileOrNull(3, 3))
    }

    @Test
    fun incrementalMipsEqualFullRebuild() {
        val base = photo(150, 90, 2)
        val old = MipChain.build(base)
        val d = base.derive()
        for (y in 20 until 41) for (x in 33 until 70) d.setPixel(x, y, Pixels.opaque(0xFF0000))
        d.freeze()
        val inc = MipChain.derive(old, d, IntRect(33, 20, 70, 41))
        val full = MipChain.build(d)
        assertEquals(full.levels.size, inc.levels.size)
        for (i in full.levels.indices) assertArrayEquals("level $i", pixels(full.levels[i]), pixels(inc.levels[i]))
    }

    // ------------------------------------------------------------------ brush

    private fun stroke(target: TiledRaster, mode: StrokeMode, s: BrushSettings, pts: List<PointD>, toDoc: Affine = Affine.IDENTITY, sel: TiledRaster? = null): BrushStroke {
        val b = BrushStroke(target, toDoc, mode, s, sel)
        b.begin(pts[0].x, pts[0].y)
        for (p in pts.drop(1)) b.lineTo(p.x, p.y)
        b.end()
        b.result()
        return b
    }

    private val hard = BrushSettings(size = 10.0, hardness = 1.0, opacity = 1.0, flow = 1.0, spacing = 0.1, smoothing = 0.0, pressureSize = false)

    @Test
    fun hardBrushPaintsOpaqueColourAndLeavesOriginalIntact() {
        val layer = TiledRaster.solid(64, 64, Pixels.TRANSPARENT)
        val s = stroke(layer, StrokeMode.Paint(0x3366CC), hard, listOf(PointD(10.0, 32.0), PointD(50.0, 32.0)))
        val r = s.working
        assertEquals(Pixels.opaque(0x3366CC), r.pixel(30, 32))
        assertEquals(0, Pixels.alpha(r.pixel(30, 20)))
        assertEquals(Pixels.TRANSPARENT, layer.pixel(30, 32))
        val dirty = s.dirty!!
        assertTrue(dirty.left <= 5 && dirty.right >= 55 && dirty.top <= 27 && dirty.bottom >= 37)
    }

    @Test
    fun strokeOpacityIsACapNotCumulative() {
        val layer = TiledRaster.solid(64, 64, Pixels.TRANSPARENT)
        val s = hard.copy(opacity = 0.5)
        // Scribble back and forth over the same spot many times in one stroke.
        val pts = (0 until 20).map { PointD(if (it % 2 == 0) 20.0 else 40.0, 32.0) }
        val r = stroke(layer, StrokeMode.Paint(0xFFFFFF), s, pts).working
        assertEquals(128, Pixels.alpha(r.pixel(30, 32)), 1)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("expected $expected±$tolerance but was $actual", abs(expected - actual) <= tolerance)

    @Test
    fun lowFlowBuildsUpWithOverlap() {
        val layer = TiledRaster.solid(64, 64, Pixels.TRANSPARENT)
        val s = hard.copy(flow = 0.1)
        val once = stroke(layer, StrokeMode.Paint(0xFFFFFF), s, listOf(PointD(32.0, 32.0))).working
        val many = stroke(layer, StrokeMode.Paint(0xFFFFFF), s, (0 until 30).map { PointD(if (it % 2 == 0) 31.0 else 33.0, 32.0) }).working
        val a1 = Pixels.alpha(once.pixel(32, 32))
        val a2 = Pixels.alpha(many.pixel(32, 32))
        assertTrue("single dab ~10%: $a1", a1 in 20..30)
        assertTrue("overlap builds up: $a2 > $a1", a2 > 200)
    }

    @Test
    fun softBrushFallsOffAndIsDeterministic() {
        val layer = TiledRaster.solid(80, 80, Pixels.TRANSPARENT)
        val soft = hard.copy(size = 40.0, hardness = 0.0)
        val pts = listOf(PointD(40.0, 40.0), PointD(41.0, 40.0))
        val a = stroke(layer, StrokeMode.Paint(0), soft, pts).working
        val b = stroke(layer, StrokeMode.Paint(0), soft, pts).working
        assertArrayEquals(pixels(a), pixels(b))
        val centre = Pixels.alpha(a.pixel(40, 40))
        val mid = Pixels.alpha(a.pixel(50, 40))
        val edge = Pixels.alpha(a.pixel(59, 40))
        assertTrue("$centre > $mid > $edge", centre > mid && mid > edge)
        assertEquals(0, Pixels.alpha(a.pixel(62, 40)))
    }

    @Test
    fun eraserRemovesPixels() {
        val layer = photo(64, 64, 3)
        val r = stroke(layer, StrokeMode.Erase, hard, listOf(PointD(32.0, 32.0))).working
        assertEquals(Pixels.TRANSPARENT, r.pixel(32, 32))
        assertEquals(layer.pixel(5, 5), r.pixel(5, 5))
        assertEquals(255, Pixels.alpha(layer.pixel(32, 32)))
    }

    @Test
    fun selectionClipsTheStroke() {
        val layer = TiledRaster.solid(64, 64, Pixels.TRANSPARENT)
        val sel = SelectionRaster.rasterize(SelectionShape.Rect(0.0, 0.0, 32.0, 64.0), 64, 64)
        val r = stroke(layer, StrokeMode.Paint(0xFF0000), hard.copy(size = 20.0), listOf(PointD(32.0, 32.0)), sel = sel).working
        assertEquals(255, Pixels.alpha(r.pixel(28, 32)))
        assertEquals(0, Pixels.alpha(r.pixel(36, 32)))
    }

    @Test
    fun brushSizeIsInDocumentPixelsOnScaledLayers() {
        // Layer shown at 50 %: a 10-doc-px brush covers 20 layer pixels.
        val t = LayerTransform(25.0, 25.0, 0.5, 0.5)
        val layer = TiledRaster.solid(100, 100, Pixels.TRANSPARENT)
        val toDoc = t.layerToDocument(100, 100)
        val r = stroke(layer, StrokeMode.Paint(0), hard, listOf(PointD(25.0, 25.0)), toDoc).working
        assertEquals(255, Pixels.alpha(r.pixel(50, 50)))
        assertEquals(255, Pixels.alpha(r.pixel(58, 50)))
        assertEquals(0, Pixels.alpha(r.pixel(62, 50)))
    }

    @Test
    fun pressureScalesSize() {
        val layer = TiledRaster.solid(64, 64, Pixels.TRANSPARENT)
        val s = hard.copy(size = 20.0, pressureSize = true)
        val b = BrushStroke(layer, Affine.IDENTITY, StrokeMode.Paint(0), s)
        b.begin(32.0, 32.0, pressure = 0.25)
        b.end()
        val r = b.result()
        // Diameter = 20 × (0.08 + 0.92 × 0.25) = 6.2 → radius ≈ 3.1.
        assertEquals(255, Pixels.alpha(r.pixel(33, 32)))
        assertEquals(0, Pixels.alpha(r.pixel(37, 32)))
    }

    // ------------------------------------------------------------------ selections

    @Test
    fun rectangleSelectionHasExactAndAntialiasedCoverage() {
        val sel = SelectionRaster.rasterize(SelectionShape.Rect(10.0, 10.0, 20.5, 20.0), 40, 40)
        assertEquals(255, Pixels.alpha(sel.pixel(15, 15)))
        assertEquals(0, Pixels.alpha(sel.pixel(9, 15)))
        assertEquals(128, Pixels.alpha(sel.pixel(20, 15)), 2)
        assertEquals(IntRect(10, 10, 21, 20), SelectionRaster.bounds(sel))
    }

    @Test
    fun ellipseAreaMatchesGeometry() {
        val sel = SelectionRaster.rasterize(SelectionShape.Ellipse(10.0, 20.0, 90.0, 60.0), 100, 80)
        var sum = 0.0
        for (y in 0 until 80) for (x in 0 until 100) sum += Pixels.alpha(sel.pixel(x, y)) / 255.0
        val expected = PI * 40 * 20
        assertTrue("area $sum vs $expected", abs(sum - expected) / expected < 0.01)
    }

    @Test
    fun lassoPolygonAndCombineOps() {
        val w = 60; val h = 60
        val tri = SelectionRaster.rasterize(SelectionShape.Polygon(doubleArrayOf(0.0, 0.0, 60.0, 0.0, 0.0, 60.0)), w, h)
        assertEquals(255, Pixels.alpha(tri.pixel(10, 10)))
        assertEquals(0, Pixels.alpha(tri.pixel(50, 50)))
        val rect = SelectionRaster.rasterize(SelectionShape.Rect(20.0, 0.0, 60.0, 60.0), w, h)
        val add = SelectionRaster.combine(tri, rect, SelectionOp.Add)
        val sub = SelectionRaster.combine(tri, rect, SelectionOp.Subtract)
        val inter = SelectionRaster.combine(tri, rect, SelectionOp.Intersect)
        assertEquals(255, Pixels.alpha(add.pixel(50, 50)))
        assertEquals(255, Pixels.alpha(sub.pixel(10, 10)))
        assertEquals(0, Pixels.alpha(sub.pixel(25, 10)))
        assertEquals(255, Pixels.alpha(inter.pixel(25, 10)))
        assertEquals(0, Pixels.alpha(inter.pixel(10, 10)))
        val inv = SelectionRaster.invert(tri)
        assertEquals(0, Pixels.alpha(inv.pixel(10, 10)))
        assertEquals(255, Pixels.alpha(inv.pixel(50, 50)))
        assertTrue(SelectionRaster.isEmpty(SelectionRaster.combine(null, rect, SelectionOp.Intersect)))
    }

    @Test
    fun fullCanvasSelectionCostsAlmostNoMemory() {
        val sel = SelectionRaster.rasterize(SelectionShape.Rect(0.0, 0.0, 4000.0, 3000.0), 4000, 3000)
        val ids = IdentityHashMap<IntArray, Boolean>()
        sel.collectTileArrays(ids)
        assertTrue("distinct tile arrays: ${ids.size}", ids.size <= 2)
        val inv = SelectionRaster.invert(sel)
        assertTrue(SelectionRaster.isEmpty(inv))
    }

    // ------------------------------------------------------------------ masks & compositing

    /** Two photographs, the top one with a mask. */
    private class TwoPhotos(val test: PaintingTest) {
        val store = RasterStore()
        val bottomR = test.photo(80, 60, 10)
        val topR = test.photo(80, 60, 20)
        val bottomId = RasterId("bottom"); val topId = RasterId("top"); val maskId = RasterId("mask")
        val doc: Document

        init {
            store.put(bottomId, MipChain.build(bottomR))
            store.put(topId, MipChain.build(topR))
            store.put(maskId, MipChain.build(PixelOps.solidMask(80, 60, 255)))
            doc = Document("d", "Composite", 80, 60, listOf(
                test.layer("Bottom", bottomId, 80, 60),
                test.layer("Top", topId, 80, 60, mask = LayerMask(maskId)),
            ), LayerId("Top"))
        }
    }

    /**
     * Roadmap M4 acceptance: a mask combines two photographs without destructively removing
     * source pixels, and every stroke is undoable.
     */
    @Test
    fun acceptanceMaskCombinesTwoPhotosNonDestructively() {
        val f = TwoPhotos(this)
        val topBefore = pixels(f.topR)
        val revealAll = render(f.doc, f.store)
        assertArrayEquals("reveal-all mask shows the top photo", pixels(f.topR), revealAll)

        // Paint black (conceal) over the left half of the mask with a hard brush.
        val maskBase = f.store.mips(f.maskId)!!.base
        val b = BrushStroke(maskBase, Affine.IDENTITY, StrokeMode.MaskValue(0), hard.copy(size = 30.0))
        b.begin(-20.0, 0.0)
        var y = 0.0
        while (y <= 60.0) { b.lineTo(20.0, y); b.lineTo(-20.0, y + 5); y += 10 }
        b.end()
        val painted = b.result()
        val newMaskId = RasterId("mask2")
        f.store.put(newMaskId, MipChain.derive(f.store.mips(f.maskId)!!, painted, b.dirty!!))
        val history = mutableListOf(f.doc)
        val masked = f.doc.applied(CanvasEdits.setMaskRaster(f.doc, LayerId("Top"), newMaskId))
        history += masked

        val out = render(masked, f.store)
        for (yy in 0 until 60) {
            assertEquals("left half shows bottom photo (row $yy)", f.bottomR.pixel(5, yy), out[yy * 80 + 5])
            assertEquals("right half shows top photo (row $yy)", f.topR.pixel(70, yy), out[yy * 80 + 70])
        }
        // Source pixels are untouched: the top layer still holds every original pixel.
        assertArrayEquals(topBefore, pixels(f.topR))
        assertEquals(LayerId("Top"), (masked.layer(LayerId("Top")) as RasterLayer).id)
        assertEquals(f.topId, (masked.layer(LayerId("Top")) as RasterLayer).rasterId)

        // Disabling the mask shows the full top photo again; deleting it too.
        val disabled = masked.applied(CanvasEdits.setMaskEnabled(masked, LayerId("Top"), false))
        assertArrayEquals(pixels(f.topR), render(disabled, f.store))
        // Undo (previous document snapshot) restores the exact previous composite.
        assertArrayEquals(revealAll, render(history[history.size - 2], f.store))
        // Mask alone view shows the mask as gray.
        val alone = render(masked, f.store, maskDisplay = MaskDisplay(LayerId("Top"), MaskDisplay.Mode.Alone))
        assertEquals(Pixels.opaque(0x000000), alone[30 * 80 + 2])
        assertEquals(Pixels.opaque(0xFFFFFF), alone[30 * 80 + 75])
    }

    @Test
    fun maskSamplingMatchesAcrossMipLevelsAndLiveMasks() {
        val f = TwoPhotos(this)
        val hide = MipChain.build(PixelOps.solidMask(80, 60, 0))
        f.store.put(RasterId("hide"), hide)
        val doc = f.doc.applied(CanvasEdits.setMaskRaster(f.doc, LayerId("Top"), RasterId("hide")))
        // Zoomed-out render (uses reduced mip levels) still hides the top photo.
        val req = RenderRequest(RectD(0.0, 0.0, 80.0, 60.0), 20, 15)
        val small = Compositor().render(doc, f.store, req)
        val bottomOnly = Compositor().render(doc.copy(layers = doc.layers.take(1), activeLayerId = LayerId("Bottom")), f.store, req)
        assertArrayEquals(bottomOnly, small)
        // A live single-level mask (during a stroke) must still apply at reduced levels.
        f.store.put(RasterId("hide"), MipChain.live(PixelOps.solidMask(80, 60, 0)))
        assertArrayEquals(bottomOnly, Compositor().render(doc, f.store, req))
    }

    @Test
    fun hitTestRespectsMasks() {
        val f = TwoPhotos(this)
        f.store.put(RasterId("hide"), MipChain.build(PixelOps.solidMask(80, 60, 0)))
        val doc = f.doc.applied(CanvasEdits.setMaskRaster(f.doc, LayerId("Top"), RasterId("hide")))
        assertEquals(LayerId("Bottom"), Compositor().hitTest(doc, f.store, 10.0, 10.0))
    }

    @Test
    fun maskFromSelectionInvertAndApply() {
        val sel = SelectionRaster.rasterize(SelectionShape.Rect(0.0, 0.0, 40.0, 60.0), 80, 60)
        val mask = PixelOps.maskFromSelection(80, 60, Affine.IDENTITY, sel, reveal = true)
        assertEquals(255, Pixels.alpha(mask.pixel(10, 10)))
        assertEquals(0, Pixels.alpha(mask.pixel(60, 10)))
        val inv = PixelOps.invertMask(mask)
        assertEquals(0, Pixels.alpha(inv.pixel(10, 10)))
        assertEquals(255, Pixels.alpha(inv.pixel(60, 10)))
        val p = photo(80, 60, 4)
        val applied = PixelOps.applyMask(p, mask)
        assertEquals(p.pixel(10, 10), applied.pixel(10, 10))
        assertEquals(Pixels.TRANSPARENT, applied.pixel(60, 10))
        // A moved layer maps the selection through its transform.
        val moved = PixelOps.maskFromSelection(80, 60, Affine.translate(20.0, 0.0), sel, reveal = true)
        assertEquals(255, Pixels.alpha(moved.pixel(15, 10)))
        assertEquals(0, Pixels.alpha(moved.pixel(25, 10)))
    }

    // ------------------------------------------------------------------ pixel ops

    @Test
    fun clearFillAndExtractHonourSelection() {
        val p = photo(64, 64, 5)
        val sel = SelectionRaster.rasterize(SelectionShape.Rect(10.0, 10.0, 30.0, 30.0), 64, 64)
        val cleared = PixelOps.clear(p, Affine.IDENTITY, sel)!!
        assertEquals(Pixels.TRANSPARENT, cleared.raster.pixel(20, 20))
        assertEquals(p.pixel(40, 40), cleared.raster.pixel(40, 40))
        assertTrue(cleared.dirty.left <= 10 && cleared.dirty.right >= 30)
        val filled = PixelOps.fill(p, Affine.IDENTITY, sel, 0x00FF00)!!
        assertEquals(Pixels.opaque(0x00FF00), filled.raster.pixel(20, 20))
        assertEquals(p.pixel(5, 5), filled.raster.pixel(5, 5))
        val ex = PixelOps.extract(p, Affine.IDENTITY, sel)
        assertEquals(p.pixel(20, 20), ex.pixel(20, 20))
        assertEquals(Pixels.TRANSPARENT, ex.pixel(40, 40))
        assertNull(PixelOps.clear(p, Affine.IDENTITY, SelectionRaster.empty(64, 64)))
        // Whole layer without a selection.
        assertEquals(Pixels.TRANSPARENT, PixelOps.clear(p, Affine.IDENTITY, null)!!.raster.pixel(63, 63))
    }

    @Test
    fun mergeMatchesCompositeIncludingMasks() {
        val f = TwoPhotos(this)
        val sel = SelectionRaster.rasterize(SelectionShape.Rect(0.0, 0.0, 40.0, 60.0), 80, 60)
        f.store.put(RasterId("half"), MipChain.build(PixelOps.maskFromSelection(80, 60, Affine.IDENTITY, sel, true)))
        val doc = f.doc.applied(CanvasEdits.setMaskRaster(f.doc, LayerId("Top"), RasterId("half")))
        val expected = render(doc, f.store, Sampling.Bilinear)
        val merged = PixelOps.merge(doc, f.store, doc.layers)!!
        assertArrayEquals(expected, pixels(merged))
        f.store.put(RasterId("merged"), MipChain.build(merged))
        val ml = CanvasEdits.canvasLayer(doc, LayerId("M"), "Merged", RasterId("merged"))
        val flat = doc.applied(CanvasEdits.mergeDown(doc, LayerId("Top"), ml))
        assertEquals(1, flat.layers.size)
        assertArrayEquals(expected, render(flat, f.store))
    }

    // ------------------------------------------------------------------ canvas

    private fun oneLayerDoc(store: RasterStore, r: TiledRaster): Document {
        store.put(RasterId("p"), MipChain.build(r))
        return Document("d", "D", r.width, r.height, listOf(layer("L", RasterId("p"), r.width, r.height)), LayerId("L"))
    }

    @Test
    fun rotateCanvasMapsPixelsExactly() {
        val store = RasterStore()
        val r = photo(30, 20, 6)
        val doc = oneLayerDoc(store, r)
        val cw = doc.applied(CanvasEdits.rotateCanvas(doc, CanvasEdits.Rotation.Cw90))
        assertEquals(20, cw.width); assertEquals(30, cw.height)
        val out = render(cw, store)
        // CW: source (x, y) → (H − 1 − y, x).
        for (y in 0 until 20) for (x in 0 until 30) assertEquals(r.pixel(x, y), out[x * 20 + (19 - y)])
        val back = cw.applied(CanvasEdits.rotateCanvas(cw, CanvasEdits.Rotation.Ccw90))
        assertArrayEquals(pixels(r), render(back, store))
        val half = doc.applied(CanvasEdits.rotateCanvas(doc, CanvasEdits.Rotation.Half))
        assertEquals(r.pixel(0, 0), render(half, store)[19 * 30 + 29])
    }

    @Test
    fun flipCanvasAndLayer() {
        val store = RasterStore()
        val r = photo(30, 20, 7)
        val doc = oneLayerDoc(store, r)
        val h = doc.applied(CanvasEdits.flipCanvas(doc, horizontal = true))
        val out = render(h, store)
        for (y in 0 until 20) for (x in 0 until 30) assertEquals(r.pixel(x, y), out[y * 30 + (29 - x)])
        val v = doc.applied(CanvasEdits.flipCanvas(doc, horizontal = false))
        assertEquals(r.pixel(3, 0), render(v, store)[19 * 30 + 3])
        val hh = h.applied(CanvasEdits.flipCanvas(h, horizontal = true))
        assertArrayEquals(pixels(r), render(hh, store))
        val fl = doc.applied(CanvasEdits.flipLayer(doc, LayerId("L"), horizontal = true))
        assertArrayEquals(out, render(fl, store))
    }

    @Test
    fun rotatedLayerSurvivesCanvasRotationAndFlip() {
        // A transformed layer composites identically to rotating/flipping the rendered image.
        val store = RasterStore()
        store.put(RasterId("p"), MipChain.build(photo(16, 10, 8)))
        val doc = Document("d", "D", 40, 30, listOf(layer("L", RasterId("p"), 16, 10, LayerTransform(15.0, 12.0, 1.0, -1.0, 90.0))), LayerId("L"))
        val base = render(doc, store)
        val flipped = render(doc.applied(CanvasEdits.flipCanvas(doc, horizontal = true)), store)
        for (y in 0 until 30) for (x in 0 until 40) assertEquals(base[y * 40 + x], flipped[y * 40 + 39 - x])
        val rot = render(doc.applied(CanvasEdits.rotateCanvas(doc, CanvasEdits.Rotation.Cw90)), store)
        for (y in 0 until 30) for (x in 0 until 40) assertEquals(base[y * 40 + x], rot[x * 30 + (29 - y)])
    }

    @Test
    fun cropAndCanvasSizeAreNonDestructive() {
        val store = RasterStore()
        val r = photo(40, 30, 9)
        val doc = oneLayerDoc(store, r)
        val cropped = doc.applied(CanvasEdits.crop(doc, IntRect(10, 5, 30, 25)))
        assertEquals(20, cropped.width); assertEquals(20, cropped.height)
        assertEquals(r.pixel(10, 5), render(cropped, store)[0])
        // Growing the canvas back reveals the cropped pixels: nothing was deleted.
        val grown = cropped.applied(CanvasEdits.canvasSize(cropped, 40, 30, anchorX = 0.5, anchorY = 0.5))
        assertArrayEquals(pixels(r), render(grown, store))
        assertTrue(CanvasEdits.crop(doc, IntRect(0, 0, 0, 10)) is EditResult.Rejected)
    }

    @Test
    fun imageSizeScalesProportionally() {
        val store = RasterStore()
        val doc = oneLayerDoc(store, photo(40, 30, 11))
        val big = doc.applied(CanvasEdits.imageSize(doc, 80, 60))
        val t = (big.layers[0] as RasterLayer).transform
        assertEquals(40.0, t.centerX, 1e-9); assertEquals(2.0, t.scaleX, 1e-9)
        assertTrue(CanvasEdits.imageSize(doc, 80, 30) is EditResult.Rejected)
    }

    // ------------------------------------------------------------------ transform handles

    @Test
    fun cornerScaleKeepsOppositeCornerFixed() {
        val t = LayerTransform(50.0, 40.0, 1.0, 1.0, 30.0)
        val br0 = TransformHandles.handlePoint(t, 40, 20, 1, 1)
        val tl0 = TransformHandles.handlePoint(t, 40, 20, -1, -1)
        val n = TransformHandles.scale(t, 40, 20, Handle.BottomRight, br0, PointD(br0.x + 13.0, br0.y + 7.0), proportional = false, fromCenter = false)
        val tl1 = TransformHandles.handlePoint(n, 40, 20, -1, -1)
        val br1 = TransformHandles.handlePoint(n, 40, 20, 1, 1)
        assertEquals(tl0.x, tl1.x, 1e-9); assertEquals(tl0.y, tl1.y, 1e-9)
        assertEquals(br0.x + 13.0, br1.x, 1e-9); assertEquals(br0.y + 7.0, br1.y, 1e-9)
        assertEquals(30.0, n.rotationDegrees, 1e-9)
    }

    @Test
    fun proportionalAndCentredScale() {
        val t = LayerTransform(50.0, 50.0)
        val br = TransformHandles.handlePoint(t, 40, 20, 1, 1)
        val p = TransformHandles.scale(t, 40, 20, Handle.BottomRight, br, PointD(br.x + 40.0, br.y + 20.0), proportional = true, fromCenter = false)
        assertEquals(2.0, p.scaleX, 1e-9); assertEquals(2.0, p.scaleY, 1e-9)
        val c = TransformHandles.scale(t, 40, 20, Handle.Right, TransformHandles.handlePoint(t, 40, 20, 1, 0), PointD(90.0, 50.0), proportional = false, fromCenter = true)
        assertEquals(50.0, c.centerX, 1e-9); assertEquals(2.0, c.scaleX, 1e-9); assertEquals(1.0, c.scaleY, 1e-9)
        // Dragging an edge past the anchor never yields a zero or invalid scale.
        val tiny = TransformHandles.scale(t, 40, 20, Handle.Right, TransformHandles.handlePoint(t, 40, 20, 1, 0), PointD(30.0, 50.0), proportional = false, fromCenter = false)
        assertTrue(abs(tiny.scaleX) * 40 >= TransformHandles.MIN_EXTENT - 1e-9)
    }

    @Test
    fun rotateHandleSnapsAndHitTestFindsHandles() {
        val t = LayerTransform(50.0, 50.0)
        val r = TransformHandles.rotate(t, PointD(100.0, 50.0), PointD(50.0, 101.0), AngleSnap(true, 45.0, 5.0))
        assertEquals(90.0, r.rotationDegrees, 1e-9)
        assertEquals(Handle.TopLeft, TransformHandles.hitTest(t, 40, 20, PointD(31.0, 41.0), 4.0, 12.0))
        assertEquals(Handle.Move, TransformHandles.hitTest(t, 40, 20, PointD(50.0, 50.0), 4.0, 12.0))
        assertEquals(Handle.Rotate, TransformHandles.hitTest(t, 40, 20, PointD(22.0, 34.0), 4.0, 12.0))
        assertNull(TransformHandles.hitTest(t, 40, 20, PointD(0.0, 0.0), 4.0, 12.0))
    }

    @Test
    fun cropBoxKeepsAspect() {
        val start = CropBox.initial(300, 200, 1.0)
        assertEquals(RectD(50.0, 0.0, 250.0, 200.0), start)
        val d = CropBox.drag(start, Handle.BottomRight, -50.0, -10.0, 1.0)
        assertEquals(d.width, d.height, 1e-9)
        assertEquals(50.0, d.left, 1e-9); assertEquals(0.0, d.top, 1e-9)
        val free = CropBox.drag(start, Handle.Left, 500.0, 0.0, null)
        assertTrue(free.width >= 1.0)
        assertEquals(IntRect(50, 0, 250, 200), CropBox.toPixels(start))
    }

    // ------------------------------------------------------------------ persistence v2

    @Test
    fun masksRoundTripThroughProjectFormatV2() {
        val f = TwoPhotos(this)
        val sel = SelectionRaster.rasterize(SelectionShape.Ellipse(10.0, 10.0, 60.0, 50.0), 80, 60)
        f.store.put(RasterId("ellipse"), MipChain.build(PixelOps.maskFromSelection(80, 60, Affine.IDENTITY, sel, true)))
        var doc = f.doc.applied(CanvasEdits.setMaskRaster(f.doc, LayerId("Top"), RasterId("ellipse")))
        doc = doc.applied(CanvasEdits.setMaskEnabled(doc, LayerId("Top"), false))
        // Selections are session state: not saved.
        f.store.put(RasterId("sel"), MipChain.build(sel))
        doc = doc.copy(selection = Selection(RasterId("sel"), SelectionOutline(listOf(SelectionOp.Replace to SelectionShape.Rect(0.0, 0.0, 1.0, 1.0)))))
        val bytes = ByteArrayOutputStream().also { ProjectWriter.write(doc, f.store, it, SaveInfo("t", 0L)) }.toByteArray()
        val loaded = ProjectReader.read(ByteArrayInputStream(bytes))
        assertEquals(doc.copy(selection = null), loaded.document)
        assertEquals(3, loaded.rasters.size)
        val store2 = RasterStore().also { s -> loaded.rasters.forEach { (k, v) -> s.put(k, MipChain.build(v)) } }
        val enabled = doc.applied(CanvasEdits.setMaskEnabled(doc, LayerId("Top"), true))
        assertArrayEquals(render(enabled, f.store), render(enabled.copy(selection = null), store2))
        // Masks require a v2 reader.
        val manifest = java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).let { z ->
            var m: Json.Obj? = null
            while (true) { val e = z.nextEntry ?: break; if (e.name == "manifest.json") m = Json.parse(z.readBytes().toString(Charsets.UTF_8)) as Json.Obj }
            m!!
        }
        assertEquals(2, manifest.int("minReaderVersion"))
    }

    @Test
    fun contentKeyIgnoresSelection() {
        val f = TwoPhotos(this)
        val withSel = f.doc.copy(selection = Selection(RasterId("x"), SelectionOutline(emptyList())))
        assertEquals(f.doc.contentKey(), withSel.contentKey())
        assertNotEquals(f.doc, withSel)
        assertNotNull(DocumentEdits.nextLayerName(f.doc))
    }
}
