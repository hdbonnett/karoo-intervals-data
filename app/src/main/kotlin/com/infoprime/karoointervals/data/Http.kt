package com.infoprime.karoointervals.data

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

data class HttpResult(val status: Int, val body: String?, val error: String?) {
    val ok: Boolean get() = status in 200..299 && body != null
}

interface HttpTransport {
    suspend fun get(url: String, headers: Map<String, String>): HttpResult
}

/** Plain HTTPS from this app. Works on the emulator and on the Karoo when it has Wi-Fi. */
class DirectTransport : HttpTransport {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResult = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                HttpResult(status, stream?.bufferedReader()?.use { it.readText() }, null)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            HttpResult(0, null, e.message ?: e.javaClass.simpleName)
        }
    }
}

/**
 * Requests made through Karoo OS, which uses Wi-Fi or the Companion app's Bluetooth link to the phone.
 * Karoo limits each response body to 100 KB, so requests are kept small.
 */
class KarooTransport(private val karoo: KarooSystemService) : HttpTransport {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResult =
        withTimeoutOrNull(45_000) {
            suspendCancellableCoroutine { cont ->
                val consumerId = karoo.addConsumer<OnHttpResponse>(
                    OnHttpResponse.MakeHttpRequest("GET", url, headers, waitForConnection = false),
                    onError = { msg -> if (cont.isActive) cont.resume(HttpResult(0, null, msg)) },
                ) { event ->
                    val state = event.state
                    if (state is HttpResponseState.Complete && cont.isActive) {
                        cont.resume(HttpResult(state.statusCode, state.body?.decodeToString(), state.error))
                    }
                }
                cont.invokeOnCancellation { karoo.removeConsumer(consumerId) }
            }
        } ?: HttpResult(0, null, "Timed out waiting for Karoo network")
}

/** Uses Karoo OS networking when connected to it, falling back to direct HTTPS. */
class SmartTransport(private val karooProvider: () -> KarooSystemService?) : HttpTransport {
    private val direct = DirectTransport()

    override suspend fun get(url: String, headers: Map<String, String>): HttpResult {
        val karoo = karooProvider()?.takeIf { it.connected }
        if (karoo != null) {
            val result = KarooTransport(karoo).get(url, headers)
            if (result.status != 0) return result
            Log.w(TAG, "Karoo network failed (${result.error}), trying direct")
        }
        return direct.get(url, headers)
    }
}

const val TAG = "KarooIntervals"
