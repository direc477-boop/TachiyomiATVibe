package eu.kanade.translation.mcv2probe

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Process-independent config hand-off: the activity persists the am-start extras
 * here and the service consumes them, so extras-less launches (task restores,
 * OEM relaunches) can never start a run with lost or stale configuration.
 */
internal object PendingRunConfig {
    const val FILE_NAME = "pending_config.json"

    fun file(dir: File): File = File(dir, FILE_NAME)

    fun write(dir: File, extras: Map<String, String>) {
        val json = extras.entries.joinToString(",", "{", "}") { (key, value) ->
            "${JSONObject.quote(key)}:${JSONObject.quote(value)}"
        }
        file(dir).writeText(json)
    }

    /** Consumes the pending config: read + delete. Null when absent or unparsable. */
    fun readAndClear(dir: File): Map<String, String>? {
        val file = file(dir)
        if (!file.isFile) return null
        val parsed = runCatching {
            val json = JSONObject(file.readText())
            val map = mutableMapOf<String, String>()
            for (key in json.keys()) map[key] = json.optString(key)
            map
        }.onFailure {
            Log.w(Mcv2ParityRunner.LOG_TAG, "PENDING_CONFIG unparsable: ${it.message}")
        }.getOrNull()
        file.delete()
        return parsed
    }
}
