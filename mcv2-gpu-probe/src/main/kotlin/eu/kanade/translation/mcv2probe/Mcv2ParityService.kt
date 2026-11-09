package eu.kanade.translation.mcv2probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Foreground service owning the parity/benchmark run: the run survives activity
 * teardown, stage text mirrors to a persistent notification and ParityBus, and
 * a PARTIAL wakelock with a hard 30-minute lease covers timed sections.
 */
class Mcv2ParityService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiver: android.content.BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerScreenReceiver() {
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        screenReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                LifecycleTrace.event("screen", intent?.action ?: "unknown")
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
        }.onFailure { Log.w(Mcv2ParityRunner.LOG_TAG, "screen receiver registration failed: ${it.message}") }
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // r6.2: config travels via PendingRunConfig, not the intent, so launch
        // paths without extras (task restores, OEM relaunches) can never start
        // a run or lose the requested configuration.
        Log.i(
            Mcv2ParityRunner.LOG_TAG,
            "SERVICE_START startId=$startId intent_null=${intent == null} extras_keys=${intent?.extras?.keySet()?.size ?: -1}",
        )
        val externalDir = getExternalFilesDir(null)
        if (externalDir == null) {
            failFast("storage_missing", "external_files_dir_unavailable")
            return START_NOT_STICKY
        }
        if (!ParityBus.runActive.compareAndSet(false, true)) {
            Log.w(Mcv2ParityRunner.LOG_TAG, "SERVICE_START ignored run_already_active startId=$startId")
            return START_NOT_STICKY
        }
        val configMap = PendingRunConfig.readAndClear(externalDir)
        val config = configMap?.let { Mcv2ParityRunner.HarnessConfig.fromExtras(it, externalDir) }
        val invalid = config?.validate()
        if (config == null || invalid != null) {
            ParityBus.runActive.set(false)
            failFast("input_invalid", invalid ?: "no_pending_config")
            return START_NOT_STICKY
        }

        val preparing = "Preparing: hashing models, loading input + reference…"
        startForeground(NOTIFICATION_ID, buildNotification(preparing))
        ParityBus.post(preparing)
        registerScreenReceiver()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            .apply {
                setReferenceCounted(false)
                // Bounded lease: self-expires even if release() were missed.
                acquire(WAKELOCK_MAX_LEASE_MS)
            }

        Thread({
            try {
                Mcv2ParityRunner(applicationContext, config, ::onStage).run()
            } catch (failure: Throwable) {
                Log.e(Mcv2ParityRunner.LOG_TAG, "PARITY_FAIL stage=exception", failure)
                onStage("FAILED: ${failure.javaClass.simpleName}: ${failure.message} — see logcat (tag ${Mcv2ParityRunner.LOG_TAG}).")
            } finally {
                runCatching { wakeLock?.release() }
                wakeLock = null
                ParityBus.runActive.set(false)
                unregisterScreenReceiver()
            }
            finalizeNotification()
            stopSelf()
        }, "mcv2-parity").start()
        return START_NOT_STICKY
    }

    private fun onStage(text: String) {
        ParityBus.post(text)
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun failFast(stage: String, detail: String) {
        ParityBus.runActive.set(false)
        unregisterScreenReceiver()
        Log.e(Mcv2ParityRunner.LOG_TAG, "PARITY_FAIL stage=$stage detail=$detail")
        val text = "FAILED ($stage): $detail — see logcat (tag ${Mcv2ParityRunner.LOG_TAG})."
        ParityBus.post(text)
        startForeground(NOTIFICATION_ID, buildNotification(text))
        finalizeNotification()
        stopSelf()
    }

    /** Leaves the last status visible as a dismissible notification, then stops. */
    private fun finalizeNotification() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(ParityBus.lastStage, ongoing = false))
        }
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun buildNotification(text: String, ongoing: Boolean = true): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MCV2 probe runs", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("MCV2 probe (parity/benchmark)")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        ParityBus.runActive.set(false)
        unregisterScreenReceiver()
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_ID = "mcv2_parity"
        const val NOTIFICATION_ID = 4711
        const val WAKELOCK_TAG = "mcv2probe:parity-run"
        const val WAKELOCK_MAX_LEASE_MS = 30L * 60L * 1000L
    }
}
