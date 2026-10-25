package app.phopsd.engine.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The single source of truth for **screen <-> document** mapping.
 *
 * Coordinate spaces used across pho.psd:
 *  - *screen space*: pixels of the canvas view, origin at its top-left, y down.
 *  - *document space*: document pixels, origin at the canvas top-left, y down.
 *    Pixel (i, j) covers the square [i, i+1) x [j, j+1).
 *  - *layer space*: pixels of one layer's raster; mapped into document space by
 *    [LayerTransform].
 *
 * Every tool converts input through this class; no tool keeps its own zoom logic.
 *
 * @property zoom screen pixels per document pixel (1.0 = actual pixels).
 * @property centerX document x shown at the centre of the view.
 * @property centerY document y shown at the centre of the view.
 * @property rotationDegrees view rotation; does not touch document pixels.
 */
data class Viewport(
    val viewWidth: Double,
    val viewHeight: Double,
    val docWidth: Int,
    val docHeight: Int,
    val zoom: Double = 1.0,
    val centerX: Double = docWidth / 2.0,
    val centerY: Double = docHeight / 2.0,
    val rotationDegrees: Double = 0.0,
) {
    val documentToScreen: Affine
        get() = Affine.translate(-centerX, -centerY)
            .then(Affine.scale(zoom))
            .then(Affine.rotateDegrees(rotationDegrees))
            .then(Affine.translate(viewWidth / 2.0, viewHeight / 2.0))

    val screenToDocument: Affine
        get() = Affine.translate(-viewWidth / 2.0, -viewHeight / 2.0)
            .then(Affine.rotateDegrees(-rotationDegrees))
            .then(Affine.scale(1.0 / zoom))
            .then(Affine.translate(centerX, centerY))

    fun toDocument(screenX: Double, screenY: Double): PointD =
        screenToDocument.map(PointD(screenX, screenY))

    fun toScreen(docX: Double, docY: Double): PointD =
        documentToScreen.map(PointD(docX, docY))

    /** Zoom by [factor] keeping the document point under ([focusX], [focusY]) fixed on screen. */
    fun zoomAt(focusX: Double, focusY: Double, factor: Double): Viewport {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val anchor = toDocument(focusX, focusY)
        return anchored(anchor, focusX, focusY, newZoom, rotationDegrees)
    }

    /** Sets an absolute zoom around a screen focus point (defaults to the view centre). */
    fun withZoom(newZoom: Double, focusX: Double = viewWidth / 2, focusY: Double = viewHeight / 2): Viewport =
        zoomAt(focusX, focusY, newZoom / zoom)

    /** Pans by a screen-space delta: content follows the finger. */
    fun panByScreen(dx: Double, dy: Double): Viewport {
        val docDelta = Affine.rotateDegrees(-rotationDegrees).mapVector(PointD(dx, dy))
        return copy(centerX = centerX - docDelta.x / zoom, centerY = centerY - docDelta.y / zoom)
    }

    /** Rotates the view by [deltaDegrees] around a screen focus point. */
    fun rotateAt(focusX: Double, focusY: Double, deltaDegrees: Double): Viewport {
        val anchor = toDocument(focusX, focusY)
        return anchored(anchor, focusX, focusY, zoom, normalizeDegrees(rotationDegrees + deltaDegrees))
    }

    /** Fit the whole (possibly rotated) document into the view with [marginPx] on each side. */
    fun fitToView(marginPx: Double = 24.0): Viewport {
        if (viewWidth <= 0 || viewHeight <= 0 || docWidth <= 0 || docHeight <= 0) return this
        val r = Math.toRadians(rotationDegrees)
        val cs = abs(cos(r))
        val sn = abs(sin(r))
        val bw = docWidth * cs + docHeight * sn
        val bh = docWidth * sn + docHeight * cs
        val availW = (viewWidth - 2 * marginPx).coerceAtLeast(1.0)
        val availH = (viewHeight - 2 * marginPx).coerceAtLeast(1.0)
        val z = min(availW / bw, availH / bh).coerceIn(MIN_ZOOM, MAX_ZOOM)
        return copy(zoom = z, centerX = docWidth / 2.0, centerY = docHeight / 2.0)
    }

    /** 100 % zoom (one document pixel per screen pixel) around the view centre. */
    fun actualPixels(): Viewport = withZoom(1.0)

    fun resetRotation(): Viewport = rotateAt(viewWidth / 2, viewHeight / 2, -rotationDegrees)

    fun withViewSize(width: Double, height: Double): Viewport = copy(viewWidth = width, viewHeight = height)

    /** Document-space bounds of the visible screen area, clipped to the document. */
    fun visibleDocumentRect(): RectD =
        screenToDocument.mapRectBounds(RectD(0.0, 0.0, viewWidth, viewHeight))
            .intersect(RectD(0.0, 0.0, docWidth.toDouble(), docHeight.toDouble()))

    private fun anchored(anchor: PointD, sx: Double, sy: Double, z: Double, rot: Double): Viewport {
        // screen = R * z * (doc - c) + viewCentre  =>  c = doc - R^-1 (screen - viewCentre) / z
        val v = Affine.rotateDegrees(-rot).mapVector(PointD(sx - viewWidth / 2, sy - viewHeight / 2))
        return copy(
            zoom = z,
            rotationDegrees = rot,
            centerX = anchor.x - v.x / z,
            centerY = anchor.y - v.y / z,
        )
    }

    companion object {
        const val MIN_ZOOM = 0.01
        const val MAX_ZOOM = 64.0

        /** Standard zoom stops used by zoom in / zoom out commands. */
        val ZOOM_STOPS = doubleArrayOf(
            0.01, 0.02, 0.0333, 0.05, 0.0667, 0.0833, 0.125, 0.1667, 0.25, 0.3333, 0.5, 0.6667,
            1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 12.0, 16.0, 24.0, 32.0, 64.0,
        )

        fun nextZoomStop(current: Double): Double =
            ZOOM_STOPS.firstOrNull { it > current * 1.001 } ?: MAX_ZOOM

        fun previousZoomStop(current: Double): Double =
            ZOOM_STOPS.lastOrNull { it < current / 1.001 } ?: MIN_ZOOM

        fun normalizeDegrees(deg: Double): Double {
            var r = deg % 360.0
            if (r > 180.0) r -= 360.0
            if (r <= -180.0) r += 360.0
            return r
        }
    }
}
