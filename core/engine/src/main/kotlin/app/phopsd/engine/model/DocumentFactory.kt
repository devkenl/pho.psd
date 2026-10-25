package app.phopsd.engine.model

import app.phopsd.engine.geometry.LayerTransform
import app.phopsd.engine.raster.MipChain
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.raster.TiledRaster

enum class BackgroundContents(val label: String) {
    White("White"),
    Black("Black"),
    Transparent("Transparent"),
}

/** Parameters of File ▸ New. */
data class NewDocumentSpec(
    val name: String,
    val width: Int,
    val height: Int,
    val dpi: Double = 72.0,
    val background: BackgroundContents = BackgroundContents.White,
) {
    /** Returns a reason the spec is unusable, or null when valid. */
    fun validate(): String? = when {
        name.isBlank() -> "Enter a document name"
        width < 1 || height < 1 -> "Width and height must be at least 1 px"
        width > MAX_EDGE || height > MAX_EDGE -> "Maximum edge length is $MAX_EDGE px"
        width.toLong() * height > MAX_PIXELS -> "Maximum document size is ${MAX_PIXELS / 1_000_000} megapixels"
        dpi <= 0.0 || dpi > 10_000 -> "Resolution must be between 1 and 10000 ppi"
        else -> null
    }

    companion object {
        const val MAX_EDGE = 30_000

        /** Hard ceiling; actual import limits are additionally bounded by the memory budget. */
        const val MAX_PIXELS = 200_000_000L
    }
}

data class DocumentPreset(val label: String, val width: Int, val height: Int, val dpi: Double)

object DocumentPresets {
    val all = listOf(
        DocumentPreset("HD 1920 × 1080", 1920, 1080, 72.0),
        DocumentPreset("4K UHD 3840 × 2160", 3840, 2160, 72.0),
        DocumentPreset("Square 1:1 2048", 2048, 2048, 72.0),
        DocumentPreset("Portrait 4:5 2160 × 2700", 2160, 2700, 72.0),
        DocumentPreset("A4 300 ppi", 2480, 3508, 300.0),
        DocumentPreset("Photo 3:2 6000 × 4000", 6000, 4000, 300.0),
    )
}

/** Creates documents and imported layers, registering their rasters in a [RasterStore]. */
object DocumentFactory {

    fun create(spec: NewDocumentSpec, store: RasterStore): Document {
        spec.validate()?.let { throw IllegalArgumentException(it) }
        val rasterId = RasterId.random()
        val fill = when (spec.background) {
            BackgroundContents.White -> Pixels.opaque(0xFFFFFF)
            BackgroundContents.Black -> Pixels.opaque(0x000000)
            BackgroundContents.Transparent -> Pixels.TRANSPARENT
        }
        val raster = TiledRaster.solid(spec.width, spec.height, fill)
        store.put(rasterId, MipChain.build(raster))
        val layer = RasterLayer(
            id = LayerId.random(),
            name = if (spec.background == BackgroundContents.Transparent) "Layer 1" else "Background",
            rasterId = rasterId,
            width = spec.width,
            height = spec.height,
            transform = LayerTransform.atOffset(0.0, 0.0, spec.width, spec.height),
        )
        return Document(
            id = Document.newId(),
            name = spec.name.trim(),
            width = spec.width,
            height = spec.height,
            layers = listOf(layer),
            activeLayerId = layer.id,
            dpi = spec.dpi,
        )
    }

    /** A new document whose single layer is an imported image at its native size. */
    fun fromImage(name: String, raster: MipChain, source: SourceInfo, store: RasterStore): Document {
        val rasterId = RasterId.random()
        store.put(rasterId, raster)
        val layer = RasterLayer(
            id = LayerId.random(),
            name = "Background",
            rasterId = rasterId,
            width = raster.width,
            height = raster.height,
            transform = LayerTransform.atOffset(0.0, 0.0, raster.width, raster.height),
            source = source,
        )
        return Document(
            id = Document.newId(),
            name = name,
            width = raster.width,
            height = raster.height,
            layers = listOf(layer),
            activeLayerId = layer.id,
        )
    }

    /** A new transparent raster layer covering the canvas. Costs no pixel memory until painted. */
    fun emptyLayer(doc: Document, store: RasterStore, name: String): RasterLayer {
        val rasterId = RasterId.random()
        store.put(rasterId, MipChain.single(TiledRaster.solid(doc.width, doc.height, Pixels.TRANSPARENT)))
        return RasterLayer(
            id = LayerId.random(),
            name = name,
            rasterId = rasterId,
            width = doc.width,
            height = doc.height,
            transform = LayerTransform.atOffset(0.0, 0.0, doc.width, doc.height),
        )
    }

    /**
     * Places an image as a new layer, centred on the canvas. Images larger than the canvas
     * are scaled down *non-destructively* (via the layer transform) to fit, like a desktop
     * "Place"; the original pixels stay at full resolution.
     */
    fun placedLayer(doc: Document, raster: MipChain, source: SourceInfo, store: RasterStore, name: String): RasterLayer {
        val rasterId = RasterId.random()
        store.put(rasterId, raster)
        val fit = minOf(1.0, doc.width.toDouble() / raster.width, doc.height.toDouble() / raster.height)
        return RasterLayer(
            id = LayerId.random(),
            name = name,
            rasterId = rasterId,
            width = raster.width,
            height = raster.height,
            transform = LayerTransform(
                centerX = doc.width / 2.0,
                centerY = doc.height / 2.0,
                scaleX = fit,
                scaleY = fit,
            ),
            source = source,
        )
    }
}
