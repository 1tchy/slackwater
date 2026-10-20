package io.openwaters.slackwater.iwls

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.*

/** One HTTP attempt's result. `body` is the decoded text for a 200, and null otherwise. */
data class IwlsResponse(val status: Int, val body: String?, val retryAfter: String?)

/** One GET per call, with no retries; an application can supply OkHttp or another stack. */
fun interface IwlsTransport {
    suspend fun get(url: URL): IwlsResponse
}

/**
 * The default transport, on the JVM's and Android's built-in HttpURLConnection. Each attempt is
 * bounded at `timeoutMillis`, as Swift's resource timeout bounds it: the connection's own
 * timeouts measure idle time, so a trickling response would otherwise never end.
 */
class HttpUrlConnectionTransport(private val timeoutMillis: Long = 300_000) : IwlsTransport {
    override suspend fun get(url: URL): IwlsResponse = try {
        withTimeout(timeoutMillis) { withContext(Dispatchers.IO) { fetch(url) } }
    } catch (e: TimeoutCancellationException) {
        // A timeout is a transient failure, not the caller's cancellation.
        throw SocketTimeoutException("IWLS request timed out after $timeoutMillis ms")
    }

    private suspend fun fetch(url: URL): IwlsResponse = coroutineScope {
        val connection = url.openConnection() as HttpURLConnection
        // A blocking read cannot see cancellation; disconnecting from another coroutine ends it.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                connection.disconnect()
            }
        }
        try {
            connection.connectTimeout = timeoutMillis.toInt()
            connection.readTimeout = timeoutMillis.toInt()
            val status = connection.responseCode
            val retryAfter = connection.getHeaderField("Retry-After")
            val body = if (status == 200) readBody(connection) else null
            IwlsResponse(status, body, retryAfter)
        } catch (e: IOException) {
            // The disconnect above surfaces as an IOException; report the cancellation that caused it.
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            watcher.cancel()
            connection.disconnect()
        }
    }

    // Disconnecting cannot stop a body read in progress: closing the stream waits on the lock the
    // reader holds. Reading in pieces and checking between them lets the attempt bound end a
    // response that keeps trickling in.
    private suspend fun readBody(connection: HttpURLConnection): String {
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        val stream = connection.inputStream
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = stream.read(buffer)
            if (count < 0) break
            body.write(buffer, 0, count)
        }
        return body.toString("UTF-8")
    }
}
