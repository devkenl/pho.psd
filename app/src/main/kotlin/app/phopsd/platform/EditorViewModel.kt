package app.phopsd.platform

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.phopsd.BuildConfigInfo
import app.phopsd.editor.EditorController
import app.phopsd.editor.ExportSettings
import app.phopsd.editor.NoticeKind
import app.phopsd.editor.PendingAction
import app.phopsd.editor.PickPurpose
import app.phopsd.editor.ProjectLink
import app.phopsd.editor.Screen
import app.phopsd.engine.model.Document
import app.phopsd.engine.model.RasterId
import app.phopsd.engine.model.RasterLayer
import app.phopsd.engine.raster.MipChain
import app.phopsd.engine.raster.Pixels
import app.phopsd.engine.render.SmallImage
import app.phopsd.engine.render.Thumbnails
import app.phopsd.engine.render.MaskDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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

    private val storage = ProjectStorage(app)
    private val autosave = AutosaveStore(app)
    private val exporter = ImageExporter(app.contentResolver)
    val recents = RecentProjects(app)

    private val _recovery = MutableStateFlow<AutosaveMeta?>(null)
    /** Unsaved work found from a previous session (shown on the home screen). */
    val recovery: StateFlow<AutosaveMeta?> = _recovery.asStateFlow()
    private var recoveredSessionActive = false

    private var saveJob: Job? = null
    private var pendingExport: ExportSettings? = null

    /**
     * One thread for all pixel edits: brush strokes are applied in input order, and heavy
     * operations (merge, flatten, apply mask) never race a stroke.
     */
    private val paintExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "pho-paint").apply { priority = Thread.NORM_PRIORITY + 1 } }
    private val paintDispatcher = paintExecutor.asCoroutineDispatcher()

    /** Startup I/O: must finish before any autosave is written, so leftovers are never overwritten. */
    private val startup = viewModelScope.async(Dispatchers.IO) {
        _recovery.value = runCatching { autosave.promoteLeftoverToRecovery() }.getOrNull()
        recents.load()
    }

    init {
        controller.historyBudgetBytes = (Runtime.getRuntime().maxMemory() * HISTORY_HEAP_FRACTION).toLong()
        controller.heavyRunner = { label, task ->
            viewModelScope.launch {
                controller.setBusy(label)
                try {
                    withContext(paintDispatcher) { task() }
                } catch (e: OutOfMemoryError) {
                    controller.notice("Not enough memory for this operation. Nothing was changed.", NoticeKind.Error)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    controller.notice("Operation failed: ${e.message ?: e.javaClass.simpleName}", NoticeKind.Error)
                } finally {
                    controller.setBusy(null)
                }
            }
        }
        // Base preview: depends on the document only (plus quality), never on pan/zoom.
        viewModelScope.launch {
            var lastKey: Pair<Document, MaskDisplay?>? = null
            var lastEdge = 0
            combine(state.map { it.document to it.maskDisplay }.distinctUntilChanged(), baseEdge, interacting) { d, e, i -> Triple(d, e, i) }
                .collectLatest { (key, edge, live) ->
                    val doc = key.first
                    if (doc == null) {
                        _base.value = null
                        lastKey = null
                        return@collectLatest
                    }
                    // While the document changes under a drag, render a lighter preview for
                    // responsiveness; a pan/zoom alone never re-renders (the doc is unchanged).
                    val target = if (live) minOf(edge, INTERACTIVE_EDGE) else edge
                    val k = doc to key.second
                    if (k == lastKey && lastEdge >= target) return@collectLatest
                    try {
                        _base.value = renderer.renderBase(doc, controller.renderLookup, target, key.second)
                        lastKey = k
                        lastEdge = target
                    } catch (e: OutOfMemoryError) {
                        controller.notice("Preview could not be rendered: out of memory", NoticeKind.Error)
                    }
                }
        }
        // Detail patch: depends on document and viewport; waits for the view to settle.
        viewModelScope.launch {
            state.map { Triple(it.document, it.viewport, it.maskDisplay) }.distinctUntilChanged().collectLatest { (doc, vp, md) ->
                val cur = _detail.value
                _detail.value = when {
                    cur == null || doc == null -> null
                    cur.document == doc && cur.maskDisplay == md -> cur
                    // A committed stroke: the frame was already patched with exactly these pixels.
                    doc === controller.lastStrokeDocument && cur.maskDisplay == md -> cur.patched(doc)
                    else -> null
                }
                if (doc == null || vp == null) return@collectLatest
                delay(DETAIL_SETTLE_MS)
                val base = _base.value ?: return@collectLatest
                val density = base.bitmap.width.toDouble() / doc.width
                try {
                    _detail.value = renderer.renderDetail(doc, controller.renderLookup, vp, density, md)
                } catch (e: OutOfMemoryError) {
                    _detail.value = null
                }
            }
        }
        // Live strokes: re-render only the touched region of the visible frames.
        viewModelScope.launch {
            controller.strokeRevision.collect {
                val dirty = controller.takeStrokeDirty() ?: return@collect
                val doc = state.value.document ?: return@collect
                val base = _base.value
                val detail = _detail.value
                val patches = try {
                    withContext(Dispatchers.Default) {
                        listOfNotNull(
                            base?.let { renderer.patch(it, doc, controller.renderLookup, dirty) },
                            detail?.let { renderer.patch(it, doc, controller.renderLookup, dirty) },
                        )
                    }
                } catch (e: OutOfMemoryError) {
                    emptyList()
                }
                for (p in patches) p.apply()
                if (base != null && _base.value === base && patches.any { it.frame === base }) _base.value = base.patched()
                if (detail != null && _detail.value === detail && patches.any { it.frame === detail }) _detail.value = detail.patched()
            }
        }
        // Layer and mask thumbnails: generated once per raster (rasters are immutable).
        viewModelScope.launch {
            state.map { s ->
                val layers = s.document?.layers?.mapNotNull { it as? RasterLayer } ?: emptyList()
                layers.map { it.rasterId }.toSet() to layers.mapNotNull { it.mask?.rasterId }.toSet()
            }
                .distinctUntilChanged()
                .collectLatest { (ids, maskIds) ->
                    thumbCache.keys.retainAll(ids + maskIds)
                    for (id in ids + maskIds) {
                        if (thumbCache.containsKey(id)) continue
                        val mips = controller.rasters.mips(id) ?: continue
                        val isMask = id in maskIds
                        val img = withContext(Dispatchers.Default) {
                            val small = Thumbnails.raster(mips, THUMB_EDGE)
                            (if (isMask) small.maskToGray() else small).toImageBitmap()
                        }
                        thumbCache[id] = img
                        _thumbs.value = HashMap(thumbCache)
                    }
                    _thumbs.value = HashMap(thumbCache)
                }
        }
        // Autosave: after a pause in editing, and never while nothing changed.
        viewModelScope.launch {
            var wasEditing = false
            state.map { Triple(it.screen, it.history?.current, it.dirty) }.distinctUntilChanged().collectLatest { (screen, doc, dirty) ->
                startup.await()
                if (screen != Screen.Editor || doc == null) {
                    // Closing a document is an explicit decision (unsaved changes were confirmed).
                    if (wasEditing) withContext(Dispatchers.IO) { autosave.clearCurrent() }
                    wasEditing = false
                    return@collectLatest
                }
                wasEditing = true
                if (!dirty) {
                    withContext(Dispatchers.IO) { autosave.clearCurrent() }
                    return@collectLatest
                }
                delay(AUTOSAVE_DELAY_MS)
                autosaveNow()
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

    // ------------------------------------------------------------ painting (paint thread)

    private fun onPaintThread(block: () -> Unit) {
        paintExecutor.execute {
            try {
                block()
            } catch (e: OutOfMemoryError) {
                controller.tools.cancelStroke()
                controller.notice("Not enough memory to finish the stroke. It was discarded.", NoticeKind.Error)
            } catch (e: Exception) {
                controller.tools.cancelStroke()
                controller.notice("Painting failed: ${e.message ?: e.javaClass.simpleName}", NoticeKind.Error)
            }
        }
    }

    fun strokeBegin(docX: Double, docY: Double, pressure: Double, eraserTip: Boolean, stylus: Boolean) =
        onPaintThread { controller.tools.beginStroke(docX, docY, pressure, eraserTip, stylus) }

    /** Points as (x, y, pressure) triples in document space, in input order. */
    fun strokeTo(points: DoubleArray) = onPaintThread {
        var i = 0
        while (i + 2 < points.size) {
            controller.tools.strokeTo(points[i], points[i + 1], points[i + 2])
            i += 3
        }
    }

    fun strokeEnd() = onPaintThread { controller.tools.endStroke() }

    fun strokeCancel() = onPaintThread { controller.tools.cancelStroke() }

    override fun onCleared() {
        paintExecutor.shutdown()
        super.onCleared()
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

    // ------------------------------------------------------------ save / open / export

    /** Result of the Save As location picker (null = cancelled). */
    fun onSaveLocationPicked(uri: Uri?) {
        if (uri == null) {
            controller.saveCancelled()
            return
        }
        storage.persistAccess(uri, write = true)
        val name = storage.displayName(uri) ?: uri.lastPathSegment ?: "Untitled.phopsd"
        saveProject(ProjectLink(uri.toString(), name))
    }

    fun saveProject(link: ProjectLink) {
        if (saveJob?.isActive == true) return
        val snap = controller.snapshotForSave() ?: return
        controller.markSaving()
        saveJob = viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val thumb = withContext(Dispatchers.Default) { ImageExporter.thumbnailPng(snap.document, snap) }
                storage.save(snap, Uri.parse(link.uri), thumb, BuildConfigInfo.VERSION_NAME, now)
                controller.markSaved(link, snap.document, now)
                finishRecoveryIfAny()
                recents.record(link.uri, link.displayName, snap.document.width, snap.document.height, now, thumb)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException) {
                controller.markSaveFailed(e.message ?: "unknown error")
            } catch (e: Exception) {
                controller.markSaveFailed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Result of the Open Project picker. */
    fun onProjectPicked(uri: Uri?) {
        if (uri == null) return
        storage.persistAccess(uri, write = true)
        openProject(uri.toString())
    }

    fun openProject(uriString: String) {
        val uri = Uri.parse(uriString)
        importJob?.cancel()
        importJob = viewModelScope.launch {
            controller.setBusy("Opening project…")
            try {
                val loaded = storage.open(uri)
                val mips = withContext(Dispatchers.Default) { loaded.rasters.mapValues { MipChain.build(it.value) } }
                thumbCache.clear()
                val name = storage.displayName(uri) ?: "${loaded.document.name}.phopsd"
                controller.loadProject(loaded.document, mips, ProjectLink(uriString, name), loaded.warnings)
                recents.record(uriString, name, loaded.document.width, loaded.document.height, System.currentTimeMillis(), loaded.thumbnailPng)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException) {
                controller.notice("Could not open the project: ${e.message}", NoticeKind.Error)
            } catch (e: Exception) {
                controller.notice("Could not open the project: ${e.message ?: e.javaClass.simpleName}", NoticeKind.Error)
            } finally {
                controller.setBusy(null)
            }
        }
    }

    /** Opens a recent project, asking first if the current document has unsaved changes. */
    fun openRecent(uri: String) = controller.requestOrConfirm(PendingAction.OpenRecent(uri))

    fun removeRecent(uri: String) {
        viewModelScope.launch { recents.remove(uri) }
    }

    /** Called when an export location picker is about to be shown. */
    fun prepareExport(settings: ExportSettings) {
        pendingExport = settings
    }

    fun onExportLocationPicked(uri: Uri?) {
        val settings = pendingExport ?: return
        pendingExport = null
        if (uri == null) return
        val snap = controller.snapshotForSave() ?: return
        viewModelScope.launch {
            controller.setBusy("Exporting ${settings.format.label}…")
            try {
                val r = exporter.export(snap, settings, uri)
                val name = storage.displayName(uri) ?: "image.${settings.format.extension}"
                controller.notice("Exported “$name” (${r.width} × ${r.height} px)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException) {
                controller.notice("Export failed: ${e.message}", NoticeKind.Error)
            } catch (e: Exception) {
                controller.notice("Export failed: ${e.message ?: e.javaClass.simpleName}", NoticeKind.Error)
            } finally {
                controller.setBusy(null)
            }
        }
    }

    // ------------------------------------------------------------ autosave & recovery

    /** Activity stopped (home button, task switch): autosave right away if there is unsaved work. */
    fun onAppBackgrounded() {
        if (controller.state.value.dirty && controller.state.value.screen == Screen.Editor) {
            viewModelScope.launch {
                startup.await()
                autosaveNow()
            }
        }
    }

    private suspend fun autosaveNow() {
        val s = controller.state.value
        if (saveJob?.isActive == true) return
        val snap = controller.snapshotForSave() ?: return
        val now = System.currentTimeMillis()
        try {
            autosave.write(
                snap,
                AutosaveMeta(snap.document.name, snap.document.width, snap.document.height, now, s.project),
                BuildConfigInfo.VERSION_NAME,
            )
            controller.markAutosaved(now)
            finishRecoveryIfAny()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            controller.notice("Autosave failed: ${e.message ?: e.javaClass.simpleName}", NoticeKind.Warning)
        }
    }

    fun recoverAutosave() {
        val meta = _recovery.value ?: return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            controller.setBusy("Recovering “${meta.documentName}”…")
            try {
                val loaded = autosave.loadRecovery()
                val mips = withContext(Dispatchers.Default) { loaded.rasters.mapValues { MipChain.build(it.value) } }
                thumbCache.clear()
                controller.loadProject(loaded.document, mips, meta.project, loaded.warnings, recovered = true)
                _recovery.value = null
                // The recovery file is kept until the recovered work is autosaved or saved again.
                recoveredSessionActive = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                controller.notice("The autosave could not be recovered: ${e.message ?: e.javaClass.simpleName}", NoticeKind.Error)
            } finally {
                controller.setBusy(null)
            }
        }
    }

    fun discardAutosave() {
        _recovery.value = null
        viewModelScope.launch(Dispatchers.IO) { autosave.discardRecovery() }
    }

    private fun finishRecoveryIfAny() {
        if (recoveredSessionActive) {
            recoveredSessionActive = false
            autosave.discardRecovery()
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
        private const val AUTOSAVE_DELAY_MS = 30_000L
        private const val INTERACTIVE_EDGE = 1280
        private const val BASE_OVERSAMPLE = 1.25
        private const val DETAIL_SETTLE_MS = 140L
        private const val CHANNEL_DEBOUNCE_MS = 350L
        private const val THUMB_EDGE = 96
        private const val CHANNEL_EDGE = 96
        /** Share of the Java heap that undo history may keep in pixel tiles. */
        private const val HISTORY_HEAP_FRACTION = 0.3
    }
}

/** A mask thumbnail (value in alpha) as an opaque grayscale image. */
fun SmallImage.maskToGray(): SmallImage =
    SmallImage(width, height, IntArray(premul.size) { val a = Pixels.alpha(premul[it]); Pixels.pack(255, a, a, a) })

/** Premultiplied engine image -> Compose bitmap. */
fun SmallImage.toImageBitmap(): ImageBitmap {
    val straight = premul.copyOf()
    Pixels.unpremultiplyInPlace(straight)
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bmp.setPixels(straight, 0, width, 0, 0, width, height)
    return bmp.asImageBitmap()
}
