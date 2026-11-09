package eu.kanade.translation.engines.coloring

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.floats.shouldBeWithinPercentageOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Hard-fail contract and 512×512 NHWC unit math: failures surface typed and never
 * fall back; JVM-tested through the engine seam exactly like the MCV2 NPU route.
 */
class MangaLightNpuColorizationEngineTest {

    private class FakeSession : MangaLightNpuColorizationEngine.Dependencies.Session {
        var closed = false

        override fun run(input: FloatArray): FloatArray =
            FloatArray(MangaLightNpuColorizationEngine.OUTPUT_FLOAT_COUNT)

        override fun close() {
            closed = true
        }
    }

    private class FakeDeps(
        val artifactResult: () -> MangaLightNpuColorizationEngine.Dependencies.Artifact? = { null },
        val precheckResult: String? = null,
        val openSessionResult: (MangaLightNpuColorizationEngine.Dependencies.Artifact) ->
            MangaLightNpuColorizationEngine.Dependencies.Session = { FakeSession() },
    ) {
        var openCalls = 0

        fun toDependencies(): MangaLightNpuColorizationEngine.Dependencies =
            MangaLightNpuColorizationEngine.Dependencies(
                artifact = { artifactResult() },
                precheck = { precheckResult },
                openSession = { artifact ->
                    openCalls += 1
                    openSessionResult(artifact)
                },
            )
    }

    private fun artifact(
        sha256: String = MangaLightNpuColorizationEngine.EXPECTED_ARTIFACT_SHA256,
    ): MangaLightNpuColorizationEngine.Dependencies.Artifact =
        MangaLightNpuColorizationEngine.Dependencies.Artifact(File("unused"), sha256)

    @Test
    fun `missing dispatch libraries surface a typed initialization error`() {
        val reason = "Qualcomm NPU dispatch libraries missing from /data/app: libQnnHtpV75Skel.so"
        val engine = MangaLightNpuColorizationEngine(FakeDeps(precheckResult = reason).toDependencies())

        engine.isAvailable() shouldBe false
        engine.initializationError() shouldContain reason
        engine.currentStamp() shouldBe null
    }

    @Test
    fun `missing artifact surfaces a typed initialization error`() {
        val engine = MangaLightNpuColorizationEngine(FakeDeps().toDependencies())

        engine.isAvailable() shouldBe false
        engine.initializationError() shouldContain "artifact is not present"
    }

    @Test
    fun `wrong artifact hash is refused instead of run`() {
        val engine = MangaLightNpuColorizationEngine(
            FakeDeps(artifactResult = { artifact(sha256 = "not-the-trial-compiled-artifact") }).toDependencies(),
        )

        engine.isAvailable() shouldBe false
        engine.initializationError() shouldContain "hash"
        engine.initializationError() shouldContain MangaLightNpuColorizationEngine.EXPECTED_ARTIFACT_SHA256
    }

    @Test
    fun `native bring-up failure carries the underlying cause`() {
        val engine = MangaLightNpuColorizationEngine(
            FakeDeps(
                artifactResult = { artifact() },
                openSessionResult = { _ -> throw IllegalStateException("DISPATCH_OP unresolved") },
            ).toDependencies(),
        )

        engine.isAvailable() shouldBe false
        engine.initializationError() shouldContain "DISPATCH_OP unresolved"
    }

    @Test
    fun `healthy engine is available and stamps the compiled artifact`() {
        val engine = MangaLightNpuColorizationEngine(FakeDeps(artifactResult = { artifact() }).toDependencies())

        engine.isAvailable() shouldBe true
        engine.initializationError() shouldBe null
        engine.currentStamp() shouldContain MangaLightNpuColorizationEngine.STAMP_PREFIX
        engine.currentStamp() shouldContain "512x512"
        engine.currentStamp() shouldContain MangaLightNpuColorizationEngine.EXPECTED_ARTIFACT_SHA256
    }

    @Test
    fun `colorize on an unavailable engine throws hard with no fallback`() {
        val engine = MangaLightNpuColorizationEngine(FakeDeps(precheckResult = "no libs").toDependencies())
        engine.isAvailable() shouldBe false

        val failure = shouldThrow<IllegalStateException> {
            engine.colorize(mockk(), seed = 1L)
        }
        failure.message shouldContain "no fallback"
        failure.message shouldContain "no libs"
    }

    @Test
    fun `close releases the runtime and a later availability call rebuilds it`() {
        val fake = FakeDeps(artifactResult = { artifact() })
        val engine = MangaLightNpuColorizationEngine(fake.toDependencies())

        engine.isAvailable() shouldBe true
        engine.close()
        engine.currentStamp() shouldBe null
        // close releases the native runtime; the next availability probe rebuilds it.
        engine.isAvailable() shouldBe true
        fake.openCalls shouldBe 2
        engine.initializationError() shouldBe null
    }

    @Test
    fun `dispatch precheck passes when all four libraries are co-located`(@TempDir tempDir: File) {
        MangaLightNpuColorizationEngine.REQUIRED_LIBRARIES.forEach { File(tempDir, it).writeBytes(byteArrayOf(1)) }

        MangaLightNpuColorizationEngine.Dependencies.dispatchPrecheck(tempDir) shouldBe null
    }

    @Test
    fun `dispatch precheck names the missing libraries and the directory`(@TempDir tempDir: File) {
        File(tempDir, MangaLightNpuColorizationEngine.REQUIRED_LIBRARIES.first()).writeBytes(byteArrayOf(1))

        val reason = MangaLightNpuColorizationEngine.Dependencies.dispatchPrecheck(tempDir)

        reason.shouldBeInstanceOf<String>()
        reason shouldContain MangaLightNpuColorizationEngine.REQUIRED_LIBRARIES.last()
        reason shouldContain tempDir.absolutePath
    }

    @Test
    fun `model contract is the 512 square NHWC generator-only fold`() {
        MangaLightNpuColorizationEngine.INPUT_NAME shouldBe "L_bw"
        MangaLightNpuColorizationEngine.OUTPUT_NAME shouldBe "rgb_pred"
        MangaLightNpuColorizationEngine.INPUT_SIZE shouldBe 512
        MangaLightNpuColorizationEngine.OUTPUT_FLOAT_COUNT shouldBe 512 * 512 * 3
        MangaLightNpuColorizationEngine.EXPECTED_ARTIFACT_BYTES shouldBe 115_000_872L
    }

    @Test
    fun `normalization maps luma 0-1 onto model units -1 to 1`() {
        val units = MangaLightNpuColorizationEngine.normalizeLuma01(floatArrayOf(0f, 0.5f, 1f))

        units.size shouldBe 3
        units[0] shouldBe -1f
        units[1] shouldBe 0f
        units[2] shouldBe 1f
    }

    @Test
    fun `denormalization maps model units onto rgb 0-1 and clamps overshoot`() {
        val rgb01 = MangaLightNpuColorizationEngine.denormalizeToRgb01(floatArrayOf(-1f, 0f, 1f, -1.2f, 1.1f))

        rgb01.size shouldBe 5
        rgb01[0] shouldBe 0f
        rgb01[1] shouldBe 0.5f
        rgb01[2] shouldBe 1f
        rgb01[3] shouldBe 0f
        rgb01[4] shouldBe 1f
    }

    @Test
    fun `normalization scale matches the reference gray-127_5-1 contract`() {
        // gray 127.5/255 = 0.5 -> 0.0; gray 200/255 -> 200/127.5 - 1 within float tolerance.
        val units = MangaLightNpuColorizationEngine.normalizeLuma01(floatArrayOf(200f / 255f))

        units[0].shouldBeWithinPercentageOf(200f / 127.5f - 1f, 0.001)
    }
}
