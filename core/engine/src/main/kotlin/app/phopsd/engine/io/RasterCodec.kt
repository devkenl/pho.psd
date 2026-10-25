package app.phopsd.engine.io

import app.phopsd.engine.raster.TiledRaster
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

class RasterFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Lossless binary encoding of a [TiledRaster] ("PRAS" v1), used for rasters inside
 * `.phopsd` projects. It stores exactly the engine's in-memory representation
 * (premultiplied 8-bit ARGB tiles), so a save/reopen round trip is bit-identical.
 *
 * ```
 * "PRAS"  u16 version=1  u16 flags=0
 * i32 width  i32 height  i32 tileSize  i32 fill(premul ARGB)  i32 tileCount
 * tileCount × { i32 tx  i32 ty  i32 payloadLength  payload }
 * "END!"
 * ```
 * Payload = DEFLATE of the tile's ARGB bytes after a per-row "Sub" predictor (each byte
 * minus the same channel of the pixel to its left, as in PNG), which typically makes
 * photographic tiles markedly smaller. Unwritten tiles (solid [TiledRaster.fill]) are not
 * stored at all. All big-endian. Integrity is checked by the project container (SHA-256).
 */
object RasterCodec {
    const val VERSION = 1
    private const val MAGIC = 0x50524153 // "PRAS"
    private const val END = 0x454E4421 // "END!"
    const val MAX_EDGE = 65_536

    fun encode(raster: TiledRaster, out: OutputStream, level: Int = Deflater.BEST_SPEED, isCancelled: () -> Boolean = { false }) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC)
        d.writeShort(VERSION)
        d.writeShort(0)
        d.writeInt(raster.width)
        d.writeInt(raster.height)
        d.writeInt(raster.tileSize)
        d.writeInt(raster.fill)
        val coords = ArrayList<IntArray>()
        for (ty in 0 until raster.tilesY) for (tx in 0 until raster.tilesX) {
            if (raster.tileOrNull(tx, ty) != null) coords += intArrayOf(tx, ty)
        }
        d.writeInt(coords.size)
        val ts = raster.tileSize
        val raw = ByteArray(ts * ts * 4)
        val deflater = Deflater(level)
        var buf = ByteArray(raw.size + raw.size / 8 + 64)
        try {
            for ((tx, ty) in coords.map { it[0] to it[1] }) {
                if (isCancelled()) throw InterruptedException("Save cancelled")
                filter(raster.tileOrNull(tx, ty)!!, ts, raw)
                deflater.reset()
                deflater.setInput(raw)
                deflater.finish()
                var len = 0
                while (!deflater.finished()) {
                    if (len == buf.size) buf = buf.copyOf(buf.size * 2)
                    len += deflater.deflate(buf, len, buf.size - len)
                }
                d.writeInt(tx)
                d.writeInt(ty)
                d.writeInt(len)
                d.write(buf, 0, len)
            }
        } finally {
            deflater.end()
        }
        d.writeInt(END)
        d.flush()
    }

    /** Decodes a raster; the result is frozen. Throws [RasterFormatException] on any inconsistency. */
    fun decode(input: InputStream, isCancelled: () -> Boolean = { false }): TiledRaster {
        val d = DataInputStream(input)
        try {
            if (d.readInt() != MAGIC) throw RasterFormatException("Not a pho.psd raster (bad magic)")
            val version = d.readUnsignedShort()
            if (version > VERSION) throw RasterFormatException("Raster format v$version is newer than this app supports (v$VERSION)")
            d.readUnsignedShort() // flags, reserved
            val w = d.readInt()
            val h = d.readInt()
            val ts = d.readInt()
            val fill = d.readInt()
            if (w !in 1..MAX_EDGE || h !in 1..MAX_EDGE) throw RasterFormatException("Invalid raster size ${w}x$h")
            if (ts < 16 || ts > 4096 || (ts and (ts - 1)) != 0) throw RasterFormatException("Invalid tile size $ts")
            val raster = TiledRaster(w, h, ts, fill)
            val count = d.readInt()
            if (count < 0 || count > raster.tilesX * raster.tilesY) throw RasterFormatException("Invalid tile count $count")
            val raw = ByteArray(ts * ts * 4)
            val seen = HashSet<Int>()
            val inflater = Inflater()
            try {
                repeat(count) {
                    if (isCancelled()) throw InterruptedException("Open cancelled")
                    val tx = d.readInt()
                    val ty = d.readInt()
                    val len = d.readInt()
                    if (tx !in 0 until raster.tilesX || ty !in 0 until raster.tilesY) throw RasterFormatException("Tile ($tx,$ty) out of range")
                    if (!seen.add(ty * raster.tilesX + tx)) throw RasterFormatException("Duplicate tile ($tx,$ty)")
                    if (len <= 0 || len > raw.size * 2 + 1024) throw RasterFormatException("Invalid tile payload length $len")
                    val payload = ByteArray(len)
                    d.readFully(payload)
                    inflater.reset()
                    inflater.setInput(payload)
                    var n = 0
                    while (n < raw.size && !inflater.finished()) {
                        val got = inflater.inflate(raw, n, raw.size - n)
                        if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                        n += got
                    }
                    if (n != raw.size || !inflater.finished()) throw RasterFormatException("Tile ($tx,$ty) has wrong decompressed size")
                    raster.putTile(tx, ty, unfilter(raw, ts))
                }
            } finally {
                inflater.end()
            }
            if (d.readInt() != END) throw RasterFormatException("Raster data is truncated or corrupted (missing end marker)")
            return raster.freeze()
        } catch (e: EOFException) {
            throw RasterFormatException("Raster data is truncated", e)
        } catch (e: DataFormatException) {
            throw RasterFormatException("Raster data is corrupted", e)
        }
    }

    private fun filter(tile: IntArray, ts: Int, out: ByteArray) {
        var o = 0
        for (y in 0 until ts) {
            var pa = 0; var pr = 0; var pg = 0; var pb = 0
            val base = y * ts
            for (x in 0 until ts) {
                val p = tile[base + x]
                val a = p ushr 24
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                out[o] = (a - pa).toByte()
                out[o + 1] = (r - pr).toByte()
                out[o + 2] = (g - pg).toByte()
                out[o + 3] = (b - pb).toByte()
                o += 4
                pa = a; pr = r; pg = g; pb = b
            }
        }
    }

    private fun unfilter(raw: ByteArray, ts: Int): IntArray {
        val tile = IntArray(ts * ts)
        var o = 0
        for (y in 0 until ts) {
            var a = 0; var r = 0; var g = 0; var b = 0
            val base = y * ts
            for (x in 0 until ts) {
                a = (a + raw[o]) and 0xFF
                r = (r + raw[o + 1]) and 0xFF
                g = (g + raw[o + 2]) and 0xFF
                b = (b + raw[o + 3]) and 0xFF
                tile[base + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                o += 4
            }
        }
        return tile
    }
}
