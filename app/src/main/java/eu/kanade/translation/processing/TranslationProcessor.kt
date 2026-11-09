package eu.kanade.translation.processing

import android.content.Context
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.PageColorizeOutcome
import eu.kanade.translation.engines.coloring.PageColorizationEngine
import eu.kanade.translation.engines.inpainting.InpaintingHardwareOverride
import eu.kanade.translation.engines.translator.NativeStallWatchdog
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.upscaling.PageUpscalingEngine
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.LeaseAcquisition
import eu.kanade.translation.persistence.PageWriteOrigin
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.processing.CleanedPublication
import eu.kanade.translation.processing.DecodedPage
import eu.kanade.translation.processing.EngineLane
import eu.kanade.translation.processing.PageDecode
import eu.kanade.translation.processing.adaptive.DevicePagePermitGate
import eu.kanade.translation.processing.adaptive.DevicePermitPriority
import eu.kanade.translation.processing.execution.NativeRunQuarantine
import eu.kanade.translation.processing.execution.TranslationStreamRegistry
import eu.kanade.translation.processing.memory.MemoryGovernance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.TranslationPreferences
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * TachiyomiAT: reader-pipeline stage vocabulary for live feedback. These are
 * the real boundaries [processSinglePage] traverses — no stage is invented
 * that does not exist in this processor. Deliberately NOT a persistence
 * concern: signals are transient, wired reader-side only.
 */
enum class TranslationStageSignal {
    PREPARING,
    READING,
    TRANSLATING,
    CLEANING,
    COLORING,
    FINISHING,
}

/** Executes one page's decode, OCR, translation, and inpaint operations. */
internal class TranslationProcessor(
    private val context: Context,
    private val provider: TranslationFileProvider? = null,
    private val translationPreferences: TranslationPreferences? = null,
    private val streamRegistry: TranslationStreamRegistry? = null,
    private val suppliedCleanedPublication: CleanedPublication? = null,
) : AutoCloseable {

    sealed interface ProcessResult {
        data class Success(val page: PageTranslation) : ProcessResult
        data class Failure(
            val pageKey: String,
            val failedStage: String,
            val errorMessage: String,
            val cause: Throwable? = null,
            val page: PageTranslation? = null,
        ) : ProcessResult
    }

    private val nativeRunScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nativeStallWatchdog = NativeStallWatchdog(
        scope = nativeRunScope,
        thresholdMs = TranslationProcessingTimeouts.NATIVE_STALL_THRESHOLD_MS,
    )
    private val nativeRunQuarantine = NativeRunQuarantine(
        scope = nativeRunScope,
        occupancyObserver = object : NativeRunQuarantine.OccupancyObserver {
            override fun onLaneOccupied(token: Long, pageKey: String, startedAtEpochMs: Long) {
                nativeStallWatchdog.onLaneOccupied(token, pageKey, startedAtEpochMs)
            }

            override fun onLaneReleased(token: Long) {
                nativeStallWatchdog.onLaneReleased(token)
            }
        },
    )
    private val inFlightPageKeys = ConcurrentHashMap.newKeySet<String>()
    private val engineLane: EngineLane? = if (translationPreferences != null) {
        EngineLane(
            context = context,
            translationPreferences = translationPreferences,
            nativeRunQuarantine = nativeRunQuarantine,
            inFlightPageKeys = inFlightPageKeys,
            onPageStuck = { onPageStuck },
            drainScope = nativeRunScope,
        )
    } else {
        null
    }
    private val devicePagePermitGate = DevicePagePermitGate()
    private val cleanedPublication: CleanedPublication? by lazy {
        suppliedCleanedPublication ?: if (provider != null && streamRegistry != null && engineLane != null) {
            CleanedPublication(
                provider = provider,
                currentInpaintingMode = { engineLane.currentInpaintingMode },
                currentNeuralInpaintModel = { engineLane.currentNeuralInpaintModel },
            )
        } else {
            null
        }
    }

    @Volatile
    var onPageStuck: ((chapterId: Long?, pageKey: String) -> Unit)? = null

    /** Owned by TranslationManager; the colorization stage gates on the pref at stage time. */
    @Volatile
    var colorizationEngine: PageColorizationEngine? = null

    /** The colorization engine only when attached and the user gate is on. */
    fun activeColorizationEngine(): PageColorizationEngine? =
        colorizationEngine?.takeIf { translationPreferences?.translationColorizationEnabled()?.get() == true }

    /** Owned by TranslationManager; the upscaling stage gates on the pref at stage time. */
    @Volatile
    var upscalingEngine: PageUpscalingEngine? = null

    /** The upscaling engine only when attached and the user gate is on. */
    fun activeUpscalingEngine(): PageUpscalingEngine? =
        upscalingEngine?.takeIf { translationPreferences?.translationUpscalingEnabled()?.get() == true }

    val nativeStall get() = nativeStallWatchdog.state
    fun permitHolderPageKeySnapshot(): String? = engineLane?.permitHolderPageKeySnapshot()
    val recognitionEngine: PageRecognitionEngine get() = requireEngineLane().recognitionEngine
    val textTranslator: TextTranslator get() = requireEngineLane().textTranslator

    private fun requireEngineLane(): EngineLane = checkNotNull(engineLane) {
        "TranslationProcessor was created without production engine configuration"
    }

    suspend fun prepareEngines(
        from: eu.kanade.translation.model.TextRecognizerLanguage,
        to: eu.kanade.translation.model.TextTranslatorLanguage,
    ) = requireEngineLane().ensureEnginesBuiltFor(from, to)

    suspend fun warmUp() = requireEngineLane().warmUp()

    fun setInpaintingHardwareOverride(override: InpaintingHardwareOverride) =
        requireEngineLane().setInpaintingHardwareOverride(override)

    fun closeEngines() {
        // Attached (not detached) so a restart in the same session colorizes again.
        colorizationEngine?.let { engine -> runCatching { engine.close() } }
        upscalingEngine?.let { engine -> runCatching { engine.close() } }
        requireEngineLane().closeEngines()
    }

    fun forceReleaseNativeBuffers() {
        engineLane?.let { lane -> MemoryGovernance.forceReleaseNativeBuffers { lane.recognitionEngine } }
    }

    fun companionDirectory(manga: Manga, chapter: Chapter, source: HttpSource): UniFile? =
        provider?.getCompanionImageDir(manga.title, source, chapter.name, chapter.scanlator)

    /**
     * Colorize-only primitive for ONE page — the same recipe the batch
     * colorize-only pass runs per page: decode the original, colorize it
     * directly (no OCR/translation/inpaint stage ever runs), commit to the
     * chapter store. Never throws for a page failure; the outcome says what
     * happened. Acquires the page stage lease for [origin] (MANUAL preempts
     * reader AUTO work; AUTO is denied while any stage owns the page) and
     * publishes a RUNNING coloring entry first, so holders, the status pill
     * and the batch tracker show live state instead of a silent stall.
     */
    suspend fun colorizeOriginalPage(
        pageKey: String,
        streamFn: () -> InputStream,
        store: ChapterTranslationStore,
        companionDir: UniFile?,
        origin: PageWriteOrigin,
    ): PageColorizeOutcome {
        // First call builds the colorization runtime; absence is feature-absence.
        val engine = activeColorizationEngine() ?: return PageColorizeOutcome.UNAVAILABLE
        val lease = when (val acquired = store.tryAcquirePageStageLease(pageKey, PageStage.Render, origin)) {
            is LeaseAcquisition.Granted -> acquired.lease
            is LeaseAcquisition.Denied -> return PageColorizeOutcome.PAGE_BUSY
        }
        try {
            store.markColorizeRunning(pageKey, "$origin:colorize-running")
            val decoded = try {
                decodeImage(pageKey, streamFn) { recognitionEngine }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logcat(LogPriority.WARN, t) { "TranslationProcessor: decode failed for colorize on page $pageKey" }
                null
            } ?: run {
                finishTerminal(
                    pageKey,
                    store,
                    lease.token,
                    StageStatus.FAILED,
                    "Page image could not be decoded",
                    origin,
                )
                return PageColorizeOutcome.DECODE_FAILED
            }
            try {
                val page = PageTranslation(sourceFileName = pageKey).apply {
                    imgWidth = decoded.bitmap.width.toFloat()
                    imgHeight = decoded.bitmap.height.toFloat()
                    originalImgWidth = decoded.originalWidth.toFloat()
                    originalImgHeight = decoded.originalHeight.toFloat()
                    decodeSampleSize = decoded.sampleSize
                    sourceFingerprint = decoded.sourceFingerprint
                    ocrStatus = StageStatus.SKIPPED
                    translationStatus = StageStatus.SKIPPED
                    inpaintStatus = StageStatus.SKIPPED
                    originalImageFallback = true
                    updatedAt = System.currentTimeMillis()
                     // colorize() never throws for a page failure: it marks
                     // coloringStatus FAILED and returns null.
                     val upscaler = activeUpscalingEngine()
                     colorize(
                         pageKey = pageKey,
                         page = this,
                         coloringEngine = engine,
                         companionDir = if (upscaler != null) null else companionDir,
                         base = decoded.bitmap,
                         fromOriginal = true,
                     )
                     if (upscaler != null && coloringStatus == StageStatus.READY) {
                         try {
                             upscale(pageKey, this, upscaler, companionDir)
                         } catch (cancelled: CancellationException) {
                             throw cancelled
                         } catch (t: Throwable) {
                             coloringStatus = StageStatus.FAILED
                             coloringError = t.message ?: "Upscaling failed"
                         }
                     }
                 }
                 val commit = try {
                     store.updatePageFromCurrentSnapshot(pageKey, "$origin:colorize-page") { page }
                 } finally {
                     releasePageBitmaps(page)
                 }
                if (commit is ChapterTranslationStore.PatchResult.Rejected) {
                    // The store moved under us — a newer owner owns the state.
                    // Writing a terminal now would clobber it, so the RUNNING
                    // marker is left for that owner to supersede.
                    logcat(LogPriority.WARN) {
                        "TranslationProcessor: colorize commit rejected for page $pageKey: ${commit.reason}"
                    }
                    return PageColorizeOutcome.FAILED
                }
                return if (page.coloringStatus == StageStatus.READY) {
                    PageColorizeOutcome.COMPLETED
                } else {
                    PageColorizeOutcome.FAILED
                }
            } finally {
                releaseDecoded(decoded)
            }
        } catch (cancelled: CancellationException) {
            finishTerminal(
                pageKey,
                store,
                lease.token,
                StageStatus.CANCELLED,
                "Colorization cancelled",
                origin,
            )
            throw cancelled
        } finally {
            store.releasePageStageLease(pageKey, origin, lease.token)
        }
    }

    /**
     * Transitions a stranded RUNNING marker to a terminal state, only while
     * we still own the page lease — an evicted holder must never touch the
     * page a newer owner is writing.
     */
    private suspend fun finishTerminal(
        pageKey: String,
        store: ChapterTranslationStore,
        leaseToken: Long,
        status: String,
        error: String,
        origin: PageWriteOrigin,
    ) {
        withContext(NonCancellable) {
            if (store.pageLeases[pageKey]?.token != leaseToken) return@withContext
            store.updatePageFromCurrentSnapshot(pageKey, "$origin:colorize-terminal") { current ->
                (current ?: PageTranslation(sourceFileName = pageKey)).apply {
                    coloringStatus = status
                    coloringError = error
                    updatedAt = System.currentTimeMillis()
                }
            }
        }
    }

    /** Decodes a source image under the shared memory budget. */
    suspend fun decodeImage(
        fileName: String,
        streamFn: () -> InputStream,
        recognitionEngine: () -> PageRecognitionEngine,
    ): DecodedPage? = PageDecode.decodePageBitmapForTranslation(
        context = context,
        recognitionEngine = recognitionEngine,
        fileName = fileName,
        streamFn = streamFn,
    )

    /** Releases a decoded source bitmap when a caller's OCR/inpaint stages end. */
    fun releaseDecoded(decoded: DecodedPage?) {
        decoded?.bitmap?.let(::recycleIfNeeded)
    }

    fun releasePageBitmaps(page: PageTranslation) {
        page.cleanedBitmap?.let(::recycleIfNeeded)
        page.cleanedBitmap = null
        page.coloredBitmap?.let(::recycleIfNeeded)
        page.coloredBitmap = null
    }

    /** Executes OCR on the serialized native lane. */
    suspend fun analyze(
        pageKey: String,
        bitmap: Bitmap,
        recognitionEngine: PageRecognitionEngine,
        chapterId: Long? = null,
        chapterName: String = "",
        priority: DevicePermitPriority = DevicePermitPriority.BACKGROUND,
    ): PageTranslation = withContext(Dispatchers.Default) {
        var timedOut = false
        var admissionFailed = false
        val result = withNativeLane(
            chapterId = chapterId,
            chapterName = chapterName,
            pageKey = pageKey,
            priority = priority,
            onTimeout = { timedOut = true },
            onAdmissionFailure = { admissionFailed = true },
        ) { recognitionEngine.analyze(bitmap) }
        val page = result ?: PageTranslation(sourceFileName = pageKey).apply {
            ocrStatus = StageStatus.FAILED
            translationStatus = StageStatus.PENDING
            inpaintStatus = StageStatus.PENDING
            errorMessage = when {
                timedOut -> "OCR/native operation timed out"
                admissionFailed -> "OCR/native lane admission failed"
                else -> "OCR/native operation returned no result"
            }
        }
        page.sourceFileName = pageKey
        page.imgWidth = bitmap.width.toFloat()
        page.imgHeight = bitmap.height.toFloat()
        page.updatedAt = System.currentTimeMillis()
        if (page.ocrStatus != StageStatus.FAILED) page.ocrStatus = StageStatus.READY
        if (page.blocks.isEmpty() && page.ocrStatus == StageStatus.READY) {
            logcat(LogPriority.INFO) { "TranslationProcessor: page $pageKey has no text detected (textless)" }
            page.translationStatus = StageStatus.SKIPPED
            page.inpaintStatus = StageStatus.SKIPPED
        } else if (page.ocrStatus == StageStatus.READY) {
            page.translationStatus = StageStatus.PENDING
            page.inpaintStatus = StageStatus.PENDING
        }
        page
    }

    /** Translates text blocks outside the native lane. */
    suspend fun translatePage(pageKey: String, page: PageTranslation, translator: TextTranslator) =
        withContext(Dispatchers.IO) {
            if (page.blocks.isEmpty() || page.isTextlessTerminal) return@withContext
            page.translationStatus = StageStatus.RUNNING
            engineLane?.beginTranslatorUse()
            try {
                translator.translatePage(pageKey, page)
                page.translationStatus = StageStatus.READY
                page.translationError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logcat(LogPriority.ERROR, t) { "TranslationProcessor: translate failed for page $pageKey" }
                page.translationStatus = StageStatus.FAILED
                page.translationError = t.message ?: "Translation failed"
                throw t
            } finally {
                engineLane?.endTranslatorUse()
            }
        }

    /** Batch translation shares the same translator but does not add retry policy. */
    suspend fun translateBatch(pages: MutableMap<String, PageTranslation>, translator: TextTranslator) =
        withContext(Dispatchers.IO) {
            val targets = pages.filterValues { it.blocks.isNotEmpty() && !it.isTextlessTerminal }
            if (targets.isEmpty()) return@withContext
            targets.values.forEach { it.translationStatus = StageStatus.RUNNING }
            engineLane?.beginTranslatorUse()
            try {
                translator.translate(targets.toMutableMap())
                targets.values.forEach {
                    it.translationStatus = StageStatus.READY
                    it.translationError = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logcat(LogPriority.ERROR, t) { "TranslationProcessor: batch translate failed for ${targets.size} pages" }
                targets.values.forEach {
                    it.translationStatus = StageStatus.FAILED
                    it.translationError = t.message ?: "Batch translation failed"
                }
                throw t
            } finally {
                engineLane?.endTranslatorUse()
            }
        }

    /** Inpaints on the native lane and persists a verified cleaned image when possible. */
    suspend fun inpaint(
        pageKey: String,
        bitmap: Bitmap,
        page: PageTranslation,
        recognitionEngine: PageRecognitionEngine,
        companionDir: UniFile? = null,
        chapterId: Long? = null,
        chapterName: String = "",
        priority: DevicePermitPriority = DevicePermitPriority.BACKGROUND,
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (page.blocks.isEmpty() || page.isTextlessTerminal) return@withContext null
        page.inpaintStatus = StageStatus.RUNNING
        var timedOut = false
        var admissionFailed = false
        val cleaned = try {
            withNativeLane(
                chapterId,
                chapterName,
                pageKey,
                priority,
                onTimeout = { timedOut = true },
                onAdmissionFailure = { admissionFailed = true },
            ) { recognitionEngine.inpaint(bitmap, page) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            logcat(LogPriority.ERROR, oom) { "TranslationProcessor: OOM during inpaint for page $pageKey" }
            recognitionEngine.reclaimPooledMemory()
            recognitionEngine.forceReleaseNativeBuffers()
            page.inpaintStatus = StageStatus.FAILED
            page.inpaintError = "Out of memory during inpainting"
            page.originalImageFallback = true
            return@withContext null
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "TranslationProcessor: inpaint failed for page $pageKey" }
            page.inpaintStatus = StageStatus.FAILED
            page.inpaintError = t.message ?: "Inpainting failed"
            page.originalImageFallback = true
            return@withContext null
        }

        if (timedOut) {
            page.inpaintStatus = StageStatus.FAILED
            page.inpaintError = "Inpainting/native operation timed out"
            page.originalImageFallback = true
            return@withContext null
        }
        if (admissionFailed) {
            page.inpaintStatus = StageStatus.FAILED
            page.inpaintError = "Inpainting was not admitted to the native lane"
            page.originalImageFallback = true
            return@withContext null
        }
        if (cleaned == null) {
            if (page.inpaintStatus != StageStatus.FAILED) page.inpaintStatus = StageStatus.READY
            page.originalImageFallback = true
            return@withContext null
        }

        page.cleanedBitmap = cleaned
        page.inpaintStatus = StageStatus.READY
        page.inpaintError = null
        page.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
        try {
            if (companionDir != null) {
                val publication = cleanedPublication
                if (publication != null) {
                    val written = withContext(Dispatchers.IO) {
                        publication.writeVerifiedCleanedImage(companionDir, pageKey, cleaned)
                    }
                    page.cleanedImageName = written.name
                    page.cleanedImageContentHash = written.contentSha256
                    page.inpaintingModeUsed = publication.currentInpaintingStamp()
                }
            }
            if (page.cleanedImageName == null) page.originalImageFallback = true
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (page.cleanedBitmap === cleaned) {
                    recycleIfNeeded(cleaned)
                    page.cleanedBitmap = null
                }
            }
            throw cancelled
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "TranslationProcessor: cleaned image publication failed for $pageKey" }
            page.cleanedImageName = null
            page.cleanedImageContentHash = null
            page.originalImageFallback = true
        }
        cleaned
    }

    /**
     * Colorizes [base] (the cleaned bitmap by default; the decoded original for
     * colorize-only runs) with the attached engine and persists a verified
     * colored image. Never fails the page: a failure only marks
     * [PageTranslation.coloringStatus] FAILED and the reader keeps showing
     * the uncolored result.
     */
    suspend fun colorize(
        pageKey: String,
        page: PageTranslation,
        coloringEngine: PageColorizationEngine,
        companionDir: UniFile? = null,
        base: Bitmap? = page.cleanedBitmap,
        fromOriginal: Boolean = false,
    ): Bitmap? = withContext(Dispatchers.Default) {
        val input = base ?: return@withContext null
        // First call builds the engine runtime; absence is feature-absence, not
        // a page failure. A reported initializationError marks a hard-fail route
        // and must surface as FAILED instead of a silent skip.
        if (!coloringEngine.isAvailable()) {
            val initFailure = coloringEngine.initializationError()
            if (initFailure != null) {
                page.coloringStatus = StageStatus.FAILED
                page.coloringError = initFailure
            } else {
                page.coloringStatus = StageStatus.SKIPPED
            }
            return@withContext null
        }
        page.coloringStatus = StageStatus.RUNNING
        val colored = try {
            // Stable per-page seed: identical re-runs reproduce the identical
            // coloring instead of flickering between variations.
            val seed = stableSeed(pageKey)
            coloringEngine.colorize(input, seed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            logcat(LogPriority.ERROR, oom) { "TranslationProcessor: OOM during colorize for page $pageKey" }
            page.coloringStatus = StageStatus.FAILED
            page.coloringError = "Out of memory during colorization"
            return@withContext null
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "TranslationProcessor: colorize failed for page $pageKey" }
            page.coloringStatus = StageStatus.FAILED
            page.coloringError = t.message ?: "Colorization failed"
            return@withContext null
        }

        page.coloredBitmap = colored
        page.coloringStatus = StageStatus.READY
        page.coloringError = null
        page.coloringStamp = coloringEngine.currentStamp()
        page.coloredFromOriginal = fromOriginal
        try {
            if (companionDir != null) {
                val publication = cleanedPublication
                if (publication != null) {
                    val written = withContext(Dispatchers.IO) {
                        publication.writeVerifiedColoredImage(companionDir, pageKey, colored)
                    }
                    page.coloredImageName = written.name
                    page.coloredImageContentHash = written.contentSha256
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (page.coloredBitmap === colored) {
                    recycleIfNeeded(colored)
                    page.coloredBitmap = null
                    page.coloringStatus = StageStatus.FAILED
                    page.coloringError = "Colorization cancelled during publication"
                }
            }
            throw cancelled
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "TranslationProcessor: colored image publication failed for $pageKey" }
            page.coloredImageName = null
            page.coloredImageContentHash = null
            page.coloringStatus = StageStatus.FAILED
            page.coloringError = "Colored image publication failed"
        }
        colored
    }

    /**
     * Upscales [base] (the colored bitmap if present; the cleaned bitmap otherwise)
     * using [upscalingEngine] by 4x. Enforces zero silent fallback: an unprovisioned
     * or failing engine throws an explicit exception.
     */
    suspend fun upscale(
        pageKey: String,
        page: PageTranslation,
        upscalingEngine: PageUpscalingEngine,
        companionDir: UniFile? = null,
        base: Bitmap? = page.coloredBitmap ?: page.cleanedBitmap,
    ): Bitmap? = withContext(Dispatchers.Default) {
        val input = base ?: return@withContext null
        if (!upscalingEngine.isAvailable()) {
            val initFailure = upscalingEngine.initializationError()
                ?: "QuickSRNet upscaling engine unavailable"
            logcat(LogPriority.ERROR) { "TranslationProcessor: upscaling unavailable for page $pageKey: $initFailure" }
            throw IllegalStateException("Upscaling failed for page $pageKey: $initFailure")
        }
        val upscaled = try {
            upscalingEngine.upscalePage(input)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            logcat(LogPriority.ERROR, oom) { "TranslationProcessor: OOM during upscale for page $pageKey" }
            throw oom
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "TranslationProcessor: upscale failed for page $pageKey" }
            throw t
        }

        if (page.coloredBitmap === input) {
            if (input !== upscaled) recycleIfNeeded(input)
            page.coloredBitmap = upscaled
            try {
                if (companionDir != null) {
                    val publication = cleanedPublication
                    if (publication != null) {
                        val written = withContext(Dispatchers.IO) {
                            publication.writeVerifiedColoredImage(companionDir, pageKey, upscaled)
                        }
                        page.coloredImageName = written.name
                        page.coloredImageContentHash = written.contentSha256
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    if (page.coloredBitmap === upscaled) {
                        recycleIfNeeded(upscaled)
                        page.coloredBitmap = null
                        page.coloringStatus = StageStatus.FAILED
                        page.coloringError = "Upscaling cancelled during publication"
                    }
                }
                throw cancelled
            } catch (t: Throwable) {
                logcat(LogPriority.WARN, t) { "TranslationProcessor: upscaled colored image publication failed for $pageKey" }
                page.coloredImageName = null
                page.coloredImageContentHash = null
                page.coloringStatus = StageStatus.FAILED
                page.coloringError = "Upscaled colored image publication failed"
            }
        } else if (page.cleanedBitmap === input) {
            if (input !== upscaled) recycleIfNeeded(input)
            page.cleanedBitmap = upscaled
            try {
                if (companionDir != null) {
                    val publication = cleanedPublication
                    if (publication != null) {
                        val written = withContext(Dispatchers.IO) {
                            publication.writeVerifiedCleanedImage(companionDir, pageKey, upscaled)
                        }
                        page.cleanedImageName = written.name
                        page.cleanedImageContentHash = written.contentSha256
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    if (page.cleanedBitmap === upscaled) {
                        recycleIfNeeded(upscaled)
                        page.cleanedBitmap = null
                    }
                }
                throw cancelled
            } catch (t: Throwable) {
                logcat(LogPriority.WARN, t) { "TranslationProcessor: upscaled cleaned image publication failed for $pageKey" }
                page.cleanedImageName = null
                page.cleanedImageContentHash = null
                page.originalImageFallback = true
            }
        }
        upscaled
    }

    /** Stable 64-bit page seed for the diffusion sampler. */
    private fun stableSeed(pageKey: String): Long {
        var hash = 1125899906842597L
        for (char in pageKey) {
            hash = 31 * hash + char.code
        }
        return hash
    }

    /** Decode → OCR → translate → inpaint, with operation and native-lane timeouts. */
    suspend fun processSinglePage(
        pageKey: String,
        streamFn: () -> InputStream,
        recognitionEngine: PageRecognitionEngine,
        translator: TextTranslator,
        companionDir: UniFile? = null,
        inpaintingEnabled: Boolean = true,
        chapterId: Long? = null,
        chapterName: String = "",
        priority: DevicePermitPriority = DevicePermitPriority.BACKGROUND,
        stageReporter: ((TranslationStageSignal) -> Unit)? = null,
    ): ProcessResult {
        var sourceBitmap: Bitmap? = null
        var page: PageTranslation? = null
        var activeStage = "DECODE"
        // Colorization extends the page budget: 20 diffusion steps can exceed
        // the whole translate+inpaint budget when demoted to CPU.
        val colorizationEnabled = activeColorizationEngine() != null
        val upscalingEnabled = activeUpscalingEngine() != null
        val pageTimeoutMs = TranslationProcessingTimeouts.SINGLE_PAGE_TIMEOUT_MS +
            (if (colorizationEnabled) TranslationProcessingTimeouts.COLORING_PHASE_TIMEOUT_MS else 0L) +
            (if (upscalingEnabled) TranslationProcessingTimeouts.UPSCALING_PHASE_TIMEOUT_MS else 0L)
        return try {
            // TachiyomiAT: publish the transient Preparing boundary before any
            // work starts so feedback can distinguish "queued" from "running".
            stageReporter?.invoke(TranslationStageSignal.PREPARING)
            withTimeout(pageTimeoutMs) {
                val decoded = decodeImage(pageKey, streamFn) { recognitionEngine }
                    ?: return@withTimeout ProcessResult.Failure(
                        pageKey,
                        "DECODE",
                        "Failed to decode page image for $pageKey",
                        page = PageTranslation(sourceFileName = pageKey).apply {
                            ocrStatus = StageStatus.FAILED
                            errorMessage = "Failed to decode page image for $pageKey"
                        },
                    )
                sourceBitmap = decoded.bitmap
                activeStage = "OCR"
                // TachiyomiAT: decodeImage performs BOTH text detection and OCR
                // in recognitionEngine.analyze for the reader pipeline, so the
                // combined signal after decode is Reading-text. It must NEVER
                // claim a render/cleaning state that has not started yet.
                stageReporter?.invoke(TranslationStageSignal.READING)
                page = analyze(pageKey, decoded.bitmap, recognitionEngine, chapterId, chapterName, priority).apply {
                    originalImgWidth = decoded.originalWidth.toFloat()
                    originalImgHeight = decoded.originalHeight.toFloat()
                    decodeSampleSize = decoded.sampleSize
                    sourceFingerprint = decoded.sourceFingerprint
                }
                if (page!!.ocrStatus == StageStatus.FAILED) {
                    return@withTimeout ProcessResult.Failure(
                        pageKey,
                        "OCR",
                        page!!.ocrError ?: page!!.errorMessage ?: "OCR failed",
                        page = page,
                    )
                }
                if (page!!.isTextlessTerminal) return@withTimeout ProcessResult.Success(page!!)
                activeStage = "TRANSLATION"
                stageReporter?.invoke(TranslationStageSignal.TRANSLATING)
                try {
                    translatePage(pageKey, page!!, translator)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    return@withTimeout ProcessResult.Failure(
                        pageKey,
                        "TRANSLATION",
                        page!!.translationError ?: "Translation failed",
                        t,
                        page,
                    )
                }
                if (inpaintingEnabled) {
                    activeStage = "INPAINT"
                    stageReporter?.invoke(TranslationStageSignal.CLEANING)
                    inpaint(pageKey, decoded.bitmap, page!!, recognitionEngine, companionDir, chapterId, chapterName, priority)
                } else {
                    activeStage = "INPAINT"
                    page!!.inpaintStatus = StageStatus.READY
                    page!!.originalImageFallback = true
                }
                // TachiyomiAT: colorization is a derived post-inpaint stage and
                // never re-fails the page; its pill time folds under CLEANING.
                val coloringEngine = activeColorizationEngine()
                val upscaler = activeUpscalingEngine()
                if (coloringEngine != null) {
                    if (page!!.cleanedBitmap != null) {
                        activeStage = "COLOR"
                        stageReporter?.invoke(TranslationStageSignal.COLORING)
                        colorize(pageKey, page!!, coloringEngine, if (upscaler != null) null else companionDir)
                    } else {
                        page!!.coloringStatus = StageStatus.SKIPPED
                    }
                }
                if (upscaler != null) {
                    activeStage = "UPSCALE"
                    upscale(pageKey, page!!, upscaler, companionDir)
                }
                // TachiyomiAT: the candidate is assembled; remaining work is
                // persistence of the final record by the caller. Signal this so
                // the status pill can distinguish last-mile latency.
                stageReporter?.invoke(TranslationStageSignal.FINISHING)
                ProcessResult.Success(page!!)
            }
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) {
                val current = page
                if (current != null) {
                    when (activeStage) {
                        "DECODE", "OCR" -> {
                            current.ocrStatus = StageStatus.FAILED
                            current.ocrError = "Single-page OCR operation timed out"
                        }
                        "INPAINT" -> {
                            current.inpaintStatus = StageStatus.FAILED
                            current.inpaintError = "Single-page inpainting timed out"
                            current.originalImageFallback = true
                        }
                        "COLOR" -> {
                            current.coloringStatus = StageStatus.FAILED
                            current.coloringError = "Single-page colorization timed out"
                        }
                        "UPSCALE" -> {
                            current.translationStatus = StageStatus.FAILED
                            current.translationError = "Single-page upscaling timed out"
                        }
                        else -> {
                            current.translationStatus = StageStatus.FAILED
                            current.translationError = "Single-page translation timed out"
                        }
                    }
                }
                ProcessResult.Failure(pageKey, activeStage, "Single-page operation timed out", cancelled, current)
            } else {
                throw cancelled
            }
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "TranslationProcessor: processSinglePage failed for $pageKey" }
            page?.let {
                if (it.translationStatus == StageStatus.RUNNING) {
                    it.translationStatus = StageStatus.FAILED
                    it.translationError = t.message ?: "Translation processing failed"
                }
            }
            ProcessResult.Failure(pageKey, "PROCESSING", t.message ?: "Translation processing failed", t, page)
        } finally {
            withContext(NonCancellable) {
                page?.let(::releasePageBitmaps)
                sourceBitmap?.let(::recycleIfNeeded)
            }
        }
    }

    private suspend fun <T> withNativeLane(
        chapterId: Long?,
        chapterName: String,
        pageKey: String,
        priority: DevicePermitPriority,
        onTimeout: suspend () -> Unit,
        onAdmissionFailure: () -> Unit,
        block: suspend () -> T,
    ): T? {
        val engineLane = engineLane ?: return block()
        if (!inFlightPageKeys.add(pageKey)) {
            onAdmissionFailure()
            return null
        }
        return try {
            val permit = devicePagePermitGate.acquire(priority) ?: run {
                onAdmissionFailure()
                return null
            }
            try {
                engineLane.withNativeLane(
                    timeoutMs = TranslationProcessingTimeouts.ONNX_PHASE_TIMEOUT_MS,
                    chapterId = chapterId,
                    chapterName = chapterName,
                    pageKey = pageKey,
                    onTimeout = onTimeout,
                ) { block() }
            } finally {
                permit.release()
            }
        } finally {
            inFlightPageKeys.remove(pageKey)
        }
    }

    private fun recycleIfNeeded(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    override fun close() {
        colorizationEngine?.let { engine -> runCatching { engine.close() } }
        engineLane?.closeEngines()
        nativeRunScope.cancel()
    }
}
