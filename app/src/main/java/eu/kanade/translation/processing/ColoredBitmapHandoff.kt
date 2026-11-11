package eu.kanade.translation.processing

import android.graphics.Bitmap

/**
 * Bounded handoff of freshly colorized bitmaps to the reader so the currently
 * visible page swaps without a SAF re-open and full re-decode. The producer
 * offers a bitmap right before the durable commit; the holder polls it by the
 * committed colored file name at display time.
 *
 * Ownership: [offer] TRANSFERS ownership — the pipeline must drop its
 * reference (PageTranslation.coloredBitmap) so releasePageBitmaps never
 * recycles a handed-off bitmap. [poll] transfers ownership to the holder,
 * which recycles on navigation, page change, or holder teardown. Unconsumed
 * entries are recycled on eviction and [clear] (store invalidation / delete).
 * Capacity bounds memory to at most a couple of full-page bitmaps; the file on
 * disk always remains the durable source for later re-entries.
 */
internal class ColoredBitmapHandoff(private val capacity: Int = 2) {

    private data class Entry(val imageName: String, val bitmap: Bitmap)

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    /** Transfers [bitmap] into the handoff; evicts (and recycles) the oldest entry when full. */
    fun offer(imageName: String, bitmap: Bitmap): Boolean {
        require(capacity > 0)
        synchronized(lock) {
            entries.removeAll { existing ->
                if (existing.imageName == imageName) {
                    existing.bitmap.recycle()
                    true
                } else {
                    false
                }
            }
            entries.addLast(Entry(imageName, bitmap))
            while (entries.size > capacity) {
                entries.removeFirst().bitmap.recycle()
            }
        }
        return true
    }

    /** Removes and returns the bitmap for [imageName], transferring ownership to the caller. */
    fun poll(imageName: String): Bitmap? {
        synchronized(lock) {
            val index = entries.indexOfFirst { it.imageName == imageName }
            if (index < 0) return null
            return entries.removeAt(index).bitmap
        }
    }

    /** Recycles every unconsumed bitmap. */
    fun clear() {
        synchronized(lock) {
            entries.forEach { it.bitmap.recycle() }
            entries.clear()
        }
    }
}
