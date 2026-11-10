package okhttp3

import okio.BufferedSource
import okio.GzipSource
import okio.Source

object Gzip : CompressionInterceptor.DecompressionAlgorithm {
    override val encoding: String = "gzip"

    override fun decompress(source: BufferedSource): Source {
        return GzipSource(source)
    }
}
