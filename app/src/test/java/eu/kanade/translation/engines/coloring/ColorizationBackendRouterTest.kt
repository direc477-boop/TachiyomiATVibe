package eu.kanade.translation.engines.coloring

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ColorizationBackendRouterTest {

    @Test
    fun `uses primary when it is available`() {
        val primary = FakeBackend("gpu", "MCV2_GPU")
        val fallback = FakeBackend("cpu", "MCV2_CPU")
        val router = ColorizationBackendRouter(primary, fallback)

        router.isAvailable() shouldBe true
        router.currentStamp() shouldBe "MCV2_GPU"
        router.colorize("page", seed = 42L) shouldBe "gpu:page"
        primary.inferenceCalls shouldBe 1
        fallback.availabilityCalls shouldBe 0
    }

    @Test
    fun `uses fallback when primary is unavailable`() {
        val primary = FakeBackend("gpu", "MCV2_GPU", available = false)
        val fallback = FakeBackend("cpu", "MCV2_CPU")
        val router = ColorizationBackendRouter(primary, fallback)

        router.isAvailable() shouldBe true
        router.currentStamp() shouldBe "MCV2_CPU"
        router.colorize("page", seed = 42L) shouldBe "cpu:page"
        primary.availabilityCalls shouldBe 1
        fallback.availabilityCalls shouldBe 1
    }

    @Test
    fun `demotes after primary inference failure and stamps the fallback`() {
        val gpuFailure = IllegalStateException("GPU invoke failed")
        val primary = FakeBackend("gpu", "MCV2_GPU", inferenceFailure = gpuFailure)
        val fallback = FakeBackend("cpu", "MCV2_CPU")
        val router = ColorizationBackendRouter(primary, fallback)

        router.isAvailable() shouldBe true
        router.colorize("page", seed = 42L) shouldBe "cpu:page"
        router.currentStamp() shouldBe "MCV2_CPU"
        primary.closeCalls shouldBe 1
        fallback.inferenceCalls shouldBe 1
    }

    @Test
    fun `uses fallback for unsupported input without latching primary`() {
        val primary = FakeBackend("gpu", "MCV2_GPU", inputSupport = { it != "extreme" })
        val fallback = FakeBackend("cpu", "MCV2_CPU")
        val router = ColorizationBackendRouter(primary, fallback)

        router.isAvailable() shouldBe true
        router.colorize("extreme", seed = 42L) shouldBe "cpu:extreme"
        router.currentStamp() shouldBe "MCV2_CPU"
        router.colorize("normal", seed = 42L) shouldBe "gpu:normal"
        router.currentStamp() shouldBe "MCV2_GPU"
        primary.inferenceCalls shouldBe 1
        primary.closeCalls shouldBe 0
        fallback.inferenceCalls shouldBe 1
    }

    @Test
    fun `uses primary for unsupported input when fallback is unavailable`() {
        val primary = FakeBackend("gpu", "MCV2_GPU", inputSupport = { it != "extreme" })
        val fallback = FakeBackend("cpu", "MCV2_CPU", available = false)
        val router = ColorizationBackendRouter(primary, fallback)

        router.isAvailable() shouldBe true
        router.colorize("extreme", seed = 42L) shouldBe "gpu:extreme"
        router.currentStamp() shouldBe "MCV2_GPU"
        primary.availabilityCalls shouldBe 1
        primary.inferenceCalls shouldBe 1
        fallback.availabilityCalls shouldBe 1
        primary.closeCalls shouldBe 0
    }

    @Test
    fun `close permits a fresh primary attempt`() {
        val primary = FakeBackend("gpu", "MCV2_GPU")
        val fallback = FakeBackend("cpu", "MCV2_CPU")
        val router = ColorizationBackendRouter(primary, fallback)

        router.isAvailable() shouldBe true
        router.close()
        router.currentStamp() shouldBe "MCV2_GPU"
        primary.availabilityCalls shouldBe 2
        primary.closeCalls shouldBe 1
    }

    private class FakeBackend(
        override val name: String,
        private val stamp: String,
        private val available: Boolean = true,
        private val inferenceFailure: Throwable? = null,
        private val inputSupport: (String) -> Boolean = { true },
    ) : ColorizationBackend<String> {
        var availabilityCalls = 0
        var inferenceCalls = 0
        var closeCalls = 0

        override fun supports(input: String): Boolean = inputSupport(input)

        override fun isAvailable(): Boolean {
            availabilityCalls++
            return available
        }

        override fun currentStamp(): String = stamp

        override fun colorize(source: String, seed: Long): String {
            inferenceCalls++
            inferenceFailure?.let { throw it }
            return "$name:$source"
        }

        override fun close() {
            closeCalls++
        }
    }
}
