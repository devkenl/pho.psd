package app.phopsd.editor

import app.phopsd.engine.edit.DocumentEdits
import app.phopsd.engine.edit.EditResult
import app.phopsd.engine.edit.History
import app.phopsd.engine.geometry.LayerTransform
import app.phopsd.engine.geometry.Viewport
import app.phopsd.engine.model.BlendMode
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.DocumentFactory
import app.phopsd.engine.model.LayerId
import app.phopsd.engine.model.NewDocumentSpec
import app.phopsd.engine.model.RasterLayer
import app.phopsd.engine.model.RasterStore
import app.phopsd.engine.model.SourceInfo
import app.phopsd.engine.raster.MipChain
import app.phopsd.engine.render.Compositor
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * The editor's state machine. Every UI surface calls into this class; it validates,
 * records undo history and exposes immutable [EditorState]. Platform work (decoding,
 * bitmaps, pickers) stays outside so this class is fully unit-tested on the JVM.
 *
 * Threading: methods may be called from any thread; state updates are atomic via
 * [MutableStateFlow.update]. The [rasters] store is thread-safe.
 */
class EditorController(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val rasters = RasterStore()
    private val compositor = Compositor()
    private val noticeIds = AtomicLong(0)

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<EditorEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<EditorEffect> = _effects.asSharedFlow()

    /** Last known canvas size, so documents opened later can be fitted immediately. */
    private var viewSize: Pair<Double, Double>? = null

    // ---------------------------------------------------------------- documents

    fun createDocument(spec: NewDocumentSpec): Boolean {
        spec.validate()?.let { notice(it, NoticeKind.Error); return false }
        rasters.clear()
        val doc = DocumentFactory.create(spec, rasters)
        startSession(doc, "New Document")
        return true
    }

    /** Opens a decoded image as a new document (replaces the current one). */
    fun openImage(name: String, mips: MipChain, source: SourceInfo) {
        rasters.clear()
        val doc = DocumentFactory.fromImage(name, mips, source, rasters)
        startSession(doc, "Open")
        if (source.downsampled) {
            notice(
                "“${source.displayName}” is ${source.originalWidth}×${source.originalHeight}; it was opened at " +
                    "${mips.width}×${mips.height} to stay within this device's memory budget.",
                NoticeKind.Warning,
            )
        }
    }

    /** Adds a decoded image as a new layer above the active layer. */
    fun placeImage(name: String, mips: MipChain, source: SourceInfo) {
        val doc = state.value.document ?: run { notice("Open or create a document first", NoticeKind.Error); return }
        val layer = DocumentFactory.placedLayer(doc, mips, source, rasters, uniqueName(doc, name))
        commit("Place “${layer.name}”", DocumentEdits.addLayer(doc, layer))
        if (layer.transform.scaleX < 1.0) {
            notice("Placed at ${(layer.transform.scaleX * 100).roundToInt()}% to fit the canvas. Original pixels are kept at full resolution.")
        }
        if (source.downsampled) {
            notice("“${source.displayName}” was decoded at ${mips.width}×${mips.height} (memory budget).", NoticeKind.Warning)
        }
    }

    fun closeDocument() {
        rasters.clear()
        _state.update {
            it.copy(
                screen = Screen.Home, history = null, live = null, viewport = null,
                dialog = null, dirty = false, lastEditAtMillis = null, busy = null,
            )
        }
    }

    private fun startSession(doc: Document, label: String) {
        val vs = viewSize
        val vp = vs?.let { Viewport(it.first, it.second, doc.width, doc.height).fitToView() }
        _state.update {
            it.copy(
                screen = Screen.Editor,
                history = History.start(doc, label),
                live = null,
                viewport = vp,
                dialog = null,
                dirty = false,
                lastEditAtMillis = null,
                busy = null,
                tool = if (it.tool.availability.isEnabled) it.tool else Tool.Move,
            )
        }
    }

    fun setBusy(message: String?) = _state.update { it.copy(busy = message) }

    // ---------------------------------------------------------------- notices & dialogs

    fun notice(text: String, kind: NoticeKind = NoticeKind.Info) {
        val n = Notice(noticeIds.incrementAndGet(), text, kind)
        _state.update { it.copy(notice = n) }
    }

    fun dismissNotice(id: Long) = _state.update { if (it.notice?.id == id) it.copy(notice = null) else it }

    fun showDialog(dialog: EditorDialog) = _state.update { it.copy(dialog = dialog) }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    /** Called when the user confirms a "discard unsaved changes" dialog. */
    fun confirmDiscard(action: PendingAction) {
        dismissDialog()
        when (action) {
            PendingAction.Close -> closeDocument()
            is PendingAction.Pick -> _effects.tryEmit(EditorEffect.PickImage(PickPurpose.OpenAsDocument, action.source))
            PendingAction.NewDocument -> showDialog(EditorDialog.NewDocument)
        }
    }

    // ---------------------------------------------------------------- commands

    fun availability(id: CommandId): Availability {
        id.planned?.let { return it }
        val s = state.value
        val doc = s.document
        val noDoc = Availability.Blocked("Open or create a document first")
        val active = doc?.activeLayer
        fun needLayer(): Availability = when {
            doc == null -> noDoc
            active == null -> Availability.Blocked("Select a layer first")
            else -> Availability.Enabled
        }
        return when (id) {
            CommandId.NewDocument, CommandId.OpenImage, CommandId.OpenFromPhotos,
            CommandId.About, CommandId.Shortcuts, CommandId.FeatureStatus, CommandId.Diagnostics,
            -> Availability.Enabled

            CommandId.Undo -> when {
                doc == null -> noDoc
                s.history?.canUndo == true -> Availability.Enabled
                else -> Availability.Blocked("Nothing to undo")
            }
            CommandId.Redo -> when {
                doc == null -> noDoc
                s.history?.canRedo == true -> Availability.Enabled
                else -> Availability.Blocked("Nothing to redo")
            }
            CommandId.DeleteLayer -> needLayer().orElse {
                when {
                    doc!!.layers.size <= 1 -> Availability.Blocked("A document must keep at least one layer")
                    active!!.locked -> Availability.Blocked("“${active.name}” is locked")
                    else -> Availability.Enabled
                }
            }
            CommandId.BringForward, CommandId.BringToFront -> needLayer().orElse {
                if (doc!!.indexOf(active!!.id) == doc.layers.size - 1) Availability.Blocked("Layer is already at the top") else Availability.Enabled
            }
            CommandId.SendBackward, CommandId.SendToBack -> needLayer().orElse {
                if (doc!!.indexOf(active!!.id) == 0) Availability.Blocked("Layer is already at the bottom") else Availability.Enabled
            }
            CommandId.ResetLayerTransform -> needLayer().orElse {
                val l = active as RasterLayer
                when {
                    l.locked -> Availability.Blocked("“${l.name}” is locked")
                    l.transform.isIdentityScaleAndRotation -> Availability.Blocked("Layer has no scale or rotation to reset")
                    else -> Availability.Enabled
                }
            }
            CommandId.DuplicateLayer, CommandId.RenameLayer, CommandId.ToggleLayerVisibility,
            CommandId.ToggleLayerLock,
            -> needLayer()

            CommandId.ResetViewRotation -> when {
                doc == null -> noDoc
                (s.viewport?.rotationDegrees ?: 0.0) == 0.0 -> Availability.Blocked("View is not rotated")
                else -> Availability.Enabled
            }
            else -> if (doc == null) noDoc else Availability.Enabled
        }
    }

    private inline fun Availability.orElse(next: () -> Availability): Availability =
        if (this.isEnabled) next() else this

    /** Runs a command, or explains why it cannot run. */
    fun dispatch(id: CommandId) {
        val a = availability(id)
        if (!a.isEnabled) {
            notice("${id.label.removeSuffix("…")}: ${a.explanation}", if (a is Availability.Planned) NoticeKind.Info else NoticeKind.Warning)
            return
        }
        val s = state.value
        val doc = s.document
        val activeId = doc?.activeLayerId
        when (id) {
            CommandId.NewDocument ->
                if (s.dirty) showDialog(EditorDialog.ConfirmDiscard(PendingAction.NewDocument)) else showDialog(EditorDialog.NewDocument)
            CommandId.OpenImage -> requestOpen(PickSource.Documents)
            CommandId.OpenFromPhotos -> requestOpen(PickSource.Photos)
            CommandId.PlaceImage -> _effects.tryEmit(EditorEffect.PickImage(PickPurpose.PlaceAsLayer, PickSource.Documents))
            CommandId.DocumentInfo -> showDialog(EditorDialog.DocumentInfo)
            CommandId.CloseDocument ->
                if (s.dirty) showDialog(EditorDialog.ConfirmDiscard(PendingAction.Close)) else closeDocument()

            CommandId.Undo -> undo()
            CommandId.Redo -> redo()

            CommandId.NewLayer -> {
                val d = doc!!
                val layer = DocumentFactory.emptyLayer(d, rasters, DocumentEdits.nextLayerName(d))
                commit("New Layer", DocumentEdits.addLayer(d, layer))
            }
            CommandId.DuplicateLayer -> commit("Duplicate Layer", DocumentEdits.duplicateLayer(doc!!, activeId!!))
            CommandId.DeleteLayer -> commit("Delete Layer", DocumentEdits.deleteLayer(doc!!, activeId!!))
            CommandId.RenameLayer -> showDialog(EditorDialog.RenameLayer(activeId!!, doc!!.activeLayer!!.name))
            CommandId.ToggleLayerVisibility -> toggleVisibility(activeId!!)
            CommandId.ToggleLayerLock -> toggleLock(activeId!!)
            CommandId.BringForward -> commit("Bring Forward", DocumentEdits.moveUp(doc!!, activeId!!))
            CommandId.SendBackward -> commit("Send Backward", DocumentEdits.moveDown(doc!!, activeId!!))
            CommandId.BringToFront -> commit("Bring to Front", DocumentEdits.moveLayer(doc!!, activeId!!, doc.layers.size - 1))
            CommandId.SendToBack -> commit("Send to Back", DocumentEdits.moveLayer(doc!!, activeId!!, 0))
            CommandId.ResetLayerTransform -> commit("Reset Transform", DocumentEdits.resetTransform(doc!!, activeId!!))

            CommandId.ZoomIn -> viewportCentered { vp -> vp.withZoom(Viewport.nextZoomStop(vp.zoom)) }
            CommandId.ZoomOut -> viewportCentered { vp -> vp.withZoom(Viewport.previousZoomStop(vp.zoom)) }
            CommandId.FitOnScreen -> viewportCentered { it.fitToView() }
            CommandId.ActualPixels -> viewportCentered { it.actualPixels() }
            CommandId.RotateViewLeft -> viewportCentered { it.rotateAt(it.viewWidth / 2, it.viewHeight / 2, -15.0) }
            CommandId.RotateViewRight -> viewportCentered { it.rotateAt(it.viewWidth / 2, it.viewHeight / 2, 15.0) }
            CommandId.ResetViewRotation -> viewportCentered { it.resetRotation() }

            CommandId.WindowLayers -> _state.update {
                val show = !(it.panels.dockVisible && it.dockTab == DockTab.Layers)
                it.copy(panels = it.panels.copy(dockVisible = show), dockTab = DockTab.Layers)
            }
            CommandId.WindowChannels -> _state.update { it.copy(panels = it.panels.copy(dockVisible = true), dockTab = DockTab.Channels) }
            CommandId.WindowPaths -> _state.update { it.copy(panels = it.panels.copy(dockVisible = true), dockTab = DockTab.Paths) }
            CommandId.WindowHistory -> _state.update {
                it.copy(panels = it.panels.copy(historyVisible = !it.panels.historyVisible, dockVisible = true))
            }
            CommandId.WindowToolOptions -> _state.update { it.copy(panels = it.panels.copy(toolOptionsVisible = !it.panels.toolOptionsVisible)) }
            CommandId.WindowStatusBar -> _state.update { it.copy(panels = it.panels.copy(statusBarVisible = !it.panels.statusBarVisible)) }
            CommandId.ResetWorkspace -> _state.update { it.copy(panels = Panels(), dockTab = DockTab.Layers, thumbSize = ThumbSize.Medium) }

            CommandId.Shortcuts -> showDialog(EditorDialog.Shortcuts)
            CommandId.FeatureStatus -> showDialog(EditorDialog.FeatureStatus)
            CommandId.About -> showDialog(EditorDialog.About)
            CommandId.Diagnostics -> showDialog(EditorDialog.Diagnostics)

            else -> notice("${id.label} is not wired yet", NoticeKind.Warning) // defensive: planned ids never get here
        }
    }

    /** Whether a toggle-style command is currently "on" (for check marks in menus). */
    fun isChecked(id: CommandId): Boolean? {
        val s = state.value
        return when (id) {
            CommandId.WindowLayers -> s.panels.dockVisible && s.dockTab == DockTab.Layers
            CommandId.WindowChannels -> s.panels.dockVisible && s.dockTab == DockTab.Channels
            CommandId.WindowPaths -> s.panels.dockVisible && s.dockTab == DockTab.Paths
            CommandId.WindowHistory -> s.panels.historyVisible
            CommandId.WindowToolOptions -> s.panels.toolOptionsVisible
            CommandId.WindowStatusBar -> s.panels.statusBarVisible
            else -> null
        }
    }

    private fun requestOpen(source: PickSource) {
        if (state.value.dirty) showDialog(EditorDialog.ConfirmDiscard(PendingAction.Pick(source)))
        else _effects.tryEmit(EditorEffect.PickImage(PickPurpose.OpenAsDocument, source))
    }

    // ---------------------------------------------------------------- history

    fun undo() {
        val h = state.value.history ?: return
        if (!h.canUndo) return
        val label = h.undoLabel
        _state.update { it.copy(history = h.undo(), live = null, dirty = true, lastEditAtMillis = clock()) }
        notice("Undo $label")
    }

    fun redo() {
        val h = state.value.history ?: return
        if (!h.canRedo) return
        val label = h.redoLabel
        _state.update { it.copy(history = h.redo(), live = null, dirty = true, lastEditAtMillis = clock()) }
        notice("Redo $label")
    }

    fun jumpToHistory(index: Int) = _state.update {
        val h = it.history ?: return@update it
        if (index == h.index) it else it.copy(history = h.jumpTo(index), live = null, dirty = true, lastEditAtMillis = clock())
    }

    /** Ends a continuous edit (slider release): the next change starts a new undo step. */
    fun endContinuousEdit() = _state.update { s -> s.history?.let { s.copy(history = it.sealed()) } ?: s }

    /**
     * Applies an edit result. Rejections are explained to the user, except "No change"
     * which is silently ignored so idempotent UI events never create empty undo steps.
     */
    private fun commit(label: String, result: EditResult, coalesce: String? = null): Boolean {
        when (result) {
            is EditResult.Rejected -> {
                if (result.reason != "No change") notice(result.reason, NoticeKind.Warning)
                return false
            }
            is EditResult.Applied -> {
                _state.update { s ->
                    val h = s.history ?: return@update s
                    s.copy(history = h.push(label, result.document, coalesce), dirty = true, lastEditAtMillis = clock())
                }
                state.value.history?.let { rasters.retainOnly(it.referencedRasters()) }
                return true
            }
        }
    }

    /** Applies a change that is not an undo step (layer selection). */
    private fun replaceCurrent(result: EditResult) {
        if (result is EditResult.Applied) _state.update { s ->
            val h = s.history ?: return@update s
            s.copy(history = h.replaceCurrent(result.document))
        }
    }

    // ---------------------------------------------------------------- layers

    fun selectLayer(id: LayerId) {
        val doc = state.value.history?.current ?: return
        if (doc.activeLayerId == id) return
        replaceCurrent(DocumentEdits.setActive(doc, id))
    }

    fun toggleVisibility(id: LayerId) {
        val doc = state.value.history?.current ?: return
        val l = doc.layer(id) ?: return
        commit(if (l.visible) "Hide Layer" else "Show Layer", DocumentEdits.setVisible(doc, id, !l.visible))
    }

    fun toggleLock(id: LayerId) {
        val doc = state.value.history?.current ?: return
        val l = doc.layer(id) ?: return
        commit(if (l.locked) "Unlock Layer" else "Lock Layer", DocumentEdits.setLocked(doc, id, !l.locked))
    }

    fun renameLayer(id: LayerId, name: String): Boolean {
        val doc = state.value.history?.current ?: return false
        val ok = commit("Rename Layer", DocumentEdits.renameLayer(doc, id, name))
        if (ok) dismissDialog()
        return ok
    }

    fun renameDocument(name: String): Boolean {
        val doc = state.value.history?.current ?: return false
        val ok = commit("Rename Document", DocumentEdits.renameDocument(doc, name))
        if (ok) dismissDialog()
        return ok
    }

    /** Opacity in 0..1. Continuous drags coalesce into one undo step until [endContinuousEdit]. */
    fun setOpacity(id: LayerId, opacity: Float) {
        val doc = state.value.history?.current ?: return
        commit("Layer Opacity", DocumentEdits.setOpacity(doc, id, opacity), coalesce = "opacity:${id.value}")
    }

    fun setBlendMode(id: LayerId, mode: BlendMode) {
        val doc = state.value.history?.current ?: return
        commit("Blend Mode: ${mode.displayName}", DocumentEdits.setBlendMode(doc, id, mode))
    }

    fun moveLayerTo(id: LayerId, index: Int) {
        val doc = state.value.history?.current ?: return
        if (doc.indexOf(id) == index) return
        commit("Reorder Layers", DocumentEdits.moveLayer(doc, id, index))
    }

    /** Numeric transform edits from tool options; [field] groups coalescing per control. */
    fun setLayerTransform(id: LayerId, transform: LayerTransform, field: String) {
        val doc = state.value.history?.current ?: return
        commit("Transform Layer", DocumentEdits.setTransform(doc, id, transform), coalesce = "transform:$field:${id.value}")
    }

    fun applyNumeric(target: NumericTarget, value: Double) {
        val v = value.coerceIn(target.min, target.max)
        val doc = state.value.history?.current
        fun layer(id: LayerId) = doc?.layer(id) as? RasterLayer
        when (target) {
            is NumericTarget.LayerOpacity -> setOpacity(target.id, (v / 100.0).toFloat())
            is NumericTarget.LayerScale -> layer(target.id)?.let {
                setLayerTransform(it.id, it.transform.copy(scaleX = v / 100.0, scaleY = v / 100.0), "scale")
            }
            is NumericTarget.LayerRotation -> layer(target.id)?.let {
                setLayerTransform(it.id, it.transform.copy(rotationDegrees = v), "rotation")
            }
            is NumericTarget.LayerX -> layer(target.id)?.let { setLayerTransform(it.id, it.transform.copy(centerX = v), "x") }
            is NumericTarget.LayerY -> layer(target.id)?.let { setLayerTransform(it.id, it.transform.copy(centerY = v), "y") }
            NumericTarget.ViewZoom -> viewportCentered { it.withZoom(v / 100.0) }
        }
        endContinuousEdit()
        dismissDialog()
    }

    fun setThumbSize(size: ThumbSize) = _state.update { it.copy(thumbSize = size) }

    fun setDockTab(tab: DockTab) = _state.update { it.copy(dockTab = tab, panels = it.panels.copy(dockVisible = true)) }

    fun setDockVisible(visible: Boolean) = _state.update { it.copy(panels = it.panels.copy(dockVisible = visible)) }

    fun setHistoryVisible(visible: Boolean) = _state.update { it.copy(panels = it.panels.copy(historyVisible = visible)) }

    fun setToolOptionsVisible(visible: Boolean) = _state.update { it.copy(panels = it.panels.copy(toolOptionsVisible = visible)) }

    // ---------------------------------------------------------------- tools

    fun selectTool(tool: Tool) {
        if (!tool.availability.isEnabled) {
            notice("${tool.label}: ${tool.availability.explanation}")
            return
        }
        _state.update { it.copy(tool = tool) }
    }

    fun setAutoSelectLayer(on: Boolean) = _state.update { it.copy(autoSelectLayer = on) }

    fun setZoomToolZoomsOut(out: Boolean) = _state.update { it.copy(zoomToolZoomsOut = out) }

    fun setSpacePanning(on: Boolean) = _state.update { if (it.spacePanning == on) it else it.copy(spacePanning = on) }

    /** Move tool: pointer down at a screen point. Returns false if nothing can be moved. */
    fun beginMove(screenX: Double, screenY: Double): Boolean {
        val s = state.value
        val doc = s.history?.current ?: return false
        val vp = s.viewport ?: return false
        val p = vp.toDocument(screenX, screenY)
        val targetId = if (s.autoSelectLayer) {
            compositor.hitTest(doc, rasters, p.x, p.y) ?: run {
                notice("No visible pixels under the pointer")
                return false
            }
        } else {
            doc.activeLayerId ?: run { notice("Select a layer to move"); return false }
        }
        val layer = doc.layer(targetId) as? RasterLayer ?: return false
        if (targetId != doc.activeLayerId) selectLayer(targetId)
        if (layer.locked) {
            notice("“${layer.name}” is locked", NoticeKind.Warning)
            return false
        }
        _state.update { it.copy(live = LiveTransform(targetId, layer.transform, layer.transform, p.x, p.y)) }
        return true
    }

    fun updateMove(screenX: Double, screenY: Double) {
        _state.update { s ->
            val live = s.live ?: return@update s
            val vp = s.viewport ?: return@update s
            val p = vp.toDocument(screenX, screenY)
            s.copy(live = live.copy(current = live.start.translatedBy(p.x - live.anchorDocX, p.y - live.anchorDocY)))
        }
    }

    fun endMove() {
        val live = state.value.live ?: return
        _state.update { it.copy(live = null) }
        if (live.current == live.start) return
        val doc = state.value.history?.current ?: return
        commit("Move", DocumentEdits.setTransform(doc, live.layerId, live.current))
    }

    fun cancelMove() = _state.update { it.copy(live = null) }

    /** Arrow-key nudge in document pixels. */
    fun nudgeActiveLayer(dx: Double, dy: Double) {
        val doc = state.value.history?.current ?: return
        val l = doc.activeLayer as? RasterLayer ?: return
        commit("Nudge", DocumentEdits.setTransform(doc, l.id, l.transform.translatedBy(dx, dy)), coalesce = "nudge:${l.id.value}")
    }

    fun zoomToolTap(screenX: Double, screenY: Double, zoomOut: Boolean) = updateViewport { vp ->
        val target = if (zoomOut) Viewport.previousZoomStop(vp.zoom) else Viewport.nextZoomStop(vp.zoom)
        vp.zoomAt(screenX, screenY, target / vp.zoom)
    }

    // ---------------------------------------------------------------- viewport

    fun setViewSize(width: Double, height: Double) {
        if (width <= 0 || height <= 0) return
        viewSize = width to height
        _state.update { s ->
            val doc = s.document ?: return@update s
            val vp = s.viewport
            val next = if (vp == null) Viewport(width, height, doc.width, doc.height).fitToView() else vp.withViewSize(width, height)
            s.copy(viewport = next)
        }
    }

    fun panBy(dx: Double, dy: Double) = updateViewport { it.panByScreen(dx, dy) }

    fun zoomAt(focusX: Double, focusY: Double, factor: Double) = updateViewport { it.zoomAt(focusX, focusY, factor) }

    fun rotateViewAt(focusX: Double, focusY: Double, deltaDegrees: Double) = updateViewport { it.rotateAt(focusX, focusY, deltaDegrees) }

    /** Combined pinch gesture: pan, then zoom and rotate about the centroid. */
    fun transformView(centroidX: Double, centroidY: Double, panX: Double, panY: Double, zoom: Double, rotationDegrees: Double) =
        updateViewport { vp ->
            var v = vp.panByScreen(panX, panY)
            if (zoom != 1.0) v = v.zoomAt(centroidX, centroidY, zoom)
            if (rotationDegrees != 0.0) v = v.rotateAt(centroidX, centroidY, rotationDegrees)
            v
        }

    private inline fun updateViewport(crossinline f: (Viewport) -> Viewport) =
        _state.update { s -> s.viewport?.let { s.copy(viewport = f(it)) } ?: s }

    private inline fun viewportCentered(crossinline f: (Viewport) -> Viewport) = updateViewport(f)

    // ---------------------------------------------------------------- helpers

    private fun uniqueName(doc: Document, base: String): String {
        val clean = base.trim().ifEmpty { "Layer" }.take(DocumentEdits.MAX_NAME_LENGTH)
        val used = doc.layers.map { it.name }.toSet()
        if (clean !in used) return clean
        var n = 2
        while ("$clean $n" in used) n++
        return "$clean $n"
    }

    /** Document statistics for Diagnostics / Document Info. */
    fun stats(): DocumentStats? {
        val s = state.value
        val doc = s.document ?: return null
        return DocumentStats(
            layerCount = doc.layers.size,
            rasterCount = rasters.size,
            rasterBytes = rasters.allocatedBytes,
            historyEntries = s.history?.entries?.size ?: 0,
        )
    }
}

data class DocumentStats(val layerCount: Int, val rasterCount: Int, val rasterBytes: Long, val historyEntries: Int)
