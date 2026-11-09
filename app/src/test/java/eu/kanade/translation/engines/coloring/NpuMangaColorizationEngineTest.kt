package eu.kanade.translation.engines.coloring

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Hard-fail contract: failures surface typed and never fall back; JVM-tested through the engine seam. */
class NpuMangaColorizationEngineTest {

    private class FakeSession : NpuMangaColorizationEngine.Dependencies.Session {
        var closed = false

        override fun run(input: FloatArray): FloatArray = FloatArray(NpuMangaColorizationEngine.OUTPUT_FLOAT_COUNT)

        override fun close() {
            closed = true
        }
    }

    private class FakeDeps(
        val artifactResult: () -> NpuMangaColorizationEngine.Dependencies.Artifact? = { null },
        val precheckResult: String? = null,
        val openSessionResult: (NpuMangaColorizationEngine.Dependencies.Artifact) -> NpuMangaColorizationEngine.Dependencies.Session = { FakeSession() },
    ) {
        var openCalls = 0

        fun toDependencies(): NpuMangaColorizationEngine.Dependencies =
            NpuMangaColorizationEngine.Dependencies(
                artifact = { artifactResult() },
                precheck = { precheckResult },
                openSession = { artifact ->
                    openCalls += 1
                    openSessionResult(artifact)
                },
            )
    }

    private fun artifact(
        sha256: String = NpuMangaColorizationEngine.EXPECTED_ARTIFACT_SHA256,
    ): NpuMangaColorizationEngine.Dependencies.Artifact =
        NpuMangaColorizationEngine.Dependencies.Artifact(File("unused"), sha256)

    @Test
    fun `missing dispatch libraries surface a typed initialization error`() {
        val reason = "Qualcomm NPU dispatch libraries missing from /data/app: libQnnHtpV75Skel.so"
        val engine = NpuMangaColorizationEngine(FakeDeps(precheckResult = reason).toDependencies())

        engine.isAvailable() shouldBe false
        engine.initializationError() shouldContain reason
        engine.currentStamp() shouldBe null
    }

    @Test
    fun `missing artifact surfaces a typed initialization error`() {
        val engine = NpuMangaColorizationEngine(FakeDeps().toDependencies())

        engine.isAvailable() shouldBe false
        engine.initializationError() shouldContain "artifact is not present"
    }

    @Test
    fun `wrong artifact hash is refused instead of run`() {
        val engine = NpuMangaColorizationEngine(
            FakeDeps(artifactResult = { artifact(sha256 = "not-the-device-validated-artifact") }).toDependencies(),
        )

        engine.isAvailable() shouldBe false
        engine.initializationError() shouldContain "hash"
        engine.initializationError() shouldContain NpuMangaColorizationEngine.EXPECTED_ARTIFACT_SHA256
    }

    @Test
    fun `native bring-up failure carries the underlying cause`() {
        val engine = NpuMangaColorizationEngine(
            FakeDeps(
                artifactResult = { artifact() },
                openSessionResult = { _ -> throw IllegalStateException("DISPATCH_OP unresolved") },
            ).toDependencies(),
        )

        engine.isAvailable() shouldBe false
        engine.initializationError() shouldContain "DISPATCH_OP unresolved"
    }

    @Test
    fun `healthy engine is available and stamps the validated artifact`() {
        val engine = NpuMangaColorizationEngine(FakeDeps(artifactResult = { artifact() }).toDependencies())

        engine.isAvailable() shouldBe true
        engine.initializationError() shouldBe null
        engine.currentStamp() shouldContain "NPU"
        engine.currentStamp() shouldContain "W8A8"
        engine.currentStamp() shouldContain "832x576"
        engine.currentStamp() shouldContain NpuMangaColorizationEngine.EXPECTED_ARTIFACT_SHA256
    }

    @Test
    fun `stamp suffix marks the built-in screentone smoothing config on every healthy engine`() {
        val engine = NpuMangaColorizationEngine(FakeDeps(artifactResult = { artifact() }).toDependencies())
        engine.isAvailable() shouldBe true
        engine.currentStamp() shouldContain MangaColorizationPreprocessor.STAMP_SUFFIX
    }

    @Test
    fun `model dimensions match the W8A8 apply_plugin artifact`() {
        NpuMangaColorizationEngine.INPUT_WIDTH shouldBe 576
        NpuMangaColorizationEngine.INPUT_HEIGHT shouldBe 832
        NpuMangaColorizationEngine.OUTPUT_FLOAT_COUNT shouldBe NpuMangaColorizationEngine.INPUT_WIDTH *
            NpuMangaColorizationEngine.INPUT_HEIGHT * 3
    }

    @Test
    fun `colorize on an unavailable engine throws hard with no fallback`() {
        val engine = NpuMangaColorizationEngine(FakeDeps(precheckResult = "no libs").toDependencies())
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
        val engine = NpuMangaColorizationEngine(fake.toDependencies())

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
        NpuMangaColorizationEngine.REQUIRED_LIBRARIES.forEach { File(tempDir, it).writeBytes(byteArrayOf(1)) }

        NpuMangaColorizationEngine.Dependencies.dispatchPrecheck(tempDir) shouldBe null
    }

    @Test
    fun `dispatch precheck names the missing libraries and the directory`(@TempDir tempDir: File) {
        File(tempDir, NpuMangaColorizationEngine.REQUIRED_LIBRARIES.first()).writeBytes(byteArrayOf(1))

        val reason = NpuMangaColorizationEngine.Dependencies.dispatchPrecheck(tempDir)

        reason.shouldBeInstanceOf<String>()
        reason shouldContain NpuMangaColorizationEngine.REQUIRED_LIBRARIES.last()
        reason shouldContain tempDir.absolutePath
    }
}
