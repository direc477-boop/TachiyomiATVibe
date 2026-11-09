package eu.kanade.translation.mcv2probe

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Launcher/controller for the parity harness: mirrors live stage text from
 * ParityBus; the service owns the run, so closing this screen never affects it.
 * Config is handed over via PendingRunConfig (file), never the intent.
 */
class Mcv2GpuParityActivity : Activity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            textSize = 18f
            setPadding(32, 32, 32, 32)
        }
        setContentView(statusView)

        ParityBus.listener = { text -> runOnUiThread { statusView.text = text } }

        val extraKeys = intent.extras?.keySet().orEmpty()
        if (ParityBus.runActive.get()) {
            statusView.text = ParityBus.lastStage
                .ifEmpty { getString(R.string.parity_observe) }
            android.util.Log.i(
                Mcv2ParityRunner.LOG_TAG,
                "ACTIVITY onCreate run_active=observe_only extras_keys=${extraKeys.size}",
            )
            return
        }
        if (extraKeys.isEmpty()) {
            // Restore/relaunch without a run request: drop any stale pending
            // config so the service can never run an old configuration.
            getExternalFilesDir(null)?.let { PendingRunConfig.readAndClear(it) }
            statusView.text = getString(R.string.parity_idle_no_run)
            android.util.Log.i(Mcv2ParityRunner.LOG_TAG, "ACTIVITY onCreate no_extras idle")
            return
        }

        val configMap = mutableMapOf<String, String>()
        for (key in extraKeys) {
            intent.extras?.getString(key)?.let { configMap[key] = it }
        }
        val dir = getExternalFilesDir(null)
        if (dir == null) {
            statusView.text = getString(R.string.parity_storage_missing)
            android.util.Log.e(Mcv2ParityRunner.LOG_TAG, "ACTIVITY onCreate storage_missing")
            return
        }
        PendingRunConfig.write(dir, configMap)
        android.util.Log.i(
            Mcv2ParityRunner.LOG_TAG,
            "ACTIVITY onCreate config_written keys=${configMap.keys}",
        )

        statusView.text = getString(R.string.parity_starting)
        startForegroundService(android.content.Intent(this, Mcv2ParityService::class.java))
    }

    override fun onStart() {
        super.onStart()
        LifecycleTrace.event("activity", "onStart")
    }

    override fun onResume() {
        super.onResume()
        LifecycleTrace.event("activity", "onResume")
    }

    override fun onPause() {
        super.onPause()
        LifecycleTrace.event("activity", "onPause")
    }

    override fun onStop() {
        super.onStop()
        LifecycleTrace.event("activity", "onStop")
    }

    override fun onDestroy() {
        ParityBus.listener = null
        super.onDestroy()
    }
}
