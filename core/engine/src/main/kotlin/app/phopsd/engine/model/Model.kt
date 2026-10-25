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
) {
    init {
        require(width > 0 && height > 0) { "Document must be at least 1x1" }
        require(layers.map { it.id }.toSet().size == layers.size) { "Layer ids must be unique" }
        require(activeLayerId == null || layers.any { it.id == activeLayerId }) { "Active layer must exist" }
    }

    fun layer(id: LayerId): Layer? = layers.firstOrNull { it.id == id }
    fun indexOf(id: LayerId): Int = layers.indexOfFirst { it.id == id }
    val activeLayer: Layer? get() = activeLayerId?.let { layer(it) }

    fun rasterIds(): Set<RasterId> = layers.mapNotNull { (it as? RasterLayer)?.rasterId }.toSet()

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
