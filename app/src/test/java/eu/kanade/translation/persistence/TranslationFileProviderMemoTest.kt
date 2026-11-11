package eu.kanade.translation.persistence

import android.content.Context
import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.storage.DiskUtil
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

/**
 * Pins the per-manga SAF memos behind the chapter-list speedup: one directory
 * walk per manga per invalidation window (including memoized negatives), one
 * listing per invalidation window, and one findFile for the companion page
 * file. Every memo drops in [TranslationFileProvider.invalidateDirectoryMemo],
 * which creation paths and the resolver's clear protocol call.
 */
class TranslationFileProviderMemoTest {

    @TempDir
    lateinit var backing: File

    private val source: Source = mockk {
        every { id } returns 77L
    }

    private var findFileWalks = 0

    private fun seededProvider(): TranslationFileProvider {
        val mangaDir = File(File(backing, sourceDirName()), mangaDirName()).also { it.mkdirs() }
        File(mangaDir, "Chapter 1.json").writeText("{}")
        val companion = File(mangaDir, "Chapter 1_images").also { it.mkdirs() }
        File(companion, "p1.cleaned.v1.jpg").writeBytes(byteArrayOf(1))
        val root = spyk(FakeUniFile(parent = null, backing = backing))
        every { root.findFile(any()) } answers {
            findFileWalks++
            callOriginal()
        }
        return providerWithRoot(root)
    }

    private fun mangaDirName(): String = DiskUtil.buildValidFilename("Manga")

    private fun sourceDirName(): String = DiskUtil.buildValidFilename(source.toString())

    private fun providerWithRoot(root: UniFile): TranslationFileProvider {
        val storageManager = mockk<StorageManager>()
        every { storageManager.getTranslationsDirectory() } returns root
        return TranslationFileProvider(mockk<Context>(relaxed = true), storageManager)
    }

    private fun providerOver(backing: File): TranslationFileProvider {
        val root = spyk(FakeUniFile(parent = null, backing = backing))
        every { root.findFile(any()) } answers {
            findFileWalks++
            callOriginal()
        }
        return providerWithRoot(root)
    }

    @Test
    fun `repeat manga dir lookups are served from the memo`() {
        val provider = seededProvider()

        val first = provider.findMangaDirMemoized("Manga", source)
        val second = provider.findMangaDirMemoized("Manga", source)

        // Only root-level findFile calls are counted; one source-dir lookup, no repeats.
        findFileWalks shouldBe 1
        (first === second) shouldBe true
    }

    @Test
    fun `absent manga dir is memoized negative and re-walks after invalidation`() {
        val provider = providerOver(backing)

        provider.findMangaDirMemoized("Manga", source) shouldBe null
        provider.findMangaDirMemoized("Manga", source) shouldBe null
        findFileWalks shouldBe 1

        provider.invalidateDirectoryMemo()
        provider.findMangaDirMemoized("Manga", source) shouldBe null
        findFileWalks shouldBe 2
    }

    @Test
    fun `manga dir listing is served once until invalidation sees new files`() {
        val provider = seededProvider()
        val stem = DiskUtil.buildValidFilename("Chapter 1")

        val before = provider.mangaDirFileNames("Manga", source)
        before shouldBe setOf("Chapter 1.json", "${stem}_images")

        // A manifest lands after the listing was taken.
        File(File(File(backing, sourceDirName()), mangaDirName()), "Chapter 2.json").writeText("{}")

        provider.mangaDirFileNames("Manga", source) shouldBe before

        provider.invalidateDirectoryMemo()
        provider.mangaDirFileNames("Manga", source) shouldBe
            setOf("Chapter 1.json", "Chapter 2.json", "${stem}_images")
    }

    @Test
    fun `page cleaned image lookup keeps the directory walk memoized`() {
        val provider = seededProvider()

        val image = provider.findPageCleanedImageMemoized("Manga", source, "Chapter 1", null, "p1.cleaned.v1.jpg")
        image?.exists() shouldBe true
        val afterFirst = findFileWalks

        val repeat = provider.findPageCleanedImageMemoized("Manga", source, "Chapter 1", null, "p1.cleaned.v1.jpg")
        // The directory walk is memoized (no new root-level findFile); the
        // page file itself re-resolves to a fresh wrapper with the same bytes.
        repeat?.exists() shouldBe true
        findFileWalks shouldBe afterFirst

        provider.invalidateDirectoryMemo()
        provider.findPageCleanedImageMemoized("Manga", source, "Chapter 1", null, "p1.cleaned.v1.jpg")
        findFileWalks shouldBe afterFirst + 1
    }

    @Test
    fun `absent companion dir is memoized negative until invalidation`() {
        val provider = seededProvider()

        provider.findPageCleanedImageMemoized("Manga", source, "Chapter 9", null, "p1.jpg") shouldBe null
        val afterMiss = findFileWalks
        provider.findPageCleanedImageMemoized("Manga", source, "Chapter 9", null, "p1.jpg") shouldBe null
        findFileWalks shouldBe afterMiss

        val mangaDir = File(File(backing, sourceDirName()), mangaDirName())
        File(mangaDir, "Chapter 9_images").mkdirs()

        provider.invalidateDirectoryMemo()
        provider.findPageCleanedImageMemoized("Manga", source, "Chapter 9", null, "p1.jpg") shouldBe null
        findFileWalks shouldBe afterMiss + 1
    }
}
