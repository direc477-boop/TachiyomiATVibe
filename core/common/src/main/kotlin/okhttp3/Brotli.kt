package okhttp3

import okio.BufferedSource
import okio.Source
import okio.source
import org.brotli.dec.BrotliInputStream

object Brotli : CompressionInterceptor.DecompressionAlgorithm {
    override val encoding: String = "br"

    override fun decompress(source: BufferedSource): Source {
        return BrotliInputStream(source.inputStream()).source()
    }
}
