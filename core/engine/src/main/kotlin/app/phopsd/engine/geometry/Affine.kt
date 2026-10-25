package app.phopsd.engine.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Immutable 2D affine transform in double precision.
 *
 * Maps (x, y) to:
 * ```
 * x' = a * x + c * y + tx
 * y' = b * x + d * y + ty
 * ```
 * This is the same layout as `android.graphics.Matrix` (MSCALE_X = a, MSKEW_X = c,
 * MTRANS_X = tx, MSKEW_Y = b, MSCALE_Y = d, MTRANS_Y = ty), which keeps the bridge to the
 * platform trivial while the engine stays platform independent.
 *
 * Composition reads left to right: `p.then(q)` applies `p` first and then `q`.
 */
data class Affine(
    val a: Double = 1.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 1.0,
    val tx: Double = 0.0,
    val ty: Double = 0.0,
) {
    fun mapX(x: Double, y: Double): Double = a * x + c * y + tx
    fun mapY(x: Double, y: Double): Double = b * x + d * y + ty
    fun map(p: PointD): PointD = PointD(mapX(p.x, p.y), mapY(p.x, p.y))

    /** Maps a direction vector (ignores translation). */
    fun mapVector(p: PointD): PointD = PointD(a * p.x + c * p.y, b * p.x + d * p.y)

    val determinant: Double get() = a * d - b * c

    /** Geometric-mean scale factor, i.e. how much a unit area's side length changes. */
    val meanScale: Double get() = sqrt(abs(determinant))

    /** Returns `this` followed by [next]. */
    fun then(next: Affine): Affine = Affine(
        a = next.a * a + next.c * b,
        b = next.b * a + next.d * b,
        c = next.a * c + next.c * d,
        d = next.b * c + next.d * d,
        tx = next.a * tx + next.c * ty + next.tx,
        ty = next.b * tx + next.d * ty + next.ty,
    )

    /** Inverse transform, or null when the transform is singular. */
    fun invert(): Affine? {
        val det = determinant
        if (abs(det) < 1e-12) return null
        val inv = 1.0 / det
        val ia = d * inv
        val ib = -b * inv
        val ic = -c * inv
        val id = a * inv
        return Affine(
            a = ia, b = ib, c = ic, d = id,
            tx = -(ia * tx + ic * ty),
            ty = -(ib * tx + id * ty),
        )
    }

    /** Axis-aligned bounds of [rect] after transformation. */
    fun mapRectBounds(rect: RectD): RectD {
        val xs = doubleArrayOf(
            mapX(rect.left, rect.top), mapX(rect.right, rect.top),
            mapX(rect.right, rect.bottom), mapX(rect.left, rect.bottom),
        )
        val ys = doubleArrayOf(
            mapY(rect.left, rect.top), mapY(rect.right, rect.top),
            mapY(rect.right, rect.bottom), mapY(rect.left, rect.bottom),
        )
        return RectD(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /** Values in android.graphics.Matrix#setValues order. */
    fun toMatrixValues(): FloatArray = floatArrayOf(
        a.toFloat(), c.toFloat(), tx.toFloat(),
        b.toFloat(), d.toFloat(), ty.toFloat(),
        0f, 0f, 1f,
    )

    companion object {
        val IDENTITY = Affine()

        fun translate(x: Double, y: Double) = Affine(tx = x, ty = y)

        fun scale(sx: Double, sy: Double = sx) = Affine(a = sx, d = sy)

        /** Rotation in degrees; positive is clockwise on a y-down screen. */
        fun rotateDegrees(degrees: Double): Affine {
            val r = Math.toRadians(degrees)
            val cs = cos(r)
            val sn = sin(r)
            return Affine(a = cs, b = sn, c = -sn, d = cs)
        }
    }
}

data class PointD(val x: Double, val y: Double)

data class RectD(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val isEmpty: Boolean get() = width <= 0.0 || height <= 0.0

    fun intersect(other: RectD): RectD = RectD(
        maxOf(left, other.left), maxOf(top, other.top),
        minOf(right, other.right), minOf(bottom, other.bottom),
    )

    fun contains(x: Double, y: Double): Boolean = x >= left && x < right && y >= top && y < bottom
}
