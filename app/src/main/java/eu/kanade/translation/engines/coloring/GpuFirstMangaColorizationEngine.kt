package eu.kanade.translation.engines.coloring

import android.content.Context
import android.graphics.Bitmap
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** Selects the MCV2 LiteRT GPU graph first and retries pages on ONNX CPU after GPU failure. */
class GpuFirstMangaColorizationEngine(context: Context) : PageColorizationEngine {

    private val router = ColorizationBackendRouter(
        primary = LiteRtMangaColorizationBackend(context),
        fallback = PageColorizationBackend(OnnxMangaColorizationEngine(context)),
    ) { backend, failure ->
        logcat(LogPriority.WARN, failure) { "[Mcv2Color] $backend failed or unavailable" }
    }

    override fun isAvailable(): Boolean = router.isAvailable()

    override fun currentStamp(): String? = router.currentStamp()

    override fun colorize(source: Bitmap, seed: Long): Bitmap = router.colorize(source, seed)

    override fun close() = router.close()

    private class PageColorizationBackend(
        private val engine: PageColorizationEngine,
    ) : ColorizationBackend<Bitmap> {
        override val name: String = "ONNX CPU"

        override fun isAvailable(): Boolean = engine.isAvailable()

        override fun currentStamp(): String? = engine.currentStamp()

        override fun colorize(source: Bitmap, seed: Long): Bitmap = engine.colorize(source, seed)

        override fun close() = engine.close()
    }
}
