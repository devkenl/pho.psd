package app.phopsd.engine

import app.phopsd.engine.edit.DocumentEdits
import app.phopsd.engine.edit.EditResult
import app.phopsd.engine.edit.History
import app.phopsd.engine.geometry.LayerTransform
import app.phopsd.engine.model.BackgroundContents
import app.phopsd.engine.model.BlendMode
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.DocumentFactory
import app.phopsd.engine.model.NewDocumentSpec
import app.phopsd.engine.model.RasterLayer
import app.phopsd.engine.model.RasterStore
import app.phopsd.engine.model.SourceInfo
import app.phopsd.engine.raster.MipChain
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.raster.TiledRaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditingTest {
    private val store = RasterStore()

    private fun applied(r: EditResult): Document {
        assertTrue("expected Applied but was $r", r is EditResult.Applied)
        return (r as EditResult.Applied).document
    }

    private fun rejected(r: EditResult): String {
        assertTrue("expected Rejected but was $r", r is EditResult.Rejected)
        return (r as EditResult.Rejected).reason
    }

    private fun newDoc(): Document =
        DocumentFactory.create(NewDocumentSpec("Untitled", 800, 600, background = BackgroundContents.White), store)

    @Test
    fun newDocumentHasBackgroundLayerWithoutPixelMemory() {
        val d = newDoc()
        assertEquals(1, d.layers.size)
        assertEquals("Background", d.layers[0].name)
        assertEquals(d.layers[0].id, d.activeLayerId)
        assertEquals(0L, store.allocatedBytes)
        val r = d.layers[0] as RasterLayer
        assertEquals(Pixels.opaque(0xFFFFFF), store.mips(r.rasterId)!!.base.pixel(799, 599))
    }

    @Test
    fun specValidation() {
        assertNotNull(NewDocumentSpec("x", 0, 10).validate())
        assertNotNull(NewDocumentSpec("x", 40_000, 10).validate())
        assertNotNull(NewDocumentSpec(" ", 10, 10).validate())
        assertNotNull(NewDocumentSpec("x", 20_000, 20_000).validate())
        assertEquals(null, NewDocumentSpec("x", 6000, 4000).validate())
    }

    @Test
    fun addLayerGoesAboveActiveAndBecomesActive() {
        var d = newDoc()
        val l1 = DocumentFactory.emptyLayer(d, store, "Layer 1")
        d = applied(DocumentEdits.addLayer(d, l1))
        val l2 = DocumentFactory.emptyLayer(d, store, "Layer 2")
        d = applied(DocumentEdits.setActive(d, d.layers[0].id))
        d = applied(DocumentEdits.addLayer(d, l2))
        assertEquals(listOf("Background", "Layer 2", "Layer 1"), d.layers.map { it.name })
        assertEquals(l2.id, d.activeLayerId)
    }

    @Test
    fun placedLargeImageIsFitNonDestructively() {
        val d = newDoc()
        val big = MipChain.build(TiledRaster.solid(4000, 1500, Pixels.opaque(0x123456)))
        val src = SourceInfo("big.jpg", "image/jpeg", 4000, 1500, "sRGB", false)
        val layer = DocumentFactory.placedLayer(d, big, src, store, "big")
        assertEquals(4000, layer.width)
        assertEquals(0.2, layer.transform.scaleX, 1e-9)
        assertEquals(400.0, layer.transform.centerX, 1e-9)
        val small = MipChain.build(TiledRaster.solid(100, 100, Pixels.opaque(0)))
        assertEquals(1.0, DocumentFactory.placedLayer(d, small, src, store, "s").transform.scaleX, 0.0)
    }

    @Test
    fun deleteRules() {
        var d = newDoc()
        assertTrue(rejected(DocumentEdits.deleteLayer(d, d.layers[0].id)).contains("at least one"))
        d = applied(DocumentEdits.addLayer(d, DocumentFactory.emptyLayer(d, store, "Layer 1")))
        val top = d.layers[1].id
        d = applied(DocumentEdits.setLocked(d, top, true))
        assertTrue(rejected(DocumentEdits.deleteLayer(d, top)).contains("locked"))
        d = applied(DocumentEdits.setLocked(d, top, false))
        d = applied(DocumentEdits.deleteLayer(d, top))
        assertEquals(1, d.layers.size)
        assertEquals(d.layers[0].id, d.activeLayerId)
    }

    @Test
    fun duplicateSharesRasterAndGetsUniqueName() {
        var d = newDoc()
        val bg = d.layers[0] as RasterLayer
        d = applied(DocumentEdits.duplicateLayer(d, bg.id))
        d = applied(DocumentEdits.duplicateLayer(d, bg.id))
        assertEquals(listOf("Background", "Background copy 2", "Background copy"), d.layers.map { it.name })
        assertEquals(bg.rasterId, (d.layers[1] as RasterLayer).rasterId)
        assertNotEquals(bg.id, d.layers[1].id)
    }

    @Test
    fun propertyEdits() {
        var d = newDoc()
        val id = d.layers[0].id
        d = applied(DocumentEdits.renameLayer(d, id, "  Sky  "))
        assertEquals("Sky", d.layers[0].name)
        rejected(DocumentEdits.renameLayer(d, id, "   "))
        d = applied(DocumentEdits.setOpacity(d, id, 0.5f))
        d = applied(DocumentEdits.setOpacity(d, id, 1.7f))
        assertEquals(1f, d.layers[0].opacity)
        d = applied(DocumentEdits.setOpacity(d, id, 0.25f))
        assertEquals(0.25f, d.layers[0].opacity)
        d = applied(DocumentEdits.setBlendMode(d, id, BlendMode.Screen))
        assertEquals(BlendMode.Screen, d.layers[0].blendMode)
        d = applied(DocumentEdits.setVisible(d, id, false))
        assertFalse(d.layers[0].visible)
        rejected(DocumentEdits.setVisible(d, id, false)) // no-op is not an undo step
    }

    @Test
    fun lockedLayerCannotBeTransformed() {
        var d = newDoc()
        val id = d.layers[0].id
        d = applied(DocumentEdits.setLocked(d, id, true))
        rejected(DocumentEdits.setTransform(d, id, LayerTransform(1.0, 1.0)))
    }

    @Test
    fun reorder() {
        var d = newDoc()
        d = applied(DocumentEdits.addLayer(d, DocumentFactory.emptyLayer(d, store, "A")))
        d = applied(DocumentEdits.addLayer(d, DocumentFactory.emptyLayer(d, store, "B")))
        val a = d.layers[1].id
        d = applied(DocumentEdits.moveUp(d, a))
        assertEquals(listOf("Background", "B", "A"), d.layers.map { it.name })
        rejected(DocumentEdits.moveUp(d, a))
        d = applied(DocumentEdits.moveLayer(d, a, 0))
        assertEquals(listOf("A", "Background", "B"), d.layers.map { it.name })
        rejected(DocumentEdits.moveDown(d, a))
    }

    @Test
    fun historyUndoRedoAndBranching() {
        val d0 = newDoc()
        val id = d0.layers[0].id
        var h = History.start(d0)
        assertFalse(h.canUndo)
        val d1 = applied(DocumentEdits.renameLayer(d0, id, "One"))
        h = h.push("Rename", d1)
        val d2 = applied(DocumentEdits.renameLayer(d1, id, "Two"))
        h = h.push("Rename", d2)
        assertEquals("Two", h.current.layers[0].name)
        h = h.undo()
        assertEquals("One", h.current.layers[0].name)
        assertTrue(h.canRedo)
        h = h.redo()
        assertEquals("Two", h.current.layers[0].name)
        h = h.undo().undo()
        assertEquals("Background", h.current.layers[0].name)
        // New edit discards redo branch.
        h = h.push("Rename", applied(DocumentEdits.renameLayer(h.current, id, "Three")))
        assertFalse(h.canRedo)
        assertEquals(2, h.entries.size)
    }

    @Test
    fun historyCoalescesSliderDrags() {
        val d0 = newDoc()
        val id = d0.layers[0].id
        var h = History.start(d0)
        for (o in listOf(0.9f, 0.7f, 0.5f)) {
            h = h.push("Opacity", applied(DocumentEdits.setOpacity(h.current, id, o)), coalesce = "opacity:${id.value}")
        }
        assertEquals(2, h.entries.size)
        assertEquals(0.5f, h.current.layers[0].opacity)
        h = h.sealed()
        h = h.push("Opacity", applied(DocumentEdits.setOpacity(h.current, id, 0.2f)), coalesce = "opacity:${id.value}")
        assertEquals(3, h.entries.size)
        h = h.undo()
        assertEquals(0.5f, h.current.layers[0].opacity)
    }

    @Test
    fun historyRespectsLimitAndTracksRasters() {
        val d0 = newDoc()
        val id = d0.layers[0].id
        var h = History.start(d0, limit = 3)
        for (i in 1..5) h = h.push("R$i", applied(DocumentEdits.renameLayer(h.current, id, "N$i")))
        assertEquals(3, h.entries.size)
        assertEquals("N5", h.current.layers[0].name)
        assertEquals(setOf((d0.layers[0] as RasterLayer).rasterId), h.referencedRasters())
    }

    @Test
    fun replaceCurrentDoesNotCreateUndoStep() {
        var d = newDoc()
        d = applied(DocumentEdits.addLayer(d, DocumentFactory.emptyLayer(d, store, "A")))
        var h = History.start(d)
        h = h.replaceCurrent(applied(DocumentEdits.setActive(h.current, h.current.layers[0].id)))
        assertFalse(h.canUndo)
        assertEquals(h.current.layers[0].id, h.current.activeLayerId)
    }

    @Test
    fun rasterStoreReleasesUnreferencedRasters() {
        val d = newDoc()
        val extra = DocumentFactory.emptyLayer(d, store, "tmp")
        assertEquals(2, store.size)
        val removed = store.retainOnly(d.rasterIds())
        assertEquals(1, removed)
        assertFalse(extra.rasterId in store)
    }
}
