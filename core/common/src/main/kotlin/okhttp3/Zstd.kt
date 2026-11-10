package okhttp3

import okio.BufferedSource
import okio.Source

object Zstd : CompressionInterceptor.DecompressionAlgorithm {
    override val encoding: String = "zstd"

    override fun decompress(source: BufferedSource): Source {
        return source
    }
}
