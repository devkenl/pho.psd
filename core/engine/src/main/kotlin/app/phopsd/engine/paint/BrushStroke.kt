package app.phopsd.engine.paint

import app.phopsd.engine.geometry.Affine
import app.phopsd.engine.geometry.IntRect
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.raster.TiledRaster
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Brush parameters. [size] is the diameter in **document** pixels, so a brush looks the
 * same size on screen regardless of how a layer is scaled.
 */
data class BrushSettings(
    val size: Double = 60.0,
    /** 0 = very soft edge, 1 = hard (anti-aliased) edge. */
    val hardness: Double = 0.8,
    /** Maximum stroke opacity, 0..1. Overlapping dabs never exceed it within one stroke. */
    val opacity: Double = 1.0,
    /** How quickly overlapping dabs build up towards [opacity], 0..1. */
    val flow: Double = 1.0,
    /** Distance between dabs as a fraction of the diameter. */
    val spacing: Double = 0.12,
    /** 0 = raw input, up to 0.95 = heavily smoothed (lagging) strokes. */
    val smoothing: Double = 0.3,
    val pressureSize: Boolean = true,
    val pressureOpacity: Boolean = false,
    val pressureFlow: Boolean = false,
) {
    init {
        require(size in MIN_SIZE..MAX_SIZE) { "Brush size out of range" }
        require(hardness in 0.0..1.0 && opacity in 0.0..1.0 && flow in 0.0..1.0)
        require(spacing in 0.01..2.0 && smoothing in 0.0..0.95)
    }

    companion object {
        const val MIN_SIZE = 1.0
        const val MAX_SIZE = 5000.0
        /** At zero pressure a pressure-sized brush keeps this fraction of its size. */
        const val MIN_PRESSURE_SIZE = 0.08
    }
}

enum class BrushPreset(val label: String, val settings: BrushSettings) {
    SoftRound("Soft Round", BrushSettings(size = 120.0, hardness = 0.0, flow = 1.0, spacing = 0.1)),
    HardRound("Hard Round", BrushSettings(size = 40.0, hardness = 1.0, flow = 1.0, spacing = 0.08)),
    Airbrush("Soft Airbrush", BrushSettings(size = 200.0, hardness = 0.0, flow = 0.12, spacing = 0.05, pressureFlow = true)),
    Detail("Detail (pressure)", BrushSettings(size = 12.0, hardness = 0.9, flow = 1.0, spacing = 0.06, pressureSize = true, pressureOpacity = true)),
}

/** What a stroke does to its target raster. */
sealed interface StrokeMode {
    /** Paint an opaque colour (straight ARGB, alpha ignored) onto layer pixels. */
    data class Paint(val straightRgb: Int) : StrokeMode
    /** Remove layer pixels (towards transparency). */
    data object Erase : StrokeMode
    /** Move mask values towards [value] (0 = conceal, 255 = reveal). */
    data class MaskValue(val value: Int) : StrokeMode
}

/**
 * One brush stroke on one raster, implemented with round dabs.
 *
 * Accumulation model (per pixel, per stroke): each dab proposes a target strength
 * `t = falloff × opacity`; the stroke strength `s` moves towards `t` by `flow` but only if
 * `t > s`. With flow 1 overlapping dabs never exceed the stroke opacity; low flow builds up
 * gradually like an airbrush. The output pixel is always recomputed from the *original*
 * pixel and `s`, so the result is deterministic and independent of dab order.
 *
 * The [working] raster is a copy-on-write derivative of the original: only touched tiles
 * are cloned, and the original (still referenced by undo history) is never modified.
 *
 * Input points are in document space and mapped through the layer's transform, so strokes
 * land correctly on scaled/rotated layers. An optional document-space [selection]
 * (alpha coverage) clips the stroke.
 */
class BrushStroke(
    private val original: TiledRaster,
    layerToDocument: Affine,
    private val mode: StrokeMode,
    private val settings: BrushSettings,
    private val selection: TiledRaster? = null,
) {
    val working: TiledRaster = original.derive()
    private val layerToDoc = layerToDocument
    private val docToLayer = layerToDocument.invert() ?: error("Layer transform is singular")
    /** Layer pixels per document pixel. */
    private val layerScale = docToLayer.meanScale
    private val ts = original.tileSize
    private val coverage = HashMap<Int, FloatArray>()
    private val paintR: Int
    private val paintG: Int
    private val paintB: Int

    private var hasLast = false
    private var lastX = 0.0 // layer space
    private var lastY = 0.0
    private var lastP = 1.0
    private var carry = 0.0
    private var smoothX = 0.0 // document space
    private var smoothY = 0.0
    private var rawX = 0.0
    private var rawY = 0.0
    private var rawP = 1.0

    /** Union of layer pixels changed so far. */
    var dirty: IntRect? = null
        private set

    var dabCount = 0
        private set

    init {
        require(original.isFrozen)
        val c = (mode as? StrokeMode.Paint)?.straightRgb ?: 0
        paintR = (c shr 16) and 0xFF
        paintG = (c shr 8) and 0xFF
        paintB = c and 0xFF
    }

    /** Pen down. Returns the layer-space rectangle changed by this call (or null). */
    fun begin(docX: Double, docY: Double, pressure: Double = 1.0): IntRect? {
        check(!hasLast) { "Stroke already started" }
        smoothX = docX; smoothY = docY
        rawX = docX; rawY = docY; rawP = pressure
        val lx = docToLayer.mapX(docX, docY)
        val ly = docToLayer.mapY(docX, docY)
        hasLast = true
        lastX = lx; lastY = ly; lastP = pressure.coerceIn(0.0, 1.0)
        carry = 0.0
        return dab(lx, ly, lastP)
    }

    /** Pen moved. Applies input smoothing, then lays dabs along the segment. */
    fun lineTo(docX: Double, docY: Double, pressure: Double = 1.0): IntRect? {
        check(hasLast) { "Call begin() first" }
        rawX = docX; rawY = docY; rawP = pressure
        val k = 1.0 - settings.smoothing
        smoothX += (docX - smoothX) * k
        smoothY += (docY - smoothY) * k
        return segmentTo(smoothX, smoothY, pressure.coerceIn(0.0, 1.0))
    }

    /** Pen up: catches the smoothed position up to the last real input point. */
    fun end(): IntRect? = if (hasLast && settings.smoothing > 0.0) segmentTo(rawX, rawY, rawP.coerceIn(0.0, 1.0)) else null

    /** Freezes and returns the edited raster (call after [end]). */
    fun result(): TiledRaster = working.freeze()

    private fun segmentTo(docX: Double, docY: Double, pressure: Double): IntRect? {
        val x = docToLayer.mapX(docX, docY)
        val y = docToLayer.mapY(docX, docY)
        val dx = x - lastX
        val dy = y - lastY
        val len = hypot(dx, dy)
        var changed: IntRect? = null
        if (len <= 0.0) return null
        var t = step(lastP) - carry
        while (t <= len) {
            val f = t / len
            val p = lastP + (pressure - lastP) * f
            dab(lastX + dx * f, lastY + dy * f, p)?.let { changed = it.union(changed) }
            t += step(p)
        }
        carry = len - (t - step(pressure))
        if (carry < 0) carry = 0.0
        lastX = x; lastY = y; lastP = pressure
        return changed
    }

    private fun diameterLayer(p: Double): Double {
        val sizeF = if (settings.pressureSize) BrushSettings.MIN_PRESSURE_SIZE + (1 - BrushSettings.MIN_PRESSURE_SIZE) * p else 1.0
        return max(1.0, settings.size * sizeF * layerScale)
    }

    private fun step(p: Double): Double = max(0.75, settings.spacing * diameterLayer(p))

    private fun dab(cx: Double, cy: Double, p: Double): IntRect? {
        val r = diameterLayer(p) / 2.0
        val opacity = settings.opacity * (if (settings.pressureOpacity) p else 1.0)
        val flow = settings.flow * (if (settings.pressureFlow) max(0.02, p) else 1.0)
        if (opacity <= 0.0 || flow <= 0.0) return null
        val x0 = max(0, floor(cx - r - 1).toInt())
        val y0 = max(0, floor(cy - r - 1).toInt())
        val x1 = min(original.width, ceil(cx + r + 1).toInt())
        val y1 = min(original.height, ceil(cy + r + 1).toInt())
        if (x0 >= x1 || y0 >= y1) return null
        dabCount++
        val hard = settings.hardness
        val inner = r * hard
        val soft = r - inner
        val shift = Integer.numberOfTrailingZeros(ts)
        val mask = ts - 1
        for (py in y0 until y1) {
            val dy = py + 0.5 - cy
            val ty = py shr shift
            for (px in x0 until x1) {
                val dx = px + 0.5 - cx
                val d = sqrt(dx * dx + dy * dy)
                val falloff = if (hard >= 0.999 || soft < 1.0) {
                    // Hard edge, anti-aliased over one pixel.
                    (r - d + 0.5).coerceIn(0.0, 1.0)
                } else if (d <= inner) {
                    1.0
                } else if (d >= r) {
                    0.0
                } else {
                    val u = 1.0 - (d - inner) / soft
                    u * u * (3 - 2 * u) // smoothstep
                }
                if (falloff <= 0.0) continue
                val target = (falloff * opacity).toFloat()
                val key = ty * original.tilesX + (px shr shift)
                val cov = coverage.getOrPut(key) { FloatArray(ts * ts) }
                val ci = ((py and mask) shl shift) or (px and mask)
                val s = cov[ci]
                if (target <= s) continue
                val ns = (s + (target - s) * flow.toFloat()).coerceAtMost(1f)
                cov[ci] = ns
                writePixel(px, py, ns)
            }
        }
        val rect = IntRect(x0, y0, x1, y1)
        dirty = rect.union(dirty)
        return rect
    }

    private fun writePixel(px: Int, py: Int, strength: Float) {
        var s = strength.toDouble()
        if (selection != null) {
            val dx = layerToDoc.mapX(px + 0.5, py + 0.5)
            val dy = layerToDoc.mapY(px + 0.5, py + 0.5)
            s *= Pixels.alpha(selection.pixel(floor(dx).toInt(), floor(dy).toInt())) / 255.0
        }
        val o = original.pixel(px, py)
        val oa = Pixels.alpha(o)
        val out = when (mode) {
            is StrokeMode.Paint -> {
                val inv = 1.0 - s
                Pixels.pack(
                    round255(255 * s + oa * inv),
                    round255(paintR * s + Pixels.red(o) * inv),
                    round255(paintG * s + Pixels.green(o) * inv),
                    round255(paintB * s + Pixels.blue(o) * inv),
                )
            }
            StrokeMode.Erase -> {
                val k = 1.0 - s
                Pixels.pack(round255(oa * k), round255(Pixels.red(o) * k), round255(Pixels.green(o) * k), round255(Pixels.blue(o) * k))
            }
            is StrokeMode.MaskValue -> Pixels.pack(round255(oa + (mode.value - oa) * s), 0, 0, 0)
        }
        working.setPixel(px, py, out)
    }

    private fun round255(v: Double): Int = (v + 0.5).toInt().coerceIn(0, 255)
}
