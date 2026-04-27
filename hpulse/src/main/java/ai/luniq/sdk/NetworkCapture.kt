package ai.luniq.sdk

/**
 * Network capture as an OkHttp Interceptor. Apps add this to their existing
 * OkHttpClient builder if they want every request emitted as a $network_call event.
 *
 *     val client = OkHttpClient.Builder()
 *         .addInterceptor(LuniqInterceptor())
 *         .build()
 *
 * For apps using HttpURLConnection or other clients, manual track() calls are required.
 */
internal object NetworkCapture {
    private var pulseEndpoint: String = ""
    private var emit: ((String, Map<String, Any>) -> Unit)? = null

    fun install(endpoint: String, emit: (String, Map<String, Any>) -> Unit) {
        this.pulseEndpoint = endpoint
        this.emit = emit
    }

    fun emitCall(host: String, path: String, method: String, status: Int, durationMs: Long, error: String?) {
        if (host.isBlank()) return
        if (pulseEndpoint.isNotBlank() && (pulseEndpoint.contains(host))) return
        emit?.invoke("\$network_call", mapOf(
            "host" to host,
            "path" to path,
            "method" to method,
            "status" to status,
            "duration_ms" to durationMs.toInt(),
            "error" to (error ?: ""),
        ))
    }
}

// Public interceptor users wire into their OkHttpClient.
class LuniqInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val req = chain.request()
        val t0 = System.currentTimeMillis()
        return try {
            val resp = chain.proceed(req)
            NetworkCapture.emitCall(
                host = req.url.host, path = req.url.encodedPath,
                method = req.method, status = resp.code,
                durationMs = System.currentTimeMillis() - t0, error = null,
            )
            resp
        } catch (e: Exception) {
            NetworkCapture.emitCall(
                host = req.url.host, path = req.url.encodedPath,
                method = req.method, status = 0,
                durationMs = System.currentTimeMillis() - t0, error = e.message,
            )
            throw e
        }
    }
}
