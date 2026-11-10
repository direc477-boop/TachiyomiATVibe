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

/** SM8650 NPU MCV2 W8A8 route: HARD FAIL, no fallback; cross-series-calibrated artifact. */
internal class NpuMangaColorizationEngine(
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
        return try {
            val precheck = deps.precheck()
            check(precheck == null) { precheck ?: "Qualcomm NPU runtime unavailable" }
            val artifact = deps.artifact() ?: error("MCV2 NPU artifact is not present in app assets")
            check(artifact.sha256 == EXPECTED_ARTIFACT_SHA256) {
                "MCV2 NPU artifact hash ${artifact.sha256} does not match the compiled contract " +
                    "($EXPECTED_ARTIFACT_SHA256); refusing to run an unverified artifact on the NPU"
            }
            val runtime = deps.openSession(artifact)
            session = Session(runtime, artifact.sha256)
            stamp = "${STAMP_PREFIX}_${artifact.sha256}"
            logcat(LogPriority.INFO) {
                "[Mcv2NpuColor] NPU ready (W8A8 ${INPUT_HEIGHT}x${INPUT_WIDTH} apply_plugin, sha256=${artifact.sha256})"
            }
            true
        } catch (failure: Throwable) {
            if (failure.isFatal()) throw failure
            initFailure = failure.message ?: failure.javaClass.simpleName
            logcat(LogPriority.ERROR, failure) { "[Mcv2NpuColor] NPU initialization failed (no fallback on this route)" }
            closeRuntime()
            false
        }
    }

    override fun initializationError(): String? = initFailure

    /** Stamp is pipeline-config identity: the suffix marks the built-in screentone smoothing stage
     *  that always runs on this route — never a per-page outcome. */
    @Synchronized
    override fun currentStamp(): String? {
        val base = stamp ?: return null
        return base + MangaColorizationPreprocessor.STAMP_SUFFIX
    }

    @Synchronized
    override fun colorize(source: Bitmap, seed: Long): Bitmap {
        // seed is unused: the generator is deterministic.
        val active = session ?: throw IllegalStateException(
            initFailure?.let { "MCV2 NPU colorization unavailable (no fallback): $it" }
                ?: "MCV2 NPU colorization unavailable (no fallback)",
        )
        val letterbox = MangaColorizationLetterbox.fit(source.width, source.height, INPUT_WIDTH, INPUT_HEIGHT)
        check(!letterbox.requiresCpuFallback) {
            "MCV2 NPU colorization does not support pages below " +
                "${MINIMUM_CONTENT_DIMENSION}px content (got ${letterbox.width}x${letterbox.height}); " +
                "this route has no fallback by design"
        }
        // Quality-only preprocessing on the aspect-preserved content rect; padding
        // is applied strictly after, and stage failures degrade to the plain downsample.
        val preprocessed = MangaColorizationPreprocessor.grayPlane(source, letterbox.width, letterbox.height)
        logcat(LogPriority.INFO) {
            "[Mcv2NpuColor] ScreentoneSmooth score=" + preprocessed.score +
                " smoothed=" + preprocessed.smoothingApplied
        }
        val input = MangaColorizationPreprocessor.grayNchwFromGrayPlane(
            preprocessed.plane,
            letterbox,
            INPUT_WIDTH,
            INPUT_HEIGHT,
        )
        val output = active.runtime.run(input)
        val expectedOutputCount = OUTPUT_FLOAT_COUNT
        check(output.size == expectedOutputCount) {
            "MCV2 NPU returned ${output.size} floats; expected $expectedOutputCount"
        }
        check(output.all { it.isFinite() }) { "MCV2 NPU returned non-finite values" }
        check(output.any { it != 0f }) { "MCV2 NPU returned an all-zero image" }

        val coloredWork = MangaColorizationImageProcessing.interleavedRgb01ToBitmap(
            MangaColorizationImageProcessing.cropRgb01Letterbox(output, letterbox, INPUT_WIDTH, INPUT_HEIGHT),
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
            /** Runs one inference; returns the interleaved RGB output. */
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
                if (missing.isNotEmpty()) {
                    return "Qualcomm NPU dispatch libraries missing from ${nativeLibraryDir.absolutePath}: " +
                        missing.joinToString(", ")
                }
                val systemLib = File(nativeLibraryDir, "libQnnSystem.so")
                if (systemLib.length() == 2_689_368L) {
                    return "Qualcomm QNN system library is the outdated 1.7.0 version; requires QNN >= 1.14"
                }
                return null
            }

            /** Streams the asset into a version-stamped cache, hashing as it copies. */
            private fun extractModel(context: Context): Artifact? {
                val assetPath = MODEL_ASSET
                val fileName = MODEL_FILE_NAME
                val assetExists = try {
                    context.assets.open(assetPath).use { }
                    true
                } catch (_: FileNotFoundException) {
                    false
                }
                if (!assetExists) return null

                val assetLength = runCatching {
                    context.assets.openFd(assetPath).use { it.length }
                }.getOrNull()
                val destination = File(context.noBackupFilesDir, "$MODEL_CACHE_DIR/$fileName")
                destination.parentFile?.mkdirs()
                val preferences = context.getSharedPreferences(MODEL_CACHE_PREFERENCES, Context.MODE_PRIVATE)
                val appUpdateTime = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                }.getOrDefault(0L)
                val cachedHash = preferences.getString(KEY_SHA256 + fileName, null)
                val cacheIsValid = destination.isFile &&
                    destination.length() > 0L &&
                    preferences.getLong(KEY_APP_UPDATE_TIME + fileName, -1L) == appUpdateTime &&
                    preferences.getLong(KEY_EXTRACTED_SIZE + fileName, -1L) == destination.length() &&
                    (assetLength == null || destination.length() == assetLength) &&
                    cachedHash?.matches(SHA256_PATTERN) == true
                // The pinned hash must judge the file's real bytes, never the
                // recorded one - recompute even on a cache hit so a corrupted
                // cache fails visibly instead of reaching the device.
                if (cacheIsValid) return Artifact(destination, MangaColorizationModelStore.sha256Of(destination))

                val temporary = File(destination.parentFile, "$fileName.tmp")
                return try {
                    val sha256 = MangaColorizationModelStore.copyAssetWithDigest(context, assetPath, temporary)
                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        temporary.delete()
                    }
                    preferences.edit()
                        .putLong(KEY_APP_UPDATE_TIME + fileName, appUpdateTime)
                        .putLong(KEY_EXTRACTED_SIZE + fileName, destination.length())
                        .putString(KEY_SHA256 + fileName, sha256)
                        .apply()
                    Artifact(destination, sha256)
                } catch (failure: Throwable) {
                    temporary.delete()
                    if (destination.isFile && destination.length() > 0L) {
                        val sha256 = MangaColorizationModelStore.sha256Of(destination)
                        preferences.edit()
                            .putLong(KEY_APP_UPDATE_TIME + fileName, appUpdateTime)
                            .putLong(KEY_EXTRACTED_SIZE + fileName, destination.length())
                            .putString(KEY_SHA256 + fileName, sha256)
                            .apply()
                        return Artifact(destination, sha256)
                    }
                    if (failure.isFatal()) throw failure
                    throw IllegalStateException("Failed to extract $assetPath: ${failure.message}", failure)
                }
            }
        }
    }

    /** Bring-up configuration: DispatchLibraryDir + plain NPU options. */
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
            /** Pure AOT-load; a compiler plugin engaging JIT here is a red flag. */
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
                    validateTensor(model, INPUT_NAME, listOf(1, 5, INPUT_HEIGHT, INPUT_WIDTH), isInput = true)
                    validateTensor(model, OUTPUT_NAME, listOf(1, INPUT_HEIGHT, INPUT_WIDTH, 3), isInput = false)
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
                    "MCV2 NPU tensor '$name' is not float32"
                }
                check(type.layout?.dimensions == dimensions) {
                    "MCV2 NPU tensor '$name' has dimensions ${type.layout?.dimensions}; expected $dimensions"
                }
            }
        }
    }

    internal companion object {
        const val MODEL_ASSET = "models/coloring/manga_colorize_npu_sm8650_w8a8.tflite"
        const val MODEL_CACHE_DIR = "tachiyomi-models/coloring"
        const val MODEL_CACHE_PREFERENCES = "mcv2_npu_model_cache"
        const val MODEL_FILE_NAME = "manga_colorize_npu_sm8650_w8a8.tflite"
        const val KEY_APP_UPDATE_TIME = "app_update_time_"
        const val KEY_EXTRACTED_SIZE = "extracted_size_"
        const val KEY_SHA256 = "sha256_"

        // The cross-series-calibrated W8A8 apply_plugin artifact (mixed manga+SASA
        // calibration; 35.60 dB held-out via evaluate_cross_series.py).
        const val EXPECTED_ARTIFACT_SHA256 = "d687f414868e11b47049ac0566b7087f767abcb2a7ca02d0edad01e1437d4ac9"

        const val STAMP_PREFIX = "MC_V2_LITERT_NPU_W8A8_SM8650_832x576"

        const val SIGNATURE = "serving_default"
        const val INPUT_NAME = "input"
        const val OUTPUT_NAME = "rgb"
        const val INPUT_WIDTH = 576
        const val INPUT_HEIGHT = 832
        const val OUTPUT_FLOAT_COUNT = INPUT_WIDTH * INPUT_HEIGHT * 3

        // Conservative GPU-backend minimum: behavior below 256px content is
        // unverified on the NPU, so the route fails visibly instead of
        // claiming support it has not shown.
        const val MINIMUM_CONTENT_DIMENSION = 256

        val REQUIRED_LIBRARIES = listOf(
            "libQnnHtp.so",
            "libQnnSystem.so",
            "libQnnHtpV75Stub.so",
            "libQnnHtpV75Skel.so",
        )
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}
