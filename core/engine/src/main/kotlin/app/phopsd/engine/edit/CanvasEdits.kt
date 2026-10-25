package app.phopsd.engine.edit

import app.phopsd.engine.geometry.Affine
import app.phopsd.engine.geometry.IntRect
import app.phopsd.engine.geometry.LayerTransform
import app.phopsd.engine.geometry.PointD
import app.phopsd.engine.geometry.Viewport
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.Layer
import app.phopsd.engine.model.LayerId
import app.phopsd.engine.model.LayerMask
import app.phopsd.engine.model.NewDocumentSpec
import app.phopsd.engine.model.RasterId
import app.phopsd.engine.model.RasterLayer
import app.phopsd.engine.model.Selection
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Document-level geometry, mask and selection edits.
 *
 * Canvas operations (crop, canvas size, rotate, flip, image size) are **non-destructive**:
 * they change the canvas and each layer's transform, never layer pixels. Pixels cropped
 * away stay in the layer (outside the canvas) and come back if the canvas grows again.
 * The selection is dropped by these operations (its raster is in old canvas coordinates).
 */
object CanvasEdits {

    private fun validSize(w: Int, h: Int): String? = when {
        w < 1 || h < 1 -> "Width and height must be at least 1 px"
        w > NewDocumentSpec.MAX_EDGE || h > NewDocumentSpec.MAX_EDGE -> "Maximum edge length is ${NewDocumentSpec.MAX_EDGE} px"
        w.toLong() * h > NewDocumentSpec.MAX_PIXELS -> "Maximum document size is ${NewDocumentSpec.MAX_PIXELS / 1_000_000} megapixels"
        else -> null
    }

    /** Applies document-space map [m] (a similarity or flip) to every layer transform. */
    private fun mapLayers(doc: Document, f: (LayerTransform) -> LayerTransform): List<Layer> =
        doc.layers.map { l -> when (l) { is RasterLayer -> l.copy(transform = f(l.transform)) } }

    /** Crops the canvas to [rect] (document pixels; may extend beyond the canvas). */
    fun crop(doc: Document, rect: IntRect): EditResult {
        validSize(rect.width, rect.height)?.let { return EditResult.Rejected(it) }
        if (rect == IntRect(0, 0, doc.width, doc.height)) return EditResult.Rejected("No change")
        val dx = -rect.left.toDouble()
        val dy = -rect.top.toDouble()
        return EditResult.Applied(
            doc.copy(width = rect.width, height = rect.height, layers = mapLayers(doc) { it.translatedBy(dx, dy) }, selection = null),
        )
    }

    /**
     * Resizes the canvas without scaling content. [anchorX]/[anchorY] in 0..1 choose which
     * part stays put (0,0 = top-left, 0.5,0.5 = centre).
     */
    fun canvasSize(doc: Document, width: Int, height: Int, anchorX: Double = 0.5, anchorY: Double = 0.5): EditResult {
        validSize(width, height)?.let { return EditResult.Rejected(it) }
        if (width == doc.width && height == doc.height) return EditResult.Rejected("No change")
        val dx = ((width - doc.width) * anchorX.coerceIn(0.0, 1.0)).roundToInt().toDouble()
        val dy = ((height - doc.height) * anchorY.coerceIn(0.0, 1.0)).roundToInt().toDouble()
        return EditResult.Applied(
            doc.copy(width = width, height = height, layers = mapLayers(doc) { it.translatedBy(dx, dy) }, selection = null),
        )
    }

    enum class Rotation(val label: String) { Cw90("90° Clockwise"), Ccw90("90° Counter-clockwise"), Half("180°") }

    fun rotateCanvas(doc: Document, rotation: Rotation): EditResult {
        val w = doc.width.toDouble()
        val h = doc.height.toDouble()
        val (nw, nh) = if (rotation == Rotation.Half) doc.width to doc.height else doc.height to doc.width
        val delta = when (rotation) { Rotation.Cw90 -> 90.0; Rotation.Ccw90 -> -90.0; Rotation.Half -> 180.0 }
        fun mapPoint(x: Double, y: Double): PointD = when (rotation) {
            Rotation.Cw90 -> PointD(h - y, x)
            Rotation.Ccw90 -> PointD(y, w - x)
            Rotation.Half -> PointD(w - x, h - y)
        }
        val layers = mapLayers(doc) { t ->
            val c = mapPoint(t.centerX, t.centerY)
            t.copy(centerX = c.x, centerY = c.y, rotationDegrees = Viewport.normalizeDegrees(t.rotationDegrees + delta))
        }
        return EditResult.Applied(doc.copy(width = nw, height = nh, layers = layers, selection = null))
    }

    /** Mirrors the whole canvas (all layers) horizontally or vertically. */
    fun flipCanvas(doc: Document, horizontal: Boolean): EditResult {
        val layers = mapLayers(doc) { flipAbout(it, horizontal, if (horizontal) doc.width / 2.0 else doc.height / 2.0) }
        return EditResult.Applied(doc.copy(layers = layers, selection = null))
    }

    /**
     * Mirror of a transform about the document line x = [axis] (horizontal) or y = [axis].
     * F·R(θ) = R(−θ)·F and F·S(sx, sy) = S(−sx, sy) (or S(sx, −sy) for vertical).
     */
    private fun flipAbout(t: LayerTransform, horizontal: Boolean, axis: Double): LayerTransform =
        if (horizontal) t.copy(centerX = 2 * axis - t.centerX, scaleX = -t.scaleX, rotationDegrees = Viewport.normalizeDegrees(-t.rotationDegrees))
        else t.copy(centerY = 2 * axis - t.centerY, scaleY = -t.scaleY, rotationDegrees = Viewport.normalizeDegrees(-t.rotationDegrees))

    /** Mirrors one layer in place (about its own centre, along its own axes). */
    fun flipLayer(doc: Document, id: LayerId, horizontal: Boolean): EditResult {
        val l = doc.layer(id) as? RasterLayer ?: return EditResult.Rejected("Layer not found")
        if (l.locked) return EditResult.Rejected("“${l.name}” is locked")
        val t = l.transform
        val nt = if (horizontal) t.copy(scaleX = -t.scaleX) else t.copy(scaleY = -t.scaleY)
        return DocumentEdits.setTransform(doc, id, nt)
    }

    /**
     * Proportional Image Size. Layers are rescaled through their transforms, so the original
     * pixels are kept at full resolution (resampling happens when rendering/exporting).
     */
    fun imageSize(doc: Document, width: Int, height: Int): EditResult {
        validSize(width, height)?.let { return EditResult.Rejected(it) }
        if (width == doc.width && height == doc.height) return EditResult.Rejected("No change")
        val kx = width.toDouble() / doc.width
        val ky = height.toDouble() / doc.height
        val k = sqrt(kx * ky)
        // Proportional only: a non-uniform scale cannot be expressed on rotated layers.
        if (abs(kx / ky - 1.0) > 0.02) return EditResult.Rejected("Image Size keeps proportions; adjust width or height only")
        val layers = mapLayers(doc) { t ->
            t.copy(centerX = t.centerX * kx, centerY = t.centerY * ky, scaleX = t.scaleX * k, scaleY = t.scaleY * k)
        }
        return EditResult.Applied(doc.copy(width = width, height = height, layers = layers, selection = null))
    }

    // ---- Layer pixels & masks ------------------------------------------------------------

    private inline fun updateRaster(doc: Document, id: LayerId, f: (RasterLayer) -> RasterLayer): EditResult {
        val idx = doc.indexOf(id)
        if (idx < 0) return EditResult.Rejected("Layer not found")
        val l = doc.layers[idx] as RasterLayer
        val n = f(l)
        if (n == l) return EditResult.Rejected("No change")
        return EditResult.Applied(doc.copy(layers = doc.layers.toMutableList().apply { set(idx, n) }))
    }

    /** Points a layer at new pixels (after a stroke, fill, clear…). Locked layers refuse. */
    fun replaceLayerRaster(doc: Document, id: LayerId, rasterId: RasterId): EditResult {
        val l = doc.layer(id) as? RasterLayer ?: return EditResult.Rejected("Layer not found")
        if (l.locked) return EditResult.Rejected("“${l.name}” is locked")
        return updateRaster(doc, id) { it.copy(rasterId = rasterId) }
    }

    /** Adds, replaces or (with null) deletes a layer mask. */
    fun setMask(doc: Document, id: LayerId, mask: LayerMask?): EditResult {
        val l = doc.layer(id) as? RasterLayer ?: return EditResult.Rejected("Layer not found")
        if (l.locked) return EditResult.Rejected("“${l.name}” is locked")
        return updateRaster(doc, id) { it.copy(mask = mask) }
    }

    fun setMaskRaster(doc: Document, id: LayerId, rasterId: RasterId): EditResult {
        val l = doc.layer(id) as? RasterLayer ?: return EditResult.Rejected("Layer not found")
        val m = l.mask ?: return EditResult.Rejected("“${l.name}” has no mask")
        return setMask(doc, id, m.copy(rasterId = rasterId))
    }

    fun setMaskEnabled(doc: Document, id: LayerId, enabled: Boolean): EditResult {
        val l = doc.layer(id) as? RasterLayer ?: return EditResult.Rejected("Layer not found")
        val m = l.mask ?: return EditResult.Rejected("“${l.name}” has no mask")
        return updateRaster(doc, id) { it.copy(mask = m.copy(enabled = enabled)) }
    }

    fun setSelection(doc: Document, selection: Selection?): EditResult =
        if (doc.selection == selection) EditResult.Rejected("No change")
        else EditResult.Applied(doc.copy(selection = selection))

    /**
     * Replaces the layer at [id] and the one below it with [merged] (Merge Down). The caller
     * renders the merged pixels with [PixelOps.merge].
     */
    fun mergeDown(doc: Document, id: LayerId, merged: RasterLayer): EditResult {
        val idx = doc.indexOf(id)
        if (idx < 0) return EditResult.Rejected("Layer not found")
        if (idx == 0) return EditResult.Rejected("There is no layer below to merge into")
        val upper = doc.layers[idx]
        val lower = doc.layers[idx - 1]
        mergeDownProblem(upper, lower)?.let { return EditResult.Rejected(it) }
        val list = doc.layers.toMutableList()
        list.removeAt(idx)
        list[idx - 1] = merged
        return EditResult.Applied(doc.copy(layers = list, activeLayerId = merged.id))
    }

    fun mergeDownProblem(upper: Layer, lower: Layer): String? = when {
        upper.locked || lower.locked -> "Unlock both layers before merging"
        !upper.visible || !lower.visible -> "Both layers must be visible to merge"
        else -> null
    }

    /** Replaces every layer with [flattened] (hidden layers are discarded). */
    fun flatten(doc: Document, flattened: RasterLayer): EditResult =
        EditResult.Applied(doc.copy(layers = listOf(flattened), activeLayerId = flattened.id, selection = null))

    /** A layer covering the canvas exactly (used for merge results). */
    fun canvasLayer(doc: Document, id: LayerId, name: String, rasterId: RasterId): RasterLayer =
        RasterLayer(
            id = id, name = name, rasterId = rasterId, width = doc.width, height = doc.height,
            transform = LayerTransform.atOffset(0.0, 0.0, doc.width, doc.height),
        )

    /** Document → layer pixel map for a layer (convenience for tools). */
    fun layerToDocument(l: RasterLayer): Affine = l.transform.layerToDocument(l.width, l.height)
}
