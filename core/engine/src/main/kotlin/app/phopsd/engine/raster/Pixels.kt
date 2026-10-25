package app.phopsd.engine.raster

/**
 * Packed 8-bit ARGB helpers.
 *
 * Engine convention: **all stored raster data is premultiplied ARGB** packed as
 * 0xAARRGGBB. Android's `Bitmap.getPixels/setPixels` use *straight* (unpremultiplied)
 * colour ints, so conversion happens exactly once at the import/display/export boundary.
 * Colour values are sRGB-encoded (not linear light); see ARCHITECTURE.md "Colour".
 */
object Pixels {
    const val TRANSPARENT: Int = 0

    fun alpha(p: Int): Int = p ushr 24
    fun red(p: Int): Int = (p shr 16) and 0xFF
    fun green(p: Int): Int = (p shr 8) and 0xFF
    fun blue(p: Int): Int = p and 0xFF

    fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    /** Straight -> premultiplied, rounding to nearest. */
    fun premultiply(straight: Int): Int {
        val a = alpha(straight)
        if (a == 255) return straight
        if (a == 0) return 0
        return pack(
            a,
            mulDiv255(red(straight), a),
            mulDiv255(green(straight), a),
            mulDiv255(blue(straight), a),
        )
    }

    /** Premultiplied -> straight, rounding to nearest. Fully transparent maps to 0. */
    fun unpremultiply(premul: Int): Int {
        val a = alpha(premul)
        if (a == 255) return premul
        if (a == 0) return 0
        val half = a / 2
        return pack(
            a,
            ((red(premul) * 255 + half) / a).coerceAtMost(255),
            ((green(premul) * 255 + half) / a).coerceAtMost(255),
            ((blue(premul) * 255 + half) / a).coerceAtMost(255),
        )
    }

    /** Round(x * y / 255) for x, y in 0..255. */
    fun mulDiv255(x: Int, y: Int): Int {
        val t = x * y + 128
        return (t + (t shr 8)) shr 8
    }

    /** In-place conversion of a premultiplied buffer to straight ARGB (for Bitmap.setPixels). */
    fun unpremultiplyInPlace(buffer: IntArray, from: Int = 0, to: Int = buffer.size) {
        for (i in from until to) buffer[i] = unpremultiply(buffer[i])
    }

    /** Opaque straight colour from 0xRRGGBB. */
    fun opaque(rgb: Int): Int = rgb or (0xFF shl 24)
}
