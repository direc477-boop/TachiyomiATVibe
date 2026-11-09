package eu.kanade.translation.engines.coloring

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import eu.kanade.translation.engines.runtime.onnx.OnnxModelStore
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.nio.FloatBuffer

internal enum class MangaColorizationModelRequirement {
    PREFER_INT8,
    INT8_ONLY,
}

/** Intra-op CPU thread budget shared by on-device ONNX colorization sessions. */
internal val MANGA_COLORIZATION_ONNX_THREADS = minOf(4, maxOf(1, Runtime.getRuntime().availableProcessors()))

internal class MangaColorizationOnnxSession(
    context: Context,
    private val requirement: MangaColorizationModelRequirement,
) : AutoCloseable {

    private val modelStore = OnnxModelStore(context)

    @Volatile
    private var session: OrtSession? = null

    private var stamp: String? = null

    @Synchronized
    fun isAvailable(): Boolean {
        if (session != null) return true
        return try {
            initializeLocked()
            true
        } catch (failure: Throwable) {
            logcat(LogPriority.ERROR, failure) { "OnnxMangaColorization initialization failed" }
            false
        }
    }

    /** Stamp only when already brought up; querying never triggers model bring-up. */
    @Synchronized
    fun currentStamp(): String? = session?.let { stamp }

    @Synchronized
    fun run(input: FloatArray, height: Int, width: Int): FloatArray {
        val activeSession = session ?: run {
            if (!isAvailable()) throw IllegalStateException("OnnxMangaColorization assets/runtime unavailable")
            checkNotNull(session) { "OnnxMangaColorization session missing after init" }
        }
        require(width > 0 && height > 0)
        check(width % SHAPE_MULTIPLE == 0 && height % SHAPE_MULTIPLE == 0) {
            "MCV2 input dimensions must be multiples of " + SHAPE_MULTIPLE
        }
        check(input.size == CHANNELS * width * height) { "MCV2 input buffer does not match dimensions" }

        val inputShape = longArrayOf(1L, CHANNELS.toLong(), height.toLong(), width.toLong())
        val tensor = OnnxTensor.createTensor(OnnxRuntimeProvider.environment, FloatBuffer.wrap(input), inputShape)
        val results = try {
            activeSession.run(mapOf(INPUT_NAME to tensor))
        } finally {
            tensor.close()
        }
        return try {
            val output = results[0] as OnnxTensor
            val expectedShape = longArrayOf(1L, CHANNELS_RGB.toLong(), height.toLong(), width.toLong())
            check(output.info.shape.contentEquals(expectedShape)) {
                "MCV2 returned shape " + output.info.shape.contentToString() + " for input " + height + "x" + width
            }
            val buffer = output.floatBuffer
            check(buffer.capacity() == CHANNELS_RGB * width * height) {
                "MCV2 returned " + buffer.capacity() + " floats for input " + height + "x" + width
            }
            FloatArray(buffer.capacity()).also {
                buffer.position(0)
                buffer.get(it)
            }
        } finally {
            results.close()
        }
    }

    @Synchronized
    override fun close() {
        session?.close()
        session = null
        stamp = null
    }

    private fun initializeLocked() {
        val startedAt = System.nanoTime()
        val modelFile = when (requirement) {
            MangaColorizationModelRequirement.PREFER_INT8 -> modelStore.ensureMangaColorization()
            MangaColorizationModelRequirement.INT8_ONLY -> modelStore.ensureMangaColorizationInt8()
        }
        val precision = when (modelFile.name) {
            INT8_MODEL_FILE -> Precision.INT8
            FP32_MODEL_FILE -> Precision.FP32
            else -> error("Unexpected MCV2 ONNX model file: " + modelFile.name)
        }
        check(requirement != MangaColorizationModelRequirement.INT8_ONLY || precision == Precision.INT8) {
            "The explicit MCV2 INT8 route resolved a non-INT8 artifact"
        }

        val options = OnnxRuntimeProvider.createSessionOptions(useAccelerator = false) {
            it.setIntraOpNumThreads(MANGA_COLORIZATION_ONNX_THREADS)
        }
        val built = try {
            OnnxRuntimeProvider.environment.createSession(modelFile.absolutePath, options)
        } finally {
            options.close()
        }
        try {
            check(built.inputNames == setOf(INPUT_NAME)) { "Unexpected MCV2 inputs: " + built.inputNames }
            check(built.outputNames == setOf(OUTPUT_NAME)) { "Unexpected MCV2 outputs: " + built.outputNames }
        } catch (failure: Throwable) {
            built.close()
            throw failure
        }

        session = built
        stamp = when (precision) {
            Precision.INT8 -> STAMP_INT8
            Precision.FP32 -> STAMP_FP32
        }
        logcat(LogPriority.INFO) {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
            "OnnxMangaColorization ready in " + elapsedMs + "ms (cpu " + precision.label +
                ", threads=" + MANGA_COLORIZATION_ONNX_THREADS + "); inputs=" + built.inputNames + " outputs=" + built.outputNames
        }
    }

    private enum class Precision(val label: String) {
        INT8("int8"),
        FP32("fp32"),
    }

    private companion object {
        const val INPUT_NAME = "input"
        const val OUTPUT_NAME = "rgb"
        const val CHANNELS = 5
        const val CHANNELS_RGB = 3
        const val SHAPE_MULTIPLE = 32
        const val FP32_MODEL_FILE = "manga_colorize_fp32.onnx"
        const val INT8_MODEL_FILE = "manga_colorize_int8.onnx"
        const val STAMP_FP32 = "ONNX_MC_V2_CPU_FP32_R2"
        const val STAMP_INT8 = "ONNX_MC_V2_CPU_INT8_R1"
    }
}
