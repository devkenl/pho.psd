package app.phopsd.engine.edit

import app.phopsd.engine.model.Document
import app.phopsd.engine.model.RasterId

/**
 * Immutable linear undo history of document snapshots.
 *
 * Snapshots are cheap because [Document] holds only metadata and raster *references*;
 * rasters are immutable and shared. Pixel-editing tools (M4) will add new rasters
 * derived copy-on-write per tile, so history memory grows with changed tiles only.
 *
 * @property coalesceKey key of the top entry; consecutive pushes with the same key
 *   (e.g. an opacity slider drag) replace the top entry instead of adding new ones.
 */
data class History(
    val entries: List<Entry>,
    val index: Int,
    val limit: Int = DEFAULT_LIMIT,
    val coalesceKey: String? = null,
) {
    data class Entry(val label: String, val document: Document)

    init {
        require(entries.isNotEmpty() && index in entries.indices)
        require(limit >= 2)
    }

    val current: Document get() = entries[index].document
    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index < entries.size - 1
    val undoLabel: String? get() = if (canUndo) entries[index].label else null
    val redoLabel: String? get() = if (canRedo) entries[index + 1].label else null

    /**
     * Records a new state. Discards any redo branch. When [coalesce] matches the key of the
     * top entry (and nothing was undone in between) the top entry is replaced.
     */
    fun push(label: String, document: Document, coalesce: String? = null): History {
        if (coalesce != null && coalesce == coalesceKey && index == entries.size - 1 && index > 0) {
            val list = entries.toMutableList()
            list[index] = Entry(label, document)
            return copy(entries = list, coalesceKey = coalesce)
        }
        val kept = entries.subList(0, index + 1).toMutableList()
        kept.add(Entry(label, document))
        val overflow = (kept.size - limit).coerceAtLeast(0)
        val trimmed = if (overflow > 0) kept.subList(overflow, kept.size).toList() else kept
        return copy(entries = trimmed, index = trimmed.size - 1, coalesceKey = coalesce)
    }

    /** Replaces the current state without creating an undo step (e.g. active-layer change). */
    fun replaceCurrent(document: Document): History {
        val list = entries.toMutableList()
        list[index] = list[index].copy(document = document)
        return copy(entries = list)
    }

    fun undo(): History = if (canUndo) copy(index = index - 1, coalesceKey = null) else this
    fun redo(): History = if (canRedo) copy(index = index + 1, coalesceKey = null) else this

    /** Jump to any state (History panel). */
    fun jumpTo(target: Int): History =
        if (target in entries.indices) copy(index = target, coalesceKey = null) else this

    /** Breaks coalescing so the next push always creates a new entry. */
    fun sealed(): History = if (coalesceKey == null) this else copy(coalesceKey = null)

    /**
     * Forgets the oldest state (memory-budget trimming). Never drops the current state;
     * returns this unchanged when the current state is already the oldest.
     */
    fun dropOldest(): History =
        if (index == 0) this else copy(entries = entries.drop(1), index = index - 1)

    /** Every raster referenced by any reachable state; everything else may be released. */
    fun referencedRasters(): Set<RasterId> = entries.flatMapTo(HashSet()) { it.document.rasterIds() }

    companion object {
        const val DEFAULT_LIMIT = 100

        fun start(document: Document, label: String = "Open", limit: Int = DEFAULT_LIMIT) =
            History(listOf(Entry(label, document)), 0, limit)
    }
}
