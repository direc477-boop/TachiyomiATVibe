package eu.kanade.translation.persistence
import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.storage.DiskUtil
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

class TranslationFileProvider(
    private val context: Context,
    private val storageManager: StorageManager = Injekt.get(),
) {

    // Per-manga SAF memos: one walk per manga per invalidation window instead
    // of per-chapter findFile round-trips (60-210ms each over Binder). All
    // entries — including negatives — drop in invalidateDirectoryMemo.
    private data class MangaDirMemoKey(val sourceId: Long, val mangaTitle: String)

    private data class CompanionDirMemoKey(
        val sourceId: Long,
        val mangaTitle: String,
        val companionDirName: String,
    )

    private val mangaDirMemo = ConcurrentHashMap<MangaDirMemoKey, Optional<UniFile>>()
    private val mangaDirListingMemo = ConcurrentHashMap<MangaDirMemoKey, Set<String>>()
    private val companionDirMemo = ConcurrentHashMap<CompanionDirMemoKey, Optional<UniFile>>()

    /**
     * Single invalidation protocol for every directory memo. Durable mutations
     * (store creation through [getMangaDir], resets, deletions, batch closure)
     * route here — usually via the resolver's clearDurableStatusCache — so a
     * memo never outlives the durable truth it mirrors.
     */
    fun invalidateDirectoryMemo() {
        mangaDirMemo.clear()
        mangaDirListingMemo.clear()
        companionDirMemo.clear()
    }

    /** Read-through memo over [findMangaDir]; negatives included until invalidation. */
    internal fun findMangaDirMemoized(mangaTitle: String, source: Source): UniFile? {
        val key = MangaDirMemoKey(source.id, mangaTitle)
        mangaDirMemo[key]?.let { return it.orElse(null) }
        val dir = findMangaDir(mangaTitle, source)
        mangaDirMemo[key] = Optional.ofNullable(dir)
        return dir
    }

    /**
     * File-name index of the manga translation directory, listed once per
     * invalidation window. A failed SAF walk is served uncached so the next
     * call retries instead of pinning an empty index.
     */
    internal fun mangaDirFileNames(mangaTitle: String, source: Source): Set<String> {
        val key = MangaDirMemoKey(source.id, mangaTitle)
        mangaDirListingMemo[key]?.let { return it }
        val dir = findMangaDirMemoized(mangaTitle, source)
        val children = dir?.listFiles()
        if (dir != null && children == null) return emptySet()
        val listing = children?.mapNotNull { it.name }?.toSet() ?: emptySet()
        mangaDirListingMemo[key] = listing
        return listing
    }

    /** Read-through memo over [findCompanionImageDir]; negatives included until invalidation. */
    internal fun findCompanionImageDirMemoized(
        mangaTitle: String,
        source: Source,
        chapterName: String,
        chapterScanlator: String?,
    ): UniFile? {
        val dirName = companionImageDirName(chapterName, chapterScanlator)
        val key = CompanionDirMemoKey(source.id, mangaTitle, dirName)
        companionDirMemo[key]?.let { return it.orElse(null) }
        val dir = findCompanionImageDir(mangaTitle, source, chapterName, chapterScanlator)
        companionDirMemo[key] = Optional.ofNullable(dir)
        return dir
    }

    /** Page file resolved with one findFile against the memoized companion directory. */
    internal fun findPageCleanedImageMemoized(
        mangaTitle: String,
        source: Source,
        chapterName: String,
        chapterScanlator: String?,
        pageImageName: String,
    ): UniFile? {
        val dir = findCompanionImageDirMemoized(mangaTitle, source, chapterName, chapterScanlator) ?: return null
        return dir.findFile(pageImageName)
    }

    private fun companionImageDirName(chapterName: String, chapterScanlator: String?): String {
        return getTranslationFileName(chapterName, chapterScanlator).removeSuffix(".json") + "_images"
    }

    internal sealed interface MangaDirectoryLookup {
        data class Found(val directory: UniFile) : MangaDirectoryLookup

        data object NoPriorTranslationRecords : MangaDirectoryLookup

        data object Unavailable : MangaDirectoryLookup
    }

    private val translationDir: UniFile?
        get() = storageManager.getTranslationsDirectory()

    /**
     * Returns the translation directory for a manga. For internal use only.
     *
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the manga.
     */
    internal fun getMangaDir(mangaTitle: String, source: Source): UniFile {
        try {
            DiskUtil.createNoMediaFile(translationDir, context)
            val sourceDir = translationDir!!
                .createDirectory(getSourceDirName(source))!!
                .also { DiskUtil.createNoMediaFile(it, context) }
            return sourceDir
                .createDirectory(getMangaDirName(mangaTitle))!!
                .also { DiskUtil.createNoMediaFile(it, context) }
                .also { invalidateDirectoryMemo() }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Invalid translation directory" }
            throw Exception(
                context.stringResource(
                    MR.strings.invalid_location,
                    translationDir?.displayablePath ?: "",
                ),
            )
        }
    }

    /**
     * Returns the translation directory for a source if it exists.
     *
     * @param source the source to query.
     */
    fun findSourceDir(source: Source): UniFile? {
        val sourceDir = translationDir?.findFile(getSourceDirName(source))
        return sourceDir
    }

    /**
     * Returns the translation directory for a manga if it exists.
     *
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the manga.
     */
    fun findMangaDir(mangaTitle: String, source: Source): UniFile? {
        val sourceDir = findSourceDir(source)
        return sourceDir?.findFile(getMangaDirName(mangaTitle))
    }

    /**
     * Resolves a manga artifact directory for completed-download reconciliation.
     * A missing source/manga child inside an accessible translation root proves
     * there are no prior artifact records; an unavailable root remains a failure.
     */
    internal fun findMangaDirForCompletedDownload(
        mangaTitle: String,
        source: Source,
    ): MangaDirectoryLookup {
        val root = translationDir ?: return MangaDirectoryLookup.Unavailable
        if (!root.exists() || !root.isDirectory) return MangaDirectoryLookup.Unavailable
        val sourceDir = root.findFile(getSourceDirName(source))
            ?: return MangaDirectoryLookup.NoPriorTranslationRecords
        if (!sourceDir.exists() || !sourceDir.isDirectory) return MangaDirectoryLookup.Unavailable
        val mangaDir = sourceDir.findFile(getMangaDirName(mangaTitle))
            ?: return MangaDirectoryLookup.NoPriorTranslationRecords
        if (!mangaDir.exists() || !mangaDir.isDirectory) return MangaDirectoryLookup.Unavailable
        return MangaDirectoryLookup.Found(mangaDir)
    }

    /**
     * Returns the translation directory name for a source.
     *
     * @param source the source to query.
     */
    fun getSourceDirName(source: Source): String {
        return DiskUtil.buildValidFilename(source.toString())
    }

    /**
     * Returns the translation directory name for a manga.
     *
     * @param mangaTitle the title of the manga to query.
     */
    fun getMangaDirName(mangaTitle: String): String {
        return DiskUtil.buildValidFilename(mangaTitle)
    }

    /**
     * Returns the chapter directory name for a chapter.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     */
    fun getTranslationFileName(chapterName: String, chapterScanlator: String?): String {
        val newChapterName = sanitizeChapterName(chapterName)
        return DiskUtil.buildValidFilename(
            when {
                !chapterScanlator.isNullOrBlank() -> "${chapterScanlator}_$newChapterName.json"
                else -> "$newChapterName.json"
            },
        )
    }

    /**
     * Return the new name for the chapter (in case it's empty or blank)
     *
     * @param chapterName the name of the chapter
     */
    private fun sanitizeChapterName(chapterName: String): String {
        return chapterName.ifBlank {
            "Chapter"
        }
    }

    fun getCompanionImageDir(mangaTitle: String, source: Source, chapterName: String, chapterScanlator: String?): UniFile? {
        val mangaDir = getMangaDir(mangaTitle, source)
        val translationFileName = getTranslationFileName(chapterName, chapterScanlator)
        val companionDirName = translationFileName.removeSuffix(".json") + "_images"
        return mangaDir.createDirectory(companionDirName)?.also {
            DiskUtil.createNoMediaFile(it, context)
            invalidateDirectoryMemo()
        }
    }

    fun findCompanionImageDir(mangaTitle: String, source: Source, chapterName: String, chapterScanlator: String?): UniFile? {
        val mangaDir = findMangaDir(mangaTitle, source) ?: return null
        val translationFileName = getTranslationFileName(chapterName, chapterScanlator)
        val companionDirName = translationFileName.removeSuffix(".json") + "_images"
        return mangaDir.findFile(companionDirName)
    }

    fun findPageCleanedImage(mangaTitle: String, source: Source, chapterName: String, chapterScanlator: String?, pageImageName: String): UniFile? {
        val dir = findCompanionImageDir(mangaTitle, source, chapterName, chapterScanlator) ?: return null
        return dir.findFile(pageImageName)
    }

    fun deleteCompanionImages(mangaTitle: String, source: Source, chapterName: String, chapterScanlator: String?) {
        findCompanionImageDir(mangaTitle, source, chapterName, chapterScanlator)?.delete()
    }

    fun companionImageNameForPage(pageKey: String, type: String = "cleaned"): String {
        val sanitized = pageKey.replace(Regex("[^a-zA-Z0-9.\\-_]"), "_")
        return "$sanitized.$type.png"
    }
}
