package app.phopsd.engine.model

import app.phopsd.engine.raster.MipChain
import java.util.concurrent.ConcurrentHashMap

/** Read access to raster pixel data by id (what the compositor needs). */
fun interface RasterLookup {
    fun mips(id: RasterId): MipChain?
}

/**
 * Thread-safe owner of all raster pixel data for an editing session.
 * Entries are immutable [MipChain]s, so readers on render threads never race writers.
 */
class RasterStore : RasterLookup {
    private val entries = ConcurrentHashMap<RasterId, MipChain>()

    fun put(id: RasterId, mips: MipChain) {
        require(mips.base.isFrozen)
        entries[id] = mips
    }

    override fun mips(id: RasterId): MipChain? = entries[id]

    operator fun contains(id: RasterId): Boolean = entries.containsKey(id)

    val size: Int get() = entries.size

    /** Drops rasters no longer referenced by any live document or history entry. */
    fun retainOnly(live: Set<RasterId>): Int {
        var removed = 0
        val it = entries.keys.iterator()
        while (it.hasNext()) {
            if (it.next() !in live) {
                it.remove(); removed++
            }
        }
        return removed
    }

    fun clear() = entries.clear()

    /** Pixel memory in bytes across all levels of all rasters. */
    val allocatedBytes: Long get() = entries.values.sumOf { it.allocatedBytes }
}
