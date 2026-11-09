package eu.kanade.translation.coordinator

import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.onlinePageTranslationKey
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.IOException
import java.io.InputStream

/**
 * One planned page. [imageUrl] is the page-list value the reader keys on;
 * lazy-URL sources resolve the real image URL at prepare time, exactly like
 * the reader's own [eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader].
 */
internal class PlannedPage(
    val sourcePage: Page,
    val imageUrl: String?,
) {
    @Volatile
    internal var resolvedImageUrl: String? = null

    internal fun effectiveImageUrl(): String? = resolvedImageUrl ?: imageUrl?.takeIf { it.isNotBlank() }
}

/**
 * Derives the ordered (pageKey → planned page) entries from a source page
 * list. Pure: keys use the reader's online identity ([onlinePageTranslationKey])
 * computed from the LIST values only — the reader stamps `sourceFileName` the
 * same way in getPages, before any lazy URL resolution. Blank-key pages drop.
 */
internal fun onlineColorizePageEntries(pages: List<Page>): List<Pair<String, PlannedPage>> =
    pages.mapNotNull { page ->
        val imageUrl = page.imageUrl?.takeIf { it.isNotBlank() }
        val pageKey = onlinePageTranslationKey(imageUrl, page.url)
        if (pageKey.isBlank()) return@mapNotNull null
        pageKey to PlannedPage(page, imageUrl)
    }

/**
 * Stream source for a COLORIZE-ONLY batch run over a STREAMED chapter: the
 * page list comes from the online source once; images download into the
 * chapter cache on demand (suspend, cancellation-safe). Page keys match the
 * reader's online identity so commits are the entries the reader observes.
 */
internal class OnlineColorizePageStreamResolver(
    private val chapterCache: ChapterCache,
) {

    /** Ordered (reader-consistent) page plan for one streamed chapter. */
    internal class Plan(
        val orderedPageKeys: List<String>,
        private val source: HttpSource,
        private val pagesByKey: Map<String, PlannedPage>,
        private val chapterCache: ChapterCache,
    ) {

        /** Cache-backed stream factory; throws when the page was never prepared. */
        fun streamFor(pageKey: String): InputStream {
            val page = pagesByKey[pageKey] ?: throw IOException("Page stream is missing for $pageKey")
            val imageUrl = page.effectiveImageUrl()
                ?: throw IOException("No resolved image URL for page $pageKey")
            return chapterCache.getImageFile(imageUrl).inputStream()
        }

        /**
         * Resolves the image URL (lazy-URL sources) and downloads the page
         * into the chapter cache on first call; a cache hit is kept as-is.
         * Mirrors the reader's load flow: getImageUrl when the list had none,
         * publish it on the source page (default HttpSource.imageRequest reads
         * page.imageUrl!!), then getImage, then cache. Cancellation propagates
         * untouched.
         */
        suspend fun prepareStream(pageKey: String) {
            val page = pagesByKey[pageKey] ?: throw IOException("Page stream is missing for $pageKey")
            if (page.effectiveImageUrl() == null) {
                try {
                    page.resolvedImageUrl = source.getImageUrl(page.sourcePage)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    throw IOException("Couldn't resolve the image URL for page $pageKey: ${t.message}", t)
                }
            }
            val imageUrl = page.effectiveImageUrl()
                ?: throw IOException("Couldn't resolve the image URL for page $pageKey")
            page.sourcePage.imageUrl = imageUrl
            if (chapterCache.isImageInCache(imageUrl)) return
            try {
                chapterCache.putImageToCache(imageUrl, source.getImage(page.sourcePage))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                throw IOException("Couldn't download page $pageKey for colorize: ${t.message}", t)
            }
        }
    }

    /** Fetches the page list and plans its streams; null when the source fails. */
    suspend fun resolve(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ): Plan? = try {
        val pages = source.getPageList(chapter.toSChapter())
        if (pages.isEmpty()) null else buildPlan(pages, source)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun buildPlan(pages: List<Page>, source: HttpSource): Plan {
        // A source can hand out duplicate keys (shared last path segment);
        // keep the first occurrence so ordering and identity stay stable.
        val deduped = onlineColorizePageEntries(pages).distinctBy { it.first }
        return Plan(
            orderedPageKeys = deduped.map { it.first },
            source = source,
            pagesByKey = deduped.toMap(),
            chapterCache = chapterCache,
        )
    }
}
