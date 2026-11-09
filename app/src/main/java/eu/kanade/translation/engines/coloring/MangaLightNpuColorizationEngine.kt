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

/**
 * SM8650 NPU route for the Manga Light colorizer (v6 generator, zero-SAM/zero-WD14
 * fold): HARD FAIL, no fallback, same policy as [NpuMangaColorizationEngine].
 *
 * Artifact contract (fixed 512×512 generator-only, byte-verified at trial parity
 * max diff ≈ 4·10⁻⁶ vs ONNX Runtime fp32):
 *  - input  `L_bw`    (1,512,512,1) float32 NHWC, model units = gray/127.5 − 1
 *  - output `rgb_pred` (1,512,512,3) float32 NHWC, pre-denormalization ≈ [−1, 1]
 *  - signature `serving_default`
 * Unlike MCV2, preprocessing is a plain square resize — no letterbox, no
 * 5-channel stacking, no screentone smoothing stage. Source ink is preserved by
 * the pipeline-wide ink-aware merge, matching the other coloring backends.
 */
internal class MangaLightNpuColorizationEngine(
    private val deps: Dependencies,
) : PageColorizationEngine {

    /** Production wiring: the Android context drives artifact, pre-check, and LiteRT. */
    constructor(context: Context) : this(Dependencies.from(context))

    /** One live compiled model with its bound buffers and owning environment. */
    private class Session(
        val runtime: Dependencies.Session,
        val artifactSha256: String,
    )

    private var session: Session? = null
    private var initialized = false
    private var initFailure: String? = null
    private var stamp: String? = null

    @Synchronized
    override fun isAvailable(): Boolean {
        if (initialized) return session != null
        initialized = true
        val initStart = System.nanoTime()
        return try {
            val precheck = deps.precheck()
            check(precheck == null) { precheck ?: "Qualcomm NPU runtime unavailable" }
            val artifact = deps.artifact() ?: error("Manga Light NPU artifact is not present in app assets")
            check(artifact.sha256 == EXPECTED_ARTIFACT_SHA256) {
                "Manga Light NPU artifact hash ${artifact.sha256} does not match the compiled contract " +
                    "($EXPECTED_ARTIFACT_SHA256); refusing to run an unverified artifact on the NPU"
            }
            val runtime = deps.openSession(artifact)
            session = Session(runtime, artifact.sha256)
            stamp = STAMP_PREFIX + "_" + artifact.sha256
            logcat(LogPriority.INFO) {
                "[MangaLightNpu] session created (Accelerator.NPU requested; actual execution not independently verified, " +
                    "512x512 generator-only zero-SAM, sha256=${artifact.sha256}, init=${elapsedMs(initStart)}ms)"
            }
            true
        } catch (failure: Throwable) {
            if (failure.isFatal()) throw failure
            initFailure = failure.message ?: failure.javaClass.simpleName
            logcat(LogPriority.ERROR, failure) {
                "[MangaLightNpu] NPU initialization failed (no fallback on this route)"
            }
            closeRuntime()
            false
        }
    }

    override fun initializationError(): String? = initFailure

    @Synchronized
    override fun currentStamp(): String? = stamp

    @Synchronized
    override fun colorize(source: Bitmap, seed: Long): Bitmap {
        // seed is unused: the generator is deterministic.
        val active = session ?: throw IllegalStateException(
            initFailure?.let { "Manga Light NPU colorization unavailable (no fallback): $it" }
                ?: "Manga Light NPU colorization unavailable (no fallback)",
        )
        val pageStart = System.nanoTime()

        // Plain square resize to the fixed inference size; the model sees the whole
        // page frame with no letterbox and no aspect preservation — the exact
        // operating point the artifact was compiled and validated for.
        val luma01 = MangaColorizationImageProcessing.lumaFromArgb(
            MangaColorizationImageProcessing.scaledArgb(source, INPUT_SIZE, INPUT_SIZE),
        )
        val input = normalizeLuma01(luma01)
        val preprocessEnd = System.nanoTime()

        val output = active.runtime.run(input)
        val inferenceEnd = System.nanoTime()

        check(output.size == OUTPUT_FLOAT_COUNT) {
            "Manga Light NPU returned ${output.size} floats; expected $OUTPUT_FLOAT_COUNT"
        }
        check(output.all { it.isFinite() }) { "Manga Light NPU returned non-finite values" }
        check(output.any { it != 0f }) { "Manga Light NPU returned an all-zero image" }

        val coloredWork = MangaColorizationImageProcessing.interleavedRgb01ToBitmap(
            denormalizeToRgb01(output),
            INPUT_SIZE,
            INPUT_SIZE,
        )
        return try {
            MangaColorizationImageProcessing.combineColoredWithSource(source, coloredWork)
        } finally {
            coloredWork.recycle()
            logcat(LogPriority.INFO) {
                "[MangaLightNpu] page ${source.width}x${source.height} " +
                    "preprocess=${elapsedMs(pageStart, preprocessEnd)}ms " +
                    "inference=${elapsedMs(preprocessEnd, inferenceEnd)}ms " +
                    "postprocess=${elapsedMs(inferenceEnd)}ms " +
                    "total=${elapsedMs(pageStart)}ms"
            }
        }
    }

    @Synchronized
    override fun close() {
        closeRuntime()
        initialized = false
    }

    private fun closeRuntime() {
        session?.let { runCatching { it.runtime.close() } }
        session = null
        stamp = null
    }

    /** Test seam separating engine policy from Android/LiteRT plumbing; tests substitute fakes. */
    internal class Dependencies(
        /** Extracts and hashes the staged artifact, or null when it is absent. */
        val artifact: () -> Artifact?,
        /** Non-null typed reason when the device cannot support this route. */
        val precheck: () -> String?,
        /** Full native bring-up; throws a descriptive failure on any problem. */
        val openSession: (Artifact) -> Session,
    ) {
        internal class Artifact(val file: File, val sha256: String)

        internal interface Session : AutoCloseable {
            /** Runs one inference; returns the interleaved RGB output in model units. */
            fun run(input: FloatArray): FloatArray
        }

        internal companion object {
            fun from(context: Context): Dependencies {
                val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
                return Dependencies(
                    artifact = { extractModel(context) },
                    precheck = { dispatchPrecheck(File(nativeLibraryDir)) },
                    openSession = { artifact -> LiteRtSession.open(context, artifact) },
                )
            }

            /** Typed pre-check before native calls: absent libs otherwise fail opaquely (debug CheckJNI aborts). */
            internal fun dispatchPrecheck(nativeLibraryDir: File): String? {
                val missing = REQUIRED_LIBRARIES.filterNot { File(nativeLibraryDir, it).isFile }
                if (missing.isEmpty()) return null
                return "Qualcomm NPU dispatch libraries missing from ${nativeLibraryDir.absolutePath}: " +
                    missing.joinToString(", ")
            }

            /** Streams the asset into a version-stamped cache, hashing as it copies. */
            private fun extractModel(context: Context): Artifact? {
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
                val cachedHash = preferences.getString(KEY_SHA256 + MODEL_FILE_NAME, null)
                val cacheIsValid = destination.isFile &&
                    destination.length() > 0L &&
                    preferences.getLong(KEY_APP_UPDATE_TIME + MODEL_FILE_NAME, -1L) == appUpdateTime &&
                    preferences.getLong(KEY_EXTRACTED_SIZE + MODEL_FILE_NAME, -1L) == destination.length() &&
                    (assetLength == null || destination.length() == assetLength) &&
                    cachedHash?.matches(SHA256_PATTERN) == true
                // The pinned hash must judge the file's real bytes, never the
                // recorded one - recompute even on a cache hit so a corrupted
                // cache fails visibly instead of reaching the device.
                if (cacheIsValid) return Artifact(destination, MangaColorizationModelStore.sha256Of(destination))

                val temporary = File(destination.parentFile, "$MODEL_FILE_NAME.tmp")
                return try {
                    val sha256 = MangaColorizationModelStore.copyAssetWithDigest(context, MODEL_ASSET, temporary)
                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        temporary.delete()
                    }
                    preferences.edit()
                        .putLong(KEY_APP_UPDATE_TIME + MODEL_FILE_NAME, appUpdateTime)
                        .putLong(KEY_EXTRACTED_SIZE + MODEL_FILE_NAME, destination.length())
                        .putString(KEY_SHA256 + MODEL_FILE_NAME, sha256)
                        .apply()
                    Artifact(destination, sha256)
                } catch (failure: Throwable) {
                    temporary.delete()
                    if (failure.isFatal()) throw failure
                    throw IllegalStateException("Failed to extract $MODEL_ASSET: ${failure.message}", failure)
                }
            }
        }
    }

    /** Pure AOT-load; a compiler plugin engaging JIT here is a red flag. */
    private class LiteRtSession private constructor(
        private val environment: Environment,
        private val model: CompiledModel,
        private val input: TensorBuffer,
        private val output: TensorBuffer,
    ) : Dependencies.Session {

        override fun run(input: FloatArray): FloatArray {
            this.input.writeFloat(input)
            model.run(
                mapOf(INPUT_NAME to this.input),
                mapOf(OUTPUT_NAME to this.output),
                SIGNATURE,
            )
            return output.readFloat()
        }

        override fun close() {
            runCatching { input.close() }
            runCatching { output.close() }
            runCatching { model.close() }
            runCatching { environment.close() }
        }

        companion object {
            fun open(context: Context, artifact: Dependencies.Artifact): LiteRtSession {
                val environment = Environment.create(
                    context.applicationContext,
                    mapOf(Environment.Option.DispatchLibraryDir to context.applicationInfo.nativeLibraryDir),
                )
                var model: CompiledModel? = null
                var input: TensorBuffer? = null
                var output: TensorBuffer? = null
                try {
                    model = CompiledModel.create(
                        artifact.file.absolutePath,
                        CompiledModel.Options(Accelerator.NPU),
                        environment,
                    )
                    validateTensor(model, INPUT_NAME, listOf(1, INPUT_SIZE, INPUT_SIZE, 1), isInput = true)
                    validateTensor(model, OUTPUT_NAME, listOf(1, INPUT_SIZE, INPUT_SIZE, 3), isInput = false)
                    input = model.createInputBuffer(INPUT_NAME, SIGNATURE)
                    output = model.createOutputBuffer(OUTPUT_NAME, SIGNATURE)
                    return LiteRtSession(environment, model, input, output)
                } catch (failure: Throwable) {
                    input?.let { runCatching { it.close() } }
                    output?.let { runCatching { it.close() } }
                    model?.let { runCatching { it.close() } }
                    runCatching { environment.close() }
                    throw failure
                }
            }

            private fun validateTensor(model: CompiledModel, name: String, dimensions: List<Int>, isInput: Boolean) {
                val type = if (isInput) {
                    model.getInputTensorType(name, SIGNATURE)
                } else {
                    model.getOutputTensorType(name, SIGNATURE)
                }
                check(type.elementType == TensorType.ElementType.FLOAT) {
                    "Manga Light NPU tensor '$name' is not float32"
                }
                check(type.layout?.dimensions == dimensions) {
                    "Manga Light NPU tensor '$name' has dimensions ${type.layout?.dimensions}; expected $dimensions"
                }
            }
        }
    }

    internal companion object {
        const val MODEL_ASSET = "models/coloring/manga_light_colorizer_npu_sm8650_fp32.tflite"
        const val MODEL_CACHE_DIR = "tachiyomi-models/coloring"
        const val MODEL_CACHE_PREFERENCES = "manga_light_npu_model_cache"
        const val MODEL_FILE_NAME = "manga_light_colorizer_npu_sm8650_fp32.tflite"
        const val KEY_APP_UPDATE_TIME = "app_update_time_"
        const val KEY_EXTRACTED_SIZE = "extracted_size_"
        const val KEY_SHA256 = "sha256_"

        // Trial-compiled FP32 SM8650 apply_plugin artifact (parity-verified on the
        // same toolchain line as the device-validated MCV2 artifact); 1346/1364
        // ops in 19 HTP partitions, CPU residue = 18 shape queries.
        const val EXPECTED_ARTIFACT_SHA256 = "0e4e38dc7f1c319728722c75705e9e948eebca72261191f5629d59d1ab962737"
        const val EXPECTED_ARTIFACT_BYTES = 115_000_872L

        const val SIGNATURE = "serving_default"
        const val INPUT_NAME = "L_bw"
        const val OUTPUT_NAME = "rgb_pred"
        const val INPUT_SIZE = 512
        const val OUTPUT_FLOAT_COUNT = INPUT_SIZE * INPUT_SIZE * 3
        const val STAMP_PREFIX = "MANGA_LIGHT_LITERT_NPU_FP32_SM8650_512x512"

        val REQUIRED_LIBRARIES = listOf(
            "libQnnHtp.so",
            "libQnnSystem.so",
            "libQnnHtpV75Stub.so",
            "libQnnHtpV75Skel.so",
        )
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

        /** [0,1] luma -> model units gray/127.5 − 1 (= ×2 − 1). NHWC single channel = row-major plane. */
        internal fun normalizeLuma01(luma01: FloatArray): FloatArray {
            val units = FloatArray(luma01.size)
            for (i in luma01.indices) units[i] = luma01[i] * 2f - 1f
            return units
        }

        /** Model units [−1, 1] -> interleaved RGB [0,1], clamping tanh overshoot. */
        internal fun denormalizeToRgb01(units: FloatArray): FloatArray {
            val rgb01 = FloatArray(units.size)
            for (i in units.indices) rgb01[i] = ((units[i] + 1f) / 2f).coerceIn(0f, 1f)
            return rgb01
        }

        private fun elapsedMs(startNanos: Long, endNanos: Long = System.nanoTime()): Long =
            ((endNanos - startNanos) / 1_000_000L).coerceAtLeast(0L)
    }
}
