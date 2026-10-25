package app.phopsd.editor

import app.phopsd.engine.geometry.CropBox
import app.phopsd.engine.geometry.PointD
import app.phopsd.engine.geometry.RectD
import app.phopsd.engine.model.SelectionOp
import app.phopsd.engine.model.SelectionShape
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Foreground/background colours as straight 0xRRGGBB. */
data class ColorPair(val foreground: Int = 0x000000, val background: Int = 0xFFFFFF) {
    fun swapped() = ColorPair(background, foreground)

    companion object {
        /** Mask value (0 conceals, 255 reveals) for a colour: its luminance, like painting a grayscale mask. */
        fun maskValue(rgb: Int): Int {
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            return ((r * 299 + g * 587 + b * 114 + 500) / 1000).coerceIn(0, 255)
        }
    }
}

/** Where brush strokes land on the active layer. */
enum class PaintTarget(val label: String) { Pixels("Pixels"), Mask("Mask") }

/** How a layer mask is shown while editing. */
enum class MaskView(val label: String) { Alone("Mask alone"), Overlay("Overlay") }

/** A selection being drawn (not yet part of the document). Coordinates are document space. */
sealed interface SelectionDraft {
    val op: SelectionOp

    /** The shape to commit, or null when it is too small to mean anything (a click). */
    fun shape(): SelectionShape?

    data class Box(
        val ellipse: Boolean,
        val startX: Double,
        val startY: Double,
        val endX: Double,
        val endY: Double,
        override val op: SelectionOp,
        /** Constrain to a square/circle (Shift). */
        val square: Boolean = false,
        /** Draw from the centre (Alt). */
        val fromCenter: Boolean = false,
    ) : SelectionDraft {
        val rect: RectD
            get() {
                var dx = endX - startX
                var dy = endY - startY
                if (square) {
                    val m = max(abs(dx), abs(dy))
                    dx = if (dx < 0) -m else m
                    dy = if (dy < 0) -m else m
                }
                return if (fromCenter) RectD(startX - abs(dx), startY - abs(dy), startX + abs(dx), startY + abs(dy))
                else RectD(min(startX, startX + dx), min(startY, startY + dy), max(startX, startX + dx), max(startY, startY + dy))
            }

        override fun shape(): SelectionShape? {
            val r = rect
            if (r.width < MIN_EXTENT || r.height < MIN_EXTENT) return null
            return if (ellipse) SelectionShape.Ellipse(r.left, r.top, r.right, r.bottom)
            else SelectionShape.Rect(r.left, r.top, r.right, r.bottom)
        }
    }

    /** Freehand lasso ([closedByRelease] = true) or polygonal lasso (points added by taps). */
    data class Lasso(
        val points: List<PointD>,
        override val op: SelectionOp,
        val polygonal: Boolean,
        /** Rubber-band end of a polygonal lasso (pointer position), not yet a vertex. */
        val cursor: PointD? = null,
    ) : SelectionDraft {
        override fun shape(): SelectionShape? {
            if (points.size < 3) return null
            var area = 0.0
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                area += a.x * b.y - b.x * a.y
            }
            if (abs(area) / 2 < MIN_EXTENT * MIN_EXTENT) return null
            return SelectionShape.Polygon(DoubleArray(points.size * 2) { if (it % 2 == 0) points[it / 2].x else points[it / 2].y })
        }
    }

    companion object {
        /** Selections smaller than this (document px) are treated as a click. */
        const val MIN_EXTENT = 1.0
    }
}

/** An interactive crop (Crop tool). Committed with Enter / the ✓ button. */
data class CropSession(val rect: RectD, val aspect: CropBox.Aspect)

/** Content choices of Edit ▸ Fill. */
enum class FillContent(val label: String) {
    Foreground("Foreground color"), Background("Background color"), Black("Black"), White("White"), Gray("50% Gray");

    fun rgb(colors: ColorPair): Int = when (this) {
        Foreground -> colors.foreground
        Background -> colors.background
        Black -> 0x000000
        White -> 0xFFFFFF
        Gray -> 0x808080
    }
}

/** Which colour the colour picker edits. */
enum class ColorSlot { Foreground, Background }

/** A region of the document whose pixels changed during a live stroke (for partial redraws). */
data class StrokeFrame(val revision: Long, val dirtyDoc: RectD)
