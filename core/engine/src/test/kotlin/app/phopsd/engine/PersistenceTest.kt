package app.phopsd.engine

import app.phopsd.engine.edit.DocumentEdits
import app.phopsd.engine.edit.EditResult
import app.phopsd.engine.geometry.LayerTransform
import app.phopsd.engine.geometry.RectD
import app.phopsd.engine.io.Json
import app.phopsd.engine.io.JsonException
import app.phopsd.engine.io.ProjectFormat
import app.phopsd.engine.io.ProjectFormatException
import app.phopsd.engine.io.ProjectReader
import app.phopsd.engine.io.ProjectWriter
import app.phopsd.engine.io.RasterCodec
import app.phopsd.engine.io.RasterFormatException
import app.phopsd.engine.io.SaveInfo
import app.phopsd.engine.io.array
import app.phopsd.engine.io.int
import app.phopsd.engine.io.obj
import app.phopsd.engine.io.string
import app.phopsd.engine.model.BackgroundContents
import app.phopsd.engine.model.BlendMode
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.DocumentFactory
import app.phopsd.engine.model.NewDocumentSpec
import app.phopsd.engine.model.RasterId
import app.phopsd.engine.model.RasterLayer
import app.phopsd.engine.model.RasterStore
import app.phopsd.engine.model.SourceInfo
import app.phopsd.engine.raster.MipChain
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.raster.TiledRaster
import app.phopsd.engine.render.Compositor
import app.phopsd.engine.render.ExportRenderer
import app.phopsd.engine.render.RenderRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private fun randomRaster(w: Int, h: Int, seed: Long, tileSize: Int = 32): TiledRaster {
    val rnd = Random(seed)
    val px = IntArray(w * h) {
        val a = if (rnd.nextInt(5) == 0) rnd.nextInt(256) else 255
        Pixels.pack(a, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
    }
    return TiledRaster.fromPixels(w, h, px, straightAlpha = true, tileSize = tileSize)
}

class PersistenceTest {

    // ---------------------------------------------------------------- JSON

    @Test
    fun jsonRoundTripsValuesAndEscapes() {
        val v = Json.obj(
            "s" to Json.str("quote \" backslash \\ newline \n tab \t unicode é 📷 ctrl \u0001"),
            "i" to Json.num(42),
            "neg" to Json.num(-7.25),
            "big" to Json.num(1234567890123.0),
            "t" to Json.bool(true),
            "n" to Json.Null,
            "arr" to Json.arr(listOf(Json.num(1), Json.str("x"), Json.arr(emptyList()), Json.obj())),
        )
        for (pretty in listOf(true, false)) {
            assertEquals(v, Json.parse(Json.write(v, pretty)))
        }
        assertEquals("{\"a\":1}", Json.write(Json.obj("a" to Json.num(1)), pretty = false))
    }

    @Test
    fun jsonRejectsMalformedInput() {
        for (bad in listOf("", "{", "{\"a\":}", "[1,]", "{\"a\":1,\"a\":2}", "01", "1.", "\"abc", "tru", "{} x", "\"\\q\"", "NaN")) {
            try {
                Json.parse(bad)
                fail("accepted: $bad")
            } catch (_: JsonException) {
            }
        }
        val deep = "[".repeat(200) + "]".repeat(200)
        try { Json.parse(deep); fail("accepted deep nesting") } catch (_: JsonException) {}
    }

    // ---------------------------------------------------------------- raster codec

    private fun encode(r: TiledRaster): ByteArray = ByteArrayOutputStream().also { RasterCodec.encode(r, it) }.toByteArray()

    private fun assertSameRaster(a: TiledRaster, b: TiledRaster) {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        assertEquals(a.fill, b.fill)
        for (y in 0 until a.height) for (x in 0 until a.width) assertEquals("($x,$y)", a.pixel(x, y), b.pixel(x, y))
    }

    @Test
    fun rasterCodecIsLosslessIncludingEdgeTilesAndFill() {
        val r = randomRaster(77, 45, 1)
        val back = RasterCodec.decode(ByteArrayInputStream(encode(r)))
        assertSameRaster(r, back)
        assertTrue(back.isFrozen)
        assertEquals(r.allocatedTileCount, back.allocatedTileCount)

        val solid = TiledRaster.solid(5000, 3000, Pixels.opaque(0xFFFFFF))
        val bytes = encode(solid)
        assertTrue("solid raster must not store pixels (${bytes.size} bytes)", bytes.size < 64)
        val solidBack = RasterCodec.decode(ByteArrayInputStream(bytes))
        assertEquals(0, solidBack.allocatedTileCount)
        assertEquals(Pixels.opaque(0xFFFFFF), solidBack.pixel(4999, 2999))
    }

    @Test
    fun predictorCompressesSmoothImages() {
        val w = 256
        val px = IntArray(w * w) { i -> Pixels.opaque(((i % w) shl 16) or ((i / w) shl 8) or 0x40) }
        val r = TiledRaster.fromPixels(w, w, px, straightAlpha = true)
        val size = encode(r).size
        assertTrue("gradient should compress well, got $size of ${w * w * 4}", size < w * w * 4 / 10)
    }

    @Test
    fun rasterCodecRejectsDamage() {
        val good = encode(randomRaster(40, 40, 2))
        val cases = mapOf(
            "bad magic" to good.copyOf().also { it[0] = 0 },
            "truncated" to good.copyOf(good.size - 20),
            "newer version" to good.copyOf().also { it[5] = 9 },
            "payload flip" to good.copyOf().also { it[60] = (it[60].toInt() xor 0x5A).toByte() },
        )
        for ((name, bytes) in cases) {
            try {
                RasterCodec.decode(ByteArrayInputStream(bytes))
                fail("$name accepted")
            } catch (_: RasterFormatException) {
            }
        }
    }

    // ---------------------------------------------------------------- project container

    private class Fixture {
        val store = RasterStore()
        val doc: Document

        init {
            var d = DocumentFactory.create(NewDocumentSpec("Mountain Horizon", 120, 90, 300.0, BackgroundContents.White), store)
            val photo = MipChain.build(randomRaster(64, 48, 7))
            val placed = DocumentFactory.placedLayer(
                d, photo, SourceInfo("IMG_0001.jpg", "image/jpeg", 6000, 4500, "Display P3", true), store, "Sky",
            )
            d = (DocumentEdits.addLayer(d, placed) as EditResult.Applied).document
            d = (DocumentEdits.setTransform(d, placed.id, LayerTransform(70.5, 33.25, 0.8, 0.8, 17.5)) as EditResult.Applied).document
            d = (DocumentEdits.setBlendMode(d, placed.id, BlendMode.SoftLight) as EditResult.Applied).document
            d = (DocumentEdits.setOpacity(d, placed.id, 0.65f) as EditResult.Applied).document
            d = (DocumentEdits.duplicateLayer(d, placed.id) as EditResult.Applied).document
            val dup = d.activeLayerId!!
            d = (DocumentEdits.setVisible(d, dup, false) as EditResult.Applied).document
            d = (DocumentEdits.setLocked(d, dup, true) as EditResult.Applied).document
            d = (DocumentEdits.renameLayer(d, dup, "Sky ✦ \"copy\"") as EditResult.Applied).document
            d = (DocumentEdits.addLayer(d, DocumentFactory.emptyLayer(d, store, "Empty")) as EditResult.Applied).document
            d = (DocumentEdits.setActive(d, placed.id) as EditResult.Applied).document
            doc = d
        }

        fun save(thumb: ByteArray? = byteArrayOf(1, 2, 3)): ByteArray =
            ByteArrayOutputStream().also { ProjectWriter.write(doc, store, it, SaveInfo("test", 1_760_000_000_000L), thumb) }.toByteArray()
    }

    private fun load(bytes: ByteArray) = ProjectReader.read(ByteArrayInputStream(bytes))

    private fun storeOf(rasters: Map<RasterId, TiledRaster>) = RasterStore().also { s -> rasters.forEach { (k, v) -> s.put(k, MipChain.build(v)) } }

    @Test
    fun projectRoundTripPreservesDocumentExactly() {
        val f = Fixture()
        val loaded = load(f.save())
        assertEquals(f.doc, loaded.document)
        assertTrue(loaded.warnings.isEmpty())
        assertEquals(1_760_000_000_000L, loaded.savedAtMillis)
        assertEquals("test", loaded.appVersion)
        assertArrayEquals(byteArrayOf(1, 2, 3), loaded.thumbnailPng)
        // Duplicated layers share a raster: 3 distinct rasters for 4 layers.
        assertEquals(3, loaded.rasters.size)
        for ((id, r) in loaded.rasters) assertSameRaster(f.store.mips(id)!!.base, r)
    }

    @Test
    fun reopenedProjectRendersPixelIdentically() {
        val f = Fixture()
        val loaded = load(f.save())
        val store2 = storeOf(loaded.rasters)
        val req = RenderRequest(RectD(0.0, 0.0, 120.0, 90.0), 120, 90)
        val c = Compositor()
        assertArrayEquals(c.render(f.doc, f.store, req), c.render(loaded.document, store2, req))
    }

    @Test
    fun resavingALoadedProjectIsStable() {
        val f = Fixture()
        val first = load(f.save())
        val store2 = storeOf(first.rasters)
        val again = ByteArrayOutputStream().also {
            ProjectWriter.write(first.document, store2, it, SaveInfo("test", 1_760_000_000_000L), byteArrayOf(1, 2, 3))
        }.toByteArray()
        assertEquals(f.doc, load(again).document)
    }

    @Test
    fun verifyAcceptsGoodFilesWithoutDecodingPixels() {
        val v = ProjectReader.verify(ByteArrayInputStream(Fixture().save()))
        assertEquals(ProjectFormat.FORMAT_VERSION, v.formatVersion)
        assertEquals(4, v.layerCount)
        assertEquals(3, v.rasterCount)
    }

    private fun assertRejected(bytes: ByteArray, expect: String) {
        for (op in listOf<(ByteArray) -> Unit>({ load(it) }, { ProjectReader.verify(ByteArrayInputStream(it)) })) {
            try {
                op(bytes)
                fail("damaged project accepted (expected: $expect)")
            } catch (e: ProjectFormatException) {
                assertTrue("message “${e.message}” should mention “$expect”", e.message!!.contains(expect, ignoreCase = true))
            }
        }
    }

    /** Rebuilds an archive applying [edit] to each entry (name, bytes) -> bytes or null to drop. */
    private fun rewrite(src: ByteArray, edit: (String, ByteArray) -> ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zout ->
            val zin = ZipInputStream(ByteArrayInputStream(src))
            while (true) {
                val e = zin.nextEntry ?: break
                val data = edit(e.name, zin.readBytes()) ?: continue
                zout.putNextEntry(ZipEntry(e.name))
                zout.write(data)
                zout.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun corruptedPixelDataIsDetected() {
        val bytes = rewrite(Fixture().save()) { name, data ->
            if (name.endsWith(".pras") && data.size > 200) data.copyOf().also { it[150] = (it[150].toInt() xor 1).toByte() } else data
        }
        assertRejected(bytes, "damaged")
    }

    @Test
    fun missingPixelDataIsDetected() {
        var dropped = false
        val bytes = rewrite(Fixture().save()) { name, data ->
            if (name.endsWith(".pras") && !dropped) { dropped = true; null } else data
        }
        assertRejected(bytes, "missing")
    }

    @Test
    fun truncatedAndForeignFilesAreRejected() {
        val good = Fixture().save()
        try {
            load(good.copyOf(good.size / 2))
            fail("truncated file accepted")
        } catch (_: ProjectFormatException) {
        }
        assertRejected(rewrite(good) { name, data -> if (name == "mimetype") "application/zip".toByteArray() else data }, "not a pho.psd")
        assertRejected(rewrite(good) { name, data -> if (name == "manifest.json") null else data }, "manifest")
        assertRejected(rewrite(good) { name, data -> if (name == "manifest.json") data.copyOf(data.size - 5) else data }, "manifest")
        try {
            load("plain text".toByteArray())
            fail("text accepted")
        } catch (_: ProjectFormatException) {
        }
    }

    private fun editManifest(bytes: ByteArray, f: (String) -> String): ByteArray =
        rewrite(bytes) { name, data -> if (name == "manifest.json") f(data.toString(Charsets.UTF_8)).toByteArray() else data }

    @Test
    fun newerFormatIsRefusedWithAClearMessage() {
        val bytes = editManifest(Fixture().save()) {
            it.replace("\"minReaderVersion\": 1", "\"minReaderVersion\": 7").replace("\"formatVersion\": 1", "\"formatVersion\": 7")
        }
        assertRejected(bytes, "newer version")
    }

    @Test
    fun unknownBlendModeFallsBackWithWarningAndUnknownEntriesAreIgnored() {
        val withExtra = rewrite(Fixture().save()) { _, data -> data }.let { src ->
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z ->
                val zin = ZipInputStream(ByteArrayInputStream(src))
                while (true) {
                    val e = zin.nextEntry ?: break
                    z.putNextEntry(ZipEntry(e.name)); z.write(zin.readBytes()); z.closeEntry()
                }
                z.putNextEntry(ZipEntry("future/feature.bin")); z.write(ByteArray(10)); z.closeEntry()
            }
            out.toByteArray()
        }
        val bytes = editManifest(withExtra) { it.replaceFirst("\"softLight\"", "\"vividLight\"") }
        val loaded = load(bytes)
        assertEquals(1, loaded.warnings.size)
        assertTrue(loaded.warnings[0].contains("vividLight"))
        assertTrue(loaded.document.layers.any { it.blendMode == BlendMode.Normal && it.name == "Sky" })
    }

    @Test
    fun unknownLayerTypeIsRefusedRatherThanDropped() {
        val bytes = editManifest(Fixture().save()) { it.replaceFirst("\"type\": \"raster\"", "\"type\": \"text\"") }
        try {
            load(bytes)
            fail("unknown layer type accepted")
        } catch (e: ProjectFormatException) {
            assertTrue(e.message!!.contains("not supported"))
        }
    }

    @Test
    fun manifestRecordsHashesAndMetadata() {
        val bytes = Fixture().save()
        var manifest: Json.Obj? = null
        val zin = ZipInputStream(ByteArrayInputStream(bytes))
        val names = ArrayList<String>()
        while (true) {
            val e = zin.nextEntry ?: break
            names += e.name
            if (e.name == "manifest.json") manifest = Json.parse(zin.readBytes().toString(Charsets.UTF_8)) as Json.Obj
        }
        assertEquals("mimetype", names.first())
        val m = manifest!!
        assertEquals("phopsd", m.string("format"))
        assertEquals(1, m.int("formatVersion"))
        assertEquals(300, m.obj("document").int("dpi"))
        assertTrue(m.array("rasters").all { (it as Json.Obj).string("sha256").length == 64 })
    }

    // ---------------------------------------------------------------- export

    @Test
    fun exportBandsMatchSinglePassAndAreStraightAlpha() {
        val f = Fixture()
        val full = Compositor().render(f.doc, f.store, RenderRequest(RectD(0.0, 0.0, 120.0, 90.0), 120, 90))
        val out = IntArray(120 * 90)
        val ok = ExportRenderer.render(f.doc, f.store, ExportRenderer.Options(bandRows = 7)) { y0, rows, w, band ->
            System.arraycopy(band, 0, out, y0 * w, rows * w)
        }
        assertTrue(ok)
        for (i in full.indices) assertEquals(Pixels.unpremultiply(full[i]), out[i])
    }

    @Test
    fun exportOverWhiteIsOpaqueAndScaleReducesSize() {
        val store = RasterStore()
        val doc = DocumentFactory.create(NewDocumentSpec("T", 10, 10, background = BackgroundContents.Transparent), store)
        var px: IntArray? = null
        ExportRenderer.render(doc, store, ExportRenderer.Options(background = Pixels.opaque(0xFFFFFF))) { _, _, _, band -> px = band.copyOf() }
        assertTrue(px!!.all { it == Pixels.opaque(0xFFFFFF) })
        assertEquals(5 to 3, ExportRenderer.outputSize(Document("d", "x", 10, 6, doc.layers.map { (it as RasterLayer) }, null), 0.5))
        assertFalse(ExportRenderer.render(doc, store, ExportRenderer.Options(), isCancelled = { true }) { _, _, _, _ -> })
    }

    @Test
    fun emptyProjectLayerListIsRejected() {
        val bytes = editManifest(Fixture().save()) { text ->
            val m = Json.parse(text) as Json.Obj
            val d = m.obj("document")
            val newDoc = Json.Obj(d.fields + ("layers" to Json.arr(emptyList())) + ("activeLayerId" to Json.Null))
            Json.write(Json.Obj(m.fields + ("document" to newDoc)))
        }
        try {
            load(bytes)
            fail("accepted")
        } catch (e: ProjectFormatException) {
            assertNull(null)
        }
    }
}
