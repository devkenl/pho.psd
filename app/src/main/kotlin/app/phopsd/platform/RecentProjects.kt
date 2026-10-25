package app.phopsd.platform

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.phopsd.engine.io.Json
import app.phopsd.engine.io.int
import app.phopsd.engine.io.long
import app.phopsd.engine.io.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class RecentProject(
    val uri: String,
    val name: String,
    val width: Int,
    val height: Int,
    val lastOpenedMillis: Long,
    val thumbnail: ImageBitmap?,
)

/**
 * Projects the user saved or opened in pho.psd (most recent first, max [LIMIT]).
 * Stored as a small JSON file plus cached PNG thumbnails in app-private storage.
 */
class RecentProjects(context: Context) {
    private val file = File(context.filesDir, "recents.json")
    private val thumbDir = File(context.filesDir, "recent-thumbs").apply { mkdirs() }
    private val _items = MutableStateFlow<List<RecentProject>>(emptyList())
    val items: StateFlow<List<RecentProject>> = _items.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _items.value = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    suspend fun record(uri: String, name: String, width: Int, height: Int, nowMillis: Long, thumbnailPng: ByteArray?) =
        withContext(Dispatchers.IO) {
            val thumbFile = thumbFor(uri)
            if (thumbnailPng != null) runCatching { thumbFile.writeBytes(thumbnailPng) }
            val thumb = decodeThumb(thumbFile)
            val updated = listOf(RecentProject(uri, name, width, height, nowMillis, thumb)) +
                _items.value.filter { it.uri != uri }
            _items.value = updated.take(LIMIT)
            persist()
        }

    suspend fun remove(uri: String) = withContext(Dispatchers.IO) {
        _items.value = _items.value.filter { it.uri != uri }
        thumbFor(uri).delete()
        persist()
    }

    private fun persist() {
        val json = Json.arr(
            _items.value.map {
                Json.obj(
                    "uri" to Json.str(it.uri),
                    "name" to Json.str(it.name),
                    "width" to Json.num(it.width),
                    "height" to Json.num(it.height),
                    "lastOpened" to Json.num(it.lastOpenedMillis),
                )
            },
        )
        val tmp = File(file.parentFile, "recents.json.tmp")
        tmp.writeText(Json.write(json))
        tmp.renameTo(file)
    }

    private fun parse(text: String): List<RecentProject> =
        (Json.parse(text) as Json.Arr).items.mapNotNull { item ->
            val o = item as? Json.Obj ?: return@mapNotNull null
            runCatching {
                val uri = o.string("uri")
                RecentProject(uri, o.string("name"), o.int("width"), o.int("height"), o.long("lastOpened"), decodeThumb(thumbFor(uri)))
            }.getOrNull()
        }.take(LIMIT)

    private fun thumbFor(uri: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { String.format("%02x", it) }
        return File(thumbDir, "$hash.png")
    }

    private fun decodeThumb(f: File): ImageBitmap? =
        if (f.exists()) runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull() else null

    companion object {
        const val LIMIT = 20
    }
}
