package okhttp3

import okhttp3.ResponseBody.Companion.asResponseBody
import okio.BufferedSource
import okio.Source
import okio.buffer

open class CompressionInterceptor(
    vararg val algorithms: DecompressionAlgorithm = arrayOf(Gzip),
) : Interceptor {

    interface DecompressionAlgorithm {
        val encoding: String
        fun decompress(source: BufferedSource): Source
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val userRequest = chain.request()
        val requestBuilder = userRequest.newBuilder()

        if (algorithms.isNotEmpty() && userRequest.header("Accept-Encoding") == null && userRequest.header("Range") == null) {
            val acceptEncoding = algorithms.joinToString(", ") { it.encoding }
            requestBuilder.header("Accept-Encoding", acceptEncoding)
        }

        val response = chain.proceed(requestBuilder.build())

        if (!response.promisesBody()) {
            return response
        }

        val responseBody = response.body ?: return response
        val encoding = response.header("Content-Encoding") ?: return response

        val algorithm = algorithms.firstOrNull { it.encoding.equals(encoding, ignoreCase = true) }
            ?: return response

        val decompressedSource = algorithm.decompress(responseBody.source()).buffer()
        val strippedHeaders = response.headers.newBuilder()
            .removeAll("Content-Encoding")
            .removeAll("Content-Length")
            .build()

        return response.newBuilder()
            .headers(strippedHeaders)
            .body(decompressedSource.asResponseBody(responseBody.contentType(), -1L))
            .build()
    }

    private fun Response.promisesBody(): Boolean {
        if (request.method == "HEAD") return false
        val responseCode = code
        if ((responseCode < 100 || responseCode >= 200) && responseCode != 204 && responseCode != 304) {
            return true
        }
        return false
    }
}
