package eu.kanade.translation.engines.upscaling

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import eu.kanade.translation.engines.coloring.MangaColorizationModelStore
import eu.kanade.translation.engines.coloring.isFatal
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.min

/**
 * Experimental SM8650 NPU QuickSRNetLarge route: HARD FAIL, no fallback.
 *
 * Full-page upscaling via 2D halo tiling with exact-match preservation.
 * Host probe analysis verified that the network is a local Conv2D stack with an analytic
 * receptive field of 27x27 pixels (radius 13 in_px). Full-page tiling with
 * halo >= 14 in_px (default HALO = 16) is mathematically and empirically verified
 * (0.000e+00 difference in shared interior bands across independent tile runs).
 * Availability is gated upstream by [UpscalingModelCatalog] until device validation passes.
 *
 * Comprehensive In-Flight Memory Footprint:
 * 1. Source Bitmap: Win * Hin * 4 bytes (e.g. ~8.6MB for 1200x1800).
 * 2. Destination Bitmap: 16 * Win * Hin * 4 bytes = 64 * Win * Hin bytes
 *    (e.g., ~61MB for 800x1200 -> 3200x4800; ~138MB for 1200x1800 -> 4800x7200).
 * 3. Native Qualcomm NPU Runtime: ~25-35MB (QNN HTP execution context, compiled weights,
 *    DMA buffers, and bound I/O TensorBuffers).
 * 4. Temporary Heap Buffers: Fixed at ~4.3MB (reusable 128x128x3 float input,
 *    512x512x3 float output, and 512x512 int crop buffers reused across all tiles).
 * Total peak resident memory scales as ~Source + Destination + 35MB (e.g. ~176MB for 1200x1800).
 *
 * Proactive Allocation Bounds & Fatal Error Contract:
 * - Proactively bounded by [MAX_INPUT_DIMENSION] (2048px input -> 8192px 4x output) and
 *   [MAX_OUTPUT_PIXELS] (48 MP / ~192MB ARGB_8888 destination) to reject oversized requests
 *   before allocation. These are application policy limits, not universal hardware guarantees.
 * - [OutOfMemoryError] and VM fatal errors are NEVER caught or wrapped into non-fatal exceptions;
 *   if an OOM occurs, it is rethrown directly per the fatal VM error contract.
 */
internal class LiteRTQuickSRNetUpscalingEngine(
    private val deps: Dependencies,
) : PageUpscalingEngine {

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

    @Synchronized
    override fun isAvailable(): Boolean {
        if (initialized) return session != null
        initialized = true
        return try {
            val precheck = deps.precheck()
            check(precheck == null) { precheck ?: "Qualcomm NPU runtime unavailable" }
            val artifact = deps.artifact() ?: error("QuickSRNetLarge artifact is not present in app assets")
            check(artifact.sha256 == EXPECTED_ARTIFACT_SHA256) {
                "QuickSRNetLarge artifact hash ${artifact.sha256} does not match the compiled contract " +
                    "($EXPECTED_ARTIFACT_SHA256); refusing to run an unverified artifact on the NPU"
            }
            val runtime = deps.openSession(artifact)
            session = Session(runtime, artifact.sha256)
            logcat(LogPriority.INFO) {
                "[QuickSRNet] NPU ready (SM8650 float apply_plugin, x4 128->512, sha256=${artifact.sha256})"
            }
            true
        } catch (failure: Throwable) {
            if (failure.isFatal()) throw failure
            initFailure = failure.message ?: failure.javaClass.simpleName
            logcat(LogPriority.ERROR, failure) { "[QuickSRNet] NPU initialization failed (no fallback on this route)" }
            closeRuntime()
            false
        }
    }

    override fun initializationError(): String? = initFailure

    /**
     * Represents a 1D tiling slice along one dimension:
     * - [inStart], [inEnd]: sub-range in source coordinates [0..dimSize)
     * - [cropStart], [cropEnd]: valid non-halo range within the 128px tile [0..MODEL_SIDE)
     * - [dstStart], [dstEnd]: contiguous placement range in 4x destination coordinates
     */
    internal data class Slice(
        val inStart: Int,
        val inEnd: Int,
        val cropStart: Int,
        val cropEnd: Int,
        val dstStart: Int,
        val dstEnd: Int,
    )

    /**
     * Computes contiguous, non-overlapping 1D destination slices with halo overlap
     * for arbitrary image dimension sizes (odd, smaller than 128, or multi-tile).
     */
    internal fun computeSlices(dimSize: Int): List<Slice> {
        require(dimSize > 0) { "Dimension size must be positive: $dimSize" }
        if (dimSize <= MODEL_SIDE) {
            return listOf(
                Slice(
                    inStart = 0,
                    inEnd = dimSize,
                    cropStart = 0,
                    cropEnd = dimSize,
                    dstStart = 0,
                    dstEnd = dimSize * SCALE,
                ),
            )
        }
        val slices = ArrayList<Slice>()
        var pos = 0
        while (pos < dimSize) {
            val inStart = pos
            val inEnd = min(pos + MODEL_SIDE, dimSize)
            val tileLen = inEnd - inStart
            val cropStart = if (pos == 0) 0 else HALO
            val cropEnd = if (inEnd == dimSize) tileLen else (MODEL_SIDE - HALO)
            val dstStart = (inStart + cropStart) * SCALE
            val dstEnd = (inStart + cropEnd) * SCALE
            slices.add(Slice(inStart, inEnd, cropStart, cropEnd, dstStart, dstEnd))
            if (inEnd == dimSize) break
            pos += STEP
        }
        return slices
    }

    /**
     * Upscales an arbitrary-sized bitmap by exactly 4x using full-page halo tiling.
     * Proactively bounds allocations with [MAX_INPUT_DIMENSION] and [MAX_OUTPUT_PIXELS].
     * Fatal VM errors such as [OutOfMemoryError] are preserved and rethrown directly.
     */
    @Synchronized
    override fun upscalePage(source: Bitmap): Bitmap {
        val active = session ?: throw IllegalStateException(
            initFailure?.let { "QuickSRNet upscaling unavailable (no fallback): $it" }
                ?: "QuickSRNet upscaling unavailable (no fallback)",
        )
        val srcW = source.width
        val srcH = source.height
        check(srcW > 0 && srcH > 0) { "Invalid source dimensions: ${srcW}x$srcH" }
        check(srcW <= MAX_INPUT_DIMENSION && srcH <= MAX_INPUT_DIMENSION) {
            "Input dimensions ${srcW}x$srcH exceed maximum supported input dimension $MAX_INPUT_DIMENSION " +
                "(maximum 4x output dimension policy cap is $MAX_OUTPUT_DIMENSION px)"
        }
        val outPixels = srcW.toLong() * srcH.toLong() * (SCALE * SCALE)
        check(outPixels <= MAX_OUTPUT_PIXELS) {
            val reqMb = (outPixels * 4) / (1024 * 1024)
            val maxMb = (MAX_OUTPUT_PIXELS * 4) / (1024 * 1024)
            "4x upscaling of ${srcW}x$srcH requires $outPixels pixels (~${reqMb}MB ARGB_8888 bitmap), " +
                "exceeding proactive safety policy cap of ${MAX_OUTPUT_PIXELS} pixels (~${maxMb}MB)"
        }
        val outW = srcW * SCALE
        val outH = srcH * SCALE

        // Proactively checked above; any actual OOM from OS graphics memory is rethrown directly.
        val dstBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

        val xSlices = computeSlices(srcW)
        val ySlices = computeSlices(srcH)

        val tileInput = FloatArray(MODEL_SIDE * MODEL_SIDE * 3)
        val patchPixels = IntArray(MODEL_SIDE * MODEL_SIDE)
        val cropArgb = IntArray(MODEL_SIDE * SCALE * MODEL_SIDE * SCALE)

        for (ySlice in ySlices) {
            val patchH = ySlice.inEnd - ySlice.inStart
            val cropH = ySlice.cropEnd - ySlice.cropStart
            val outCropH = cropH * SCALE
            val dstY = ySlice.dstStart

            for (xSlice in xSlices) {
                val patchW = xSlice.inEnd - xSlice.inStart
                val cropW = xSlice.cropEnd - xSlice.cropStart
                val outCropW = cropW * SCALE
                val dstX = xSlice.dstStart

                // 1. Read patch from source with edge replication
                source.getPixels(patchPixels, 0, patchW, xSlice.inStart, ySlice.inStart, patchW, patchH)
                packTileInput(patchPixels, patchW, patchH, tileInput)

                // 2. Run tile inference on NPU
                val tileOutput = active.runtime.run(tileInput)
                check(tileOutput.size == OUTPUT_FLOAT_COUNT) {
                    "QuickSRNet returned ${tileOutput.size} floats; expected $OUTPUT_FLOAT_COUNT"
                }

                // 3. Convert valid cropped region to ARGB
                extractCroppedArgb(
                    tileOutput = tileOutput,
                    cropStartX = xSlice.cropStart,
                    cropStartY = ySlice.cropStart,
                    outCropW = outCropW,
                    outCropH = outCropH,
                    destArgb = cropArgb,
                )

                // 4. Stream directly to destination Bitmap
                dstBitmap.setPixels(cropArgb, 0, outCropW, dstX, dstY, outCropW, outCropH)
            }
        }
        return dstBitmap
    }

    /** Upscales a single sub-region (delegating to [upscalePage]). */
    @Synchronized
    fun upscaleRegion(source: Bitmap): Bitmap = upscalePage(source)

    @Synchronized
    override fun close() {
        closeRuntime()
        initialized = false
    }

    private fun closeRuntime() {
        session?.let { runCatching { it.runtime.close() } }
        session = null
    }

    /**
     * Packs ARGB pixels into the model's NHWC float32 [0,1] layout at fixed
     * MODEL_SIDExMODEL_SIDE: source at top-left, borders edge-replicated so
     * padding invents no content.
     */
    internal fun packTileInput(
        pixels: IntArray,
        width: Int,
        height: Int,
        dest: FloatArray,
    ) {
        for (y in 0 until MODEL_SIDE) {
            val sy = min(y, height - 1)
            for (x in 0 until MODEL_SIDE) {
                val sx = min(x, width - 1)
                val argb = pixels[sy * width + sx]
                val dst = (y * MODEL_SIDE + x) * 3
                dest[dst] = ((argb shr 16) and 0xFF) / 255f
                dest[dst + 1] = ((argb shr 8) and 0xFF) / 255f
                dest[dst + 2] = (argb and 0xFF) / 255f
            }
        }
    }

    internal fun rgb01NhwcFromPixels(pixels: IntArray, width: Int, height: Int): FloatArray {
        val data = FloatArray(MODEL_SIDE * MODEL_SIDE * 3)
        packTileInput(pixels, width, height, data)
        return data
    }

    /**
     * Crops the valid non-halo output region from the 512x512 tile and packs into
     * [outCropW] x [outCropH] ARGB pixels clamped to [0, 1].
     */
    internal fun extractCroppedArgb(
        tileOutput: FloatArray,
        cropStartX: Int,
        cropStartY: Int,
        outCropW: Int,
        outCropH: Int,
        destArgb: IntArray,
    ) {
        val srcBaseY = cropStartY * SCALE
        val srcBaseX = cropStartX * SCALE
        for (y in 0 until outCropH) {
            val srcRow = (srcBaseY + y) * OUTPUT_SIDE
            val dstRow = y * outCropW
            for (x in 0 until outCropW) {
                val src = (srcRow + srcBaseX + x) * 3
                val r = (tileOutput[src].coerceIn(0f, 1f) * 255f).toInt()
                val g = (tileOutput[src + 1].coerceIn(0f, 1f) * 255f).toInt()
                val b = (tileOutput[src + 2].coerceIn(0f, 1f) * 255f).toInt()
                destArgb[dstRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /** Crops the fixed 512x512 output back to (w*4, h*4) ARGB pixels, clamped to [0,1]. */
    internal fun regionScaledArgbFromRgb01(output: FloatArray, width: Int, height: Int): IntArray {
        val outW = width * SCALE
        val outH = height * SCALE
        val argb = IntArray(outW * outH)
        extractCroppedArgb(output, 0, 0, outW, outH, argb)
        return argb
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
            /** Runs one inference on the fixed 128x128 NHWC input; returns 512x512 RGB. */
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

                val destination = File(context.noBackupFilesDir, "$MODEL_CACHE_DIR/$MODEL_FILE_NAME")
                destination.parentFile?.mkdirs()
                val temporary = File(destination.parentFile, "$MODEL_FILE_NAME.tmp")
                return try {
                    // Shared pure asset primitives from the coloring package (reuse, no duplicate).
                    val sha256 = MangaColorizationModelStore.copyAssetWithDigest(context, MODEL_ASSET, temporary)
                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        temporary.delete()
                    }
                    Artifact(destination, sha256)
                } catch (failure: Throwable) {
                    temporary.delete()
                    if (failure.isFatal()) throw failure
                    throw IllegalStateException("Failed to extract $MODEL_ASSET: ${failure.message}", failure)
                }
            }
        }
    }

    /** Same device-validated probe configuration as the colorizer NPU lane. */
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
                    validateTensor(model, INPUT_NAME, INPUT_DIMS, isInput = true)
                    validateTensor(model, OUTPUT_NAME, OUTPUT_DIMS, isInput = false)
                    input = model.createInputBuffer(INPUT_NAME)
                    output = model.createOutputBuffer(OUTPUT_NAME)
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
                    model.getInputTensorType(name)
                } else {
                    model.getOutputTensorType(name)
                }
                check(type.elementType == TensorType.ElementType.FLOAT) {
                    "QuickSRNet tensor '$name' is not float32"
                }
                check(type.layout?.dimensions == dimensions) {
                    "QuickSRNet tensor '$name' has dimensions ${type.layout?.dimensions}; expected $dimensions"
                }
            }
        }
    }

    internal companion object {
        // Provisional asset path until staging confirms the final location; the
        // pinned hash is what the engine actually enforces.
        const val MODEL_ASSET = "models/upscaling/quicksrnetlarge_sm8650_float_apply_plugin.tflite"
        const val MODEL_CACHE_DIR = "tachiyomi-models/upscaling"
        const val MODEL_FILE_NAME = "quicksrnetlarge_sm8650_float_apply_plugin.tflite"

        // Compiled in this repo's pinned 2.3.0 stack for SM8650; full-offload
        // 33/33 ops in 1 partition (compile-time evidence only).
        const val EXPECTED_ARTIFACT_SHA256 = "4cf4ba774f2cae4e998ab1989a5947b3dbdc920ab24ce94c10aaebe73b90e331"

        // This export has NO signature definitions (verified from bytes); the
        // Kotlin API's default-signature path is a device bring-up unknown.
        const val INPUT_NAME = "image"
        const val OUTPUT_NAME = "upscaled_image"
        const val MODEL_SIDE = 128
        const val OUTPUT_SIDE = 512
        const val SCALE = PageUpscalingEngine.SCALE
        const val OUTPUT_FLOAT_COUNT = OUTPUT_SIDE * OUTPUT_SIDE * 3

        const val HALO = 16
        const val STEP = MODEL_SIDE - 2 * HALO // 96

        // Proactive application policy bounds:
        // Input capped at 2048px on either dimension -> maximum 8192px output dimension.
        // Total output pixels capped at 48 MP (~192MB ARGB_8888 destination bitmap).
        // (These are software policy bounds to reject pathological requests, not universal hardware limits).
        const val MAX_INPUT_DIMENSION = PageUpscalingEngine.MAX_INPUT_DIMENSION
        const val MAX_OUTPUT_DIMENSION = PageUpscalingEngine.MAX_OUTPUT_DIMENSION
        const val MAX_OUTPUT_PIXELS = PageUpscalingEngine.MAX_OUTPUT_PIXELS

        val REQUIRED_LIBRARIES = listOf(
            "libQnnHtp.so",
            "libQnnSystem.so",
            "libQnnHtpV75Stub.so",
            "libQnnHtpV75Skel.so",
        )
        val INPUT_DIMS = listOf(1, MODEL_SIDE, MODEL_SIDE, 3)
        val OUTPUT_DIMS = listOf(1, OUTPUT_SIDE, OUTPUT_SIDE, 3)
    }
}
