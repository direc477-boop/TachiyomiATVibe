package eu.kanade.translation.engines.coloring

/** Minimal contract used to select a primary colorizer and its CPU fallback. */
internal interface ColorizationBackend<T> : AutoCloseable {
    val name: String

    fun supports(input: T): Boolean = true

    fun isAvailable(): Boolean

    fun currentStamp(): String?

    fun colorize(source: T, seed: Long): T
}

/** Routes unsupported inputs to fallback and demotes after primary inference failures. */
internal class ColorizationBackendRouter<T>(
    private val primary: ColorizationBackend<T>,
    private val fallback: ColorizationBackend<T>,
    private val onFailure: (backend: String, failure: Throwable) -> Unit = { _, _ -> },
) : AutoCloseable {

    private var selected: ColorizationBackend<T>? = null
    private var lastSuccessful: ColorizationBackend<T>? = null
    private var primaryAttempted = false
    private var primaryAvailable = false
    private var fallbackAttempted = false
    private var fallbackAvailable = false

    @Synchronized
    fun isAvailable(): Boolean = resolveBackend() != null

    @Synchronized
    fun currentStamp(): String? = (lastSuccessful ?: resolveBackend())?.currentStamp()

    @Synchronized
    fun colorize(source: T, seed: Long): T {
        lastSuccessful = null
        val backend = resolveBackendFor(source) ?: error("No colorization backend is available")
        try {
            return backend.colorize(source, seed).also { lastSuccessful = backend }
        } catch (failure: Throwable) {
            if (backend !== primary || failure.isFatal()) throw failure

            onFailure(primary.name, failure)
            selected = null
            primaryAttempted = true
            primaryAvailable = false
            closeQuietly(primary)

            val fallbackBackend = resolveBackend() ?: throw failure
            try {
                return fallbackBackend.colorize(source, seed).also { lastSuccessful = fallbackBackend }
            } catch (fallbackFailure: Throwable) {
                if (fallbackFailure !== failure) fallbackFailure.addSuppressed(failure)
                throw fallbackFailure
            }
        }
    }

    @Synchronized
    override fun close() {
        closeQuietly(primary)
        closeQuietly(fallback)
        selected = null
        lastSuccessful = null
        primaryAttempted = false
        primaryAvailable = false
        fallbackAttempted = false
        fallbackAvailable = false
    }

    private fun resolveBackend(): ColorizationBackend<T>? {
        selected?.let { return it }

        if (ensurePrimaryAvailable()) {
            return primary.also { selected = it }
        }

        if (!ensureFallbackAvailable()) return null
        return fallback.also { selected = it }
    }

    private fun resolveBackendFor(input: T): ColorizationBackend<T>? {
        val backend = resolveBackend() ?: return null
        if (backend !== primary || primarySupports(input)) return backend

        if (ensureFallbackAvailable()) return fallback
        return backend
    }

    private fun primarySupports(input: T): Boolean = try {
        primary.supports(input)
    } catch (failure: Throwable) {
        if (failure.isFatal()) throw failure
        onFailure(primary.name, failure)
        false
    }

    private fun ensurePrimaryAvailable(): Boolean {
        if (primaryAttempted) return primaryAvailable
        primaryAttempted = true
        primaryAvailable = backendAvailable(primary)
        if (!primaryAvailable) closeQuietly(primary)
        return primaryAvailable
    }

    private fun ensureFallbackAvailable(): Boolean {
        if (fallbackAttempted) return fallbackAvailable
        fallbackAttempted = true
        fallbackAvailable = backendAvailable(fallback)
        if (!fallbackAvailable) closeQuietly(fallback)
        return fallbackAvailable
    }

    private fun backendAvailable(backend: ColorizationBackend<T>): Boolean = try {
        backend.isAvailable()
    } catch (failure: Throwable) {
        if (failure.isFatal()) throw failure
        onFailure(backend.name, failure)
        false
    }

    private fun closeQuietly(backend: ColorizationBackend<T>) {
        try {
            backend.close()
        } catch (failure: Throwable) {
            if (failure.isFatal()) throw failure
            onFailure(backend.name, failure)
        }
    }
}
