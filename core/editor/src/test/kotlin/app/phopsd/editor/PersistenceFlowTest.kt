package app.phopsd.editor

import app.phopsd.engine.io.ProjectReader
import app.phopsd.engine.io.ProjectWriter
import app.phopsd.engine.io.SaveInfo
import app.phopsd.engine.model.NewDocumentSpec
import app.phopsd.engine.model.RasterLayer
import app.phopsd.engine.raster.MipChain
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PersistenceFlowTest {
    private val c = EditorController(clock = { 5_000L })
    private val doc get() = c.state.value.document!!

    /** Runs [block] while collecting the effects it emits. */
    private fun capture(block: () -> Unit): List<EditorEffect> = runBlocking {
        val out = ArrayList<EditorEffect>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { c.effects.collect { out.add(it) } }
        block()
        repeat(3) { yield() }
        job.cancel()
        out
    }

    private fun newDoc() = assertTrue(c.createDocument(NewDocumentSpec("Mountain Horizon", 400, 300)))

    @Test
    fun saveWithoutProjectAsksForLocationThenMarksClean() {
        newDoc()
        c.dispatch(CommandId.NewLayer)
        assertTrue(c.state.value.dirty)
        val fx = capture { c.dispatch(CommandId.Save) }
        assertEquals(listOf<EditorEffect>(EditorEffect.PickSaveLocation("Mountain Horizon.phopsd")), fx)

        val snap = c.snapshotForSave()!!
        c.markSaving()
        assertFalse(c.availability(CommandId.Save).isEnabled)
        val link = ProjectLink("content://x/1", "Mountain Horizon.phopsd")
        c.markSaved(link, snap.document, 9_000L)
        val s = c.state.value
        assertFalse(s.dirty)
        assertEquals(link, s.project)
        assertEquals(SaveState.Saved(9_000L), s.saveState)
        assertFalse("nothing to save", c.availability(CommandId.Save).isEnabled)

        // Next Save goes straight to the known location.
        c.dispatch(CommandId.NewLayer)
        assertEquals(listOf<EditorEffect>(EditorEffect.SaveProject(link)), capture { c.dispatch(CommandId.Save) })
    }

    @Test
    fun editsDuringSaveKeepDocumentDirty() {
        newDoc()
        c.dispatch(CommandId.NewLayer)
        val snap = c.snapshotForSave()!!
        c.markSaving()
        c.dispatch(CommandId.NewLayer) // user keeps working while the save runs
        c.markSaved(ProjectLink("u", "a.phopsd"), snap.document, 1L)
        assertTrue(c.state.value.dirty)
        c.dispatch(CommandId.Undo)
        assertFalse("undoing back to the saved state is clean", c.state.value.dirty)
    }

    @Test
    fun selectingLayersDoesNotDirty() {
        newDoc()
        c.dispatch(CommandId.NewLayer)
        c.markSaved(ProjectLink("u", "a.phopsd"), c.snapshotForSave()!!.document, 1L)
        c.selectLayer(doc.layers[0].id)
        assertFalse(c.state.value.dirty)
    }

    @Test
    fun saveSnapshotSurvivesRasterRelease() {
        newDoc()
        val img = MipChain.build(app.phopsd.engine.raster.TiledRaster.solid(10, 10, 0xFF102030.toInt()))
        c.placeImage("photo", img, app.phopsd.engine.model.SourceInfo("p.jpg", null, 10, 10, null, false))
        val snap = c.snapshotForSave()!!
        // Undo the place and branch history: the placed raster is released from the store…
        c.dispatch(CommandId.Undo)
        c.dispatch(CommandId.NewLayer)
        val placed = snap.document.layers.last() as RasterLayer
        assertNull(c.rasters.mips(placed.rasterId))
        // …but the snapshot still has it, so the in-flight save completes correctly.
        val bytes = ByteArrayOutputStream().also { ProjectWriter.write(snap.document, snap.rasters, it, SaveInfo("t", 1L)) }.toByteArray()
        assertEquals(snap.document, ProjectReader.read(ByteArrayInputStream(bytes)).document)
    }

    @Test
    fun loadProjectOpensCleanAndRecoveryOpensDirty() {
        newDoc()
        c.dispatch(CommandId.NewLayer)
        val snap = c.snapshotForSave()!!
        val bytes = ByteArrayOutputStream().also { ProjectWriter.write(snap.document, snap.rasters, it, SaveInfo("t", 1L)) }.toByteArray()
        val loaded = ProjectReader.read(ByteArrayInputStream(bytes))
        val mips = loaded.rasters.mapValues { MipChain.build(it.value) }

        val fresh = EditorController()
        val link = ProjectLink("content://p", "Mountain Horizon.phopsd")
        fresh.loadProject(loaded.document, mips, link)
        assertEquals(Screen.Editor, fresh.state.value.screen)
        assertFalse(fresh.state.value.dirty)
        assertEquals(link, fresh.state.value.project)
        assertEquals(snap.document, fresh.state.value.document)

        val recovered = EditorController()
        recovered.loadProject(loaded.document, mips, link, recovered = true)
        assertTrue(recovered.state.value.dirty)
        assertEquals(NoticeKind.Warning, recovered.state.value.notice!!.kind)
    }

    @Test
    fun saveThenCloseClosesOnlyAfterSuccess() {
        newDoc()
        c.dispatch(CommandId.NewLayer)
        c.dispatch(CommandId.CloseDocument)
        assertTrue(c.state.value.dialog is EditorDialog.ConfirmDiscard)
        c.saveThen(PendingAction.Close)
        assertEquals(PendingAction.Close, c.state.value.afterSave)
        c.markSaveFailed("disk full")
        assertEquals(Screen.Editor, c.state.value.screen)
        assertNull(c.state.value.afterSave)

        c.saveThen(PendingAction.Close)
        c.markSaved(ProjectLink("u", "a.phopsd"), c.snapshotForSave()!!.document, 2L)
        assertEquals(Screen.Home, c.state.value.screen)
    }

    @Test
    fun openProjectAsksBeforeDiscarding() {
        newDoc()
        assertEquals(listOf<EditorEffect>(EditorEffect.PickProjectToOpen), capture { c.dispatch(CommandId.OpenProject) })
        c.dispatch(CommandId.NewLayer)
        assertTrue(capture { c.dispatch(CommandId.OpenProject) }.isEmpty())
        val d = c.state.value.dialog as EditorDialog.ConfirmDiscard
        assertEquals(PendingAction.OpenProject, d.action)
        assertEquals(listOf<EditorEffect>(EditorEffect.PickProjectToOpen), capture { c.confirmDiscard(d.action) })
        // Still dirty: opening a recent project asks first, then opens that exact URI.
        assertTrue(capture { c.requestOrConfirm(PendingAction.OpenRecent("content://r")) }.isEmpty())
        val d2 = c.state.value.dialog as EditorDialog.ConfirmDiscard
        assertEquals(listOf<EditorEffect>(EditorEffect.OpenProjectUri("content://r")), capture { c.confirmDiscard(d2.action) })
    }

    @Test
    fun exportDialogFlow() {
        newDoc()
        c.dispatch(CommandId.Export)
        val dlg = c.state.value.dialog as EditorDialog.Export
        assertEquals(ExportFormat.Png, dlg.settings.format)
        val settings = ExportSettings(ExportFormat.Jpeg, quality = 80, scale = 0.5)
        val fx = capture { c.confirmExport(settings) }
        assertEquals(listOf<EditorEffect>(EditorEffect.PickExportLocation("Mountain Horizon.jpg", settings)), fx)
        assertNull(c.state.value.dialog)
        assertEquals(settings, c.state.value.lastExport)
    }

    @Test
    fun fileNamesAreSanitized() {
        newDoc()
        c.renameDocument("a/b:c*?")
        assertEquals(listOf<EditorEffect>(EditorEffect.PickSaveLocation("a_b_c__.phopsd")), capture { c.dispatch(CommandId.SaveAs) })
    }

}
