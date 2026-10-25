package app.phopsd.platform

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.phopsd.editor.EditorController
import app.phopsd.editor.NoticeKind
import app.phopsd.editor.PickPurpose
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.RasterId
import app.phopsd.engine.model.RasterLayer
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.render.SmallImage
import app.phopsd.engine.render.Thumbnails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Composite-derived channel thumbnails (RGB, R, G, B). */
class ChannelThumbs(val rgb: ImageBitmap, val red: ImageBitmap, val green: ImageBitmap, val blue: ImageBitmap)

/**
 * Android host for the pure-Kotlin [EditorController]: owns decoding, preview rendering
 * and thumbnail bitmaps, all off the main thread. Survives configuration changes.
 */
class EditorViewModel(app: Application) : AndroidViewModel(app) {
    val controller = EditorController()
    val state = controller.state

    private val importer = ImageImporter(app.contentResolver)
    private val renderer = PreviewRenderer()

    private val _base = MutableStateFlow<PreviewFrame?>(null)
    val basePreview: StateFlow<PreviewFrame?> = _base.asStateFlow()

    private val _detail = MutableStateFlow<PreviewFrame?>(null)
    val detailPreview: StateFlow<PreviewFrame?> = _detail.asStateFlow()

    private val _thumbs = MutableStateFlow<Map<RasterId, ImageBitmap>>(emptyMap())
    val layerThumbnails: StateFlow<Map<RasterId, ImageBitmap>> = _thumbs.asStateFlow()

    private val _channels = MutableStateFlow<ChannelThumbs?>(null)
    val channelThumbnails: StateFlow<ChannelThumbs?> = _channels.asStateFlow()

    /** Long edge of the base preview; set from the canvas size (≈ screen resolution). */
    private val baseEdge = MutableStateFlow(2048)
    private val interacting = MutableStateFlow(false)
    private val thumbCache = ConcurrentHashMap<RasterId, ImageBitmap>()
    private var importJob: Job? = null

    init {
        // Base preview: depends on the document only (plus quality), never on pan/zoom.
        viewModelScope.launch {
            var lastDoc: Document? = null
            var lastEdge = 0
            combine(state.map { it.document }.distinctUntilChanged(), baseEdge, interacting) { d, e, i -> Triple(d, e, i) }
                .collectLatest { (doc, edge, live) ->
                    if (doc == null) {
                        _base.value = null
                        lastDoc = null
                        return@collectLatest
                    }
                    // While the document changes under a drag, render a lighter preview for
                    // responsiveness; a pan/zoom alone never re-renders (the doc is unchanged).
                    val target = if (live) minOf(edge, INTERACTIVE_EDGE) else edge
                    if (doc == lastDoc && lastEdge >= target) return@collectLatest
                    try {
                        _base.value = renderer.renderBase(doc, controller.rasters, target)
                        lastDoc = doc
                        lastEdge = target
                    } catch (e: OutOfMemoryError) {
                        controller.notice("Preview could not be rendered: out of memory", NoticeKind.Error)
                    }
                }
        }
        // Detail patch: depends on document and viewport; waits for the view to settle.
        viewModelScope.launch {
            state.map { it.document to it.viewport }.distinctUntilChanged().collectLatest { (doc, vp) ->
                _detail.value = _detail.value?.takeIf { it.document == doc }
                if (doc == null || vp == null) return@collectLatest
                delay(DETAIL_SETTLE_MS)
                val base = _base.value ?: return@collectLatest
                val density = base.bitmap.width.toDouble() / doc.width
                try {
                    _detail.value = renderer.renderDetail(doc, controller.rasters, vp, density)
                } catch (e: OutOfMemoryError) {
                    _detail.value = null
                }
            }
        }
        // Layer thumbnails: generated once per raster (rasters are immutable).
        viewModelScope.launch {
            state.map { s -> s.document?.layers?.mapNotNull { (it as? RasterLayer)?.rasterId }?.toSet() ?: emptySet() }
                .distinctUntilChanged()
                .collectLatest { ids ->
                    thumbCache.keys.retainAll(ids)
                    for (id in ids) {
                        if (thumbCache.containsKey(id)) continue
                        val mips = controller.rasters.mips(id) ?: continue
                        val img = withContext(Dispatchers.Default) { Thumbnails.raster(mips, THUMB_EDGE).toImageBitmap() }
                        thumbCache[id] = img
                        _thumbs.value = HashMap(thumbCache)
                    }
                    _thumbs.value = HashMap(thumbCache)
                }
        }
        // Channel thumbnails from the composite, debounced.
        viewModelScope.launch {
            state.map { it.history?.current }.distinctUntilChanged().collectLatest { doc ->
                if (doc == null) { _channels.value = null; return@collectLatest }
                delay(CHANNEL_DEBOUNCE_MS)
                _channels.value = withContext(Dispatchers.Default) { channelThumbs(doc) }
            }
        }
    }

    fun setCanvasSizePx(width: Int, height: Int) {
        controller.setViewSize(width.toDouble(), height.toDouble())
        baseEdge.value = (maxOf(width, height) * BASE_OVERSAMPLE).toInt().coerceIn(512, MAX_BASE_EDGE)
    }

    fun setInteracting(on: Boolean) {
        interacting.value = on
    }

    fun onImagePicked(uri: Uri, purpose: PickPurpose) {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            controller.setBusy(if (purpose == PickPurpose.OpenAsDocument) "Opening image…" else "Placing image…")
            try {
                val decoded = importer.decode(uri, ImageImporter.heapBudgetBytes())
                when (purpose) {
                    PickPurpose.OpenAsDocument -> {
                        thumbCache.clear()
                        controller.openImage(decoded.name, decoded.mips, decoded.source)
                    }
                    PickPurpose.PlaceAsLayer -> controller.placeImage(decoded.name, decoded.mips, decoded.source)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportException) {
                controller.notice(e.message ?: "Import failed", NoticeKind.Error)
            } catch (e: Exception) {
                controller.notice("Import failed: ${e.message ?: e.javaClass.simpleName}", NoticeKind.Error)
            } finally {
                controller.setBusy(null)
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        controller.setBusy(null)
    }

    private fun channelThumbs(doc: Document): ChannelThumbs {
        val composite = Thumbnails.document(doc, controller.rasters, CHANNEL_EDGE)
        return ChannelThumbs(
            rgb = composite.toImageBitmap(),
            red = Thumbnails.channel(composite, 0).toImageBitmap(),
            green = Thumbnails.channel(composite, 1).toImageBitmap(),
            blue = Thumbnails.channel(composite, 2).toImageBitmap(),
        )
    }

    companion object {
        private const val MAX_BASE_EDGE = 3072
        private const val INTERACTIVE_EDGE = 1280
        private const val BASE_OVERSAMPLE = 1.25
        private const val DETAIL_SETTLE_MS = 140L
        private const val CHANNEL_DEBOUNCE_MS = 350L
        private const val THUMB_EDGE = 96
        private const val CHANNEL_EDGE = 96
    }
}

/** Premultiplied engine image -> Compose bitmap. */
fun SmallImage.toImageBitmap(): ImageBitmap {
    val straight = premul.copyOf()
    Pixels.unpremultiplyInPlace(straight)
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bmp.setPixels(straight, 0, width, 0, 0, width, height)
    return bmp.asImageBitmap()
}
