package app.phopsd.engine.geometry

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Magnetic angle snapping for touch rotation.
 *
 * While a two-finger rotation is in progress the *raw* angle follows the fingers exactly;
 * the *applied* angle snaps to the nearest multiple of [stepDegrees] whenever the raw angle
 * is within [thresholdDegrees] of it. Because snapping is evaluated from the raw angle (not
 * from the previously snapped one) the user can always twist out of a snap, and the result
 * never drifts.
 */
data class AngleSnap(
    val enabled: Boolean = true,
    val stepDegrees: Double = 45.0,
    val thresholdDegrees: Double = 5.0,
) {
    init {
        require(stepDegrees > 0.0 && stepDegrees <= 180.0) { "Snap step must be in (0, 180]" }
        require(thresholdDegrees >= 0.0 && thresholdDegrees <= stepDegrees / 2) { "Threshold must be within half a step" }
    }

    /** The snap target for [rawDegrees], or null when not within the threshold. */
    fun target(rawDegrees: Double): Double? {
        if (!enabled) return null
        val nearest = (rawDegrees / stepDegrees).roundToLong() * stepDegrees
        return if (abs(rawDegrees - nearest) <= thresholdDegrees) nearest else null
    }

    /** Angle to apply for a raw gesture angle. */
    fun apply(rawDegrees: Double): Double = target(rawDegrees) ?: rawDegrees

    companion object {
        val OFF = AngleSnap(enabled = false)

        /** Snap-step choices offered in the UI. */
        val STEPS = listOf(15.0, 45.0, 90.0)

        /** A threshold that feels "magnetic" without making nearby angles unreachable. */
        fun thresholdFor(step: Double): Double = minOf(5.0, step / 3.0)
    }
}

/**
 * Two-finger transform of a layer, computed from the gesture's *start* state so that the
 * result is exact and never accumulates error:
 *
 * the document point under the initial finger centroid ([anchor]) stays under the current
 * centroid, the layer scales by [scaleFactor] and rotates by [rotationDelta] around it.
 */
object LayerPinch {
    const val MIN_SCALE = 0.01
    const val MAX_SCALE = 100.0

    fun apply(
        start: LayerTransform,
        anchor: PointD,
        currentCentroid: PointD,
        scaleFactor: Double,
        rotationDelta: Double,
    ): LayerTransform {
        // Clamp the resulting scale magnitude, adjusting the factor so the pivot stays exact.
        val startMag = maxOf(abs(start.scaleX), abs(start.scaleY))
        val f = scaleFactor.coerceIn(MIN_SCALE / startMag, MAX_SCALE / startMag)
        val offset = Affine.rotateDegrees(rotationDelta)
            .mapVector(PointD((start.centerX - anchor.x) * f, (start.centerY - anchor.y) * f))
        return LayerTransform(
            centerX = currentCentroid.x + offset.x,
            centerY = currentCentroid.y + offset.y,
            scaleX = start.scaleX * f,
            scaleY = start.scaleY * f,
            rotationDegrees = Viewport.normalizeDegrees(start.rotationDegrees + rotationDelta),
        )
    }
}
