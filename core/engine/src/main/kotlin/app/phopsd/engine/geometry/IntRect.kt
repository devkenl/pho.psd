package app.phopsd.engine.geometry

import kotlin.math.ceil
import kotlin.math.floor

/** Half-open integer rectangle [left, right) × [top, bottom). */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun union(o: IntRect?): IntRect =
        if (o == null || o.isEmpty) this else if (isEmpty) o
        else IntRect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))

    fun intersect(o: IntRect): IntRect =
        IntRect(maxOf(left, o.left), maxOf(top, o.top), minOf(right, o.right), minOf(bottom, o.bottom))

    fun toRectD(): RectD = RectD(left.toDouble(), top.toDouble(), right.toDouble(), bottom.toDouble())

    companion object {
        /** Smallest integer rectangle containing [r]. */
        fun enclosing(r: RectD): IntRect =
            IntRect(floor(r.left).toInt(), floor(r.top).toInt(), ceil(r.right).toInt(), ceil(r.bottom).toInt())
    }
}
