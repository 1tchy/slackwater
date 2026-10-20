package io.openwaters.slackwater.iwls

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull

/** One HTTP attempt's result. `body` is the decoded text for a 200, and null otherwise. */
data class IwlsResponse(val status: Int, val body: String?, val retryAfter: String?)

/** One GET per call, with no retries; an application can supply OkHttp or another stack. */
fun interface IwlsTransport {
    suspend fun get(url: URL): IwlsResponse
}

/**
 * The default transport, on the JVM's and Android's built-in HttpURLConnection. Each attempt is
 * bounded at `timeoutMillis`, as Swift's resource timeout bounds it, and cancelling the caller
 * ends the wait at once.
 *
 * HttpURLConnection blocks, and nothing interrupts a blocked read: a disconnect waits on the lock
 * the reader holds. So each attempt runs on its own thread, and the caller stops waiting for it
 * when the bound passes or the caller is cancelled. The abandoned thread stops at its next read
 * and is bounded by the connection's own timeouts.
 */
class HttpUrlConnectionTransport(private val timeoutMillis: Long = 300_000) : IwlsTransport {
    init {
        // HttpURLConnection reads 0 as no timeout and takes the value as an Int.
        require(timeoutMillis in 1..Int.MAX_VALUE) { "timeoutMillis must be between 1 and ${Int.MAX_VALUE}" }
    }

    override suspend fun get(url: URL): IwlsResponse {
        val abandoned = AtomicBoolean(false)
        // Its own flag: awaiting a future cancels it on cancellation, and a cancelled future counts as done.
        val running = AtomicBoolean(true)
        val connection = url.openConnection() as HttpURLConnection
        val attempt = CompletableFuture.supplyAsync({ fetch(connection, abandoned, running) }, threads)
        try {
            // Only this attempt's own bound becomes a timeout; a caller's timeout stays its cancellation.
            return withTimeoutOrNull(timeoutMillis) { attempt.await() }
                ?: throw SocketTimeoutException("IWLS request timed out after $timeoutMillis ms")
        } finally {
            if (running.get()) {
                abandoned.set(true)
                threads.execute { connection.disconnect() }
            }
        }
    }

    private fun fetch(connection: HttpURLConnection, abandoned: AtomicBoolean, running: AtomicBoolean): IwlsResponse {
        try {
            connection.connectTimeout = timeoutMillis.toInt()
            connection.readTimeout = timeoutMillis.toInt()
            val status = connection.responseCode
            // -1 is HttpURLConnection's answer to a reply that is not HTTP: an invalid server response.
            if (status < 0) throw ProtocolException("IWLS sent a response that is not HTTP")
            val retryAfter = connection.getHeaderField("Retry-After")
            val bytes = if (status == 200) readBody(connection.inputStream, abandoned) else null
            // The JDK ends a body cut short of its Content-Length as if it were complete; it is a lost link.
            val length = connection.contentLengthLong
            if (bytes != null && length >= 0 && bytes.size.toLong() != length) {
                throw EOFException("IWLS response ended after ${bytes.size} of $length bytes")
            }
            val body = bytes?.toString(Charsets.UTF_8)
            return IwlsResponse(status, body, retryAfter)
        } finally {
            running.set(false)
            connection.disconnect()
        }
    }

    // Read in pieces so an abandoned attempt stops at its next read instead of draining the response.
    internal fun readBody(stream: InputStream, abandoned: AtomicBoolean): ByteArray {
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            if (abandoned.get()) throw InterruptedIOException("IWLS attempt abandoned")
            val count = stream.read(buffer)
            if (count < 0) return body.toByteArray()
            body.write(buffer, 0, count)
        }
    }

    private companion object {
        // Unbounded, so an attempt the pacer admitted starts now rather than behind blocked I/O
        // elsewhere; daemon threads, so an abandoned attempt never keeps a process alive.
        val threads = Executors.newCachedThreadPool { task ->
            Thread(task, "iwls-http").apply { isDaemon = true }
        }
    }
}
