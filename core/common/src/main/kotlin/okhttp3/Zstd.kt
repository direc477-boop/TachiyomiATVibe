package okhttp3

import com.github.luben.zstd.ZstdInputStream
import okio.BufferedSource
import okio.Source
import okio.source

object Zstd : CompressionInterceptor.DecompressionAlgorithm {
    override val encoding: String = "zstd"

    override fun decompress(source: BufferedSource): Source {
        return ZstdInputStream(source.inputStream()).source()
    }
}

