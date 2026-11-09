package eu.kanade.translation.coordinator

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The online colorize plan must derive the reader's own page identity and
 * resolve image URLs exactly like the reader's loader: keys come from the
 * page-LIST values only, lazy-URL sources resolve at prepare time, and
 * cancellation is never swallowed.
 */
class OnlineColorizePageStreamResolverTest {

    private val source: HttpSource = mockk()
    private val chapterCache: ChapterCache = mockk(relaxed = true)

    private fun resolver() = OnlineColorizePageStreamResolver(chapterCache)

    private suspend fun planFor(pages: List<Page>): OnlineColorizePageStreamResolver.Plan {
        coEvery { source.getPageList(any()) } returns pages
        return resolver().resolve(manga = mockk(relaxed = true), chapter = mockk(relaxed = true), source = source)
            ?: error("page list plan must resolve")
    }

    @Test
    fun `image url last path segment without query becomes the key`() {
        val entries = onlineColorizePageEntries(
            listOf(Page(0, url = "chapter/1", imageUrl = "https://cdn.example/img/p1.jpg?token=abc")),
        )

        entries.map { it.first } shouldBe listOf("p1.jpg")
    }

    @Test
    fun `page url fallback is used when the image url is absent`() {
        val entries = onlineColorizePageEntries(
            listOf(Page(0, url = "https://example.org/api/page/42", imageUrl = null)),
        )

        entries.single().first shouldBe "42"
        entries.single().second.imageUrl shouldBe null
    }

    @Test
    fun `planned entry keeps the original source page handle`() {
        val sourcePage = Page(3, url = "reader/3", imageUrl = "https://cdn/d.jpg")
        val entries = onlineColorizePageEntries(listOf(sourcePage))

        entries.single().second.sourcePage shouldBe sourcePage
    }

    @Test
    fun `lazy-url page resolves at prepare time and downloads with the resolved url`() = runTest {
        val plan = planFor(listOf(Page(0, url = "https://example.org/api/page/42", imageUrl = null)))
        coEvery { source.getImageUrl(any()) } returns "https://cdn.example/img/p42.jpg?token=x"
        coEvery { source.getImage(any()) } returns mockk(relaxed = true)
        val cachedFile = File.createTempFile("p42", ".jpg").apply {
            writeBytes(byteArrayOf(1))
            deleteOnExit()
        }
        every { chapterCache.getImageFile(any()) } returns cachedFile

        // Key stays the page-list-derived one; the bytes come from the resolved URL.
        plan.orderedPageKeys.single() shouldBe "42"
        plan.prepareStream("42")

        coVerify(exactly = 1) { source.getImage(any()) }
        verify(exactly = 1) { chapterCache.putImageToCache("https://cdn.example/img/p42.jpg?token=x", any()) }
        plan.streamFor("42").shouldBeInstanceOf<InputStream>()
    }

    @Test
    fun `getImage receives the source page with the resolved url published`() = runTest {
        // Regression: default HttpSource.imageRequest reads page.imageUrl!!, so
        // the resolved URL must be published on the source page before getImage
        // (same as the reader's internalLoadPage).
        val sourcePage = Page(0, url = "https://example.org/api/page/42", imageUrl = null)
        val plan = planFor(listOf(sourcePage))
        coEvery { source.getImageUrl(any()) } returns "https://cdn.example/img/p42.jpg?token=x"
        val imageArg = slot<Page>()
        coEvery { source.getImage(capture(imageArg)) } returns mockk(relaxed = true)
        val cachedFile = File.createTempFile("p42", ".jpg").apply {
            writeBytes(byteArrayOf(1))
            deleteOnExit()
        }
        every { chapterCache.getImageFile(any()) } returns cachedFile

        plan.prepareStream("42")

        imageArg.captured shouldBe sourcePage
        imageArg.captured.imageUrl shouldBe "https://cdn.example/img/p42.jpg?token=x"
    }

    @Test
    fun `failed lazy resolution surfaces as a typed fetch failure at prepare time`() = runTest {
        val plan = planFor(listOf(Page(0, url = "https://example.org/api/page/42", imageUrl = null)))
        coEvery { source.getImageUrl(any()) } throws IOException("timeout")

        val error = assertThrows<IOException> { plan.prepareStream("42") }

        error.message!!.contains("resolve the image URL") shouldBe true
    }

    @Test
    fun `cancellation during page-list fetch is never swallowed`() = runTest {
        coEvery { source.getPageList(any()) } throws CancellationException("batch cancelled")

        val error = assertThrows<CancellationException> {
            resolver().resolve(manga = mockk(relaxed = true), chapter = mockk(relaxed = true), source = source)
        }

        error.message shouldBe "batch cancelled"
    }

    @Test
    fun `cancellation during image download is never wrapped`() = runTest {
        val plan = planFor(listOf(Page(0, url = "a", imageUrl = "https://cdn/a.jpg")))
        coEvery { source.getImage(any()) } throws CancellationException("prepare cancelled")

        val error = assertThrows<CancellationException> { plan.prepareStream("a.jpg") }

        error.message shouldBe "prepare cancelled"
    }

    @Test
    fun `source failure resolves to null plan`() = runTest {
        coEvery { source.getPageList(any()) } throws IOException("site down")

        resolver().resolve(manga = mockk(relaxed = true), chapter = mockk(relaxed = true), source = source) shouldBe null
    }
}
