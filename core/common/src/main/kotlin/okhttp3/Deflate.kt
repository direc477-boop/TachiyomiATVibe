package okhttp3

import okio.BufferedSource
import okio.InflaterSource
import okio.Source
import java.util.zip.Inflater

object Deflate : CompressionInterceptor.DecompressionAlgorithm {
    override val encoding: String = "deflate"

    override fun decompress(source: BufferedSource): Source {
        return InflaterSource(source, Inflater())
    }
}
