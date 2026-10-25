package app.phopsd.engine.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign

/**
 * Free-transform handles of a layer: 8 scale handles on the layer's (rotated) bounding box,
 * a rotation zone just outside it and the body for moving. All drag maths is computed from
 * the drag's *start* transform, so results are exact and do not accumulate error.
 */
object TransformHandles {

    enum class Handle(val fx: Int, val fy: Int) {
        TopLeft(-1, -1), Top(0, -1), TopRight(1, -1), Right(1, 0),
        BottomRight(1, 1), Bottom(0, 1), BottomLeft(-1, 1), Left(-1, 0),
        Rotate(0, 0), Move(0, 0);

        val isCorner: Boolean get() = fx != 0 && fy != 0
        val isScale: Boolean get() = this != Rotate && this != Move
    }

    /** Document position of a scale handle (fx, fy in -1..1 relative to the layer centre). */
    fun handlePoint(t: LayerTransform, w: Int, h: Int, fx: Int, fy: Int): PointD =
        t.layerToDocument(w, h).map(PointD(w / 2.0 * (1 + fx), h / 2.0 * (1 + fy)))

    /** The four corners in document space (TL, TR, BR, BL), for drawing the box. */
    fun corners(t: LayerTransform, w: Int, h: Int): List<PointD> =
        listOf(handlePoint(t, w, h, -1, -1), handlePoint(t, w, h, 1, -1), handlePoint(t, w, h, 1, 1), handlePoint(t, w, h, -1, 1))

    /**
     * Which handle is under a document point. [handleRadius] and [rotateRadius] are in
     * document units (callers convert from screen pixels so hit areas stay finger-sized).
     */
    fun hitTest(t: LayerTransform, w: Int, h: Int, p: PointD, handleRadius: Double, rotateRadius: Double): Handle? {
        val scaleHandles = Handle.entries.filter { it.isScale }
        var best: Handle? = null
        var bestD = Double.MAX_VALUE
        for (hd in scaleHandles) {
            val q = handlePoint(t, w, h, hd.fx, hd.fy)
            val d = hypot(q.x - p.x, q.y - p.y)
            // Corners win ties so small layers stay resizable diagonally.
            val dd = if (hd.isCorner) d * 0.9 else d
            if (d <= handleRadius && dd < bestD) { best = hd; bestD = dd }
        }
        if (best != null) return best
        val inv = t.documentToLayer(w, h)
        val lx = inv.mapX(p.x, p.y)
        val ly = inv.mapY(p.x, p.y)
        if (lx in 0.0..w.toDouble() && ly in 0.0..h.toDouble()) return Handle.Move
        // Rotation zone: near (outside) a corner.
        for (c in corners(t, w, h)) if (hypot(c.x - p.x, c.y - p.y) <= rotateRadius) return Handle.Rotate
        return null
    }

    /** Smallest allowed size of a transformed edge, in document pixels. */
    const val MIN_EXTENT = 1.0

    /**
     * Scale by dragging [handle] from [startPointer] to [pointer] (document space).
     * @param proportional keep the aspect ratio (default for corners on touch; Shift toggles).
     * @param fromCenter scale around the centre instead of the opposite handle (Alt).
     */
    fun scale(
        start: LayerTransform,
        w: Int,
        h: Int,
        handle: Handle,
        startPointer: PointD,
        pointer: PointD,
        proportional: Boolean,
        fromCenter: Boolean,
    ): LayerTransform {
        require(handle.isScale)
        val toDoc = start.layerToDocument(w, h)
        // Local (unscaled layer pixel) positions of the handle and the fixed anchor.
        val hx = w / 2.0 * (1 + handle.fx)
        val hy = h / 2.0 * (1 + handle.fy)
        val ax = if (fromCenter) w / 2.0 else w / 2.0 * (1 - handle.fx)
        val ay = if (fromCenter) h / 2.0 else h / 2.0 * (1 - handle.fy)
        val anchorDoc = toDoc.map(PointD(ax, ay))
        val handleDoc = toDoc.map(PointD(hx, hy))
        // Where the handle should go: its start position plus the pointer movement.
        val target = PointD(handleDoc.x + pointer.x - startPointer.x, handleDoc.y + pointer.y - startPointer.y)
        // Into the layer's rotated (but unscaled) frame, relative to the anchor.
        val unrot = Affine.rotateDegrees(-start.rotationDegrees)
        val u = unrot.mapVector(PointD(target.x - anchorDoc.x, target.y - anchorDoc.y))
        val lx = hx - ax
        val ly = hy - ay
        var sx = if (lx != 0.0) u.x / lx else start.scaleX
        var sy = if (ly != 0.0) u.y / ly else start.scaleY
        if (proportional) {
            val k = when {
                handle.isCorner -> {
                    // Project the movement onto the original diagonal.
                    val vx = start.scaleX * lx
                    val vy = start.scaleY * ly
                    (u.x * vx + u.y * vy) / (vx * vx + vy * vy)
                }
                handle.fx != 0 -> sx / start.scaleX
                else -> sy / start.scaleY
            }
            sx = start.scaleX * k
            sy = start.scaleY * k
        }
        sx = clampScale(sx, start.scaleX, w)
        sy = clampScale(sy, start.scaleY, h)
        // Keep the anchor fixed: centre = anchor − R·S·(anchorLocal − mid).
        val rot = Affine.rotateDegrees(start.rotationDegrees)
        val off = rot.mapVector(PointD(sx * (ax - w / 2.0), sy * (ay - h / 2.0)))
        return start.copy(centerX = anchorDoc.x - off.x, centerY = anchorDoc.y - off.y, scaleX = sx, scaleY = sy)
    }

    private fun clampScale(s: Double, fallbackSign: Double, extent: Int): Double {
        val min = MIN_EXTENT / extent
        if (!s.isFinite()) return fallbackSign
        if (abs(s) >= min) return s.coerceIn(-LayerPinch.MAX_SCALE, LayerPinch.MAX_SCALE)
        val sg = if (s != 0.0) sign(s) else sign(fallbackSign)
        return sg * min
    }

    /** Rotation by dragging around the layer centre, snapped with [snap]. */
    fun rotate(start: LayerTransform, startPointer: PointD, pointer: PointD, snap: AngleSnap): LayerTransform {
        val a0 = Math.toDegrees(atan2(startPointer.y - start.centerY, startPointer.x - start.centerX))
        val a1 = Math.toDegrees(atan2(pointer.y - start.centerY, pointer.x - start.centerX))
        val raw = Viewport.normalizeDegrees(start.rotationDegrees + (a1 - a0))
        return start.copy(rotationDegrees = Viewport.normalizeDegrees(snap.apply(raw)))
    }

    fun move(start: LayerTransform, startPointer: PointD, pointer: PointD): LayerTransform =
        start.translatedBy(pointer.x - startPointer.x, pointer.y - startPointer.y)
}

/** Interactive crop rectangle maths (document space, axis-aligned). */
object CropBox {

    /** Aspect presets offered in the crop options (null ratio = free). */
    enum class Aspect(val label: String, val ratio: Double?) {
        Free("Free", null), Original("Original", null), Square("1:1", 1.0),
        R4x5("4:5", 4.0 / 5.0), R3x2("3:2", 3.0 / 2.0), R16x9("16:9", 16.0 / 9.0),
    }

    fun ratioFor(aspect: Aspect, docW: Int, docH: Int): Double? =
        if (aspect == Aspect.Original) docW.toDouble() / docH else aspect.ratio

    /** The largest centred rectangle of [ratio] inside the canvas (initial crop box). */
    fun initial(docW: Int, docH: Int, ratio: Double?): RectD {
        if (ratio == null) return RectD(0.0, 0.0, docW.toDouble(), docH.toDouble())
        var w = docW.toDouble()
        var h = w / ratio
        if (h > docH) { h = docH.toDouble(); w = h * ratio }
        val l = (docW - w) / 2
        val t = (docH - h) / 2
        return RectD(l, t, l + w, t + h)
    }

    /**
     * Drags [handle] (fx, fy as in [TransformHandles.Handle]; Move translates) by ([dx], [dy])
     * from [start]. With a [ratio] the box keeps that aspect.
     */
    fun drag(start: RectD, handle: TransformHandles.Handle, dx: Double, dy: Double, ratio: Double?): RectD {
        if (handle == TransformHandles.Handle.Move) return RectD(start.left + dx, start.top + dy, start.right + dx, start.bottom + dy)
        var l = start.left; var t = start.top; var r = start.right; var b = start.bottom
        if (handle.fx < 0) l += dx else if (handle.fx > 0) r += dx
        if (handle.fy < 0) t += dy else if (handle.fy > 0) b += dy
        // Normalise inverted drags.
        if (r - l < MIN) { if (handle.fx < 0) l = r - MIN else r = l + MIN }
        if (b - t < MIN) { if (handle.fy < 0) t = b - MIN else b = t + MIN }
        if (ratio != null) {
            var w = r - l
            var h = b - t
            when {
                handle.fx != 0 && handle.fy != 0 -> if (w / h > ratio) h = w / ratio else w = h * ratio
                handle.fx != 0 -> h = w / ratio
                else -> w = h * ratio
            }
            w = max(w, MIN); h = max(h, MIN)
            // Anchor the opposite side (centre for edge handles on the free axis).
            if (handle.fx < 0) l = r - w else if (handle.fx > 0) r = l + w else { val cx = (start.left + start.right) / 2; l = cx - w / 2; r = cx + w / 2 }
            if (handle.fy < 0) t = b - h else if (handle.fy > 0) b = t + h else { val cy = (start.top + start.bottom) / 2; t = cy - h / 2; b = cy + h / 2 }
        }
        return RectD(l, t, r, b)
    }

    /** Whole-pixel crop rectangle for committing. */
    fun toPixels(r: RectD): IntRect {
        val l = Math.round(r.left).toInt()
        val t = Math.round(r.top).toInt()
        val w = max(1, Math.round(r.width).toInt())
        val h = max(1, Math.round(r.height).toInt())
        return IntRect(l, t, l + w, t + h)
    }

    private const val MIN = 1.0
}
