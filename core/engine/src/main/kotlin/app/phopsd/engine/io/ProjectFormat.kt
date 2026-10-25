package app.phopsd.engine.io

import app.phopsd.engine.geometry.LayerTransform
import app.phopsd.engine.model.BlendMode
import app.phopsd.engine.model.ColorProfileInfo
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.Layer
import app.phopsd.engine.model.LayerId
import app.phopsd.engine.model.RasterId
import app.phopsd.engine.model.RasterLayer
import app.phopsd.engine.model.RasterLookup
import app.phopsd.engine.model.SourceInfo
import app.phopsd.engine.raster.TiledRaster
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The native `.phopsd` project container (format v1).
 *
 * A ZIP archive:
 * ```
 * mimetype              "application/x-phopsd" (first entry, stored uncompressed)
 * rasters/<id>.pras     one RasterCodec file per distinct raster (shared by duplicates)
 * thumbnail.png         optional composite preview (written by the platform layer)
 * manifest.json         document, layers, transforms, colour metadata, raster table with
 *                       SHA-256 + byte length of every raster entry
 * ```
 * Integrity: ZIP CRC-32 per entry (checked while reading) plus SHA-256 per raster recorded
 * in the manifest. A manifest that references a missing or mismatching raster is rejected,
 * never "repaired" silently.
 *
 * Compatibility: `formatVersion` is the version that wrote the file; `minReaderVersion` is
 * the oldest reader that can load it without losing data. Readers reject files whose
 * `minReaderVersion` exceeds [FORMAT_VERSION]. Older files are upgraded by [migrate].
 */
object ProjectFormat {
    const val FORMAT_VERSION = 1
    const val MIN_READER_VERSION = 1
    const val MIME_TYPE = "application/x-phopsd"
    const val EXTENSION = "phopsd"
    const val MIME_ENTRY = "mimetype"
    const val MANIFEST_ENTRY = "manifest.json"
    const val THUMBNAIL_ENTRY = "thumbnail.png"
    const val RASTER_DIR = "rasters/"
    const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
    const val MAX_THUMBNAIL_BYTES = 8 * 1024 * 1024

    /** Upgrades an older manifest to the current structure. v1 is the first version. */
    fun migrate(manifest: Json.Obj, fromVersion: Int): Json.Obj = when (fromVersion) {
        1 -> manifest
        else -> throw ProjectFormatException("Unsupported project format version $fromVersion")
    }
}

class ProjectFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class SaveInfo(val appVersion: String, val savedAtMillis: Long)

data class WriteResult(val rasterCount: Int, val rasterBytes: Long)

class LoadedProject(
    val document: Document,
    val rasters: Map<RasterId, TiledRaster>,
    val savedAtMillis: Long?,
    val appVersion: String?,
    val formatVersion: Int,
    /** Non-fatal issues (e.g. unknown blend mode replaced by Normal), shown to the user. */
    val warnings: List<String>,
    val thumbnailPng: ByteArray?,
)

data class VerifyResult(val formatVersion: Int, val layerCount: Int, val rasterCount: Int)

// ====================================================================== writer

object ProjectWriter {

    /**
     * Streams [doc] into [out] as a `.phopsd` archive. Only pixel data reachable from the
     * document is written; rasters shared between layers are written once. Does not close
     * [out]. Callers implement atomicity (write to a temp file, verify, then replace).
     */
    fun write(
        doc: Document,
        rasters: RasterLookup,
        out: OutputStream,
        info: SaveInfo,
        thumbnailPng: ByteArray? = null,
        isCancelled: () -> Boolean = { false },
    ): WriteResult {
        val zip = ZipOutputStream(NonClosingOutputStream(out))
        // 1) mimetype first, stored, so the file is identifiable by its leading bytes.
        val mimeBytes = ProjectFormat.MIME_TYPE.toByteArray(Charsets.US_ASCII)
        zip.putNextEntry(storedEntry(ProjectFormat.MIME_ENTRY, mimeBytes))
        zip.write(mimeBytes)
        zip.closeEntry()

        // 2) rasters, hashing while writing.
        val table = ArrayList<Json>()
        var total = 0L
        val written = LinkedHashSet<RasterId>()
        zip.setLevel(Deflater.NO_COMPRESSION) // PRAS payloads are already deflated
        for (layer in doc.layers) {
            val rl = layer as? RasterLayer ?: continue
            if (!written.add(rl.rasterId)) continue
            val raster = rasters.mips(rl.rasterId)?.base
                ?: throw ProjectFormatException("Pixel data for layer “${rl.name}” is missing")
            if (isCancelled()) throw InterruptedException("Save cancelled")
            val path = "${ProjectFormat.RASTER_DIR}${rl.rasterId.value}.pras"
            zip.putNextEntry(ZipEntry(path))
            val digest = MessageDigest.getInstance("SHA-256")
            val counting = HashingOutputStream(NonClosingOutputStream(zip), digest)
            RasterCodec.encode(raster, counting, isCancelled = isCancelled)
            counting.flush()
            zip.closeEntry()
            total += counting.count
            table += Json.obj(
                "id" to Json.str(rl.rasterId.value),
                "path" to Json.str(path),
                "width" to Json.num(raster.width),
                "height" to Json.num(raster.height),
                "bytes" to Json.num(counting.count),
                "sha256" to Json.str(hex(digest.digest())),
            )
        }

        // 3) thumbnail
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        if (thumbnailPng != null) {
            zip.putNextEntry(ZipEntry(ProjectFormat.THUMBNAIL_ENTRY))
            zip.write(thumbnailPng)
            zip.closeEntry()
        }

        // 4) manifest last: it records the hashes computed above.
        val manifest = Json.obj(
            "format" to Json.str("phopsd"),
            "formatVersion" to Json.num(ProjectFormat.FORMAT_VERSION),
            "minReaderVersion" to Json.num(ProjectFormat.MIN_READER_VERSION),
            "app" to Json.obj("name" to Json.str("pho.psd"), "version" to Json.str(info.appVersion)),
            "savedAt" to Json.str(Instant.ofEpochMilli(info.savedAtMillis).toString()),
            "document" to documentJson(doc),
            "rasters" to Json.arr(table),
        )
        zip.putNextEntry(ZipEntry(ProjectFormat.MANIFEST_ENTRY))
        zip.write(Json.write(manifest).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.finish()
        zip.flush()
        return WriteResult(written.size, total)
    }

    private fun documentJson(doc: Document): Json = Json.obj(
        "id" to Json.str(doc.id),
        "name" to Json.str(doc.name),
        "width" to Json.num(doc.width),
        "height" to Json.num(doc.height),
        "dpi" to Json.num(doc.dpi),
        "colorProfile" to Json.obj(
            "name" to Json.str(doc.colorProfile.name),
            "model" to Json.str(doc.colorProfile.model),
            "bitsPerChannel" to Json.num(doc.colorProfile.bitsPerChannel),
        ),
        "activeLayerId" to Json.str(doc.activeLayerId?.value),
        "layers" to Json.arr(doc.layers.map(::layerJson)),
    )

    private fun layerJson(layer: Layer): Json = when (layer) {
        is RasterLayer -> Json.obj(
            "type" to Json.str("raster"),
            "id" to Json.str(layer.id.value),
            "name" to Json.str(layer.name),
            "visible" to Json.bool(layer.visible),
            "locked" to Json.bool(layer.locked),
            "opacity" to Json.num(layer.opacity.toDouble()),
            "blendMode" to Json.str(layer.blendMode.key),
            "raster" to Json.str(layer.rasterId.value),
            "width" to Json.num(layer.width),
            "height" to Json.num(layer.height),
            "transform" to Json.obj(
                "centerX" to Json.num(layer.transform.centerX),
                "centerY" to Json.num(layer.transform.centerY),
                "scaleX" to Json.num(layer.transform.scaleX),
                "scaleY" to Json.num(layer.transform.scaleY),
                "rotation" to Json.num(layer.transform.rotationDegrees),
            ),
            "source" to layer.source?.let { s ->
                Json.obj(
                    "displayName" to Json.str(s.displayName),
                    "mimeType" to Json.str(s.mimeType),
                    "originalWidth" to Json.num(s.originalWidth),
                    "originalHeight" to Json.num(s.originalHeight),
                    "sourceColorSpace" to Json.str(s.sourceColorSpace),
                    "downsampled" to Json.bool(s.downsampled),
                )
            },
        )
    }

    private fun storedEntry(name: String, data: ByteArray): ZipEntry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = data.size.toLong()
        compressedSize = data.size.toLong()
        crc = CRC32().apply { update(data) }.value
    }
}

// ====================================================================== reader

object ProjectReader {

    /** Fully loads a project: decodes every raster and validates every reference and hash. */
    fun read(input: InputStream, isCancelled: () -> Boolean = { false }): LoadedProject {
        val scan = scan(input, decodeRasters = true, isCancelled = isCancelled)
        return build(scan)
    }

    /**
     * Structural verification without decoding pixels: checks mimetype, manifest, and that
     * every referenced raster entry exists with the recorded length and SHA-256. Used to
     * validate a freshly written temp file before it replaces the user's copy.
     */
    fun verify(input: InputStream): VerifyResult {
        val scan = scan(input, decodeRasters = false, isCancelled = { false })
        val m = parseManifest(scan)
        val doc = m.obj("document")
        val table = rasterTable(m)
        checkTable(table, scan)
        return VerifyResult(m.int("formatVersion"), doc.array("layers").size, table.size)
    }

    private class EntryInfo(val sha256: String, val bytes: Long, val raster: TiledRaster?)

    private class Scan(
        var mimeOk: Boolean = false,
        var manifest: ByteArray? = null,
        var thumbnail: ByteArray? = null,
        val entries: MutableMap<String, EntryInfo> = HashMap(),
    )

    private fun scan(input: InputStream, decodeRasters: Boolean, isCancelled: () -> Boolean): Scan {
        val scan = Scan()
        try {
            val zip = ZipInputStream(NonClosingInputStream(input))
            var sawAnyEntry = false
            while (true) {
                val entry = zip.nextEntry ?: break
                sawAnyEntry = true
                if (isCancelled()) throw InterruptedException("Open cancelled")
                val name = entry.name
                when {
                    name == ProjectFormat.MIME_ENTRY -> {
                        val text = readLimited(zip, 256).toString(Charsets.US_ASCII).trim()
                        scan.mimeOk = text == ProjectFormat.MIME_TYPE
                    }
                    name == ProjectFormat.MANIFEST_ENTRY -> scan.manifest = readLimited(zip, ProjectFormat.MAX_MANIFEST_BYTES)
                    name == ProjectFormat.THUMBNAIL_ENTRY -> scan.thumbnail = readLimited(zip, ProjectFormat.MAX_THUMBNAIL_BYTES)
                    name.startsWith(ProjectFormat.RASTER_DIR) && !entry.isDirectory -> {
                        val digest = MessageDigest.getInstance("SHA-256")
                        val counted = CountingInputStream(DigestInputStream(NonClosingInputStream(zip), digest))
                        val raster = if (decodeRasters) {
                            try {
                                RasterCodec.decode(counted, isCancelled)
                            } catch (e: RasterFormatException) {
                                throw ProjectFormatException("Pixel data “$name” is damaged: ${e.message}", e)
                            }
                        } else null
                        val skip = ByteArray(64 * 1024)
                        while (counted.read(skip) >= 0) { /* drain the rest so the hash covers it */ }
                        scan.entries[name] = EntryInfo(hex(digest.digest()), counted.count, raster)
                    }
                    else -> { /* unknown entries from newer versions are ignored */ }
                }
                zip.closeEntry()
            }
            if (!sawAnyEntry) throw ProjectFormatException("The file is empty or not a pho.psd project")
        } catch (e: ZipException) {
            throw ProjectFormatException("The project file is damaged (${e.message ?: "invalid archive"})", e)
        } catch (e: java.io.EOFException) {
            throw ProjectFormatException("The project file is truncated", e)
        }
        if (!scan.mimeOk) throw ProjectFormatException("This file is not a pho.psd project")
        if (scan.manifest == null) throw ProjectFormatException("The project file is damaged: its manifest is missing")
        return scan
    }

    private fun parseManifest(scan: Scan): Json.Obj {
        val text = scan.manifest!!.toString(Charsets.UTF_8)
        val json = try {
            Json.parse(text)
        } catch (e: JsonException) {
            throw ProjectFormatException("The project manifest is damaged: ${e.message}", e)
        }
        val m = json as? Json.Obj ?: throw ProjectFormatException("The project manifest is damaged")
        return try {
            if (m.string("format") != "phopsd") throw ProjectFormatException("This file is not a pho.psd project")
            val version = m.int("formatVersion")
            val minReader = m.int("minReaderVersion")
            if (minReader > ProjectFormat.FORMAT_VERSION) {
                throw ProjectFormatException(
                    "This project was saved by a newer version of pho.psd (format $version). Update the app to open it.",
                )
            }
            if (version < ProjectFormat.FORMAT_VERSION) ProjectFormat.migrate(m, version) else m
        } catch (e: JsonException) {
            throw ProjectFormatException("The project manifest is damaged: ${e.message}", e)
        }
    }

    private data class RasterRow(val id: String, val path: String, val width: Int, val height: Int, val bytes: Long, val sha256: String)

    private fun rasterTable(m: Json.Obj): List<RasterRow> = try {
        m.array("rasters").map {
            val o = it as? Json.Obj ?: throw JsonException("Invalid raster table")
            RasterRow(o.string("id"), o.string("path"), o.int("width"), o.int("height"), o.long("bytes"), o.string("sha256"))
        }
    } catch (e: JsonException) {
        throw ProjectFormatException("The project manifest is damaged: ${e.message}", e)
    }

    private fun checkTable(table: List<RasterRow>, scan: Scan) {
        for (row in table) {
            val entry = scan.entries[row.path]
                ?: throw ProjectFormatException("The project is incomplete: pixel data “${row.path}” is missing")
            if (entry.bytes != row.bytes || !entry.sha256.equals(row.sha256, ignoreCase = true)) {
                throw ProjectFormatException("The project is damaged: pixel data “${row.path}” failed its integrity check")
            }
            entry.raster?.let {
                if (it.width != row.width || it.height != row.height) {
                    throw ProjectFormatException("The project is damaged: “${row.path}” has unexpected dimensions")
                }
            }
        }
    }

    private fun build(scan: Scan): LoadedProject {
        val m = parseManifest(scan)
        val table = rasterTable(m)
        checkTable(table, scan)
        val warnings = ArrayList<String>()
        val byId = table.associateBy { it.id }
        try {
            val d = m.obj("document")
            val rastersOut = HashMap<RasterId, TiledRaster>()
            val layers = d.array("layers").mapIndexed { index, lj ->
                val l = lj as? Json.Obj ?: throw JsonException("Invalid layer #$index")
                when (val type = l.string("type")) {
                    "raster" -> {
                        val rid = l.string("raster")
                        val row = byId[rid] ?: throw ProjectFormatException("Layer “${l.string("name")}” references missing pixel data")
                        val raster = scan.entries[row.path]!!.raster!!
                        rastersOut[RasterId(rid)] = raster
                        val modeKey = l.string("blendMode")
                        val mode = BlendMode.fromKey(modeKey) ?: BlendMode.Normal.also {
                            warnings += "Layer “${l.string("name")}” used an unknown blend mode “$modeKey”; it was set to Normal."
                        }
                        val w = l.int("width")
                        val h = l.int("height")
                        if (w != raster.width || h != raster.height) {
                            throw ProjectFormatException("Layer “${l.string("name")}” does not match its pixel data size")
                        }
                        val t = l.obj("transform")
                        RasterLayer(
                            id = LayerId(l.string("id")),
                            name = l.string("name"),
                            rasterId = RasterId(rid),
                            width = w,
                            height = h,
                            transform = LayerTransform(
                                centerX = t.double("centerX"),
                                centerY = t.double("centerY"),
                                scaleX = t.double("scaleX"),
                                scaleY = t.double("scaleY"),
                                rotationDegrees = t.double("rotation"),
                            ),
                            visible = l.bool("visible"),
                            locked = l.boolOr("locked", false),
                            opacity = l.double("opacity").toFloat().coerceIn(0f, 1f),
                            blendMode = mode,
                            source = l.objOrNull("source")?.let { s ->
                                SourceInfo(
                                    displayName = s.string("displayName"),
                                    mimeType = s.stringOrNull("mimeType"),
                                    originalWidth = s.int("originalWidth"),
                                    originalHeight = s.int("originalHeight"),
                                    sourceColorSpace = s.stringOrNull("sourceColorSpace"),
                                    downsampled = s.boolOr("downsampled", false),
                                )
                            },
                        )
                    }
                    else -> throw ProjectFormatException(
                        "Layer type “$type” is not supported by this version of pho.psd. The project was not opened, so nothing was lost.",
                    )
                }
            }
            val ids = layers.map { it.id }.toSet()
            var active = d.stringOrNull("activeLayerId")?.let { LayerId(it) }
            if (active != null && active !in ids) {
                warnings += "The selected layer could not be restored."
                active = null
            }
            if (active == null) active = layers.lastOrNull()?.id
            val cp = d.objOrNull("colorProfile")
            val doc = Document(
                id = d.string("id"),
                name = d.string("name"),
                width = d.int("width"),
                height = d.int("height"),
                layers = layers,
                activeLayerId = active,
                dpi = d.doubleOr("dpi", 72.0),
                colorProfile = cp?.let { ColorProfileInfo(it.string("name"), it.int("bitsPerChannel"), it.string("model")) }
                    ?: ColorProfileInfo.SRGB_8,
            )
            if (doc.layers.isEmpty()) throw ProjectFormatException("The project contains no layers")
            val savedAt = m.stringOrNull("savedAt")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            val app = m.objOrNull("app")?.stringOrNull("version")
            return LoadedProject(doc, rastersOut, savedAt, app, m.int("formatVersion"), warnings, scan.thumbnail)
        } catch (e: JsonException) {
            throw ProjectFormatException("The project manifest is damaged: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ProjectFormatException("The project manifest is inconsistent: ${e.message}", e)
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw ProjectFormatException("The project file contains an oversized entry")
        }
        return out.toByteArray()
    }
}

// ====================================================================== stream helpers

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { String.format("%02x", it) }

private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = flush()
}

private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
    override fun close() {}
}

private class HashingOutputStream(out: OutputStream, private val digest: MessageDigest) : FilterOutputStream(out) {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b); digest.update(b.toByte()); count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len); digest.update(b, off, len); count += len
    }
}

private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}
