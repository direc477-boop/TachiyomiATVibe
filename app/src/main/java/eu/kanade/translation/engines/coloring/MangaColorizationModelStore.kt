package eu.kanade.translation.engines.coloring

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Pure asset primitives shared by the MCV2 backends: digesting copy and file digest. */
internal object MangaColorizationModelStore {

    /** Streams [context]'s [assetPath] into [target] while digesting; returns the sha256. */
    fun copyAssetWithDigest(context: Context, assetPath: String, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(assetPath).use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val COPY_BUFFER_SIZE = 8192
}
