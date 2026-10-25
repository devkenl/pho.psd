package app.phopsd.engine.model

import app.phopsd.engine.geometry.LayerTransform
import java.util.UUID

@JvmInline
value class LayerId(val value: String) {
    companion object {
        fun random() = LayerId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class RasterId(val value: String) {
    companion object {
        fun random() = RasterId(UUID.randomUUID().toString())
    }
}

/**
 * Blend modes. Maths follows the W3C "Compositing and Blending Level 1" definitions,
 * evaluated on sRGB-encoded values (the conventional behaviour of desktop photo editors).
 * [key] is the stable identifier for serialization; never rename it.
 */
enum class BlendMode(val key: String, val displayName: String, val group: Int) {
    Normal("normal", "Normal", 0),
    Darken("darken", "Darken", 1),
    Multiply("multiply", "Multiply", 1),
    ColorBurn("colorBurn", "Color Burn", 1),
    Lighten("lighten", "Lighten", 2),
    Screen("screen", "Screen", 2),
    ColorDodge("colorDodge", "Color Dodge", 2),
    Overlay("overlay", "Overlay", 3),
    SoftLight("softLight", "Soft Light", 3),
    HardLight("hardLight", "Hard Light", 3),
    Difference("difference", "Difference", 4),
    Exclusion("exclusion", "Exclusion", 4),
    Hue("hue", "Hue", 5),
    Saturation("saturation", "Saturation", 5),
    Color("color", "Color", 5),
    Luminosity("luminosity", "Luminosity", 5);

    companion object {
        fun fromKey(key: String): BlendMode? = entries.firstOrNull { it.key == key }
    }
}

/** Colour description of a document. Only 8-bit sRGB exists today (M8 adds more). */
data class ColorProfileInfo(
    val name: String,
    val bitsPerChannel: Int,
    val model: String,
) {
    val label: String get() = "$model/$bitsPerChannel · $name"

    companion object {
        val SRGB_8 = ColorProfileInfo(name = "sRGB IEC61966-2.1", bitsPerChannel = 8, model = "RGB")
    }
}

/** Where a raster layer's pixels came from; shown in properties for honesty about conversions. */
data class SourceInfo(
    val displayName: String,
    val mimeType: String?,
    val originalWidth: Int,
    val originalHeight: Int,
    /** Colour space reported by the decoder before conversion to the working space. */
    val sourceColorSpace: String?,
    /** True when the image was decoded smaller than its original size (memory budget). */
    val downsampled: Boolean,
)

/**
 * A grayscale layer mask in the layer's own pixel space (same size as the layer raster), so
 * it moves and transforms with the layer. Values live in the alpha channel of a
 * [app.phopsd.engine.raster.TiledRaster]: 255 reveals, 0 conceals, in between partially.
 * Painting on a mask never touches the layer's pixels.
 */
data class LayerMask(
    val rasterId: RasterId,
    val enabled: Boolean = true,
)

/**
 * Shapes and operations that produced the current selection, kept for drawing the
 * "marching ants" outline. The authoritative selection is the raster mask.
 */
sealed interface SelectionShape {
    data class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double) : SelectionShape
    data class Ellipse(val left: Double, val top: Double, val right: Double, val bottom: Double) : SelectionShape
    /** Closed polygon (lasso); points as alternating x, y document coordinates. */
    class Polygon(val xy: DoubleArray) : SelectionShape {
        override fun equals(other: Any?) = other is Polygon && other.xy.contentEquals(xy)
        override fun hashCode() = xy.contentHashCode()
    }
}

enum class SelectionOp(val label: String) { Replace("New"), Add("Add"), Subtract("Subtract"), Intersect("Intersect") }

/** One step of how a selection was built; replaying the steps in order yields its outline. */
sealed interface OutlineStep {
    data class Shape(val op: SelectionOp, val shape: SelectionShape) : OutlineStep
    /** Select ▸ Inverse: everything on the canvas that was not selected. */
    data object Invert : OutlineStep
}

data class SelectionOutline(val steps: List<OutlineStep>) {
    fun then(step: OutlineStep): SelectionOutline =
        if (step is OutlineStep.Shape && step.op == SelectionOp.Replace) SelectionOutline(listOf(step))
        else SelectionOutline(steps + step)

    companion object {
        fun of(shape: SelectionShape) = SelectionOutline(listOf(OutlineStep.Shape(SelectionOp.Replace, shape)))
    }
}

/**
 * The active selection: a document-sized coverage raster (alpha channel, 0..255) in the
 * raster store, plus its outline. Undoable, but not saved in project files.
 */
data class Selection(val rasterId: RasterId, val outline: SelectionOutline)

/**
 * A node in the layer stack. Only raster layers exist today; groups (M6), adjustment
 * layers (M5), masks (M4), text and vector layers (M8) are deliberately not modelled
 * until they are implemented end-to-end.
 */
sealed interface Layer {
    val id: LayerId
    val name: String
    val visible: Boolean
    val locked: Boolean
    val opacity: Float
    val blendMode: BlendMode
    val typeLabel: String
}

data class RasterLayer(
    override val id: LayerId,
    override val name: String,
    val rasterId: RasterId,
    val width: Int,
    val height: Int,
    val transform: LayerTransform,
    override val visible: Boolean = true,
    override val locked: Boolean = false,
    override val opacity: Float = 1f,
    override val blendMode: BlendMode = BlendMode.Normal,
    val source: SourceInfo? = null,
    val mask: LayerMask? = null,
) : Layer {
    init {
        require(opacity in 0f..1f) { "Opacity must be within 0..1" }
        require(width > 0 && height > 0)
    }

    override val typeLabel: String get() = "Raster layer"
}

/**
 * Immutable document state. Pixel data is NOT held here: layers reference rasters by
 * [RasterId] in a [RasterStore]. This keeps documents cheap to copy, cheap to keep in
 * undo history and safe to expose as UI state.
 *
 * @property layers bottom-to-top stacking order.
 */
data class Document(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val layers: List<Layer>,
    val activeLayerId: LayerId?,
    val dpi: Double = 72.0,
    val colorProfile: ColorProfileInfo = ColorProfileInfo.SRGB_8,
    val selection: Selection? = null,
) {
    init {
        require(width > 0 && height > 0) { "Document must be at least 1x1" }
        require(layers.map { it.id }.toSet().size == layers.size) { "Layer ids must be unique" }
        require(activeLayerId == null || layers.any { it.id == activeLayerId }) { "Active layer must exist" }
    }

    fun layer(id: LayerId): Layer? = layers.firstOrNull { it.id == id }
    fun indexOf(id: LayerId): Int = layers.indexOfFirst { it.id == id }
    val activeLayer: Layer? get() = activeLayerId?.let { layer(it) }

    /** Every raster referenced: layer pixels, layer masks and the selection. */
    fun rasterIds(): Set<RasterId> {
        val out = HashSet<RasterId>()
        for (l in layers) if (l is RasterLayer) {
            out += l.rasterId
            l.mask?.let { out += it.rasterId }
        }
        selection?.let { out += it.rasterId }
        return out
    }

    /** Content identity for "unsaved changes": ignores selection and the active layer. */
    fun contentKey(): Document = copy(activeLayerId = null, selection = null)

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
