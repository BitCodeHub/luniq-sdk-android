package ai.luniq.sdk

/** Hooks the JVM's uncaught exception handler so unhandled crashes emit $error before the app dies. */
internal object ErrorCapture {
    @Volatile private var installed = false

    fun install(emit: (String, Map<String, Any>) -> Unit) {
        if (installed) return
        installed = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                emit("\$error", mapOf(
                    "kind" to "exception",
                    "name" to e::class.java.simpleName,
                    "message" to (e.message ?: ""),
                    "stack" to e.stackTraceToString().take(4000),
                    "thread" to t.name,
                    "fatal" to true,
                ))
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
}
