package io.openwaters.slackwater.iwls

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import kotlin.test.*
import kotlinx.coroutines.*

class TransportTest {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        // Daemon threads, so a handler still sleeping in /stall cannot hold the test JVM open.
        executor = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        createContext("/ok") { it.sendResponseHeaders(200, 2); it.responseBody.use { body -> body.write("[]".toByteArray()) } }
        createContext("/limited") {
            it.responseHeaders.add("Retry-After", "30")
            it.sendResponseHeaders(429, 9)
            it.responseBody.use { body -> body.write("slow down".toByteArray()) }
        }
        createContext("/down") { it.sendResponseHeaders(503, -1); it.close() }
        createContext("/stall") {
            try { Thread.sleep(10_000); it.sendResponseHeaders(200, -1) } catch (e: Exception) { } finally { it.close() }
        }
        createContext("/trickle") {
            try {
                it.sendResponseHeaders(200, 0)
                repeat(200) { _ -> it.responseBody.write('['.code); it.responseBody.flush(); Thread.sleep(50) }
            } catch (e: IOException) { } finally { it.close() }
        }
        start()
    }

    private fun url(path: String) = URL("http://127.0.0.1:${server.address.port}$path")

    @AfterTest fun stop() = server.stop(0)

    @Test fun readsTheBodyOfASuccess() = runBlocking {
        assertEquals(IwlsResponse(200, "[]", null), HttpUrlConnectionTransport().get(url("/ok")))
    }

    @Test fun reportsStatusAndRetryAfterWithoutTheErrorBody() = runBlocking {
        assertEquals(IwlsResponse(429, null, "30"), HttpUrlConnectionTransport().get(url("/limited")))
        assertEquals(IwlsResponse(503, null, null), HttpUrlConnectionTransport().get(url("/down")))
    }

    @Test fun timesOutAStalledResponse() = runBlocking {
        assertFailsWith<SocketTimeoutException> { HttpUrlConnectionTransport(300).get(url("/stall")) }
        Unit
    }

    @Test fun boundsTheWholeAttemptNotJustIdleTime() = runBlocking {
        // A byte every 50 ms never trips a 400 ms read timeout; the attempt bound still ends it.
        val began = System.nanoTime()
        assertFailsWith<SocketTimeoutException> { HttpUrlConnectionTransport(400).get(url("/trickle")) }
        val elapsedMillis = (System.nanoTime() - began) / 1_000_000
        assertTrue(elapsedMillis < 5_000, "took $elapsedMillis ms")
    }

    @Test fun aRefusedConnectionSurfacesAsItsOwnError() = runBlocking {
        val closedPort = java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val error = assertFailsWith<java.net.ConnectException> {
            HttpUrlConnectionTransport(5_000).get(URL("http://127.0.0.1:$closedPort/"))
        }
        assertTrue(IwlsFetch.isTransient(error))
    }

    @Test fun cancellationDisconnectsAnInFlightRequest() = runBlocking {
        val pending = launch(Dispatchers.Default) { HttpUrlConnectionTransport(60_000).get(url("/stall")) }
        delay(300)
        withTimeout(3_000) { pending.cancelAndJoin() }
        assertTrue(pending.isCancelled)
    }
}
