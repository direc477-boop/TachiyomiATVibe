package eu.kanade.translation.engines.coloring

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import eu.kanade.translation.engines.runtime.onnx.OnnxModelStore
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.FloatBuffer

/**
 * On-device FFDNet denoising engine for pre-colorization screentone removal.
 * Runs FFDNet (sigma=25 baked) at native resolution on CPU before area-resampling.
 */
internal class FfdnetDenoiserEngine(
    private val deps: Dependencies,
) : AutoCloseable {

    constructor(context: Context) : this(Dependencies.from(context))

    private var session: Dependencies.Session? = null
    private var initialized = false
    private var initError: String? = null

    @Synchronized
    fun isAvailable(): Boolean {
        if (initialized) return session != null
        initialized = true
        return try {
            val modelFile = deps.resolveModel()
            check(modelFile.isFile && modelFile.length() > 0L) {
                "FFDNet model file is missing or empty: ${modelFile.absolutePath}"
            }
            session = deps.openSession(modelFile)
            initError = null
            true
        } catch (failure: Throwable) {
            if (failure.isFatal()) throw failure
            initError = failure.message ?: failure.javaClass.simpleName
            logcat(LogPriority.ERROR, failure) { "FFDNet initialization failed" }
            close()
            false
        }
    }

    fun initializationError(): String? = initError

    /** Denoises [source] at native resolution, returning a row-major unsigned uint8 grayscale byte array. */
    @Synchronized
    fun denoise(source: Bitmap): ByteArray {
        val active = session ?: run {
            check(isAvailable()) { initError ?: "FFDNet denoiser session unavailable" }
            checkNotNull(session)
        }
        val width = source.width
        val height = source.height
        require(width > 0 && height > 0) { "invalid source bitmap dimensions: ${width}x$height" }

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        return denoisePixels(active, pixels, width, height)
    }

    /** Pure pixel array denoise, testable without Bitmap allocations. */
    internal fun denoisePixels(
        activeSession: Dependencies.Session,
        pixels: IntArray,
        width: Int,
        height: Int,
    ): ByteArray {
        val padW = width % 2 != 0
        val padH = height % 2 != 0
        val evenW = if (padW) width + 1 else width
        val evenH = if (padH) height + 1 else height
        val planeSize = evenW * evenH

        // NCHW [1, 3, evenH, evenW] input float array
        val input = FloatArray(3 * planeSize)
        val gOffset = planeSize
        val bOffset = 2 * planeSize

        // Unpack ARGB into normalized float planes [0f, 1f]
        for (y in 0 until height) {
            val srcRow = y * width
            val dstRow = y * evenW
            for (x in 0 until width) {
                val p = pixels[srcRow + x]
                val dstIdx = dstRow + x
                input[dstIdx] = ((p shr 16) and 0xFF) / 255f
                input[dstIdx + gOffset] = ((p shr 8) and 0xFF) / 255f
                input[dstIdx + bOffset] = (p and 0xFF) / 255f
            }
            if (padW) {
                val lastIdx = dstRow + (width - 1)
                val padIdx = dstRow + width
                input[padIdx] = input[lastIdx]
                input[padIdx + gOffset] = input[lastIdx + gOffset]
                input[padIdx + bOffset] = input[lastIdx + bOffset]
            }
        }
        if (padH) {
            val lastRow = (height - 1) * evenW
            val padRow = height * evenW
            for (x in 0 until evenW) {
                input[padRow + x] = input[lastRow + x]
                input[padRow + x + gOffset] = input[lastRow + x + gOffset]
                input[padRow + x + bOffset] = input[lastRow + x + bOffset]
            }
        }

        val output = activeSession.run(input, evenHeight = evenH, evenWidth = evenW)
        check(output.size >= planeSize) { "FFDNet output smaller than expected plane size $planeSize" }

        // Extract channel 0 (R) cropped back to native width x height
        val gray = ByteArray(width * height)
        for (y in 0 until height) {
            val outRow = y * evenW
            val grayRow = y * width
            for (x in 0 until width) {
                val v = output[outRow + x]
                gray[grayRow + x] = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return gray
    }

    @Synchronized
    override fun close() {
        session?.let { runCatching { it.close() } }
        session = null
        initialized = false
    }

    internal class Dependencies(
        val resolveModel: () -> File,
        val openSession: (File) -> Session,
    ) {
        internal interface Session : AutoCloseable {
            fun run(input: FloatArray, evenHeight: Int, evenWidth: Int): FloatArray
        }

        internal companion object {
            fun from(context: Context): Dependencies {
                val store = OnnxModelStore(context)
                return Dependencies(
                    resolveModel = { store.ensureFfdnetDenoising() },
                    openSession = { file -> OrtFfdnetSession.open(file) },
                )
            }
        }
    }

    private class OrtFfdnetSession(
        private val session: OrtSession,
    ) : Dependencies.Session {

        override fun run(input: FloatArray, evenHeight: Int, evenWidth: Int): FloatArray {
            val shape = longArrayOf(1L, 3L, evenHeight.toLong(), evenWidth.toLong())
            val tensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                FloatBuffer.wrap(input),
                shape,
            )
            val results = try {
                session.run(mapOf(INPUT_NAME to tensor))
            } finally {
                tensor.close()
            }
            return try {
                val outTensor = results[0] as OnnxTensor
                val fb = outTensor.floatBuffer
                val count = fb.capacity()
                FloatArray(count).also {
                    fb.position(0)
                    fb.get(it)
                }
            } finally {
                results.close()
            }
        }

        override fun close() {
            session.close()
        }

        companion object {
            private const val INPUT_NAME = "input"

            fun open(modelFile: File): OrtFfdnetSession {
                val options = OnnxRuntimeProvider.createSessionOptions(useAccelerator = false) {
                    it.setIntraOpNumThreads(MANGA_COLORIZATION_ONNX_THREADS)
                }
                val built = try {
                    OnnxRuntimeProvider.environment.createSession(modelFile.absolutePath, options)
                } finally {
                    options.close()
                }
                return OrtFfdnetSession(built)
            }
        }
    }
}
