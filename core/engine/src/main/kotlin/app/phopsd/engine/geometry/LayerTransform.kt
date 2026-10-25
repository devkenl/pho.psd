package app.phopsd.engine.geometry

/**
 * Non-destructive placement of a layer's raster in document space.
 *
 * The raster's own pixels are never resampled when this changes; the compositor samples
 * the original pixels through the transform. The pivot is the raster centre so that
 * scale and rotation edits behave like a free-transform with a centre reference point.
 */
data class LayerTransform(
    val centerX: Double,
    val centerY: Double,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    val rotationDegrees: Double = 0.0,
) {
    init {
        require(scaleX.isFinite() && scaleY.isFinite() && scaleX != 0.0 && scaleY != 0.0) {
            "Layer scale must be finite and non-zero"
        }
        require(centerX.isFinite() && centerY.isFinite() && rotationDegrees.isFinite())
    }

    /** Maps layer pixel coordinates to document coordinates. */
    fun layerToDocument(layerWidth: Int, layerHeight: Int): Affine =
        Affine.translate(-layerWidth / 2.0, -layerHeight / 2.0)
            .then(Affine.scale(scaleX, scaleY))
            .then(Affine.rotateDegrees(rotationDegrees))
            .then(Affine.translate(centerX, centerY))

    fun documentToLayer(layerWidth: Int, layerHeight: Int): Affine =
        layerToDocument(layerWidth, layerHeight).invert()
            ?: error("Layer transform is singular")

    fun translatedBy(dx: Double, dy: Double) = copy(centerX = centerX + dx, centerY = centerY + dy)

    val isIdentityScaleAndRotation: Boolean
        get() = scaleX == 1.0 && scaleY == 1.0 && rotationDegrees == 0.0

    companion object {
        /** Places a raster with its top-left corner at ([left], [top]) at 100 % scale. */
        fun atOffset(left: Double, top: Double, layerWidth: Int, layerHeight: Int) =
            LayerTransform(centerX = left + layerWidth / 2.0, centerY = top + layerHeight / 2.0)
    }
}
