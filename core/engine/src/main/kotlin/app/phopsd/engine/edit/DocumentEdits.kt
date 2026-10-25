package app.phopsd.engine.edit

import app.phopsd.engine.geometry.LayerTransform
import app.phopsd.engine.model.BlendMode
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.Layer
import app.phopsd.engine.model.LayerId
import app.phopsd.engine.model.RasterLayer

/** Outcome of an edit: either a new document or a user-presentable reason it was refused. */
sealed interface EditResult {
    data class Applied(val document: Document) : EditResult
    data class Rejected(val reason: String) : EditResult
}

/**
 * Pure, validated document edits. Every UI path (menus, shortcuts, panels, tools) goes
 * through these functions so behaviour and validation are identical everywhere.
 */
object DocumentEdits {
    const val MAX_NAME_LENGTH = 255

    fun renameDocument(doc: Document, name: String): EditResult {
        val n = name.trim()
        if (n.isEmpty()) return EditResult.Rejected("Document name cannot be empty")
        if (n.length > MAX_NAME_LENGTH) return EditResult.Rejected("Document name is too long")
        return EditResult.Applied(doc.copy(name = n))
    }

    /** Inserts [layer] directly above the active layer (or on top) and makes it active. */
    fun addLayer(doc: Document, layer: Layer): EditResult {
        if (doc.layer(layer.id) != null) return EditResult.Rejected("Layer already exists")
        val activeIndex = doc.activeLayerId?.let { doc.indexOf(it) } ?: -1
        val insertAt = if (activeIndex >= 0) activeIndex + 1 else doc.layers.size
        val list = doc.layers.toMutableList().apply { add(insertAt, layer) }
        return EditResult.Applied(doc.copy(layers = list, activeLayerId = layer.id))
    }

    fun deleteLayer(doc: Document, id: LayerId): EditResult {
        val idx = doc.indexOf(id)
        if (idx < 0) return EditResult.Rejected("Layer not found")
        val layer = doc.layers[idx]
        if (layer.locked) return EditResult.Rejected("“${layer.name}” is locked. Unlock it before deleting.")
        if (doc.layers.size == 1) return EditResult.Rejected("A document must keep at least one layer")
        val list = doc.layers.toMutableList().apply { removeAt(idx) }
        val newActive = if (doc.activeLayerId == id) list[(idx - 1).coerceAtLeast(0)].id else doc.activeLayerId
        return EditResult.Applied(doc.copy(layers = list, activeLayerId = newActive))
    }

    /** Duplicates a layer above itself. Raster pixels are shared (rasters are immutable). */
    fun duplicateLayer(doc: Document, id: LayerId, newId: LayerId = LayerId.random()): EditResult {
        val idx = doc.indexOf(id)
        if (idx < 0) return EditResult.Rejected("Layer not found")
        val copy = when (val l = doc.layers[idx]) {
            is RasterLayer -> l.copy(id = newId, name = copyName(l.name, doc), locked = false)
        }
        val list = doc.layers.toMutableList().apply { add(idx + 1, copy) }
        return EditResult.Applied(doc.copy(layers = list, activeLayerId = newId))
    }

    fun renameLayer(doc: Document, id: LayerId, name: String): EditResult {
        val n = name.trim()
        if (n.isEmpty()) return EditResult.Rejected("Layer name cannot be empty")
        if (n.length > MAX_NAME_LENGTH) return EditResult.Rejected("Layer name is too long")
        return updateLayer(doc, id) { withName(it, n) }
    }

    fun setVisible(doc: Document, id: LayerId, visible: Boolean): EditResult =
        updateLayer(doc, id) { l -> when (l) { is RasterLayer -> l.copy(visible = visible) } }

    fun setLocked(doc: Document, id: LayerId, locked: Boolean): EditResult =
        updateLayer(doc, id) { l -> when (l) { is RasterLayer -> l.copy(locked = locked) } }

    fun setOpacity(doc: Document, id: LayerId, opacity: Float): EditResult {
        if (!opacity.isFinite()) return EditResult.Rejected("Invalid opacity")
        val o = opacity.coerceIn(0f, 1f)
        return updateLayer(doc, id) { l -> when (l) { is RasterLayer -> l.copy(opacity = o) } }
    }

    fun setBlendMode(doc: Document, id: LayerId, mode: BlendMode): EditResult =
        updateLayer(doc, id) { l -> when (l) { is RasterLayer -> l.copy(blendMode = mode) } }

    fun setTransform(doc: Document, id: LayerId, transform: LayerTransform): EditResult {
        val layer = doc.layer(id) ?: return EditResult.Rejected("Layer not found")
        if (layer.locked) return EditResult.Rejected("“${layer.name}” is locked")
        return updateLayer(doc, id) { l -> when (l) { is RasterLayer -> l.copy(transform = transform) } }
    }

    /** Moves a layer to [toIndex] in bottom-to-top order. */
    fun moveLayer(doc: Document, id: LayerId, toIndex: Int): EditResult {
        val from = doc.indexOf(id)
        if (from < 0) return EditResult.Rejected("Layer not found")
        val target = toIndex.coerceIn(0, doc.layers.size - 1)
        if (target == from) return EditResult.Rejected("Layer is already at that position")
        val list = doc.layers.toMutableList()
        val l = list.removeAt(from)
        list.add(target, l)
        return EditResult.Applied(doc.copy(layers = list))
    }

    fun moveUp(doc: Document, id: LayerId): EditResult {
        val i = doc.indexOf(id)
        if (i < 0) return EditResult.Rejected("Layer not found")
        if (i == doc.layers.size - 1) return EditResult.Rejected("Layer is already at the top")
        return moveLayer(doc, id, i + 1)
    }

    fun moveDown(doc: Document, id: LayerId): EditResult {
        val i = doc.indexOf(id)
        if (i < 0) return EditResult.Rejected("Layer not found")
        if (i == 0) return EditResult.Rejected("Layer is already at the bottom")
        return moveLayer(doc, id, i - 1)
    }

    fun setActive(doc: Document, id: LayerId?): EditResult {
        if (id != null && doc.layer(id) == null) return EditResult.Rejected("Layer not found")
        return EditResult.Applied(doc.copy(activeLayerId = id))
    }

    /** Resets scale and rotation, keeping the layer centred where it is. */
    fun resetTransform(doc: Document, id: LayerId): EditResult {
        val l = doc.layer(id) as? RasterLayer ?: return EditResult.Rejected("Layer not found")
        return setTransform(doc, id, LayerTransform(l.transform.centerX, l.transform.centerY))
    }

    /** Next free "Layer N" name. */
    fun nextLayerName(doc: Document): String {
        val used = doc.layers.map { it.name }.toSet()
        var n = doc.layers.size + 1
        while ("Layer $n" in used) n++
        return "Layer $n"
    }

    private fun copyName(base: String, doc: Document): String {
        val used = doc.layers.map { it.name }.toSet()
        val first = "$base copy"
        if (first !in used) return first
        var n = 2
        while ("$base copy $n" in used) n++
        return "$base copy $n"
    }

    private fun withName(l: Layer, name: String): Layer = when (l) {
        is RasterLayer -> l.copy(name = name)
    }

    private inline fun updateLayer(doc: Document, id: LayerId, f: (Layer) -> Layer): EditResult {
        val idx = doc.indexOf(id)
        if (idx < 0) return EditResult.Rejected("Layer not found")
        val old = doc.layers[idx]
        val new = f(old)
        if (new == old) return EditResult.Rejected("No change")
        val list = doc.layers.toMutableList().apply { set(idx, new) }
        return EditResult.Applied(doc.copy(layers = list))
    }
}
