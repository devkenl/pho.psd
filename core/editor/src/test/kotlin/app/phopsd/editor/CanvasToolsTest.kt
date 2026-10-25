package app.phopsd.editor

import app.phopsd.engine.geometry.CropBox
import app.phopsd.engine.geometry.RectD
import app.phopsd.engine.model.BackgroundContents
import app.phopsd.engine.model.NewDocumentSpec
import app.phopsd.engine.model.OutlineStep
import app.phopsd.engine.model.RasterLayer
import app.phopsd.engine.model.SelectionOp
import app.phopsd.engine.model.SourceInfo
import app.phopsd.engine.paint.BrushSettings
import app.phopsd.engine.raster.MipChain
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.raster.TiledRaster
import app.phopsd.engine.render.Compositor
import app.phopsd.engine.render.RenderRequest
import app.phopsd.engine.render.Sampling
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CanvasToolsTest {
    private val c = EditorController(clock = { 5_000L })
    private val t get() = c.tools
    private val s get() = c.state.value
    private val doc get() = s.document!!
    private val active get() = doc.activeLayer as RasterLayer

    private val hard = BrushSettings(size = 10.0, hardness = 1.0, spacing = 0.1, smoothing = 0.0, pressureSize = false)

    private fun newDoc(w: Int = 80, h: Int = 60, bg: BackgroundContents = BackgroundContents.White) {
        assertTrue(c.createDocument(NewDocumentSpec("T", w, h, background = bg)))
        c.setViewSize(800.0, 600.0)
    }

    private fun photo(w: Int, h: Int, seed: Long): MipChain {
        val rnd = Random(seed)
        return MipChain.build(TiledRaster.fromPixels(w, h, IntArray(w * h) { Pixels.opaque(rnd.nextInt(0x1000000)) }, straightAlpha = true))
    }

    private fun src(w: Int, h: Int) = SourceInfo("p.jpg", "image/jpeg", w, h, null, false)

    private fun render(): IntArray {
        val d = doc
        return Compositor().render(d, c.renderLookup, RenderRequest(RectD(0.0, 0.0, d.width.toDouble(), d.height.toDouble()), d.width, d.height, Sampling.Nearest))
    }

    private fun pixels(id: app.phopsd.engine.model.RasterId): IntArray {
        val r = c.rasters.mips(id)!!.base
        return IntArray(r.width * r.height).also { r.readRegion(0, 0, r.width, r.height, it) }
    }

    private fun strokeLine(x0: Double, y0: Double, x1: Double, y1: Double, pressure: Double = 1.0, eraserTip: Boolean = false): Boolean {
        if (!t.beginStroke(x0, y0, pressure, eraserTip)) return false
        t.strokeTo((x0 + x1) / 2, (y0 + y1) / 2, pressure)
        t.strokeTo(x1, y1, pressure)
        t.endStroke()
        return true
    }

    private fun brush(settings: BrushSettings = hard) {
        c.selectTool(Tool.Brush)
        t.setBrushSettings(settings)
    }

    // ------------------------------------------------------------------ painting

    @Test
    fun brushStrokeIsOneUndoStepAndUndoRestoresPixels() {
        newDoc()
        c.dispatch(CommandId.NewLayer)
        val before = active.rasterId
        brush()
        t.setColor(ColorSlot.Foreground, 0xFF0000)
        val steps = s.history!!.entries.size
        assertTrue(strokeLine(10.0, 30.0, 70.0, 30.0))
        assertEquals(steps + 1, s.history!!.entries.size)
        assertEquals("Brush", s.history!!.undoLabel)
        assertNotEquals(before, active.rasterId)
        assertEquals(Pixels.opaque(0xFF0000), render()[30 * 80 + 40])
        assertFalse(s.stroking)
        assertNull(c.takeStrokeDirty().let { if (it == null) null else c.takeStrokeDirty() })
        c.undo()
        assertEquals(before, active.rasterId)
        assertEquals(Pixels.opaque(0xFFFFFF), render()[30 * 80 + 40])
        c.redo()
        assertEquals(Pixels.opaque(0xFF0000), render()[30 * 80 + 40])
    }

    @Test
    fun liveStrokeIsVisibleBeforeCommitAndReportsDirtyRegions() {
        newDoc()
        brush()
        c.takeStrokeDirty()
        val rev = c.strokeRevision.value
        assertTrue(t.beginStroke(40.0, 30.0, 1.0))
        t.strokeTo(50.0, 30.0, 1.0)
        assertTrue(s.stroking)
        assertTrue(c.strokeRevision.value > rev)
        val dirty = c.takeStrokeDirty()!!
        assertTrue(dirty.left <= 35 && dirty.right >= 55)
        // The document has not changed yet, but the render shows the live pixels.
        assertEquals(1, s.history!!.entries.size)
        assertEquals(Pixels.opaque(0x000000), render()[30 * 80 + 45])
        t.cancelStroke()
        assertEquals(Pixels.opaque(0xFFFFFF), render()[30 * 80 + 45])
        assertEquals(1, s.history!!.entries.size)
    }

    @Test
    fun lockedAndHiddenLayersRefusePainting() {
        newDoc()
        brush()
        c.toggleLock(active.id)
        assertFalse(t.beginStroke(10.0, 10.0, 1.0))
        assertTrue(s.notice!!.text.contains("locked"))
        c.toggleLock(active.id)
        c.toggleVisibility(active.id)
        assertFalse(t.beginStroke(10.0, 10.0, 1.0))
        assertTrue(s.notice!!.text.contains("hidden"))
    }

    @Test
    fun eraserAndStylusEraserTipRemovePixels() {
        newDoc(bg = BackgroundContents.White)
        brush()
        assertTrue(strokeLine(5.0, 10.0, 30.0, 10.0, eraserTip = true, pressure = 1.0))
        assertEquals("Eraser", s.history!!.undoLabel)
        assertEquals(0, Pixels.alpha(render()[10 * 80 + 20]))
        c.selectTool(Tool.Eraser)
        t.setBrushSettings(hard)
        assertTrue(strokeLine(5.0, 40.0, 30.0, 40.0))
        assertEquals(0, Pixels.alpha(render()[40 * 80 + 20]))
    }

    @Test
    fun stylusTurnsOffFingerPainting() {
        newDoc()
        brush()
        assertTrue(s.fingerPainting)
        assertTrue(t.beginStroke(10.0, 10.0, 0.5, stylus = true))
        t.endStroke()
        assertFalse(s.fingerPainting)
        assertTrue(s.stylusSeen)
    }

    @Test
    fun brushIsClippedToSelection() {
        newDoc()
        brush()
        c.selectTool(Tool.RectMarquee)
        t.beginSelection(0.0, 0.0)
        t.updateSelection(40.0, 60.0)
        t.endSelection()
        assertNotNull(doc.selection)
        brush()
        assertTrue(strokeLine(10.0, 30.0, 70.0, 30.0))
        val out = render()
        assertEquals(Pixels.opaque(0), out[30 * 80 + 20])
        assertEquals(Pixels.opaque(0xFFFFFF), out[30 * 80 + 60])
    }

    // ------------------------------------------------------------------ acceptance

    /**
     * Roadmap M4 acceptance, through the editor: a mask combines two photographs without
     * removing source pixels; every stroke and transform can be undone.
     */
    @Test
    fun acceptanceMaskCompositeAndUndoEverything() {
        newDoc(80, 60)
        val a = photo(80, 60, 1)
        val b = photo(80, 60, 2)
        c.placeImage("Sky", a, src(80, 60))
        c.placeImage("City", b, src(80, 60))
        val top = active
        val topPixels = pixels(top.rasterId)
        val original = render()
        val startIndex = s.history!!.index

        // Mask brush paints black on the left half: the mask is added automatically.
        c.selectTool(Tool.Mask)
        t.setBrushSettings(hard.copy(size = 30.0))
        for (y in listOf(0.0, 15.0, 30.0, 45.0, 60.0)) assertTrue(strokeLine(0.0, y, 25.0, y))
        assertNotNull(active.mask)
        val out = render()
        for (y in 0 until 60) {
            assertEquals("left shows the lower photo", a.base.pixel(3, y), out[y * 80 + 3])
            assertEquals("right shows the upper photo", b.base.pixel(75, y), out[y * 80 + 75])
        }
        // Layer pixels were never touched.
        assertEquals(top.rasterId, active.rasterId)
        assertArrayEquals(topPixels, pixels(active.rasterId))

        // A soft white stroke partially reveals again; a free transform moves the layer.
        t.setColor(ColorSlot.Foreground, 0xFFFFFF)
        t.setBrushSettings(hard.copy(size = 8.0, hardness = 0.0))
        assertTrue(strokeLine(10.0, 30.0, 12.0, 30.0))
        c.selectTool(Tool.Transform)
        assertEquals(app.phopsd.engine.geometry.TransformHandles.Handle.Move, t.transformBegin(40.0, 30.0, 4.0, 10.0))
        t.transformUpdate(45.0, 33.0)
        t.transformEnd()
        assertEquals(45.0, active.transform.centerX, 1e-9)

        // Undo everything back to the two plain photos.
        while (s.history!!.index > startIndex) c.undo()
        assertNull(active.mask)
        assertArrayEquals(original, render())
        assertArrayEquals(topPixels, pixels(active.rasterId))
        // ...and redo it all.
        while (s.history!!.canRedo) c.redo()
        assertNotNull(active.mask)
        assertEquals(45.0, active.transform.centerX, 1e-9)
    }

    // ------------------------------------------------------------------ masks

    @Test
    fun maskCommandsAddInvertDisableApplyDelete() {
        newDoc()
        c.dispatch(CommandId.NewLayer)
        brush()
        strokeLine(0.0, 30.0, 80.0, 30.0)
        c.dispatch(CommandId.AddMaskHideAll)
        assertNotNull(active.mask)
        assertEquals(PaintTarget.Mask, s.paintTarget)
        assertTrue(s.paintsMask)
        assertEquals(Pixels.opaque(0xFFFFFF), render()[30 * 80 + 40])
        c.dispatch(CommandId.InvertMask)
        assertEquals(Pixels.opaque(0), render()[30 * 80 + 40])
        c.dispatch(CommandId.ToggleMaskEnabled)
        assertFalse(active.mask!!.enabled)
        c.dispatch(CommandId.ToggleMaskEnabled)
        c.dispatch(CommandId.ViewMask)
        assertNotNull(s.maskDisplay)
        c.dispatch(CommandId.ViewMask)
        assertNull(s.maskDisplay)
        c.dispatch(CommandId.ApplyMask)
        assertNull(active.mask)
        assertEquals(PaintTarget.Pixels, s.paintTarget)
        assertEquals(Pixels.opaque(0), render()[30 * 80 + 40])
        c.dispatch(CommandId.AddMask)
        c.dispatch(CommandId.DeleteMask)
        assertNull(active.mask)
        assertFalse(c.availability(CommandId.DeleteMask).isEnabled)
    }

    @Test
    fun maskFromSelectionRevealsOnlySelection() {
        newDoc()
        c.dispatch(CommandId.NewLayer)
        brush()
        t.setBrushSettings(hard.copy(size = 200.0))
        strokeLine(40.0, 30.0, 41.0, 30.0)
        c.selectTool(Tool.EllipticalMarquee)
        t.beginSelection(0.0, 0.0); t.updateSelection(40.0, 60.0); t.endSelection()
        c.dispatch(CommandId.AddMask)
        val out = render()
        assertEquals(Pixels.opaque(0), out[30 * 80 + 20])
        assertEquals(Pixels.opaque(0xFFFFFF), out[30 * 80 + 60])
    }

    // ------------------------------------------------------------------ selections

    @Test
    fun marqueeOpsClickDeselectAndSelectionIsNotDirty() {
        newDoc()
        c.selectTool(Tool.RectMarquee)
        t.beginSelection(10.0, 10.0); t.updateSelection(30.0, 30.0); t.endSelection()
        assertEquals("Rectangular Marquee", s.history!!.undoLabel)
        assertFalse("a selection is not an unsaved change", s.dirty)
        t.beginSelection(50.0, 10.0, shift = true); t.updateSelection(70.0, 30.0); t.endSelection()
        val steps = doc.selection!!.outline.steps
        assertEquals(2, steps.size)
        assertEquals(SelectionOp.Add, (steps[1] as OutlineStep.Shape).op)
        c.dispatch(CommandId.InverseSelection)
        assertEquals(OutlineStep.Invert, doc.selection!!.outline.steps.last())
        // Click without dragging deselects.
        t.beginSelection(5.0, 5.0); t.endSelection()
        assertNull(doc.selection)
        c.dispatch(CommandId.SelectAll)
        assertNotNull(doc.selection)
        c.dispatch(CommandId.Deselect)
        assertNull(doc.selection)
        c.undo()
        assertNotNull(doc.selection)
    }

    @Test
    fun polygonalLassoClosesOnFirstPoint() {
        newDoc()
        c.selectTool(Tool.PolygonalLasso)
        t.polygonTap(10.0, 10.0, 3.0)
        t.polygonTap(60.0, 10.0, 3.0)
        t.updateSelection(30.0, 50.0)
        assertNotNull((s.selectionDraft as SelectionDraft.Lasso).cursor)
        t.polygonTap(30.0, 50.0, 3.0)
        t.polygonTap(11.0, 11.0, 3.0)
        assertNull(s.selectionDraft)
        assertNotNull(doc.selection)
        assertEquals("Polygonal Lasso", s.history!!.undoLabel)
    }

    @Test
    fun freehandLassoThinsPoints() {
        newDoc()
        c.selectTool(Tool.Lasso)
        t.beginSelection(10.0, 10.0)
        for (i in 0..200) t.updateSelection(10.0 + i * 0.1, 10.0 + i * 0.2, minStep = 2.0)
        t.updateSelection(50.0, 50.0); t.updateSelection(10.0, 50.0)
        val n = (s.selectionDraft as SelectionDraft.Lasso).points.size
        assertTrue("$n points", n < 40)
        t.endSelection()
        assertNotNull(doc.selection)
    }

    // ------------------------------------------------------------------ pixel commands

    @Test
    fun copyPasteCutAndLayerVia() {
        newDoc()
        c.selectTool(Tool.RectMarquee)
        t.beginSelection(0.0, 0.0); t.updateSelection(20.0, 20.0); t.endSelection()
        c.dispatch(CommandId.Copy)
        c.dispatch(CommandId.Paste)
        assertEquals(2, doc.layers.size)
        val pasted = active
        assertEquals(Pixels.opaque(0xFFFFFF), c.rasters.mips(pasted.rasterId)!!.base.pixel(5, 5))
        assertEquals(0, Pixels.alpha(c.rasters.mips(pasted.rasterId)!!.base.pixel(30, 30)))
        c.selectLayer(doc.layers[0].id)
        c.dispatch(CommandId.LayerViaCut)
        assertEquals(3, doc.layers.size)
        assertEquals(0, Pixels.alpha(c.rasters.mips((doc.layers[0] as RasterLayer).rasterId)!!.base.pixel(5, 5)))
        c.undo()
        assertEquals(2, doc.layers.size)
        assertEquals(255, Pixels.alpha(c.rasters.mips((doc.layers[0] as RasterLayer).rasterId)!!.base.pixel(5, 5)))
        c.dispatch(CommandId.LayerViaCopy)
        assertEquals(3, doc.layers.size)
    }

    @Test
    fun fillAndClearUseColoursAndSelection() {
        newDoc()
        t.setColor(ColorSlot.Foreground, 0x00FF00)
        c.dispatch(CommandId.Fill)
        assertTrue(s.dialog is EditorDialog.Fill)
        t.fill(FillContent.Foreground)
        assertNull(s.dialog)
        assertEquals(Pixels.opaque(0x00FF00), render()[0])
        c.selectTool(Tool.RectMarquee)
        t.beginSelection(0.0, 0.0); t.updateSelection(10.0, 10.0); t.endSelection()
        c.dispatch(CommandId.Clear)
        assertEquals(0, Pixels.alpha(render()[5 * 80 + 5]))
        assertEquals(Pixels.opaque(0x00FF00), render()[20 * 80 + 20])
        c.dispatch(CommandId.SwapColors)
        assertEquals(0xFFFFFF, s.colors.foreground)
        c.dispatch(CommandId.ResetColors)
        assertEquals(ColorPair(), s.colors)
    }

    @Test
    fun mergeDownAndFlattenMatchCompositeAndUndo() {
        newDoc()
        c.placeImage("A", photo(40, 30, 3), src(40, 30))
        c.setOpacity(active.id, 0.5f)
        val before = render()
        val layers = doc.layers.size
        c.dispatch(CommandId.MergeDown)
        assertEquals(layers - 1, doc.layers.size)
        assertArrayEquals(before, render())
        c.undo()
        assertEquals(layers, doc.layers.size)
        c.dispatch(CommandId.NewLayer)
        c.dispatch(CommandId.Flatten)
        assertTrue(s.dialog is EditorDialog.ConfirmFlatten)
        t.flatten()
        assertEquals(1, doc.layers.size)
        assertArrayEquals(before, render())
    }

    @Test
    fun eyedropperSamplesComposite() {
        newDoc()
        t.setColor(ColorSlot.Foreground, 0x123456)
        c.dispatch(CommandId.Fill); t.fill(FillContent.Foreground)
        t.setColor(ColorSlot.Foreground, 0)
        assertTrue(t.sampleColor(10.0, 10.0))
        assertEquals(0x123456, s.colors.foreground)
        assertTrue(t.sampleColor(10.0, 10.0, ColorSlot.Background))
        assertEquals(0x123456, s.colors.background)
        assertFalse(t.sampleColor(-5.0, 10.0))
    }

    // ------------------------------------------------------------------ crop, canvas, transform

    @Test
    fun cropToolCommitsAndViewportFollows() {
        newDoc(80, 60)
        c.selectTool(Tool.Crop)
        assertNotNull(s.crop)
        t.setCropAspect(CropBox.Aspect.Square)
        assertEquals(60.0, s.crop!!.rect.width, 1e-9)
        assertTrue(t.cropDragBegin(70.0, 60.0, 4.0)) // bottom-right handle
        t.cropDragUpdate(50.0, 40.0)
        t.cropDragEnd()
        t.commitCrop()
        assertEquals(40, doc.width); assertEquals(40, doc.height)
        assertEquals(40, s.viewport!!.docWidth)
        c.undo()
        assertEquals(80, doc.width)
        assertEquals(80, s.viewport!!.docWidth)
        c.selectTool(Tool.Move)
        assertNull(s.crop)
    }

    @Test
    fun cropCommandUsesSelectionBounds() {
        newDoc(80, 60)
        c.selectTool(Tool.RectMarquee)
        t.beginSelection(10.0, 5.0); t.updateSelection(30.0, 45.0); t.endSelection()
        c.dispatch(CommandId.Crop)
        assertEquals(20, doc.width); assertEquals(40, doc.height)
        assertNull(doc.selection)
    }

    @Test
    fun canvasRotateFlipAndSizeDialogs() {
        newDoc(80, 60)
        c.dispatch(CommandId.RotateCanvasCw)
        assertEquals(60, doc.width); assertEquals(80, doc.height)
        assertEquals(60, s.viewport!!.docWidth)
        c.dispatch(CommandId.FlipCanvasHorizontal)
        assertEquals("Flip Canvas Horizontal", s.history!!.undoLabel)
        c.dispatch(CommandId.ImageSize)
        assertEquals(EditorDialog.ImageSize(60, 80), s.dialog)
        assertTrue(t.imageSize(30, 40))
        assertEquals(30, doc.width)
        c.dispatch(CommandId.CanvasSize)
        assertTrue(t.canvasSize(50, 50, 0.5, 0.5))
        assertEquals(50, doc.height)
        assertFalse(t.imageSize(10, 40))
    }

    @Test
    fun transformHandlesScaleAndRotateWithSnap() {
        newDoc(80, 60)
        c.placeImage("A", photo(40, 20, 4), src(40, 20))
        c.selectTool(Tool.Transform)
        val before = s.history!!.entries.size
        // Bottom-right corner of the 40×20 layer centred at (40, 30) is (60, 40).
        assertEquals(app.phopsd.engine.geometry.TransformHandles.Handle.BottomRight, t.transformBegin(60.0, 40.0, 3.0, 10.0))
        t.transformUpdate(80.0, 50.0)
        t.transformEnd()
        assertEquals(before + 1, s.history!!.entries.size)
        assertEquals(1.5, active.transform.scaleX, 1e-9)
        assertEquals(1.5, active.transform.scaleY, 1e-9)
        // Rotation zone just outside the (new) top-left corner, dragged ~ +88°: snaps to 90°.
        val tl = app.phopsd.engine.geometry.TransformHandles.handlePoint(active.transform, 40, 20, -1, -1)
        val cx = active.transform.centerX; val cy = active.transform.centerY
        assertEquals(app.phopsd.engine.geometry.TransformHandles.Handle.Rotate, t.transformBegin(tl.x - 4, tl.y - 4, 3.0, 10.0))
        val a0 = Math.atan2(tl.y - 4 - cy, tl.x - 4 - cx)
        val a1 = a0 + Math.toRadians(88.0)
        t.transformUpdate(cx + 30 * Math.cos(a1), cy + 30 * Math.sin(a1))
        t.transformEnd()
        assertEquals(90.0, active.transform.rotationDegrees, 1e-9)
        c.undo(); c.undo()
        assertEquals(1.0, active.transform.scaleX, 1e-9)
        assertEquals(0.0, active.transform.rotationDegrees, 1e-9)
    }

    // ------------------------------------------------------------------ memory

    @Test
    fun historyIsTrimmedToThePixelBudget() {
        newDoc(600, 600, BackgroundContents.Transparent)
        brush()
        t.setBrushSettings(hard.copy(size = 40.0))
        // Each stroke touches its own 256 KiB tile (plus reduced levels); the current state
        // alone needs ~2.75 MiB, the full history much more.
        c.historyBudgetBytes = 4L shl 20
        for (i in 0 until 6) assertTrue(strokeLine(20.0 + (i % 2) * 300, 20.0 + (i / 2) * 260.0, 30.0 + (i % 2) * 300, 20.0 + (i / 2) * 260.0))
        assertTrue(c.historyPixelBytes() <= c.historyBudgetBytes)
        assertTrue(s.history!!.entries.size < 7)
        assertTrue(s.notice!!.text.contains("released"))
        // The current state is intact.
        assertEquals(255, Pixels.alpha(render()[20 * 600 + 25]))
    }

    @Test
    fun brushKeysAdjustSettings() {
        newDoc()
        brush()
        t.scaleBrushSize(2.0)
        assertEquals(20.0, s.brush.size, 1e-9)
        t.nudgeHardness(-0.25)
        assertEquals(0.75, s.brush.hardness, 1e-9)
        t.setOpacityPercent(30)
        assertEquals(0.3, s.brush.opacity, 1e-9)
        c.selectTool(Tool.Eraser)
        t.scaleBrushSize(0.5)
        assertEquals(20.0, s.brush.size, 1e-9)
        assertEquals(BrushSettings().size / 2, s.eraser.size, 1e-9)
    }
}
