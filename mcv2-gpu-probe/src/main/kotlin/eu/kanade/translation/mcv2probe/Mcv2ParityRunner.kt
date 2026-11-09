package eu.kanade.translation.mcv2probe

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * Parity + benchmark runner (r6): all test semantics unchanged from r4/r5 —
 * LiteRT CPU (XNNPACK) arm, selectable accelerated arm (GPU LITERT_CL / NPU
 * dispatch with DispatchLibraryDir), optional ONNX Runtime CPU baseline over
 * the FP16 ONNX primary, byte-identical raw inputs, reported-not-gated metrics,
 * warmup+repeats timing with run/readback split, PSS sampling, raw dumps and
 * amplified difference renders. Owned by Mcv2ParityService so runs survive
 * activity teardown; stage text flows through the injected callback.
 */
class Mcv2ParityRunner(
    private val context: Context,
    private val config: HarnessConfig,
    private val status: (String) -> Unit,
) {
    data class HarnessConfig(
        val accelerator: Accelerator,
        val cpuFile: File,
        val acceleratedFile: File,
        val ortFile: File?,
        val referenceFile: File?,
        val inputFile: File?,
        val inputName: String,
        val outputName: String,
        val allOutputNames: List<String>? = null,
        val signature: String,
        val inputDims: List<Int>,
        val outputDims: List<Int>,
        val inputFill: String,
        val dumpOutput: Boolean,
        val benchmark: Boolean,
        val warmupCount: Int,
        val repeatCount: Int,
        val gpuPrecision: String? = null,
        val auxiliaryInputs: Map<String, File> = emptyMap(),
        val samModels: List<File> = emptyList(),
        val samInput: File? = null,
    ) {
        val inputFloatCount: Int = inputDims.fold(1) { product, dim -> product * dim }
        val outputFloatCount: Int = outputDims.fold(1) { product, dim -> product * dim }
        val isRgbOutput: Boolean = outputDims.size == 4 && outputDims[3] == 3

        fun validate(): String? = when {
            !cpuFile.isFile || cpuFile.length() == 0L -> "cpu model missing: ${cpuFile.absolutePath}"
            !acceleratedFile.isFile || acceleratedFile.length() == 0L ->
                "accelerated model missing: ${acceleratedFile.absolutePath}"
            ortFile != null && (!ortFile.isFile || ortFile.length() == 0L) ->
                "ort model missing: ${ortFile.absolutePath}"
            referenceFile != null && !referenceFile.isFile ->
                "reference missing: ${referenceFile.absolutePath}"
            inputFile != null && inputFile.length() != inputFloatCount * 4L ->
                "input ${inputFile.absolutePath} has ${inputFile.length()} bytes; expected ${inputFloatCount * 4}"
            gpuPrecision != null && gpuPrecision !in KNOWN_GPU_PRECISIONS ->
                "unknown gpuPrecision '$gpuPrecision'; expected one of $KNOWN_GPU_PRECISIONS"
            else -> null
        }

        companion object {
            fun fromExtras(extras: Map<String, String>, defaultDir: File): HarnessConfig {
                val accelerator = when (extras[args[ArgumentKey.ACCELERATOR]]?.lowercase()) {
                    "cpu" -> Accelerator.CPU
                    "npu" -> Accelerator.NPU
                    else -> Accelerator.GPU
                }
                fun namedFile(argument: String, fallback: File): File =
                    extras[argument]?.let(::File) ?: fallback
                val primary = namedFile(args[ArgumentKey.MODEL]!!, File(defaultDir, MODEL_FILE_NAME))
                fun dims(argument: String, fallback: List<Int>): List<Int> =
                    extras[argument]
                        ?.split(',')
                        ?.map { it.trim().toInt() }
                        ?: fallback

                return HarnessConfig(
                    accelerator = accelerator,
                    cpuFile = namedFile(args[ArgumentKey.CPU_MODEL]!!, primary),
                    acceleratedFile = namedFile(args[ArgumentKey.ACCELERATED_MODEL]!!, primary),
                    ortFile = extras[args[ArgumentKey.ORT_MODEL]!!]?.let(::File),
                    referenceFile = extras[args[ArgumentKey.REFERENCE]!!]?.let(::File)
                        ?: File(defaultDir, DEFAULT_REFERENCE_NAME).takeIf { it.isFile },
                    inputFile = extras[args[ArgumentKey.INPUT]!!]?.let(::File)?.takeIf { it.isFile },
                    inputName = extras[args[ArgumentKey.INPUT_NAME]!!] ?: INPUT_NAME,
                    outputName = extras[args[ArgumentKey.OUTPUT_NAME]!!] ?: OUTPUT_NAME,
                    allOutputNames = extras[args[ArgumentKey.ALL_OUTPUT_NAMES]!!]
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.takeIf { it.isNotEmpty() },
                    signature = extras[args[ArgumentKey.SIGNATURE]!!] ?: SIGNATURE,
                    inputDims = dims(args[ArgumentKey.INPUT_DIMS]!!, DEFAULT_INPUT_DIMS),
                    outputDims = dims(args[ArgumentKey.OUTPUT_DIMS]!!, DEFAULT_OUTPUT_DIMS),
                    inputFill = extras[args[ArgumentKey.INPUT_FILL]!!]?.lowercase() ?: "pattern",
                    dumpOutput = extras[args[ArgumentKey.DUMP]!!] == "1",
                    benchmark = extras[args[ArgumentKey.BENCHMARK]!!] == "1",
                    warmupCount = extras[args[ArgumentKey.WARMUP]!!]?.toIntOrNull()?.coerceIn(0, 20) ?: 3,
                    repeatCount = extras[args[ArgumentKey.REPEATS]!!]?.toIntOrNull()?.coerceIn(1, 100) ?: 10,
                    gpuPrecision = extras[args[ArgumentKey.GPU_PRECISION]!!]?.lowercase()?.ifEmpty { null },
                    auxiliaryInputs = extras["auxiliaryInputs"]?.split(',')?.associate { entry ->
                        val parts = entry.split('=', limit = 2)
                        require(parts.size == 2 && parts[0].isNotBlank()) { "Invalid auxiliary input: $entry" }
                        parts[0] to File(parts[1])
                    } ?: emptyMap(),
                    samModels = extras["samModels"]?.split(',')?.map(::File) ?: emptyList(),
                    samInput = extras["samInput"]?.let(::File),
                )
            }
        }
    }

    private data class ArmResult(val output: FloatArray, val min: Float, val max: Float)

    /**
     * Log-only stage watchdog: records stage enter/exit; past a stage's soft
     * bound emits WATCHDOG_BREACH with the worker's stack dump, past the hard
     * bound PARITY_FAIL watchdog_hang. Instrumentation for stall evidence, not gates.
     */
    private class StageWatchdog(private val runThread: Thread) {
        private data class Active(
            val stage: String,
            val startedAtMs: Long,
            val softMs: Long,
            val hardMs: Long,
        )

        private val active = java.util.concurrent.atomic.AtomicReference<Active?>(null)
        private val monitor = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(5000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val current = active.get() ?: continue
                val elapsed = System.currentTimeMillis() - current.startedAtMs
                if (elapsed >= current.hardMs) {
                    dump("hard", current, elapsed)
                    active.set(null)
                } else if (elapsed >= current.softMs) {
                    dump("soft", current, elapsed)
                }
            }
        }.apply {
            isDaemon = true
            name = "mcv2-stage-watchdog"
            start()
        }

        fun enter(stage: String, softMs: Long, hardMs: Long) {
            active.set(Active(stage, System.currentTimeMillis(), softMs, hardMs))
            Log.i(LOG_TAG, "STAGE_ENTER stage=$stage soft_ms=$softMs hard_ms=$hardMs")
        }

        fun exit(stage: String) {
            val current = active.getAndSet(null)
            if (current != null) {
                val elapsed = System.currentTimeMillis() - current.startedAtMs
                Log.i(
                    LOG_TAG,
                    "STAGE_EXIT stage=$stage elapsed_ms=$elapsed breached=${elapsed >= current.softMs} " +
                        LifecycleTrace.snapshot(),
                )
            }
        }

        private fun dump(level: String, current: Active, elapsedMs: Long) {
            Log.w(
                LOG_TAG,
                "WATCHDOG_BREACH level=$level stage=${current.stage} elapsed_ms=$elapsedMs " +
                    "soft_ms=${current.softMs} hard_ms=${current.hardMs}",
            )
            val stacks = runThread.stackTrace
            Log.w(LOG_TAG, "WATCHDOG_THREAD_DUMP stage=${current.stage} depth=${stacks.size}")
            for (element in stacks) {
                Log.w(LOG_TAG, "WATCHDOG_AT $element")
            }
            if (level == "hard") {
                Log.e(
                    LOG_TAG,
                    "PARITY_FAIL stage=watchdog_hang detail=stage=${current.stage} " +
                        "thread_uninterruptible_operator_decides_force_stop",
                )
            }
        }

        fun close() {
            monitor.interrupt()
        }
    }

    fun run() {
        val watchdog = StageWatchdog(Thread.currentThread())
        try {
            status("Preparing: hashing models, loading input + reference…")
            prepareAndRun(watchdog)
        } finally {
            watchdog.close()
        }
    }

    private fun prepareAndRun(watchdog: StageWatchdog) {
        val reference = config.referenceFile?.let { loadReference(it, config.outputFloatCount) }
        val input = buildInput(config)
        Log.i(
            LOG_TAG,
            "PARITY_START accelerator=${config.accelerator} " +
                "cpu_model=${config.cpuFile.absolutePath} cpu_model_sha256=${sha256(config.cpuFile)} " +
                "acc_model=${config.acceleratedFile.absolutePath} acc_model_sha256=${sha256(config.acceleratedFile)} " +
                "ort_model=" + (config.ortFile?.absolutePath ?: "none") +
                " ort_model_sha256=" + (config.ortFile?.let { sha256(it) } ?: "none") +
                " input_source=" + (config.inputFile?.absolutePath ?: "recipe:${config.inputFill}") +
                " input_sha256=" + sha256Bytes(input) +
                " input_dims=${config.inputDims} output_dims=${config.outputDims} " +
                "gpu_precision=${config.gpuPrecision ?: "default"} " +
                "benchmark=${config.benchmark} warmup=${config.warmupCount} repeats=${config.repeatCount} " +
                "reference=" + (config.referenceFile?.absolutePath ?: "none") +
                " reference_sha256=" + (reference?.let { sha256Bytes(it) } ?: "none"),
        )

        val environment = if (config.accelerator == Accelerator.NPU) {
            // DISPATCH_OP resolution requires the dispatch library dir
            // (compiled_model.cc:982); the extracted lib dir holds it.
            Environment.create(
                context.applicationContext,
                mapOf(Environment.Option.DispatchLibraryDir to context.applicationInfo.nativeLibraryDir),
            )
        } else {
            Environment.create(context.applicationContext)
        }

        val runConfig = if (config.samModels.isNotEmpty()) {
            config.copy(auxiliaryInputs = runSamChain(config))
        } else {
            config
        }
        val cpu = runArm(environment, runConfig, config.cpuFile, Accelerator.CPU, input, "cpu", watchdog)
        val accelerated = runLitertArm(environment, runConfig, config.acceleratedFile, config.accelerator, input, "acc", watchdog)
        val ort = config.ortFile?.let { runOrtArm(it, config, input, watchdog) }

        status("Comparing outputs (metrics go to logcat, tag Mcv2GpuParity)…")

        if (reference == null) {
            Log.i(LOG_TAG, "REFERENCE_COMPARE skipped (no reference file supplied)")
        } else {
            cpu?.let { Log.i(LOG_TAG, "CPU_VS_PUSHED_REFERENCE " + compare(it.output, reference, "cpu_vs_ref")) }
            accelerated?.let {
                Log.i(LOG_TAG, "ACC_VS_PUSHED_REFERENCE " + compare(it.output, reference, "acc_vs_ref"))
            }
            ort?.let { Log.i(LOG_TAG, "ORT_VS_PUSHED_REFERENCE " + compare(it.output, reference, "ort_vs_ref")) }
        }
        if (cpu != null && accelerated != null) {
            Log.i(LOG_TAG, "ACC_VS_CPU_LITERT " + compare(accelerated.output, cpu.output, "acc_vs_cpu"))
        }
        if (cpu != null && ort != null) {
            Log.i(LOG_TAG, "ORT_VS_CPU_LITERT " + compare(ort.output, cpu.output, "ort_vs_cpu"))
        }

        val pssEnd = totalPssKb()
        Log.i(LOG_TAG, "PSS_AT_END_KB=$pssEnd")

        if (config.dumpOutput) {
            status("Saving raw output dumps…")
            cpu?.let { dumpRaw(it.output, "bench_out_cpu.raw") }
            accelerated?.let { dumpRaw(it.output, "bench_out_acc.raw") }
            ort?.let { dumpRaw(it.output, "bench_out_ort.raw") }
        }
        status("Saving renders + difference maps…")
        cpu?.let { savePng(it.output, config, "parity_cpu.png") }
        accelerated?.let { savePng(it.output, config, "parity_acc.png") }
        ort?.let { savePng(it.output, config, "parity_ort.png") }
        reference?.let { savePng(it, config, "parity_reference.png") }
        accelerated?.let { acc ->
            val baseline = reference ?: cpu?.output
            if (baseline != null) saveDiffPng(acc.output, baseline, config, "parity_diff_acc.png")
        }
        ort?.let { o ->
            val baseline = reference ?: cpu?.output
            if (baseline != null) saveDiffPng(o.output, baseline, config, "parity_diff_ort.png")
        }

        val complete = cpu != null && accelerated != null && reference != null
        Log.i(
            LOG_TAG,
            "PARITY_DONE input_sha256=" + sha256Bytes(input) + " complete=$complete " +
                "cpu_out_sha256=" + (cpu?.let { sha256Bytes(it.output) } ?: "none") +
                " acc_out_sha256=" + (accelerated?.let { sha256Bytes(it.output) } ?: "none") +
                " ort_out_sha256=" + (ort?.let { sha256Bytes(it.output) } ?: "none"),
        )
        val failedArms = listOfNotNull(
            "cpu".takeIf { cpu == null },
            "acc".takeIf { accelerated == null },
            "ort".takeIf { config.ortFile != null && ort == null },
        )
        if (failedArms.isEmpty()) {
            status("DONE — all arms executed and compared. Metrics in logcat (tag Mcv2GpuParity). " +
                "This status shows no pass/fail judgment; thresholds are evaluated externally.")
        } else {
            status("FINISHED with failed arms: ${failedArms.joinToString(", ")}. See logcat (tag Mcv2GpuParity).")
        }
        environment.close()
    }

    private fun runSamChain(config: HarnessConfig): Map<String, File> {
        require(config.samModels.size == 4) { "Expected four SAM models" }
        val source = requireNotNull(config.samInput) { "SAM input is required" }.readBytes()
        require(source.size == 1024 * 1024 * 3 * 4) { "Unexpected SAM input size" }
        val bytes = java.nio.ByteBuffer.wrap(source).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var input = FloatArray(source.size / 4) { bytes.float }
        val inputs = listOf("rgb_input", "enc_stage0_out", "enc_stage1_out", "enc_stage3_in")
        val outputs = listOf("enc_stage0_out", "enc_stage1_out", "enc_stage3_in", "sam_level0")
        val env = Environment.create(context.applicationContext, mapOf(Environment.Option.DispatchLibraryDir to context.applicationInfo.nativeLibraryDir))
        val start = System.nanoTime()
        try {
            for (index in config.samModels.indices) {
                val model = CompiledModel.create(config.samModels[index].absolutePath, CompiledModel.Options(Accelerator.NPU), env)
                val inBuffer = model.createInputBuffer(inputs[index], config.signature)
                val outBuffer = model.createOutputBuffer(outputs[index], config.signature)
                val level1 = if (index == 3) model.createOutputBuffer("sam_level1", config.signature) else null
                try {
                    inBuffer.writeFloat(input)
                    val resultBuffers = mutableMapOf(outputs[index] to outBuffer)
                    if (level1 != null) resultBuffers["sam_level1"] = level1
                    val stageStart = System.nanoTime()
                    model.run(mapOf(inputs[index] to inBuffer), resultBuffers, config.signature)
                    input = outBuffer.readFloat()
                    Log.i(LOG_TAG, "SAM_STAGE index=$index run_read_ms=${(System.nanoTime() - stageStart) / 1_000_000.0}")
                    if (level1 != null) {
                        val level0Nhwc = FloatArray(input.size)
                        for (y in 0 until 64) for (x in 0 until 64) for (c in 0 until 256) {
                            level0Nhwc[(y * 64 + x) * 256 + c] = input[(c * 64 + y) * 64 + x]
                        }
                        val result = mapOf("sam_level0" to level0Nhwc, "sam_level1" to level1.readFloat())
                        return result.mapValues { (name, values) ->
                            File(context.filesDir, "live_sam_npu_$name.raw").also { file ->
                                val buffer = java.nio.ByteBuffer.allocate(values.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                values.forEach { buffer.putFloat(it) }
                                file.writeBytes(buffer.array())
                            }
                        }
                    }
                } finally {
                    level1?.close()
                    outBuffer.close()
                    inBuffer.close()
                    model.close()
                }
            }
            error("SAM output missing")
        } finally {
            Log.i(LOG_TAG, "SAM_CHAIN_WITH_INIT_MS=${(System.nanoTime() - start) / 1_000_000.0}")
            env.close()
        }
    }

    private fun runLitertArm(
        environment: Environment,
        config: HarnessConfig,
        modelFile: File,
        accelerator: Accelerator,
        input: FloatArray,
        label: String,
        watchdog: StageWatchdog,
    ): ArmResult? {
        val armTitle = "$label arm (${accelerator.name})"
        return runCatching { runArm(environment, config, modelFile, accelerator, input, label, watchdog) }
            .onFailure { failure ->
                status("$armTitle FAILED (see logcat): ${failure.message}")
                Log.w(LOG_TAG, "ACCELERATED_ARM_COMPILE_OR_RUN_FAILED (diagnostic only)", failure)
            }
            .getOrNull()
    }

    private fun runArm(
        environment: Environment,
        config: HarnessConfig,
        modelFile: File,
        accelerator: Accelerator,
        input: FloatArray,
        label: String,
        watchdog: StageWatchdog,
    ): ArmResult {
        val armTitle = "$label arm (${accelerator.name})"
        status("$armTitle: initializing/compiling model…")
        val pssBefore = totalPssKb()
        val compileStarted = System.nanoTime()
        watchdog.enter("litert_init_$label", SOFT_BOUND_INIT_MS, HARD_BOUND_INIT_MS)
        val options = CompiledModel.Options(accelerator)
        if (accelerator == Accelerator.GPU && config.gpuPrecision != null && config.gpuPrecision != "default") {
            // Requested GPU storage-precision mode: the OpenCL accelerator is
            // asked explicitly; honoring is verified behaviorally per run
            // (outputs vs reference + delegate logcat), never assumed.
            val precision = when (config.gpuPrecision) {
                "fp16" -> CompiledModel.GpuOptions.Precision.FP16
                "fp32" -> CompiledModel.GpuOptions.Precision.FP32
                "fp16_fp32accum" -> CompiledModel.GpuOptions.Precision.FP16_WITH_FP32_ACCUM
                else -> CompiledModel.GpuOptions.Precision.DEFAULT
            }
            options.gpuOptions = CompiledModel.GpuOptions(precision = precision)
            Log.i(LOG_TAG, "GPU_PRECISION_REQUESTED option=${config.gpuPrecision} enum=$precision")
        } else {
            Log.i(LOG_TAG, "GPU_PRECISION_REQUESTED option=${config.gpuPrecision ?: "unset"} enum=stock_defaults")
        }
        val model = CompiledModel.create(modelFile.absolutePath, options, environment)
        watchdog.exit("litert_init_$label")
        val compileMillis = (System.nanoTime() - compileStarted) / 1_000_000L
        assertTensor(model, config, isInput = true)
        assertTensor(model, config, isInput = false)
        val inputBuffer = model.createInputBuffer(config.inputName, config.signature)
        val auxiliaryBuffers = config.auxiliaryInputs.mapValues { (name, file) ->
            val bytes = file.readBytes()
            require(bytes.size % 4 == 0) { "Invalid float input ${file.name}" }
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            model.createInputBuffer(name, config.signature).also { target ->
                target.writeFloat(FloatArray(bytes.size / 4) { buffer.float })
            }
        }
        val inputBuffers = mapOf(config.inputName to inputBuffer) + auxiliaryBuffers
        // CompiledModel.run requires a TensorBuffer for EVERY signature output
        // when the model exposes more than one (single-entry maps abort in JNI:
        // "The given map is missing some output TensorBuffers").
        val outputBuffers: Map<String, TensorBuffer> =
            if (config.allOutputNames.isNullOrEmpty()) {
                mapOf(config.outputName to model.createOutputBuffer(config.outputName, config.signature))
            } else {
                config.allOutputNames.associateWith { model.createOutputBuffer(it, config.signature) }
            }
        val outputBuffer = outputBuffers.getValue(config.outputName)
        try {
            inputBuffer.writeFloat(input)
            status("$armTitle: running inference…")
            watchdog.enter("litert_run_$label", SOFT_BOUND_RUN_MS, HARD_BOUND_RUN_MS)
            model.run(
                inputBuffers,
                outputBuffers,
                config.signature,
            )
            watchdog.exit("litert_run_$label")
            val output = outputBuffer.readFloat()
            check(output.size == config.outputFloatCount) { "$label output has ${output.size} floats" }
            check(output.all { it.isFinite() }) { "$label output contains non-finite values" }
            if (config.benchmark) {
                status("$armTitle: benchmarking (${config.warmupCount} warmup + ${config.repeatCount} repeats)…")
                watchdog.enter("litert_bench_$label", SOFT_BOUND_BENCH_MS, HARD_BOUND_BENCH_MS)
                benchmarkLitert(config, model, inputBuffers, outputBuffers, outputBuffer, label, modelFile, accelerator, pssBefore, compileMillis)
                watchdog.exit("litert_bench_$label")
            }
            status("$armTitle: done")
            val min = output.min()
            val max = output.max()
            Log.i(
                LOG_TAG,
                "ARM_RESULT arm=$label accelerator=$accelerator model=${modelFile.name} " +
                    "model_sha256=${sha256(modelFile)} compile_ms=$compileMillis " +
                    "min=$min max=$max mean=${output.average()} sha256=${sha256Bytes(output)}",
            )
            return ArmResult(output, min, max)
        } finally {
            runCatching { inputBuffer.close() }
            auxiliaryBuffers.values.forEach { runCatching { it.close() } }
            outputBuffers.values.forEach { runCatching { it.close() } }
            runCatching { model.close() }
        }
    }

    private fun benchmarkLitert(
        config: HarnessConfig,
        model: CompiledModel,
        inputBuffers: Map<String, TensorBuffer>,
        outputBuffers: Map<String, TensorBuffer>,
        outputBuffer: TensorBuffer,
        label: String,
        modelFile: File,
        accelerator: Accelerator,
        pssBeforeKb: Int,
        compileMillis: Long,
    ) {
        repeat(config.warmupCount) {
            model.run(
                inputBuffers,
                outputBuffers,
                config.signature,
            )
        }
        val runNanos = LongArray(config.repeatCount)
        val readNanos = LongArray(config.repeatCount)
        for (i in 0 until config.repeatCount) {
            val runStart = System.nanoTime()
            model.run(
                inputBuffers,
                outputBuffers,
                config.signature,
            )
            runNanos[i] = System.nanoTime() - runStart
            val readStart = System.nanoTime()
            outputBuffer.readFloat()
            readNanos[i] = System.nanoTime() - readStart
            Log.i(
                LOG_TAG,
                "BENCH_REPEAT arm=$label idx=${i + 1}/${config.repeatCount} " +
                    "run_ms=${runNanos[i] / 1_000_000.0} read_ms=${readNanos[i] / 1_000_000.0} " +
                    LifecycleTrace.snapshot(),
            )
        }
        val pssAfter = totalPssKb()
        Log.i(
            LOG_TAG,
            "BENCH_RESULT arm=$label backend=litert accelerator=$accelerator model=${modelFile.name} " +
                "init_ms=$compileMillis warmup=${config.warmupCount} repeats=${config.repeatCount} " +
                stats("run_ms", runNanos) + " " + stats("read_ms", readNanos) +
                " pss_before_kb=$pssBeforeKb pss_after_kb=$pssAfter",
        )
    }

    private fun runOrtArm(modelFile: File, config: HarnessConfig, input: FloatArray, watchdog: StageWatchdog): ArmResult? {
        return runCatching {
            val pssBefore = totalPssKb()
            val env = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions()
            status("ORT-CPU baseline: creating session (FP16 ONNX on CPU)…")
            watchdog.enter("ort_session_create", SOFT_BOUND_INIT_MS, HARD_BOUND_INIT_MS)
            val session = env.createSession(modelFile.absolutePath, sessionOptions)
            watchdog.exit("ort_session_create")
            val onnxInputName = session.inputNames.iterator().next()
            val shape = config.inputDims.map { it.toLong() }.toLongArray()
            val onnxOutputName = session.outputNames.iterator().next()
            try {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
                    status("ORT-CPU baseline: running inference…")
                    watchdog.enter("ort_run", SOFT_BOUND_RUN_MS, HARD_BOUND_RUN_MS)
                    session.run(mapOf(onnxInputName to tensor)).use { results ->
                        watchdog.exit("ort_run")
                        val output = nchwToNhwc((results[0] as OnnxTensor).floatBuffer, config)
                        check(output.all { it.isFinite() }) { "ort output contains non-finite values" }
                        if (config.benchmark) {
                            status("ORT-CPU baseline: benchmarking (${config.warmupCount} warmup + ${config.repeatCount} repeats)…")
                            watchdog.enter("ort_bench", SOFT_BOUND_BENCH_MS, HARD_BOUND_BENCH_MS)
                            benchmarkOrt(env, session, onnxInputName, shape, input, config, modelFile, pssBefore)
                            watchdog.exit("ort_bench")
                        }
                        status("ORT-CPU baseline: done")
                        val min = output.min()
                        val max = output.max()
                        Log.i(
                            LOG_TAG,
                            "ARM_RESULT arm=ort accelerator=CPU_ORT model=${modelFile.name} " +
                                "model_sha256=${sha256(modelFile)} onnx_input_name=$onnxInputName " +
                                "onnx_output_name=$onnxOutputName onnx_layout=NCHW_transposed_to_NHWC " +
                                "min=$min max=$max mean=${output.average()} sha256=${sha256Bytes(output)}",
                        )
                        return ArmResult(output, min, max)
                    }
                }
                error("unreachable")
            } finally {
                runCatching { session.close() }
            }
        }.onFailure { failure ->
            status("ORT-CPU baseline FAILED (see logcat): ${failure.message}")
            Log.w(LOG_TAG, "ORT_ARM_COMPILE_OR_RUN_FAILED (diagnostic only)", failure)
        }.getOrNull()
    }

    /** ONNX 'rgb' is NCHW [1,3,H,W]; device contract is NHWC [1,H,W,3]. */
    private fun nchwToNhwc(source: java.nio.FloatBuffer, config: HarnessConfig): FloatArray {
        val channels = 3
        val width = config.outputDims[2]
        val height = config.outputDims[1]
        val plane = width * height
        val nchw = FloatArray(config.outputFloatCount)
        source.get(nchw)
        val output = FloatArray(config.outputFloatCount)
        for (c in 0 until channels) {
            for (i in 0 until plane) {
                output[i * channels + c] = nchw[c * plane + i]
            }
        }
        return output
    }

    private fun benchmarkOrt(
        env: OrtEnvironment,
        session: OrtSession,
        inputName: String,
        shape: LongArray,
        input: FloatArray,
        config: HarnessConfig,
        modelFile: File,
        pssBeforeKb: Int,
    ) {
        // Tensor created once and reused across repeats, mirroring the LiteRT
        // input-written-once protocol.
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            repeat(config.warmupCount) {
                session.run(mapOf(inputName to tensor)).use { }
            }
            val runNanos = LongArray(config.repeatCount)
            val readNanos = LongArray(config.repeatCount)
            for (i in 0 until config.repeatCount) {
                val runStart = System.nanoTime()
                session.run(mapOf(inputName to tensor)).use { results ->
                    runNanos[i] = System.nanoTime() - runStart
                    val readStart = System.nanoTime()
                    val fb = (results[0] as OnnxTensor).floatBuffer
                    val sink = FloatArray(config.outputFloatCount)
                    fb.get(sink)
                    readNanos[i] = System.nanoTime() - readStart
                    check(sink.size == config.outputFloatCount)
                    Log.i(
                        LOG_TAG,
                        "BENCH_REPEAT arm=ort idx=${i + 1}/${config.repeatCount} " +
                            "run_ms=${runNanos[i] / 1_000_000.0} read_ms=${readNanos[i] / 1_000_000.0} " +
                            LifecycleTrace.snapshot(),
                    )
                }
            }
            val pssAfter = totalPssKb()
            Log.i(
                LOG_TAG,
                "BENCH_RESULT arm=ort backend=onnxruntime accelerator=CPU_ORT model=${modelFile.name} " +
                    "init_ms=na warmup=${config.warmupCount} repeats=${config.repeatCount} " +
                    stats("run_ms", runNanos) + " " + stats("read_ms", readNanos) +
                    " pss_before_kb=$pssBeforeKb pss_after_kb=$pssAfter",
            )
        }
    }

    private fun stats(name: String, nanos: LongArray): String {
        val ms = nanos.map { it / 1_000_000.0 }
        val mean = ms.average()
        val min = ms.min()
        val max = ms.max()
        val std = if (ms.size > 1) {
            val variance = ms.fold(0.0) { acc, v -> acc + (v - mean) * (v - mean) } / (ms.size - 1)
            kotlin.math.sqrt(variance)
        } else {
            0.0
        }
        return "$name min=$min mean=$mean max=$max std=$std"
    }

    private fun totalPssKb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss
    }

    private fun dumpRaw(output: FloatArray, name: String) {
        runCatching {
            val buffer = java.nio.ByteBuffer.allocate(output.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            repeat(output.size) { buffer.putFloat(output[it]) }
            val target = File(context.getExternalFilesDir(null), name)
            target.writeBytes(buffer.array())
            Log.i(LOG_TAG, "RAW_DUMPED name=$name path=${target.absolutePath} bytes=${buffer.array().size}")
        }.onFailure { Log.w(LOG_TAG, "RAW_DUMP_FAILED name=$name", it) }
    }

    private fun compare(candidate: FloatArray, reference: FloatArray, label: String): String {
        val count = minOf(candidate.size, reference.size)
        var maxAbs = 0.0
        var sumAbs = 0.0
        var sumSquared = 0.0
        var uint8Exact = 0
        for (i in 0 until count) {
            val diff = kotlin.math.abs((candidate[i] - reference[i]).toDouble())
            if (diff > maxAbs) maxAbs = diff
            sumAbs += diff
            sumSquared += diff * diff
            if (decode(candidate[i]) == decode(reference[i])) uint8Exact++
        }
        val mse = sumSquared / count
        val psnr = if (mse > 1e-12) 10.0 * Math.log10(1.0 / mse) else 999.0
        return label +
            " n=$count max_abs=$maxAbs mean_abs=${sumAbs / count}" +
            " psnr_db=$psnr uint8_exact=$uint8Exact/$count" +
            " gt_1e-3=" + countRange(candidate, reference, 1e-3f) +
            " gt_5e-3=" + countRange(candidate, reference, 5e-3f)
    }

    private fun countRange(a: FloatArray, b: FloatArray, threshold: Float): Int {
        var count = 0
        for (i in a.indices) if (kotlin.math.abs(a[i] - b[i]) > threshold) count++
        return count
    }

    private fun decode(value: Float): Int =
        (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun savePng(output: FloatArray, config: HarnessConfig, name: String) {
        if (!config.isRgbOutput) {
            Log.i(LOG_TAG, "PNG_SKIPPED name=$name reason=output_not_rgb dims=${config.outputDims}")
            return
        }
        runCatching {
            val width = config.outputDims[2]
            val height = config.outputDims[1]
            val pixels = IntArray(width * height)
            for (pixel in pixels.indices) {
                val offset = pixel * 3
                val r = decode(output[offset])
                val g = decode(output[offset + 1])
                val b = decode(output[offset + 2])
                pixels[pixel] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            writeBitmapPng(pixels, width, height, name)
        }.onFailure { Log.w(LOG_TAG, "PNG_SAVE_FAILED name=$name", it) }
    }

    private fun saveDiffPng(candidate: FloatArray, baseline: FloatArray, config: HarnessConfig, name: String) {
        if (!config.isRgbOutput) return
        runCatching {
            val width = config.outputDims[2]
            val height = config.outputDims[1]
            val pixels = IntArray(width * height)
            var maxDiff = 0f
            for (pixel in pixels.indices) {
                val offset = pixel * 3
                val dr = kotlin.math.abs(candidate[offset] - baseline[offset])
                val dg = kotlin.math.abs(candidate[offset + 1] - baseline[offset + 1])
                val db = kotlin.math.abs(candidate[offset + 2] - baseline[offset + 2])
                maxDiff = maxOf(maxDiff, dr, dg, db)
                val v = decode(kotlin.math.max(dr, kotlin.math.max(dg, db)) * 50f)
                pixels[pixel] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            Log.i(LOG_TAG, "DIFF_MAP name=$name max_diff=$maxDiff amplification=50x")
            writeBitmapPng(pixels, width, height, name)
        }.onFailure { Log.w(LOG_TAG, "DIFF_SAVE_FAILED name=$name", it) }
    }

    private fun writeBitmapPng(pixels: IntArray, width: Int, height: Int, name: String) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val target = File(context.getExternalFilesDir(null), name)
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        Log.i(LOG_TAG, "PNG_SAVED name=$name path=${target.absolutePath}")
    }

    private fun buildInput(config: HarnessConfig): FloatArray {
        config.inputFile?.let { file ->
            val bytes = file.readBytes()
            check(bytes.size == config.inputFloatCount * 4) {
                "Input ${file.name} has ${bytes.size} bytes; expected ${config.inputFloatCount * 4}"
            }
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return FloatArray(config.inputFloatCount) { buffer.float }
        }
        val input = FloatArray(config.inputFloatCount)
        when (config.inputFill) {
            "zeros" -> return input
            "random" -> {
                val random = java.util.Random(INPUT_FILL_RANDOM_SEED)
                for (i in input.indices) input[i] = random.nextFloat()
                return input
            }
            else -> if (config.inputDims == DEFAULT_INPUT_DIMS) {
                // Canonical grid pattern; bitwise-stable across sessions (feeds
                // recorded corpus/reference hashes).
                val planeSize = DEFAULT_INPUT_DIMS[3] * DEFAULT_INPUT_DIMS[2]
                for (y in 0 until DEFAULT_INPUT_DIMS[2]) {
                    for (x in 0 until DEFAULT_INPUT_DIMS[3]) {
                        input[y * DEFAULT_INPUT_DIMS[3] + x] =
                            if (x % 64 < 3 || y % 64 < 3) 0.05f else 0.95f
                    }
                }
                return input
            } else {
                Log.w(LOG_TAG, "INPUT_FILL_FALLBACK requested=pattern actual_dims=${config.inputDims} -> zeros")
                return input
            }
        }
    }

    private fun loadReference(file: File, expectedFloatCount: Int): FloatArray {
        val bytes = file.readBytes()
        check(bytes.size == expectedFloatCount * 4) {
            "Reference ${file.name} has ${bytes.size} bytes; expected ${expectedFloatCount * 4}"
        }
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val output = FloatArray(expectedFloatCount)
        repeat(expectedFloatCount) { output[it] = buffer.float }
        return output
    }

    private fun assertTensor(model: CompiledModel, config: HarnessConfig, isInput: Boolean) {
        val name = if (isInput) config.inputName else config.outputName
        val type = if (isInput) {
            model.getInputTensorType(name, config.signature)
        } else {
            model.getOutputTensorType(name, config.signature)
        }
        check(type.elementType == TensorType.ElementType.FLOAT) { "Tensor '$name' is not float32" }
        check(type.layout?.dimensions == (if (isInput) config.inputDims else config.outputDims)) {
            "Tensor '$name' dimensions are ${type.layout?.dimensions}; " +
                "expected ${if (isInput) config.inputDims else config.outputDims}"
        }
    }

    private fun sha256(file: File): String = sha256Bytes(file.readBytes())

    private fun sha256Bytes(array: FloatArray): String {
        val buffer = java.nio.ByteBuffer.allocate(array.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(array.size) { buffer.putFloat(array[it]) }
        return sha256Bytes(buffer.array())
    }

    private fun sha256Bytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private enum class ArgumentKey {
        MODEL, CPU_MODEL, ACCELERATED_MODEL, ORT_MODEL, REFERENCE, INPUT,
        ACCELERATOR, INPUT_NAME, OUTPUT_NAME, ALL_OUTPUT_NAMES, SIGNATURE,
        INPUT_DIMS, OUTPUT_DIMS, INPUT_FILL, DUMP, BENCHMARK, WARMUP, REPEATS,
        GPU_PRECISION,
    }

    companion object {
        const val LOG_TAG = "Mcv2GpuParity"
        const val MODEL_FILE_NAME = "mcv2-probe.tflite"
        const val DEFAULT_REFERENCE_NAME = "reference_f32.raw"
        const val SIGNATURE = "serving_default"
        const val INPUT_NAME = "input"
        const val OUTPUT_NAME = "rgb"
        const val INPUT_FILL_RANDOM_SEED = 0x5EED5EEDL

        // Watchdog stage bounds (instrumentation for stall evidence, not gates):
        // init historically < 1 s; a single run < 1 s on CPU/NPU; ORT-FP16 runs
        // are expected slower, hence the larger run/bench bounds.
        const val SOFT_BOUND_INIT_MS = 120_000L
        const val HARD_BOUND_INIT_MS = 600_000L
        const val SOFT_BOUND_RUN_MS = 300_000L
        const val HARD_BOUND_RUN_MS = 1_200_000L
        const val SOFT_BOUND_BENCH_MS = 600_000L
        const val HARD_BOUND_BENCH_MS = 1_800_000L

        val DEFAULT_INPUT_DIMS = listOf(1, 5, 704, 512)
        val DEFAULT_OUTPUT_DIMS = listOf(1, 704, 512, 3)

        // Accepted values for the "gpuPrecision" extra; empty/absent/"default"
        // keeps the accelerator's stock options untouched.
        val KNOWN_GPU_PRECISIONS = setOf("default", "fp16", "fp32", "fp16_fp32accum")

        private val args: Map<ArgumentKey, String> = mapOf(
            ArgumentKey.MODEL to "modelPath",
            ArgumentKey.CPU_MODEL to "cpuModelPath",
            ArgumentKey.ACCELERATED_MODEL to "acceleratedModelPath",
            ArgumentKey.ORT_MODEL to "ortModelPath",
            ArgumentKey.REFERENCE to "referencePath",
            ArgumentKey.INPUT to "inputPath",
            ArgumentKey.ACCELERATOR to "accelerator",
            ArgumentKey.INPUT_NAME to "inputName",
            ArgumentKey.OUTPUT_NAME to "outputName",
            ArgumentKey.ALL_OUTPUT_NAMES to "allOutputNames",
            ArgumentKey.SIGNATURE to "signature",
            ArgumentKey.INPUT_DIMS to "inputDims",
            ArgumentKey.OUTPUT_DIMS to "outputDims",
            ArgumentKey.INPUT_FILL to "inputFill",
            ArgumentKey.DUMP to "dumpOutput",
            ArgumentKey.BENCHMARK to "benchmark",
            ArgumentKey.WARMUP to "warmup",
            ArgumentKey.REPEATS to "repeats",
            ArgumentKey.GPU_PRECISION to "gpuPrecision",
        )
    }
}
