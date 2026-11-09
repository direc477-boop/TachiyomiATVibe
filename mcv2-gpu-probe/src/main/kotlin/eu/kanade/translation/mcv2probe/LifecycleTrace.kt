package eu.kanade.translation.mcv2probe

import android.util.Log

/**
 * Process-wide lifecycle evidence: every measurement line annotates the most
 * recent lifecycle transition and its age, so repeats affected by the user
 * moving the app foreground/background are identifiable and excludable.
 */
internal object LifecycleTrace {
    private const val MAX_EVENTS = 64

    private val events = ArrayDeque<String>()
    private val lock = Any()

    @Volatile
    private var lastTransitionEpochMs: Long = System.currentTimeMillis()

    @Volatile
    private var lastTransition: String = "process_start"

    fun event(kind: String, detail: String) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            events.addLast("kind=$kind detail=$detail t_epoch_ms=$now")
            if (events.size > MAX_EVENTS) events.removeFirst()
        }
        lastTransitionEpochMs = now
        lastTransition = "$kind:$detail"
        Log.i(Mcv2ParityRunner.LOG_TAG, "LIFECYCLE kind=$kind detail=$detail t_epoch_ms=$now")
    }

    /** Compact annotation for measurement lines: last transition + age in ms. */
    fun snapshot(): String =
        "last_lifecycle=$lastTransition ms_since_transition=${System.currentTimeMillis() - lastTransitionEpochMs}"

    fun recentEvents(): List<String> =
        synchronized(lock) { events.toList() }
}
