package app.phopsd.editor

import app.phopsd.engine.edit.CanvasEdits
import app.phopsd.engine.edit.DocumentEdits
import app.phopsd.engine.edit.EditResult
import app.phopsd.engine.edit.PixelOps
import app.phopsd.engine.edit.RasterEdit
import app.phopsd.engine.geometry.Affine
import app.phopsd.engine.geometry.CropBox
import app.phopsd.engine.geometry.IntRect
import app.phopsd.engine.geometry.LayerTransform
import app.phopsd.engine.geometry.PointD
import app.phopsd.engine.geometry.RectD
import app.phopsd.engine.geometry.TransformHandles
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.LayerId
import app.phopsd.engine.model.LayerMask
import app.phopsd.engine.model.OutlineStep
import app.phopsd.engine.model.RasterId
import app.phopsd.engine.model.RasterLayer
import app.phopsd.engine.model.Selection
import app.phopsd.engine.model.SelectionOp
import app.phopsd.engine.model.SelectionOutline
import app.phopsd.engine.model.SelectionShape
import app.phopsd.engine.paint.BrushPreset
import app.phopsd.engine.paint.BrushSettings
import app.phopsd.engine.paint.BrushStroke
import app.phopsd.engine.paint.StrokeMode
import app.phopsd.engine.raster.MipChain
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.raster.TiledRaster
import app.phopsd.engine.selection.SelectionRaster
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * M4 editing tools: brush/eraser/mask strokes, selections, layer masks, crop, free
 * transform, clipboard and pixel/canvas commands. Owned by [EditorController] (`c.tools`);
 * all edits go through the controller's history, so every operation is one undo step.
 *
 * Threading: stroke methods ([beginStroke] … [endStroke]) must be called from one thread
 * (the app's paint thread). Everything else may be called from the UI thread.
 */
class CanvasTools internal constructor(private val c: EditorController) {

    private val state get() = c.state.value
    private fun update(f: (EditorState) -> EditorState) = c.mutableState.update(f)

    private fun doc(): Document? = state.history?.current

    private fun activeRaster(doc: Document?): RasterLayer? = doc?.activeLayer as? RasterLayer

    private fun selectionRaster(doc: Document): TiledRaster? = doc.selection?.let { c.rasters.mips(it.rasterId)?.base }

    private fun toDoc(l: RasterLayer): Affine = l.transform.layerToDocument(l.width, l.height)

    /** Why the active layer's pixels can't be edited right now, or null. */
    private fun editProblem(l: RasterLayer?): String? = when {
        l == null -> "Select a layer first"
        l.locked -> "“${l.name}” is locked. Unlock it to edit its pixels."
        !l.visible -> "“${l.name}” is hidden. Show it to edit it."
        else -> null
    }

    // ================================================================ colours

    fun setColor(slot: ColorSlot, rgb: Int) = update {
        val v = rgb and 0xFFFFFF
        it.copy(colors = if (slot == ColorSlot.Foreground) it.colors.copy(foreground = v) else it.colors.copy(background = v))
    }

    fun swapColors() = update { it.copy(colors = it.colors.swapped()) }

    fun resetColors() = update { it.copy(colors = ColorPair()) }

    /** Eyedropper: samples the visible composite under a document point. */
    fun sampleColor(docX: Double, docY: Double, slot: ColorSlot = ColorSlot.Foreground): Boolean {
        val d = state.document ?: return false
        if (docX < 0 || docY < 0 || docX >= d.width || docY >= d.height) return false
        val premul = c.compositorFor().sampleColor(d, c.renderLookup, docX, docY)
        if (Pixels.alpha(premul) == 0) {
            c.notice("Nothing to sample there (transparent)")
            return false
        }
        val straight = Pixels.unpremultiply(premul) and 0xFFFFFF
        setColor(slot, straight)
        return true
    }

    // ================================================================ brush settings

    fun setBrushSettings(settings: BrushSettings) = update {
        if (it.tool == Tool.Eraser) it.copy(eraser = settings) else it.copy(brush = settings)
    }

    fun applyPreset(preset: BrushPreset) = setBrushSettings(preset.settings)

    /** `[` / `]` keys: change the brush size by a factor, clamped. */
    fun scaleBrushSize(factor: Double) {
        val b = state.activeBrush
        setBrushSettings(b.copy(size = (b.size * factor).coerceIn(BrushSettings.MIN_SIZE, BrushSettings.MAX_SIZE)))
    }

    fun nudgeHardness(delta: Double) {
        val b = state.activeBrush
        setBrushSettings(b.copy(hardness = (b.hardness + delta).coerceIn(0.0, 1.0)))
    }

    /** Number keys: opacity 10 %…100 %. */
    fun setOpacityPercent(percent: Int) {
        val b = state.activeBrush
        setBrushSettings(b.copy(opacity = (percent / 100.0).coerceIn(0.01, 1.0)))
    }

    fun setPaintTarget(target: PaintTarget) {
        val l = activeRaster(state.document)
        if (target == PaintTarget.Mask && l?.mask == null) {
            c.notice("This layer has no mask. Add one with Layer ▸ Layer Mask.")
            return
        }
        update { it.copy(paintTarget = target) }
    }

    fun setMaskView(view: MaskView?) {
        if (view != null && activeRaster(state.document)?.mask == null) {
            c.notice("This layer has no mask to show")
            return
        }
        update { it.copy(maskView = view) }
    }

    fun setFingerPainting(on: Boolean) = update { it.copy(fingerPainting = on) }

    // ================================================================ strokes

    private class StrokeSession(
        val layerId: LayerId,
        val onMask: Boolean,
        val sourceId: RasterId,
        val sourceMips: MipChain,
        val stroke: BrushStroke,
        val layerToDoc: Affine,
        val label: String,
    )

    @Volatile
    private var session: StrokeSession? = null

    /**
     * Pen/finger down with a brush-like tool. [eraserTip] is a stylus eraser end (acts as the
     * Eraser for this stroke). Returns false (with an explanation) when painting is not possible.
     */
    fun beginStroke(docX: Double, docY: Double, pressure: Double, eraserTip: Boolean = false, stylus: Boolean = false): Boolean {
        if (session != null) cancelStroke()
        val s = state
        val tool = s.tool
        if (!tool.isBrushLike) return false
        if (stylus && !s.stylusSeen) {
            update { it.copy(stylusSeen = true, fingerPainting = false) }
            c.notice("Stylus detected: the pen paints, fingers pan and zoom. Change this in the brush options.")
        }
        var doc = doc() ?: return false
        var layer = activeRaster(doc)
        editProblem(layer)?.let { c.notice(it, NoticeKind.Warning); return false }
        layer!!
        val toMask = tool == Tool.Mask || (s.paintTarget == PaintTarget.Mask && layer.mask != null)
        if (tool == Tool.Mask && layer.mask == null) {
            // The Mask brush needs a mask: add a "reveal all" one first (its own undo step).
            if (!addMask(reveal = true, fromSelection = false)) return false
            doc = doc() ?: return false
            layer = activeRaster(doc) ?: return false
        }
        val erase = eraserTip || tool == Tool.Eraser
        val colors = s.colors
        val mode: StrokeMode = when {
            toMask && erase -> StrokeMode.MaskValue(ColorPair.maskValue(colors.background))
            toMask -> StrokeMode.MaskValue(ColorPair.maskValue(colors.foreground))
            erase -> StrokeMode.Erase
            else -> StrokeMode.Paint(colors.foreground)
        }
        val settings = if (erase) s.eraser else s.brush
        val sourceId = if (toMask) layer.mask!!.rasterId else layer.rasterId
        val mips = c.rasters.mips(sourceId) ?: return false
        val toDoc = toDoc(layer)
        if (toDoc.invert() == null) { c.notice("This layer has zero size and can't be painted", NoticeKind.Warning); return false }
        val stroke = BrushStroke(mips.base, toDoc, mode, settings, selectionRaster(doc))
        val label = when {
            toMask -> "Mask Brush"
            erase -> "Eraser"
            else -> "Brush"
        }
        val sess = StrokeSession(layer.id, toMask, sourceId, mips, stroke, toDoc, label)
        session = sess
        c.setLiveOverride(sourceId, MipChain.live(stroke.working))
        update { it.copy(stroking = true) }
        report(sess, stroke.begin(docX, docY, pressure.coerceIn(0.0, 1.0)))
        return true
    }

    fun strokeTo(docX: Double, docY: Double, pressure: Double) {
        val sess = session ?: return
        report(sess, sess.stroke.lineTo(docX, docY, pressure.coerceIn(0.0, 1.0)))
    }

    private fun report(sess: StrokeSession, layerRect: IntRect?) {
        if (layerRect == null || layerRect.isEmpty) return
        c.addStrokeDirty(sess.layerToDoc.mapRectBounds(layerRect.toRectD()))
    }

    /** Pen up: the stroke becomes one undo step (only the touched tiles are new memory). */
    fun endStroke() {
        val sess = session ?: return
        report(sess, sess.stroke.end())
        val result = sess.stroke.result()
        val dirty = sess.stroke.dirty
        session = null
        if (dirty == null) {
            c.setLiveOverride(null, null)
            update { it.copy(stroking = false) }
            return
        }
        val newId = RasterId.random()
        c.putPending(newId, MipChain.derive(sess.sourceMips, result, dirty))
        val doc = doc()
        val edit = when {
            doc == null -> EditResult.Rejected("No document")
            sess.onMask -> CanvasEdits.setMaskRaster(doc, sess.layerId, newId)
            else -> CanvasEdits.replaceLayerRaster(doc, sess.layerId, newId)
        }
        c.commitPixels(sess.label, edit, newId)
        c.setLiveOverride(null, null)
        update { it.copy(stroking = false) }
        c.addStrokeDirty(sess.layerToDoc.mapRectBounds(dirty.toRectD()))
    }

    /** Abandons the stroke in progress (e.g. a second finger turned it into a pinch). */
    fun cancelStroke() {
        val sess = session ?: return
        session = null
        val dirty = sess.stroke.dirty
        c.setLiveOverride(null, null)
        update { it.copy(stroking = false) }
        if (dirty != null) c.addStrokeDirty(sess.layerToDoc.mapRectBounds(dirty.toRectD()))
    }

    val isStroking: Boolean get() = session != null

    // ================================================================ selections

    /** The selection op for a new gesture: tool option, overridden by Shift (add) / Alt (subtract) / both (intersect). */
    private fun opFor(shift: Boolean, alt: Boolean): SelectionOp = when {
        shift && alt -> SelectionOp.Intersect
        shift -> SelectionOp.Add
        alt -> SelectionOp.Subtract
        else -> state.selectionOp
    }

    fun setSelectionOp(op: SelectionOp) = update { it.copy(selectionOp = op) }

    /** Marquee / lasso press. Polygonal lasso uses [polygonTap] instead. */
    fun beginSelection(docX: Double, docY: Double, shift: Boolean = false, alt: Boolean = false) {
        val tool = state.tool
        val op = opFor(shift, alt)
        val draft: SelectionDraft = when (tool) {
            Tool.RectMarquee, Tool.EllipticalMarquee -> SelectionDraft.Box(tool == Tool.EllipticalMarquee, docX, docY, docX, docY, op)
            Tool.Lasso -> SelectionDraft.Lasso(listOf(PointD(docX, docY)), op, polygonal = false)
            else -> return
        }
        update { it.copy(selectionDraft = draft) }
    }

    /** Drag update. [minStep] (document px) thins freehand lasso points. */
    fun updateSelection(docX: Double, docY: Double, square: Boolean = false, fromCenter: Boolean = false, minStep: Double = 1.0) = update { s ->
        when (val d = s.selectionDraft) {
            is SelectionDraft.Box -> s.copy(selectionDraft = d.copy(endX = docX, endY = docY, square = square, fromCenter = fromCenter))
            is SelectionDraft.Lasso -> if (d.polygonal) s.copy(selectionDraft = d.copy(cursor = PointD(docX, docY))) else {
                val last = d.points.last()
                if (hypot(docX - last.x, docY - last.y) < minStep) s
                else s.copy(selectionDraft = d.copy(points = d.points + PointD(docX, docY)))
            }
            null -> s
        }
    }

    /** Release of a marquee/lasso drag. A click without dragging deselects (New mode). */
    fun endSelection() {
        val d = state.selectionDraft ?: return
        if (d is SelectionDraft.Lasso && d.polygonal) return
        update { it.copy(selectionDraft = null) }
        val shape = d.shape()
        if (shape == null) {
            if (d.op == SelectionOp.Replace && doc()?.selection != null) deselect()
            return
        }
        commitShape(shape, d.op, labelFor(d))
    }

    fun cancelSelectionDraft() = update { it.copy(selectionDraft = null) }

    /**
     * Polygonal lasso tap: starts a polygon, adds a vertex, or closes it when tapping near
     * the first vertex ([closeRadius] in document px).
     */
    fun polygonTap(docX: Double, docY: Double, closeRadius: Double, shift: Boolean = false, alt: Boolean = false) {
        val d = state.selectionDraft as? SelectionDraft.Lasso
        if (d == null || !d.polygonal) {
            update { it.copy(selectionDraft = SelectionDraft.Lasso(listOf(PointD(docX, docY)), opFor(shift, alt), polygonal = true)) }
            return
        }
        val first = d.points.first()
        if (d.points.size >= 3 && hypot(docX - first.x, docY - first.y) <= closeRadius) {
            finishPolygon()
            return
        }
        update { it.copy(selectionDraft = d.copy(points = d.points + PointD(docX, docY), cursor = null)) }
    }

    /** Closes the polygonal lasso (double-tap / Enter). */
    fun finishPolygon() {
        val d = state.selectionDraft as? SelectionDraft.Lasso ?: return
        update { it.copy(selectionDraft = null) }
        val shape = d.shape() ?: run { c.notice("A polygon needs at least three points"); return }
        commitShape(shape, d.op, "Polygonal Lasso")
    }

    private fun labelFor(d: SelectionDraft) = when (d) {
        is SelectionDraft.Box -> if (d.ellipse) "Elliptical Marquee" else "Rectangular Marquee"
        is SelectionDraft.Lasso -> if (d.polygonal) "Polygonal Lasso" else "Lasso"
    }

    /** Combines [shape] into the selection with [op] as one undo step. */
    fun commitShape(shape: SelectionShape, op: SelectionOp, label: String) {
        val doc = doc() ?: return
        val shapeRaster = SelectionRaster.rasterize(shape, doc.width, doc.height)
        val base = selectionRaster(doc)
        val combined = SelectionRaster.combine(base, shapeRaster, op)
        if (SelectionRaster.isEmpty(combined)) {
            if (doc.selection != null) c.commit("Deselect", CanvasEdits.setSelection(doc, null))
            else if (op != SelectionOp.Subtract) c.notice("The selection is outside the canvas")
            return
        }
        val outline = (doc.selection?.outline ?: SelectionOutline(emptyList())).then(OutlineStep.Shape(if (base == null) SelectionOp.Replace else op, shape))
        setSelection(doc, combined, outline, label)
    }

    private fun setSelection(doc: Document, raster: TiledRaster, outline: SelectionOutline, label: String) {
        val id = RasterId.random()
        c.putPending(id, MipChain.single(raster))
        c.commitPixels(label, CanvasEdits.setSelection(doc, Selection(id, outline)), id)
    }

    fun selectAll() {
        val doc = doc() ?: return
        val shape = SelectionShape.Rect(0.0, 0.0, doc.width.toDouble(), doc.height.toDouble())
        setSelection(doc, SelectionRaster.all(doc.width, doc.height), SelectionOutline.of(shape), "Select All")
    }

    fun deselect() {
        val doc = doc() ?: return
        c.commit("Deselect", CanvasEdits.setSelection(doc, null))
    }

    fun inverseSelection() {
        val doc = doc() ?: return
        val sel = doc.selection ?: return
        val base = c.rasters.mips(sel.rasterId)?.base ?: return
        val inv = SelectionRaster.invert(base)
        if (SelectionRaster.isEmpty(inv)) {
            c.commit("Inverse", CanvasEdits.setSelection(doc, null))
            return
        }
        setSelection(doc, inv, sel.outline.then(OutlineStep.Invert), "Inverse")
    }

    // ================================================================ layer masks

    /**
     * Adds a mask to the active layer. With a selection the mask reveals (or hides) the
     * selected area; otherwise it reveals (or hides) everything.
     */
    fun addMask(reveal: Boolean, fromSelection: Boolean = true): Boolean {
        val doc = doc() ?: return false
        val l = activeRaster(doc) ?: return false
        if (l.mask != null) { c.notice("“${l.name}” already has a mask"); return false }
        if (l.locked) { c.notice("“${l.name}” is locked", NoticeKind.Warning); return false }
        val sel = if (fromSelection) selectionRaster(doc) else null
        val raster = if (sel != null) PixelOps.maskFromSelection(l.width, l.height, toDoc(l), sel, reveal)
        else PixelOps.solidMask(l.width, l.height, if (reveal) 255 else 0)
        val id = RasterId.random()
        c.putPending(id, MipChain.build(raster))
        val label = when {
            sel != null && reveal -> "Reveal Selection"
            sel != null -> "Hide Selection"
            reveal -> "Add Layer Mask"
            else -> "Hide All Mask"
        }
        val ok = c.commitPixels(label, CanvasEdits.setMask(doc, l.id, LayerMask(id)), id)
        if (ok) update { it.copy(paintTarget = PaintTarget.Mask) }
        return ok
    }

    fun deleteMask() {
        val doc = doc() ?: return
        val l = activeRaster(doc) ?: return
        if (c.commit("Delete Layer Mask", CanvasEdits.setMask(doc, l.id, null))) {
            update { it.copy(paintTarget = PaintTarget.Pixels, maskView = null) }
        }
    }

    fun toggleMaskEnabled() {
        val doc = doc() ?: return
        val l = activeRaster(doc) ?: return
        val m = l.mask ?: return
        c.commit(if (m.enabled) "Disable Layer Mask" else "Enable Layer Mask", CanvasEdits.setMaskEnabled(doc, l.id, !m.enabled))
    }

    fun invertMask() {
        val doc = doc() ?: return
        val l = activeRaster(doc) ?: return
        val m = l.mask ?: return
        val base = c.rasters.mips(m.rasterId)?.base ?: return
        val id = RasterId.random()
        c.putPending(id, MipChain.build(PixelOps.invertMask(base)))
        c.commitPixels("Invert Mask", CanvasEdits.setMaskRaster(doc, l.id, id), id)
    }

    /** Bakes the mask into the layer's pixels (destructive, but undoable). */
    fun applyMask() {
        val doc = doc() ?: return
        val l = activeRaster(doc) ?: return
        val m = l.mask ?: return
        editProblem(l)?.let { c.notice(it, NoticeKind.Warning); return }
        c.runHeavy("Applying mask…") {
            val pixels = c.rasters.mips(l.rasterId)?.base ?: return@runHeavy
            val mask = c.rasters.mips(m.rasterId)?.base ?: return@runHeavy
            val baked = if (m.enabled) PixelOps.applyMask(pixels, mask) else pixels
            val id = RasterId.random()
            c.putPending(id, if (baked === pixels) c.rasters.mips(l.rasterId)!! else MipChain.build(baked))
            val d = doc() ?: return@runHeavy
            val r = CanvasEdits.replaceLayerRaster(d, l.id, id).then { CanvasEdits.setMask(it, l.id, null) }
            if (c.commitPixels("Apply Layer Mask", r, id)) update { it.copy(paintTarget = PaintTarget.Pixels, maskView = null) }
        }
    }

    // ================================================================ pixel commands

    private class Clip(val raster: TiledRaster, val width: Int, val height: Int, val transform: LayerTransform, val name: String)

    @Volatile
    private var clipboard: Clip? = null

    val hasClipboard: Boolean get() = clipboard != null

    /** Raster edit on the active layer's pixels or mask (whichever brushes target). */
    private fun editActive(label: String, op: (target: TiledRaster, toDoc: Affine, sel: TiledRaster?, onMask: Boolean) -> RasterEdit?) {
        val doc = doc() ?: return
        val l = activeRaster(doc)
        editProblem(l)?.let { c.notice(it, NoticeKind.Warning); return }
        l!!
        val onMask = state.paintsMask && l.mask != null
        val sourceId = if (onMask) l.mask!!.rasterId else l.rasterId
        val mips = c.rasters.mips(sourceId) ?: return
        val edit = op(mips.base, toDoc(l), selectionRaster(doc), onMask)
        if (edit == null) { c.notice("Nothing selected on this layer"); return }
        val id = RasterId.random()
        c.putPending(id, MipChain.derive(mips, edit.raster, edit.dirty))
        val r = if (onMask) CanvasEdits.setMaskRaster(doc, l.id, id) else CanvasEdits.replaceLayerRaster(doc, l.id, id)
        c.commitPixels(label, r, id)
    }

    fun clear() = editActive("Clear") { r, t, sel, mask ->
        if (mask) PixelOps.fillMask(r, t, sel, 0) else PixelOps.clear(r, t, sel)
    }

    fun fill(content: FillContent) {
        val rgb = content.rgb(state.colors)
        editActive("Fill") { r, t, sel, mask ->
            if (mask) PixelOps.fillMask(r, t, sel, ColorPair.maskValue(rgb)) else PixelOps.fill(r, t, sel, rgb)
        }
        c.dismissDialog()
    }

    /** Copies the selected pixels of the active layer (or the whole layer) to the internal clipboard. */
    fun copy(): Boolean {
        val doc = doc() ?: return false
        val l = activeRaster(doc) ?: return false
        val pixels = c.rasters.mips(l.rasterId)?.base ?: return false
        val sel = selectionRaster(doc)
        val raster = if (sel == null) pixels else PixelOps.extract(pixels, toDoc(l), sel)
        if (sel != null && raster.allocatedTileCount == 0 && Pixels.alpha(raster.fill) == 0) {
            c.notice("The selected area of “${l.name}” is empty")
            return false
        }
        clipboard = Clip(raster, l.width, l.height, l.transform, l.name)
        c.notice(if (sel == null) "Copied “${l.name}”" else "Copied selection")
        return true
    }

    fun cut() {
        if (!copy()) return
        clear()
    }

    /** Pastes the clipboard as a new layer in its original position. */
    fun paste() {
        val clip = clipboard ?: run { c.notice("The clipboard is empty"); return }
        val doc = doc() ?: return
        val id = RasterId.random()
        c.putPending(id, MipChain.build(clip.raster))
        val layer = RasterLayer(LayerId.random(), c.uniqueLayerName(doc, "${clip.name} (pasted)"), id, clip.width, clip.height, clip.transform)
        c.commitPixels("Paste", DocumentEdits.addLayer(doc, layer), id)
    }

    /** Layer via Copy (Ctrl+J with a selection) / Layer via Cut (Shift+Ctrl+J). */
    fun layerVia(cut: Boolean) {
        val doc = doc() ?: return
        val l = activeRaster(doc) ?: return
        if (cut) editProblem(l)?.let { c.notice(it, NoticeKind.Warning); return }
        val pixels = c.rasters.mips(l.rasterId) ?: return
        val sel = selectionRaster(doc)
        val toDoc = toDoc(l)
        val extracted = if (sel == null) pixels.base else PixelOps.extract(pixels.base, toDoc, sel)
        val newId = RasterId.random()
        c.putPending(newId, if (extracted === pixels.base) pixels else MipChain.build(extracted))
        val layer = RasterLayer(LayerId.random(), c.uniqueLayerName(doc, if (cut) "${l.name} (cut)" else "${l.name} (copy)"), newId, l.width, l.height, l.transform)
        var result = DocumentEdits.addLayer(doc, layer)
        val ids = mutableListOf(newId)
        if (cut) {
            val cleared = PixelOps.clear(pixels.base, toDoc, sel)
            if (cleared != null) {
                val cid = RasterId.random()
                c.putPending(cid, MipChain.derive(pixels, cleared.raster, cleared.dirty))
                ids += cid
                result = result.then { CanvasEdits.replaceLayerRaster(it, l.id, cid) }.then { DocumentEdits.setActive(it, layer.id) }
            }
        }
        c.commitPixels(if (cut) "Layer via Cut" else "Layer via Copy", result, ids)
    }

    fun mergeDown() {
        val doc = doc() ?: return
        val upper = activeRaster(doc) ?: return
        val idx = doc.indexOf(upper.id)
        if (idx <= 0) { c.notice("There is no layer below to merge into"); return }
        val lower = doc.layers[idx - 1] as RasterLayer
        CanvasEdits.mergeDownProblem(upper, lower)?.let { c.notice(it, NoticeKind.Warning); return }
        c.runHeavy("Merging layers…") {
            // The lower layer's own opacity and blend mode stay on the merged layer.
            val base = lower.copy(opacity = 1f, blendMode = app.phopsd.engine.model.BlendMode.Normal)
            val merged = PixelOps.merge(doc, c.rasters, listOf(base, upper)) ?: return@runHeavy
            val id = RasterId.random()
            c.putPending(id, MipChain.build(merged))
            val layer = CanvasEdits.canvasLayer(doc, lower.id, lower.name, id).copy(opacity = lower.opacity, blendMode = lower.blendMode)
            val cur = doc() ?: return@runHeavy
            c.commitPixels("Merge Down", CanvasEdits.mergeDown(cur, upper.id, layer), id)
        }
    }

    fun requestFlatten() {
        val doc = doc() ?: return
        c.showDialog(EditorDialog.ConfirmFlatten(doc.layers.count { !it.visible }))
    }

    fun flatten() {
        c.dismissDialog()
        val doc = doc() ?: return
        c.runHeavy("Flattening…") {
            val merged = PixelOps.merge(doc, c.rasters, doc.layers.filter { it.visible }) ?: return@runHeavy
            val id = RasterId.random()
            c.putPending(id, MipChain.build(merged))
            val layer = CanvasEdits.canvasLayer(doc, LayerId.random(), "Background", id)
            val cur = doc() ?: return@runHeavy
            if (c.commitPixels("Flatten Image", CanvasEdits.flatten(cur, layer), id)) {
                update { it.copy(paintTarget = PaintTarget.Pixels, maskView = null) }
            }
        }
    }

    // ================================================================ canvas

    fun rotateCanvas(r: CanvasEdits.Rotation) {
        val doc = doc() ?: return
        c.commit("Rotate Canvas ${r.label}", CanvasEdits.rotateCanvas(doc, r))
    }

    fun flipCanvas(horizontal: Boolean) {
        val doc = doc() ?: return
        c.commit(if (horizontal) "Flip Canvas Horizontal" else "Flip Canvas Vertical", CanvasEdits.flipCanvas(doc, horizontal))
    }

    fun flipLayer(horizontal: Boolean) {
        val doc = doc() ?: return
        val l = activeRaster(doc) ?: return
        c.commit(if (horizontal) "Flip Layer Horizontal" else "Flip Layer Vertical", CanvasEdits.flipLayer(doc, l.id, horizontal))
    }

    fun imageSize(width: Int, height: Int): Boolean {
        val doc = doc() ?: return false
        val ok = c.commit("Image Size", CanvasEdits.imageSize(doc, width, height))
        if (ok) c.dismissDialog()
        return ok
    }

    fun canvasSize(width: Int, height: Int, anchorX: Double, anchorY: Double): Boolean {
        val doc = doc() ?: return false
        val ok = c.commit("Canvas Size", CanvasEdits.canvasSize(doc, width, height, anchorX, anchorY))
        if (ok) c.dismissDialog()
        return ok
    }

    /** Image ▸ Crop: crops to the selection bounds, or opens the Crop tool. */
    fun cropCommand() {
        val doc = doc() ?: return
        val sel = selectionRaster(doc)
        val b = sel?.let { SelectionRaster.bounds(it) }
        if (b == null) {
            c.selectTool(Tool.Crop)
            return
        }
        c.commit("Crop", CanvasEdits.crop(doc, b))
    }

    // ================================================================ crop tool

    private class CropDrag(val handle: TransformHandles.Handle, val start: RectD, val startX: Double, val startY: Double)

    private var cropDrag: CropDrag? = null

    fun startCropSession() = update { s ->
        val d = s.document ?: return@update s
        s.copy(crop = CropSession(CropBox.initial(d.width, d.height, CropBox.ratioFor(s.cropAspect, d.width, d.height)), s.cropAspect))
    }

    fun setCropAspect(aspect: CropBox.Aspect) {
        update { it.copy(cropAspect = aspect) }
        startCropSession()
    }

    /** Handle under a document point of the crop box, or null. [radius] is in document px. */
    fun cropHandleAt(docX: Double, docY: Double, radius: Double): TransformHandles.Handle? {
        val r = state.crop?.rect ?: return null
        val xs = doubleArrayOf(r.left, (r.left + r.right) / 2, r.right)
        val ys = doubleArrayOf(r.top, (r.top + r.bottom) / 2, r.bottom)
        var best: TransformHandles.Handle? = null
        var bestD = Double.MAX_VALUE
        for (h in TransformHandles.Handle.entries.filter { it.isScale }) {
            val d = hypot(xs[h.fx + 1] - docX, ys[h.fy + 1] - docY)
            if (d <= radius && d < bestD) { best = h; bestD = d }
        }
        if (best != null) return best
        // Edges are easier to grab along their whole length.
        val nearL = abs(docX - r.left) <= radius / 2; val nearR = abs(docX - r.right) <= radius / 2
        val nearT = abs(docY - r.top) <= radius / 2; val nearB = abs(docY - r.bottom) <= radius / 2
        val inX = docX in r.left..r.right; val inY = docY in r.top..r.bottom
        return when {
            nearL && inY -> TransformHandles.Handle.Left
            nearR && inY -> TransformHandles.Handle.Right
            nearT && inX -> TransformHandles.Handle.Top
            nearB && inX -> TransformHandles.Handle.Bottom
            inX && inY -> TransformHandles.Handle.Move
            else -> null
        }
    }

    fun cropDragBegin(docX: Double, docY: Double, radius: Double): Boolean {
        if (state.crop == null) startCropSession()
        val crop = state.crop ?: return false
        val h = cropHandleAt(docX, docY, radius) ?: return false
        cropDrag = CropDrag(h, crop.rect, docX, docY)
        return true
    }

    fun cropDragUpdate(docX: Double, docY: Double) {
        val d = cropDrag ?: return
        update { s ->
            val crop = s.crop ?: return@update s
            val doc = s.document ?: return@update s
            val ratio = CropBox.ratioFor(crop.aspect, doc.width, doc.height)
            s.copy(crop = crop.copy(rect = CropBox.drag(d.start, d.handle, docX - d.startX, docY - d.startY, ratio)))
        }
    }

    fun cropDragEnd() { cropDrag = null }

    fun commitCrop() {
        val crop = state.crop ?: return
        val doc = doc() ?: return
        val px = CropBox.toPixels(crop.rect)
        if (px == IntRect(0, 0, doc.width, doc.height)) { c.notice("The crop box covers the whole canvas"); return }
        if (c.commit("Crop", CanvasEdits.crop(doc, px))) startCropSession()
    }

    fun cancelCrop() = startCropSession()

    // ================================================================ free transform

    private class TransformDrag(val handle: TransformHandles.Handle, val layerId: LayerId, val start: LayerTransform, val w: Int, val h: Int, val startPointer: PointD)

    private var transformDrag: TransformDrag? = null

    fun setKeepProportions(on: Boolean) = update { it.copy(keepProportions = on) }

    /** Press with the Transform tool. Radii are in document px (finger-sized on screen). */
    fun transformBegin(docX: Double, docY: Double, handleRadius: Double, rotateRadius: Double): TransformHandles.Handle? {
        val doc = doc() ?: return null
        val l = activeRaster(doc) ?: run { c.notice("Select a layer to transform"); return null }
        if (l.locked) { c.notice("“${l.name}” is locked", NoticeKind.Warning); return null }
        val h = TransformHandles.hitTest(l.transform, l.width, l.height, PointD(docX, docY), handleRadius, rotateRadius) ?: return null
        transformDrag = TransformDrag(h, l.id, l.transform, l.width, l.height, PointD(docX, docY))
        update { it.copy(live = LiveTransform(l.id, l.transform, l.transform, docX, docY)) }
        return h
    }

    fun transformUpdate(docX: Double, docY: Double, shift: Boolean = false, alt: Boolean = false) {
        val d = transformDrag ?: return
        val p = PointD(docX, docY)
        val s = state
        val t = when {
            d.handle == TransformHandles.Handle.Move -> TransformHandles.move(d.start, d.startPointer, p)
            d.handle == TransformHandles.Handle.Rotate -> TransformHandles.rotate(d.start, d.startPointer, p, s.rotationSnap)
            else -> {
                val proportional = if (d.handle.isCorner) s.keepProportions != shift else shift
                TransformHandles.scale(d.start, d.w, d.h, d.handle, d.startPointer, p, proportional, alt)
            }
        }
        update { it.copy(live = LiveTransform(d.layerId, d.start, t, d.startPointer.x, d.startPointer.y)) }
    }

    fun transformEnd() {
        val d = transformDrag ?: return
        transformDrag = null
        val live = state.live
        update { it.copy(live = null) }
        if (live == null || live.current == live.start) return
        val doc = doc() ?: return
        val label = when (d.handle) {
            TransformHandles.Handle.Move -> "Move"
            TransformHandles.Handle.Rotate -> "Rotate"
            else -> "Scale"
        }
        c.commit(label, DocumentEdits.setTransform(doc, d.layerId, live.current))
    }

    fun transformCancel() {
        transformDrag = null
        update { it.copy(live = null) }
    }

    // ================================================================ helpers

    private inline fun EditResult.then(f: (Document) -> EditResult): EditResult = when (this) {
        is EditResult.Applied -> when (val n = f(document)) {
            is EditResult.Applied -> n
            // A "No change" follow-up keeps the first edit.
            is EditResult.Rejected -> if (n.reason == "No change") this else n
        }
        is EditResult.Rejected -> this
    }

    /** Percent text for the options bar. */
    companion object {
        fun percent(v: Double): String = "${(v * 100).roundToInt()}%"
    }
}
