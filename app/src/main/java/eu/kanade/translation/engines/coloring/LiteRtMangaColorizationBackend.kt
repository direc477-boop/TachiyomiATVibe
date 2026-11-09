package eu.kanade.translation.engines.coloring

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.min
import kotlin.math.roundToInt

/** LiteRT GPU implementation for the fixed-shape MCV2 fp32 graph. */
internal class LiteRtMangaColorizationBackend(
    private val context: Context,
) : ColorizationBackend<Bitmap> {

    override val name: String = "LiteRT GPU"

    private class GraphSession(
        val model: CompiledModel,
        val input: TensorBuffer,
        val output: TensorBuffer,
        val artifact: ModelArtifact,
    )

    private data class ModelArtifact(val file: File, val sha256: String)

    private var environment: Environment? = null
    private var session: GraphSession? = null
    private var initialized = false
    private var stamp: String? = null

    override fun supports(input: Bitmap): Boolean = !MangaColorizationLetterbox.fit(
        input.width,
        input.height,
        INPUT_WIDTH,
        INPUT_HEIGHT,
    ).requiresCpuFallback

    @Synchronized
    override fun isAvailable(): Boolean {
        if (initialized) return session != null
        initialized = true
        return try {
            val artifact = extractModel() ?: return false
            val env = Environment.create(context)
            environment = env
            val accelerators = try {
                env.getAvailableAccelerators()
            } catch (failure: Throwable) {
                if (failure.isFatal()) throw failure
                logcat(LogPriority.WARN, failure) { "[Mcv2Color] accelerator query failed; probing LiteRT GPU" }
                emptySet()
            }
            if (accelerators.isNotEmpty() && Accelerator.GPU !in accelerators) {
                logcat(LogPriority.INFO) { "[Mcv2Color] LiteRT GPU accelerator is unavailable" }
                closeRuntime()
                return false
            }

            session = createSession(artifact, env)
            val active = session ?: run {
                closeRuntime()
                return false
            }
            stamp = "MC_V2_LITERT_GPU_FP32_704x512_${active.artifact.sha256}"
            logcat(LogPriority.INFO) {
                "[Mcv2Color] LiteRT GPU ready (704x512 fp32, sha256=${active.artifact.sha256})"
            }
            true
        } catch (failure: Throwable) {
            if (failure.isFatal()) throw failure
            logcat(LogPriority.WARN, failure) { "[Mcv2Color] LiteRT GPU initialization failed" }
            closeRuntime()
            false
        }
    }

    /** Stamp is pipeline-config identity: the suffix marks the built-in screentone smoothing stage
     *  that always runs on this route — never a per-page outcome. */
    @Synchronized
    override fun currentStamp(): String? = stamp?.plus(MangaColorizationPreprocessor.STAMP_SUFFIX)

    @Synchronized
    override fun colorize(source: Bitmap, seed: Long): Bitmap {
        val active = session ?: error("LiteRT MCV2 backend is unavailable")
        // Quality-only preprocessing on the aspect-preserved content rect; padding
        // is applied strictly after, and stage failures degrade to the plain downsample.
        val letterbox = MangaColorizationLetterbox.fit(source.width, source.height, INPUT_WIDTH, INPUT_HEIGHT)
        val preprocessed = MangaColorizationPreprocessor.grayPlane(source, letterbox.width, letterbox.height)
        logcat(LogPriority.INFO) {
            "[Mcv2Color] ScreentoneSmooth score=" + preprocessed.score +
                " smoothed=" + preprocessed.smoothingApplied
        }
        val input = MangaColorizationPreprocessor.grayNchwFromGrayPlane(
            preprocessed.plane,
            letterbox,
            INPUT_WIDTH,
            INPUT_HEIGHT,
        )
        active.input.writeFloat(input)
        active.model.run(
            mapOf(INPUT_NAME to active.input),
            mapOf(OUTPUT_NAME to active.output),
            SIGNATURE,
        )
        val output = active.output.readFloat()
        check(output.size == OUTPUT_FLOAT_COUNT) {
            "LiteRT MCV2 returned ${output.size} floats; expected $OUTPUT_FLOAT_COUNT"
        }
        check(output.all { it.isFinite() }) { "LiteRT MCV2 returned non-finite values" }
        check(output.any { it != 0f }) { "LiteRT MCV2 returned an all-zero image" }

        val coloredWork = MangaColorizationImageProcessing.interleavedRgb01ToBitmap(
            cropOutput(output, letterbox),
            letterbox.width,
            letterbox.height,
        )
        return try {
            MangaColorizationImageProcessing.combineColoredWithSource(source, coloredWork)
        } finally {
            coloredWork.recycle()
        }
    }

    @Synchronized
    override fun close() {
        closeRuntime()
        initialized = false
    }

    private fun createSession(artifact: ModelArtifact, env: Environment): GraphSession? {
        var model: CompiledModel? = null
        var input: TensorBuffer? = null
        var output: TensorBuffer? = null
        try {
            model = CompiledModel.create(artifact.file.absolutePath, CompiledModel.Options(Accelerator.GPU), env)
            validateTensor(model, INPUT_NAME, INPUT_DIMS, isInput = true)
            validateTensor(model, OUTPUT_NAME, OUTPUT_DIMS, isInput = false)
            input = model.createInputBuffer(INPUT_NAME, SIGNATURE)
            output = model.createOutputBuffer(OUTPUT_NAME, SIGNATURE)
            return GraphSession(model, input, output, artifact)
        } catch (failure: Throwable) {
            if (failure.isFatal()) throw failure
            input?.let { runCatching { it.close() } }
            output?.let { runCatching { it.close() } }
            model?.let { runCatching { it.close() } }
            logcat(LogPriority.WARN, failure) { "[Mcv2Color] LiteRT GPU rejected ${artifact.file.name}" }
            return null
        }
    }

    private fun validateTensor(model: CompiledModel, name: String, dimensions: List<Int>, isInput: Boolean) {
        val type = if (isInput) {
            model.getInputTensorType(name, SIGNATURE)
        } else {
            model.getOutputTensorType(name, SIGNATURE)
        }
        check(type.elementType == TensorType.ElementType.FLOAT) {
            "LiteRT MCV2 tensor '$name' is not float32"
        }
        check(type.layout?.dimensions == dimensions) {
            "LiteRT MCV2 tensor '$name' has dimensions ${type.layout?.dimensions}; expected $dimensions"
        }
    }

    private fun cropOutput(output: FloatArray, content: MangaColorizationLetterbox): FloatArray =
        MangaColorizationImageProcessing.cropRgb01Letterbox(output, content, INPUT_WIDTH, INPUT_HEIGHT)

    private fun extractModel(): ModelArtifact? {
        val assetExists = try {
            context.assets.open(MODEL_ASSET).use { }
            true
        } catch (_: FileNotFoundException) {
            false
        }
        if (!assetExists) return null

        val assetLength = runCatching {
            context.assets.openFd(MODEL_ASSET).use { it.length }
        }.getOrNull()
        val destination = File(context.noBackupFilesDir, "$MODEL_CACHE_DIR/$MODEL_FILE_NAME")
        destination.parentFile?.mkdirs()
        val preferences = context.getSharedPreferences(MODEL_CACHE_PREFERENCES, Context.MODE_PRIVATE)
        val appUpdateTime = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        val cacheKey = MODEL_FILE_NAME
        val cachedHash = preferences.getString(KEY_SHA256 + cacheKey, null)
        val cacheIsValid = destination.isFile && destination.length() > 0L &&
            preferences.getLong(KEY_APP_UPDATE_TIME + cacheKey, -1L) == appUpdateTime &&
            preferences.getLong(KEY_EXTRACTED_SIZE + cacheKey, -1L) == destination.length() &&
            (assetLength == null || destination.length() == assetLength) &&
            cachedHash?.matches(SHA256_PATTERN) == true
        if (cacheIsValid) return ModelArtifact(destination, checkNotNull(cachedHash))

        val temporary = File(destination.parentFile, "$MODEL_FILE_NAME.tmp")
        return try {
            val sha256 = MangaColorizationModelStore.copyAssetWithDigest(context, MODEL_ASSET, temporary)
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            preferences.edit()
                .putLong(KEY_APP_UPDATE_TIME + cacheKey, appUpdateTime)
                .putLong(KEY_EXTRACTED_SIZE + cacheKey, destination.length())
                .putString(KEY_SHA256 + cacheKey, sha256)
                .apply()
            ModelArtifact(destination, sha256)
        } catch (failure: Throwable) {
            temporary.delete()
            if (failure.isFatal()) throw failure
            logcat(LogPriority.WARN, failure) { "[Mcv2Color] failed to extract $MODEL_ASSET" }
            null
        }
    }

    private fun closeRuntime() {
        session?.let { active ->
            runCatching { active.input.close() }
            runCatching { active.output.close() }
            runCatching { active.model.close() }
        }
        session = null
        stamp = null
        runCatching { environment?.close() }
        environment = null
    }

    private companion object {
        const val MODEL_ASSET = "models/coloring/manga_colorize_gpu_fp32.tflite"
        const val MODEL_CACHE_DIR = "tachiyomi-models/coloring"
        const val MODEL_CACHE_PREFERENCES = "mcv2_litert_model_cache"
        const val MODEL_FILE_NAME = "manga_colorize_gpu_fp32.tflite"
        const val KEY_APP_UPDATE_TIME = "app_update_time_"
        const val KEY_EXTRACTED_SIZE = "extracted_size_"
        const val KEY_SHA256 = "sha256_"
        const val SIGNATURE = "serving_default"
        const val INPUT_NAME = "input"
        const val OUTPUT_NAME = "rgb"
        const val INPUT_WIDTH = 512
        const val INPUT_HEIGHT = 704
        const val CHANNELS = 5
        const val CHANNELS_RGB = 3
        const val OUTPUT_FLOAT_COUNT = INPUT_WIDTH * INPUT_HEIGHT * CHANNELS_RGB
        val INPUT_DIMS = listOf(1, CHANNELS, INPUT_HEIGHT, INPUT_WIDTH)
        val OUTPUT_DIMS = listOf(1, INPUT_HEIGHT, INPUT_WIDTH, CHANNELS_RGB)
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

internal data class MangaColorizationLetterbox(
    val width: Int,
    val height: Int,
    val left: Int,
    val top: Int,
) {
    val requiresCpuFallback: Boolean
        get() = minOf(width, height) < MINIMUM_GPU_CONTENT_DIMENSION

    companion object {
        private const val MINIMUM_GPU_CONTENT_DIMENSION = 256

        fun fit(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): MangaColorizationLetterbox {
            require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
            require(targetWidth > 0 && targetHeight > 0) { "Target dimensions must be positive" }
            val scale = min(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)
            val width = (sourceWidth * scale).roundToInt().coerceIn(1, targetWidth)
            val height = (sourceHeight * scale).roundToInt().coerceIn(1, targetHeight)
            return MangaColorizationLetterbox(
                width = width,
                height = height,
                left = (targetWidth - width) / 2,
                top = (targetHeight - height) / 2,
            )
        }
    }
}
