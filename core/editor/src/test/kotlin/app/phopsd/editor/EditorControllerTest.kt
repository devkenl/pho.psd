package app.phopsd.editor

import app.phopsd.engine.model.BackgroundContents
import app.phopsd.engine.model.BlendMode
import app.phopsd.engine.model.NewDocumentSpec
import app.phopsd.engine.model.RasterLayer
import app.phopsd.engine.model.SourceInfo
import app.phopsd.engine.raster.MipChain
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.raster.TiledRaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorControllerTest {
    private val c = EditorController(clock = { 1_000L })

    private fun newDoc(w: Int = 1000, h: Int = 800) {
        assertTrue(c.createDocument(NewDocumentSpec("Test", w, h, background = BackgroundContents.White)))
    }

    private fun image(w: Int, h: Int, rgb: Int = 0xFF0000) =
        MipChain.build(TiledRaster.fromPixels(w, h, IntArray(w * h) { Pixels.opaque(rgb) }, straightAlpha = true))

    private fun src(w: Int, h: Int, downsampled: Boolean = false) =
        SourceInfo("photo.jpg", "image/jpeg", w, h, "sRGB IEC61966-2.1", downsampled)

    private val doc get() = c.state.value.document!!

    @Test
    fun plannedCommandsAreNeverEnabled() {
        newDoc()
        for (id in CommandId.entries.filter { it.planned != null }) {
            assertFalse("$id must stay disabled", c.availability(id).isEnabled)
        }
        // Dispatching a planned command explains instead of acting.
        c.dispatch(CommandId.NewGroup)
        assertTrue(c.state.value.notice!!.text.contains("M6"))
    }

    @Test
    fun everyImplementedCommandIsHandled() {
        // Each non-planned command must be reachable without hitting the defensive fallback.
        for (id in CommandId.entries.filter { it.planned == null }) {
            val fresh = EditorController(clock = { 0L })
            fresh.createDocument(NewDocumentSpec("T", 100, 100))
            fresh.dispatch(CommandId.NewLayer) // gives undo/reorder commands something to do
            fresh.setViewSize(800.0, 600.0)
            fresh.dispatch(id)
            val n = fresh.state.value.notice?.text ?: ""
            assertFalse("$id hit fallback: $n", n.contains("not wired"))
        }
    }

    @Test
    fun homeStateBlocksDocumentCommands() {
        assertEquals(Screen.Home, c.state.value.screen)
        assertFalse(c.availability(CommandId.NewLayer).isEnabled)
        assertTrue(c.availability(CommandId.OpenImage).isEnabled)
    }

    @Test
    fun newLayerUndoRedo() {
        newDoc()
        c.dispatch(CommandId.NewLayer)
        assertEquals(listOf("Background", "Layer 2"), doc.layers.map { it.name })
        assertTrue(c.state.value.dirty)
        c.dispatch(CommandId.Undo)
        assertEquals(1, doc.layers.size)
        c.dispatch(CommandId.Redo)
        assertEquals(2, doc.layers.size)
        assertEquals(doc.layers[1].id, doc.activeLayerId)
    }

    @Test
    fun openImageCreatesDocumentAtNativeSizeAndFitsView() {
        c.setViewSize(1200.0, 800.0)
        c.openImage("photo", image(3000, 2000), src(3000, 2000))
        val s = c.state.value
        assertEquals(Screen.Editor, s.screen)
        assertEquals(3000, doc.width)
        assertEquals(2000, doc.height)
        assertNotNull(s.viewport)
        assertTrue(s.viewport!!.zoom < 0.4)
        assertFalse(s.history!!.canUndo)
        assertFalse(s.dirty)
    }

    @Test
    fun downsampledImportWarns() {
        c.openImage("photo", image(100, 50), src(4000, 2000, downsampled = true))
        assertEquals(NoticeKind.Warning, c.state.value.notice!!.kind)
    }

    @Test
    fun placeImageAddsFittedLayerAboveActive() {
        newDoc(1000, 800)
        c.placeImage("photo", image(4000, 1000), src(4000, 1000))
        val placed = doc.layers.last() as RasterLayer
        assertEquals("photo", placed.name)
        assertEquals(0.25, placed.transform.scaleX, 1e-9)
        assertEquals(placed.id, doc.activeLayerId)
        c.placeImage("photo", image(10, 10), src(10, 10))
        assertEquals("photo 2", doc.layers.last().name)
        c.dispatch(CommandId.Undo)
        c.dispatch(CommandId.Undo)
        assertEquals(1, doc.layers.size)
    }

    @Test
    fun moveDragIsOneUndoStepAndUsesViewportMapping() {
        newDoc(1000, 800)
        c.setViewSize(500.0, 400.0) // fit zoom: (500-48)/1000 = 0.452
        c.dispatch(CommandId.NewLayer)
        val id = doc.activeLayerId!!
        val zoom = c.state.value.viewport!!.zoom
        val start = (doc.layer(id) as RasterLayer).transform
        assertTrue(c.beginMove(100.0, 100.0))
        c.updateMove(110.0, 100.0)
        c.updateMove(150.0, 120.0)
        // Live preview moves the layer before commit…
        val live = (doc.layer(id) as RasterLayer).transform
        assertEquals(start.centerX + 50.0 / zoom, live.centerX, 1e-6)
        assertEquals(start.centerY + 20.0 / zoom, live.centerY, 1e-6)
        val stepsBefore = c.state.value.history!!.entries.size
        c.endMove()
        assertEquals(stepsBefore + 1, c.state.value.history!!.entries.size)
        assertNull(c.state.value.live)
        c.dispatch(CommandId.Undo)
        assertEquals(start, (doc.layer(id) as RasterLayer).transform)
    }

    @Test
    fun lockedLayerRefusesMoveAndDelete() {
        newDoc()
        c.dispatch(CommandId.NewLayer)
        c.setViewSize(800.0, 600.0)
        c.dispatch(CommandId.ToggleLayerLock)
        assertTrue(doc.activeLayer!!.locked)
        assertFalse(c.beginMove(10.0, 10.0))
        assertFalse(c.availability(CommandId.DeleteLayer).isEnabled)
    }

    @Test
    fun autoSelectPicksLayerUnderPointer() {
        newDoc(100, 100)
        c.setViewSize(100.0 + 48, 100.0 + 48) // zoom 1, doc at (24,24)
        val bgId = doc.layers[0].id
        c.placeImage("dot", image(10, 10), src(10, 10)) // centred at 50,50
        c.selectLayer(bgId)
        c.setAutoSelectLayer(true)
        assertTrue(c.beginMove(24.0 + 50.0, 24.0 + 50.0))
        assertEquals(doc.layers[1].id, doc.activeLayerId)
        c.cancelMove()
    }

    @Test
    fun opacitySliderCoalesces() {
        newDoc()
        val id = doc.activeLayerId!!
        val before = c.state.value.history!!.entries.size
        c.setOpacity(id, 0.8f)
        c.setOpacity(id, 0.6f)
        c.setOpacity(id, 0.4f)
        c.endContinuousEdit()
        assertEquals(before + 1, c.state.value.history!!.entries.size)
        assertEquals(0.4f, doc.layers[0].opacity)
        c.setBlendMode(id, BlendMode.Multiply)
        assertEquals(before + 2, c.state.value.history!!.entries.size)
    }

    @Test
    fun numericEntryIsClampedAndApplied() {
        newDoc()
        val id = doc.activeLayerId!!
        c.applyNumeric(NumericTarget.LayerOpacity(id), 150.0)
        assertEquals(1f, doc.layers[0].opacity)
        c.applyNumeric(NumericTarget.LayerScale(id), 50.0)
        assertEquals(0.5, (doc.layers[0] as RasterLayer).transform.scaleX, 1e-9)
        c.applyNumeric(NumericTarget.LayerRotation(id), 30.0)
        assertTrue(c.availability(CommandId.ResetLayerTransform).isEnabled)
        c.dispatch(CommandId.ResetLayerTransform)
        assertTrue((doc.layers[0] as RasterLayer).transform.isIdentityScaleAndRotation)
    }

    @Test
    fun unavailableToolExplainsItself() {
        newDoc()
        c.selectTool(Tool.Healing)
        assertEquals(Tool.Move, c.state.value.tool)
        assertTrue(c.state.value.notice!!.text.contains("M6"))
        c.selectTool(Tool.Hand)
        assertEquals(Tool.Hand, c.state.value.tool)
        c.setSpacePanning(true)
        assertEquals(Tool.Hand, c.state.value.effectiveTool)
    }

    @Test
    fun viewCommands() {
        newDoc(2000, 1000)
        c.setViewSize(1000.0, 600.0)
        c.dispatch(CommandId.ActualPixels)
        assertEquals(1.0, c.state.value.viewport!!.zoom, 1e-9)
        c.dispatch(CommandId.ZoomIn)
        assertEquals(1.5, c.state.value.viewport!!.zoom, 1e-9)
        assertFalse(c.availability(CommandId.ResetViewRotation).isEnabled)
        c.dispatch(CommandId.RotateViewRight)
        assertEquals(15.0, c.state.value.viewport!!.rotationDegrees, 1e-9)
        c.dispatch(CommandId.ResetViewRotation)
        assertEquals(0.0, c.state.value.viewport!!.rotationDegrees, 1e-9)
        c.dispatch(CommandId.FitOnScreen)
        assertEquals((1000.0 - 48) / 2000, c.state.value.viewport!!.zoom, 1e-9)
    }

    @Test
    fun closeWithUnsavedChangesAsksFirst() {
        newDoc()
        c.dispatch(CommandId.NewLayer)
        c.dispatch(CommandId.CloseDocument)
        val dlg = c.state.value.dialog
        assertTrue(dlg is EditorDialog.ConfirmDiscard)
        assertEquals(Screen.Editor, c.state.value.screen)
        c.confirmDiscard((dlg as EditorDialog.ConfirmDiscard).action)
        assertEquals(Screen.Home, c.state.value.screen)
        assertEquals(0, c.rasters.size)
    }

    @Test
    fun windowTogglesAndResetWorkspace() {
        newDoc()
        c.dispatch(CommandId.WindowLayers)
        assertFalse(c.state.value.panels.dockVisible)
        c.dispatch(CommandId.WindowChannels)
        assertTrue(c.state.value.panels.dockVisible)
        assertEquals(DockTab.Channels, c.state.value.dockTab)
        c.dispatch(CommandId.WindowHistory)
        assertFalse(c.state.value.panels.historyVisible)
        c.dispatch(CommandId.ResetWorkspace)
        assertEquals(Panels(), c.state.value.panels)
    }

    @Test
    fun historyJumpAndRasterRelease() {
        newDoc()
        c.placeImage("a", image(10, 10), src(10, 10))
        c.placeImage("b", image(10, 10), src(10, 10))
        assertEquals(3, c.rasters.size)
        c.jumpToHistory(0)
        assertEquals(1, doc.layers.size)
        // Rasters stay alive while reachable through redo.
        assertEquals(3, c.rasters.size)
        c.dispatch(CommandId.NewLayer) // discards redo branch → placed rasters released
        assertEquals(2, c.rasters.size)
    }

    @Test
    fun renameValidation() {
        newDoc()
        val id = doc.activeLayerId!!
        assertFalse(c.renameLayer(id, "   "))
        assertTrue(c.renameLayer(id, "Sky"))
        assertEquals("Sky", doc.layers[0].name)
        assertTrue(c.renameDocument("Mountain"))
        assertEquals("Mountain", doc.name)
    }
}
