import re

tp_path = r"app/src/main/java/eu/kanade/translation/processing/TranslationProcessor.kt"
with open(tp_path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Add import
import_needle = "import eu.kanade.translation.engines.coloring.PageColorizationEngine\n"
new_import = (
    "import eu.kanade.translation.engines.coloring.PageColorizationEngine\n"
    "import eu.kanade.translation.engines.upscaling.PageUpscalingEngine\n"
)
assert import_needle in content, "import needle not found in TranslationProcessor.kt"
content = content.replace(import_needle, new_import, 1)

# 2. Add properties
prop_needle = """    /** The colorization engine only when attached and the user gate is on. */
    fun activeColorizationEngine(): PageColorizationEngine? =
        colorizationEngine?.takeIf { translationPreferences?.translationColorizationEnabled()?.get() == true }"""

new_prop = """    /** The colorization engine only when attached and the user gate is on. */
    fun activeColorizationEngine(): PageColorizationEngine? =
        colorizationEngine?.takeIf { translationPreferences?.translationColorizationEnabled()?.get() == true }

    /** Owned by TranslationManager; the upscaling stage gates on the pref at stage time. */
    @Volatile
    var upscalingEngine: PageUpscalingEngine? = null

    /** The upscaling engine only when attached and the user gate is on. */
    fun activeUpscalingEngine(): PageUpscalingEngine? =
        upscalingEngine?.takeIf { translationPreferences?.translationUpscalingEnabled()?.get() == true }"""
assert prop_needle in content, "prop needle not found in TranslationProcessor.kt"
content = content.replace(prop_needle, new_prop, 1)

# 3. Update closeEngines
close_needle = """    fun closeEngines() {
        // Attached (not detached) so a restart in the same session colorizes again.
        colorizationEngine?.let { engine -> runCatching { engine.close() } }
        requireEngineLane().closeEngines()
    }"""

new_close = """    fun closeEngines() {
        // Attached (not detached) so a restart in the same session colorizes again.
        colorizationEngine?.let { engine -> runCatching { engine.close() } }
        upscalingEngine?.let { engine -> runCatching { engine.close() } }
        requireEngineLane().closeEngines()
    }"""
assert close_needle in content, "close needle not found in TranslationProcessor.kt"
content = content.replace(close_needle, new_close, 1)

# 4. Update colorizeOriginalPage
colorize_orig_needle = """                // colorize() never throws for a page failure: it marks
                // coloringStatus FAILED and returns null.
                colorize(
                    pageKey = pageKey,
                    page = this,
                    coloringEngine = engine,
                    companionDir = companionDir,
                    base = decoded.bitmap,
                    fromOriginal = true,
                )
            }"""

new_colorize_orig = """                // colorize() never throws for a page failure: it marks
                // coloringStatus FAILED and returns null.
                colorize(
                    pageKey = pageKey,
                    page = this,
                    coloringEngine = engine,
                    companionDir = companionDir,
                    base = decoded.bitmap,
                    fromOriginal = true,
                )
                val upscaler = activeUpscalingEngine()
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
            }"""
assert colorize_orig_needle in content, "colorize_orig needle not found in TranslationProcessor.kt"
content = content.replace(colorize_orig_needle, new_colorize_orig, 1)

# 5. Add upscale method after colorize method
# Find stableSeed definition and place upscale before stableSeed
seed_needle = """    /** Stable 64-bit page seed for the diffusion sampler. */
    private fun stableSeed(pageKey: String): Long {"""

upscale_method = """    /**
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

        page.imgWidth = upscaled.width.toFloat()
        page.imgHeight = upscaled.height.toFloat()
        val scale = PageUpscalingEngine.SCALE
        page.blocks.forEach { block ->
            block.x *= scale
            block.y *= scale
            block.width *= scale
            block.height *= scale
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
    private fun stableSeed(pageKey: String): Long {"""
assert seed_needle in content, "seed needle not found in TranslationProcessor.kt"
content = content.replace(seed_needle, upscale_method, 1)

# 6. Update processSinglePage timeout and execution
timeout_needle = """        val colorizationEnabled = activeColorizationEngine() != null
        val pageTimeoutMs = TranslationProcessingTimeouts.SINGLE_PAGE_TIMEOUT_MS +
            if (colorizationEnabled) TranslationProcessingTimeouts.COLORING_PHASE_TIMEOUT_MS else 0L"""

new_timeout = """        val colorizationEnabled = activeColorizationEngine() != null
        val upscalingEnabled = activeUpscalingEngine() != null
        val pageTimeoutMs = TranslationProcessingTimeouts.SINGLE_PAGE_TIMEOUT_MS +
            (if (colorizationEnabled) TranslationProcessingTimeouts.COLORING_PHASE_TIMEOUT_MS else 0L) +
            (if (upscalingEnabled) TranslationProcessingTimeouts.UPSCALING_PHASE_TIMEOUT_MS else 0L)"""
assert timeout_needle in content, "timeout needle not found in TranslationProcessor.kt"
content = content.replace(timeout_needle, new_timeout, 1)

stage_call_needle = """                val coloringEngine = activeColorizationEngine()
                if (coloringEngine != null) {
                    if (page!!.cleanedBitmap != null) {
                        activeStage = "COLOR"
                        stageReporter?.invoke(TranslationStageSignal.COLORING)
                        colorize(pageKey, page!!, coloringEngine, companionDir)
                    } else {
                        page!!.coloringStatus = StageStatus.SKIPPED
                    }
                }
                // TachiyomiAT: the candidate is assembled; remaining work is"""

new_stage_call = """                val coloringEngine = activeColorizationEngine()
                if (coloringEngine != null) {
                    if (page!!.cleanedBitmap != null) {
                        activeStage = "COLOR"
                        stageReporter?.invoke(TranslationStageSignal.COLORING)
                        colorize(pageKey, page!!, coloringEngine, companionDir)
                    } else {
                        page!!.coloringStatus = StageStatus.SKIPPED
                    }
                }
                val upscaler = activeUpscalingEngine()
                if (upscaler != null) {
                    activeStage = "UPSCALE"
                    upscale(pageKey, page!!, upscaler, companionDir)
                }
                // TachiyomiAT: the candidate is assembled; remaining work is"""
assert stage_call_needle in content, "stage_call needle not found in TranslationProcessor.kt"
content = content.replace(stage_call_needle, new_stage_call, 1)

# 7. Update timeout cancellation in processSinglePage
timeout_cancel_needle = """                        "COLOR" -> {
                            current.coloringStatus = StageStatus.FAILED
                            current.coloringError = "Single-page colorization timed out"
                        }"""

new_timeout_cancel = """                        "COLOR" -> {
                            current.coloringStatus = StageStatus.FAILED
                            current.coloringError = "Single-page colorization timed out"
                        }
                        "UPSCALE" -> {
                            current.translationStatus = StageStatus.FAILED
                            current.translationError = "Single-page upscaling timed out"
                        }"""
assert timeout_cancel_needle in content, "timeout_cancel needle not found in TranslationProcessor.kt"
content = content.replace(timeout_cancel_needle, new_timeout_cancel, 1)

with open(tp_path, "w", encoding="utf-8") as f:
    f.write(content)

print("TranslationProcessor.kt updated successfully")
