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

/** Runs the static SAM encoder on the NPU and the live-conditioned generator on the GPU. */
internal class MangaLightHybridColorizationEngine(private val context: Context) : PageColorizationEngine {
    private var session: Session? = null
    private var initialized = false
    private var error: String? = null

    @Synchronized
    override fun isAvailable(): Boolean {
        if (initialized) return session != null
        initialized = true
        return try {
            val precheck = dispatchPrecheck(File(context.applicationInfo.nativeLibraryDir))
            check(precheck == null) { precheck ?: "Qualcomm NPU runtime unavailable" }
            session = Session.open(context, ARTIFACTS.map { extract(it) })
            error = null
            true
        } catch (failure: Throwable) {
            if (failure.isFatal()) throw failure
            error = failure.message ?: "Manga Light initialization failed"
            false
        }
    }

    override fun initializationError(): String? = error

    @Synchronized
    override fun currentStamp(): String? = session?.let { STAMP }

    @Synchronized
    override fun colorize(source: Bitmap, seed: Long): Bitmap {
        val active = checkNotNull(session) { error ?: "Manga Light is unavailable" }
        val started = System.nanoTime()
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val gray = MangaColorizationPreprocessor.grayLFromArgb(pixels)
        val image512 = AreaResampler.resample(gray, source.width, source.height, SIZE, SIZE)
        val luma = FloatArray(SIZE * SIZE) { (image512[it].toInt() and 0xff) / 255f }
        val rgb = samRgb(luma)
        val prepared = System.nanoTime()
        val output = active.run(luma, rgb)
        val inferred = System.nanoTime()
        require(output.size == SIZE * SIZE * 3 && output.all(Float::isFinite)) { "Invalid Manga Light output" }
        val work = MangaColorizationImageProcessing.interleavedRgb01ToBitmap(
            FloatArray(output.size) { ((output[it] + 1f) * 0.5f).coerceIn(0f, 1f) },
            SIZE,
            SIZE,
        )
        return try {
            MangaColorizationImageProcessing.combineColoredWithSource(source, work)
        } finally {
            work.recycle()
            logcat(LogPriority.INFO) {
                "Manga Light hybrid: preprocess=${(prepared - started) / 1_000_000}ms " +
                    "inference=${(inferred - prepared) / 1_000_000}ms " +
                    "total=${(System.nanoTime() - started) / 1_000_000}ms"
            }
        }
    }

    @Synchronized
    override fun close() {
        session?.close()
        session = null
        initialized = false
    }

    private fun extract(artifact: Artifact): File {
        val dir = File(context.noBackupFilesDir, "tachiyomi-models/manga-light-hybrid")
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create Manga Light model directory" }
        val target = File(dir, artifact.name)
        if (target.isFile && target.length() == artifact.bytes && MangaColorizationModelStore.sha256Of(target) == artifact.sha256) return target
        val temp = File(dir, artifact.name + ".tmp")
        try {
            val digest = MangaColorizationModelStore.copyAssetWithDigest(context, "models/coloring/manga-light-hybrid/${artifact.name}", temp)
            check(temp.length() == artifact.bytes && digest == artifact.sha256) { "Invalid Manga Light artifact ${artifact.name}" }
            check(temp.renameTo(target)) { "Unable to publish Manga Light artifact ${artifact.name}" }
            return target
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private class Session(private val environments: List<Environment>, private val stages: List<Stage>, private val generator: Stage) : AutoCloseable {
        fun run(luma: FloatArray, rgb: FloatArray): FloatArray {
            var input = rgb
            for (stage in stages.take(3)) input = stage.run(mapOf(stage.inputNames.single() to input)).getValue(stage.outputNames.single())
            val features = stages.last().run(mapOf("enc_stage3_in" to input))
            val nchw = features.getValue("sam_level0")
            val level0 = samLevel0ToNhwc(nchw)
            val level1 = features.getValue("sam_level1")
            check(level1.size == 256 * 32 * 32) { "Invalid SAM level 1" }
            return generator.run(
                mapOf(
                    "L_bw" to FloatArray(luma.size) { luma[it] * 2f - 1f },
                    "sam_level0" to level0,
                    "sam_level1" to level1,
                ),
            ).getValue("rgb_pred")
        }

        override fun close() {
            (stages + generator).asReversed().forEach { runCatching { it.close() } }
            environments.asReversed().forEach { runCatching { it.close() } }
        }

        companion object {
            fun open(context: Context, files: List<File>): Session {
                val environments = mutableListOf<Environment>()
                val models = mutableListOf<Stage>()
                try {
                    val npu = Environment.create(context.applicationContext, mapOf(Environment.Option.DispatchLibraryDir to context.applicationInfo.nativeLibraryDir)).also(environments::add)
                    val gpu = Environment.create(context.applicationContext).also(environments::add)
                    val stageInputs = listOf("rgb_input", "enc_stage0_out", "enc_stage1_out", "enc_stage3_in")
                    val stageOutputs = listOf(listOf("enc_stage0_out"), listOf("enc_stage1_out"), listOf("enc_stage3_in"), listOf("sam_level0", "sam_level1"))
                    for (i in 0..3) models += Stage.open(files[i], npu, Accelerator.NPU, listOf(stageInputs[i]), stageOutputs[i])
                    val generator = Stage.open(files[4], gpu, Accelerator.GPU, listOf("L_bw", "sam_level0", "sam_level1"), listOf("rgb_pred")).also(models::add)
                    return Session(environments, models.take(4), generator)
                } catch (failure: Throwable) {
                    models.asReversed().forEach { runCatching { it.close() } }
                    environments.asReversed().forEach { runCatching { it.close() } }
                    throw failure
                }
            }
        }
    }

    private class Stage(val model: CompiledModel, val inputs: Map<String, TensorBuffer>, val outputs: Map<String, TensorBuffer>) : AutoCloseable {
        val inputNames get() = inputs.keys
        val outputNames get() = outputs.keys

        fun run(values: Map<String, FloatArray>): Map<String, FloatArray> {
            check(values.keys == inputs.keys) { "Unexpected model inputs" }
            values.forEach { (name, value) -> inputs.getValue(name).writeFloat(value) }
            model.run(inputs, outputs, SIGNATURE)
            return outputs.mapValues { it.value.readFloat() }
        }

        override fun close() {
            inputs.values.forEach { runCatching { it.close() } }
            outputs.values.forEach { runCatching { it.close() } }
            model.close()
        }

        companion object {
            fun open(file: File, environment: Environment, accelerator: Accelerator, inputNames: List<String>, outputNames: List<String>): Stage {
                val options = CompiledModel.Options(accelerator)
                if (accelerator == Accelerator.GPU) options.gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
                val model = CompiledModel.create(file.absolutePath, options, environment)
                val inputs = mutableMapOf<String, TensorBuffer>()
                val outputs = mutableMapOf<String, TensorBuffer>()
                try {
                    inputNames.forEach {
                        validate(model.getInputTensorType(it, SIGNATURE), it, true)
                        inputs[it] = model.createInputBuffer(it, SIGNATURE)
                    }
                    outputNames.forEach {
                        validate(model.getOutputTensorType(it, SIGNATURE), it, false)
                        outputs[it] = model.createOutputBuffer(it, SIGNATURE)
                    }
                    return Stage(model, inputs, outputs)
                } catch (failure: Throwable) {
                    inputs.values.forEach { runCatching { it.close() } }
                    outputs.values.forEach { runCatching { it.close() } }
                    model.close()
                    throw failure
                }
            }

            private fun validate(type: TensorType, name: String, input: Boolean) {
                val expected = when (name) {
                    "rgb_input" -> listOf(1, 1024, 1024, 3)
                    "enc_stage0_out" -> listOf(1, 256, 256, 96)
                    "enc_stage1_out" -> listOf(1, 128, 128, 192)
                    "enc_stage3_in" -> listOf(1, 64, 64, 384)
                    "sam_level0" -> if (input) listOf(1, 64, 64, 256) else listOf(1, 256, 64, 64)
                    "sam_level1" -> listOf(1, 32, 32, 256)
                    "L_bw" -> listOf(1, 512, 512, 1)
                    "rgb_pred" -> listOf(1, 512, 512, 3)
                    else -> error("Unexpected Manga Light tensor: $name")
                }
                check(type.elementType == TensorType.ElementType.FLOAT && type.layout?.dimensions == expected) {
                    "Invalid Manga Light tensor $name: ${type.layout?.dimensions}"
                }
            }
        }
    }

    private data class Artifact(val name: String, val bytes: Long, val sha256: String)

    companion object {
        val REQUIRED_LIBRARIES = listOf(
            "libQnnHtp.so",
            "libQnnSystem.so",
            "libQnnHtpV75Stub.so",
            "libQnnHtpV75Skel.so",
        )

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

        private const val SIZE = 512
        private const val SIGNATURE = "serving_default"
        private const val STAMP = "MANGA_LIGHT_SAM_NPU_GPU_FP32_512_v1"
        private val ARTIFACTS = listOf(
            Artifact("sam0.tflite", 36_312_536L, "06a0436722c853e54c23a3b810a39f7bb47b77a33e74cbbd1763aa7cffe8253f"),
            Artifact("sam1.tflite", 32_891_512L, "7da91f7f4d00abd4110e6da9583897d833d0c65f60e4e341baca74896c5bc7c7"),
            Artifact("sam2.tflite", 23_498_360L, "cc0b844f39f6e5a1ca8faa6742102947ba904889cb725c19b49a9cb7ca2aeed0"),
            Artifact("sam3.tflite", 52_386_032L, "2f9deffb67b09f4d0d328c287c01e31ddfe83b30c06daf03bb26a1b5147ec382"),
            Artifact("generator.tflite", 179_058_976L, "49f13c8d8dd8f543ebed6a3b76ee60fcf3195680ec9acd93d4fe83e3cfcb72da"),
        )

        internal fun samLevel0ToNhwc(nchw: FloatArray): FloatArray {
            require(nchw.size == 256 * 64 * 64) { "Invalid SAM level 0" }
            val output = FloatArray(nchw.size)
            for (y in 0 until 64) {
                for (x in 0 until 64) {
                    for (c in 0 until 256) {
                        output[(y * 64 + x) * 256 + c] = nchw[(c * 64 + y) * 64 + x]
                    }
                }
            }
            return output
        }

        internal fun samRgb(luma: FloatArray): FloatArray {
            require(luma.size == SIZE * SIZE)
            val output = FloatArray(1024 * 1024 * 3)
            for (y in 0 until 1024) {
                val fy = (y + 0.5f) / 2f - 0.5f
                val baseY = kotlin.math.floor(fy).toInt()
                val y0 = baseY.coerceIn(0, SIZE - 1)
                val y1 = (baseY + 1).coerceIn(0, SIZE - 1)
                val wy = (fy - kotlin.math.floor(fy)).coerceIn(0f, 1f)
                for (x in 0 until 1024) {
                    val fx = (x + 0.5f) / 2f - 0.5f
                    val baseX = kotlin.math.floor(fx).toInt()
                    val x0 = baseX.coerceIn(0, SIZE - 1)
                    val x1 = (baseX + 1).coerceIn(0, SIZE - 1)
                    val wx = (fx - kotlin.math.floor(fx)).coerceIn(0f, 1f)
                    val top = luma[y0 * SIZE + x0] * (1f - wx) + luma[y0 * SIZE + x1] * wx
                    val bottom = luma[y1 * SIZE + x0] * (1f - wx) + luma[y1 * SIZE + x1] * wx
                    val value = top * (1f - wy) + bottom * wy
                    val offset = (y * 1024 + x) * 3
                    output[offset] = value
                    output[offset + 1] = value
                    output[offset + 2] = value
                }
            }
            return output
        }
    }
}
