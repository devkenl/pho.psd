package app.phopsd.editor

import app.phopsd.engine.edit.History
import app.phopsd.engine.geometry.LayerTransform
import app.phopsd.engine.geometry.Viewport
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.LayerId
import app.phopsd.engine.model.RasterLayer

enum class Screen { Home, Editor }

enum class DockTab(val label: String) { Layers("Layers"), Channels("Channels"), Paths("Paths") }

enum class ThumbSize(val dp: Int) { Small(32), Medium(44), Large(60) }

data class Panels(
    val dockVisible: Boolean = true,
    val historyVisible: Boolean = true,
    val toolOptionsVisible: Boolean = true,
    val statusBarVisible: Boolean = true,
)

enum class NoticeKind { Info, Warning, Error }

data class Notice(val id: Long, val text: String, val kind: NoticeKind)

/** Where an image picker result should go. */
enum class PickPurpose { OpenAsDocument, PlaceAsLayer }

/** Which system picker to use. */
enum class PickSource { Documents, Photos }

/** One-shot requests the platform layer must fulfil (pickers need an Activity). */
sealed interface EditorEffect {
    data class PickImage(val purpose: PickPurpose, val source: PickSource) : EditorEffect
}

/** Actions deferred behind a "discard unsaved changes?" confirmation. */
sealed interface PendingAction {
    data object Close : PendingAction
    data class Pick(val source: PickSource) : PendingAction
    data object NewDocument : PendingAction
}

/** Numeric fields that accept precise entry (double-tap a value). */
sealed interface NumericTarget {
    val title: String
    val unit: String
    val min: Double
    val max: Double

    data class LayerOpacity(val id: LayerId) : NumericTarget {
        override val title = "Opacity"; override val unit = "%"; override val min = 0.0; override val max = 100.0
    }
    data class LayerScale(val id: LayerId) : NumericTarget {
        override val title = "Scale"; override val unit = "%"; override val min = 1.0; override val max = 1000.0
    }
    data class LayerRotation(val id: LayerId) : NumericTarget {
        override val title = "Rotation"; override val unit = "°"; override val min = -180.0; override val max = 180.0
    }
    data class LayerX(val id: LayerId) : NumericTarget {
        override val title = "Centre X"; override val unit = "px"; override val min = -100_000.0; override val max = 100_000.0
    }
    data class LayerY(val id: LayerId) : NumericTarget {
        override val title = "Centre Y"; override val unit = "px"; override val min = -100_000.0; override val max = 100_000.0
    }
    data object ViewZoom : NumericTarget {
        override val title = "Zoom"; override val unit = "%"; override val min = Viewport.MIN_ZOOM * 100; override val max = Viewport.MAX_ZOOM * 100
    }
}

sealed interface EditorDialog {
    data object NewDocument : EditorDialog
    data class RenameLayer(val id: LayerId, val current: String) : EditorDialog
    data class RenameDocument(val current: String) : EditorDialog
    data object DocumentInfo : EditorDialog
    data object About : EditorDialog
    data object Shortcuts : EditorDialog
    data object FeatureStatus : EditorDialog
    data object Diagnostics : EditorDialog
    data class ConfirmDiscard(val action: PendingAction) : EditorDialog
    data class NumericEntry(val target: NumericTarget, val current: Double) : EditorDialog
}

/** A transform being dragged on canvas; committed to history as one step on release. */
data class LiveTransform(
    val layerId: LayerId,
    val start: LayerTransform,
    val current: LayerTransform,
    val anchorDocX: Double,
    val anchorDocY: Double,
) {
    fun applyTo(doc: Document): Document {
        val idx = doc.indexOf(layerId)
        if (idx < 0) return doc
        val l = doc.layers[idx] as? RasterLayer ?: return doc
        val list = doc.layers.toMutableList()
        list[idx] = l.copy(transform = current)
        return doc.copy(layers = list)
    }
}

/**
 * Complete UI-facing editor state. Immutable; pixel data lives in the controller's
 * RasterStore and is referenced by id from the document.
 */
data class EditorState(
    val screen: Screen = Screen.Home,
    val history: History? = null,
    val live: LiveTransform? = null,
    val viewport: Viewport? = null,
    val tool: Tool = Tool.Move,
    val autoSelectLayer: Boolean = false,
    val zoomToolZoomsOut: Boolean = false,
    val dockTab: DockTab = DockTab.Layers,
    val panels: Panels = Panels(),
    val thumbSize: ThumbSize = ThumbSize.Medium,
    val dialog: EditorDialog? = null,
    val notice: Notice? = null,
    val busy: String? = null,
    /** True once the document differs from what was created/opened (nothing is saved yet). */
    val dirty: Boolean = false,
    val lastEditAtMillis: Long? = null,
    /** Space bar held: canvas temporarily behaves like the Hand tool. */
    val spacePanning: Boolean = false,
) {
    /** Document as displayed, including an in-progress drag. */
    val document: Document? get() = history?.current?.let { d -> live?.applyTo(d) ?: d }

    val effectiveTool: Tool get() = if (spacePanning) Tool.Hand else tool
}
