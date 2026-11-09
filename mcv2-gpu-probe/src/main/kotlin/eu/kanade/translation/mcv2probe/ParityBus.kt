package eu.kanade.translation.mcv2probe

/** Cross-component stage channel: the service posts, a resumed activity may observe. */
object ParityBus {
    @Volatile
    var listener: ((String) -> Unit)? = null

    @Volatile
    var lastStage: String = ""

    /** True while a parity run owns the service; guards duplicate starts from OEM relaunches. */
    val runActive = java.util.concurrent.atomic.AtomicBoolean(false)

    fun post(text: String) {
        lastStage = text
        listener?.invoke(text)
    }
}
