package io.openwaters.slackwater.iwls

import java.net.URL

/** One HTTP attempt's result. `body` is the decoded text for a 200, and null otherwise. */
data class IwlsResponse(val status: Int, val body: String?, val retryAfter: String?)

/** One GET per call, with no retries; an application can supply OkHttp or another stack. */
fun interface IwlsTransport {
    suspend fun get(url: URL): IwlsResponse
}

class HttpUrlConnectionTransport(private val timeoutMillis: Long = 300_000) : IwlsTransport {
    override suspend fun get(url: URL): IwlsResponse = throw UnsupportedOperationException("HttpUrlConnectionTransport arrives in Task 8")
}
