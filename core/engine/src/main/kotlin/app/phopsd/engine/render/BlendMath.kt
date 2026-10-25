package app.phopsd.engine.render

import app.phopsd.engine.model.BlendMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Blend functions B(Cb, Cs) from W3C Compositing and Blending Level 1, operating on
 * *straight* (unpremultiplied) colour components in 0..1. The compositor combines B with
 * Porter-Duff source-over:
 *
 *   co = (1 - αb)·cs + (1 - αs)·cb + αs·αb·B(Cb, Cs)
 *   αo = αs + αb·(1 - αs)
 *
 * where cs/cb are premultiplied. Note: some desktop editors use slightly different
 * Soft Light curves; pho.psd intentionally implements the published W3C formula.
 */
object BlendMath {

    fun isSeparable(mode: BlendMode): Boolean = when (mode) {
        BlendMode.Hue, BlendMode.Saturation, BlendMode.Color, BlendMode.Luminosity -> false
        else -> true
    }

    /** Separable blend of one channel. */
    fun separable(mode: BlendMode, cb: Float, cs: Float): Float = when (mode) {
        BlendMode.Normal -> cs
        BlendMode.Multiply -> cb * cs
        BlendMode.Screen -> screen(cb, cs)
        BlendMode.Overlay -> hardLight(cs, cb)
        BlendMode.Darken -> min(cb, cs)
        BlendMode.Lighten -> max(cb, cs)
        BlendMode.ColorDodge -> when {
            cb <= 0f -> 0f
            cs >= 1f -> 1f
            else -> min(1f, cb / (1f - cs))
        }
        BlendMode.ColorBurn -> when {
            cb >= 1f -> 1f
            cs <= 0f -> 0f
            else -> 1f - min(1f, (1f - cb) / cs)
        }
        BlendMode.HardLight -> hardLight(cb, cs)
        BlendMode.SoftLight -> softLight(cb, cs)
        BlendMode.Difference -> abs(cb - cs)
        BlendMode.Exclusion -> cb + cs - 2f * cb * cs
        BlendMode.Hue, BlendMode.Saturation, BlendMode.Color, BlendMode.Luminosity ->
            throw IllegalArgumentException("$mode is not separable")
    }

    private fun screen(cb: Float, cs: Float) = cb + cs - cb * cs

    private fun hardLight(cb: Float, cs: Float): Float =
        if (cs <= 0.5f) cb * (2f * cs) else screen(cb, 2f * cs - 1f)

    private fun softLight(cb: Float, cs: Float): Float {
        if (cs <= 0.5f) return cb - (1f - 2f * cs) * cb * (1f - cb)
        val d = if (cb <= 0.25f) ((16f * cb - 12f) * cb + 4f) * cb else sqrt(cb)
        return cb + (2f * cs - 1f) * (d - cb)
    }

    /**
     * Non-separable modes. Writes the blended straight RGB into [out] (size >= 3).
     */
    fun nonSeparable(
        mode: BlendMode,
        br: Float, bg: Float, bb: Float,
        sr: Float, sg: Float, sb: Float,
        out: FloatArray,
    ) {
        when (mode) {
            BlendMode.Hue -> {
                setSat(sr, sg, sb, sat(br, bg, bb), out)
                setLum(out[0], out[1], out[2], lum(br, bg, bb), out)
            }
            BlendMode.Saturation -> {
                setSat(br, bg, bb, sat(sr, sg, sb), out)
                setLum(out[0], out[1], out[2], lum(br, bg, bb), out)
            }
            BlendMode.Color -> setLum(sr, sg, sb, lum(br, bg, bb), out)
            BlendMode.Luminosity -> setLum(br, bg, bb, lum(sr, sg, sb), out)
            else -> throw IllegalArgumentException("$mode is separable")
        }
    }

    fun lum(r: Float, g: Float, b: Float): Float = 0.3f * r + 0.59f * g + 0.11f * b

    fun sat(r: Float, g: Float, b: Float): Float = max(r, max(g, b)) - min(r, min(g, b))

    fun setLum(r: Float, g: Float, b: Float, l: Float, out: FloatArray) {
        val d = l - lum(r, g, b)
        clipColor(r + d, g + d, b + d, out)
    }

    private fun clipColor(r: Float, g: Float, b: Float, out: FloatArray) {
        val l = lum(r, g, b)
        val n = min(r, min(g, b))
        val x = max(r, max(g, b))
        var cr = r
        var cg = g
        var cb = b
        if (n < 0f) {
            val k = l - n
            cr = l + (cr - l) * l / k
            cg = l + (cg - l) * l / k
            cb = l + (cb - l) * l / k
        }
        if (x > 1f) {
            val k = x - l
            cr = l + (cr - l) * (1f - l) / k
            cg = l + (cg - l) * (1f - l) / k
            cb = l + (cb - l) * (1f - l) / k
        }
        out[0] = cr
        out[1] = cg
        out[2] = cb
    }

    /** SetSat from the W3C spec: rescales so max-min equals [s], preserving channel order. */
    fun setSat(r: Float, g: Float, b: Float, s: Float, out: FloatArray) {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val range = mx - mn
        fun f(c: Float): Float = when {
            range <= 0f -> 0f
            else -> (c - mn) * s / range
        }
        out[0] = f(r)
        out[1] = f(g)
        out[2] = f(b)
    }
}
