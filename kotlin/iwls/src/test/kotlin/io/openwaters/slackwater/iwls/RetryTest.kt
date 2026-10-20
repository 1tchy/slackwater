package io.openwaters.slackwater.iwls

import io.openwaters.slackwater.iwls.IwlsFetch.Outcome
import java.io.EOFException
import java.io.IOException
import java.net.*
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*
import kotlinx.serialization.json.*

class RetryTest {
    @Test fun retriesObeyTheSharedContract() {
        for (row in fixture().array("retries").map { it.jsonObject }) {
            val delay = IwlsFetch.retryDelaySeconds(Outcome.Status(row.long("status").toInt()), row.long("attempt").toInt(), row.doubleOrNull("retryAfter"))
            assertEquals(row.doubleOrNull("delay"), delay, row.toString())
        }
    }

    @Test fun retriesWhatAnotherAttemptCouldGetPast() {
        val transient = listOf(SocketTimeoutException(), ConnectException(), NoRouteToHostException(), PortUnreachableException(),
            SocketException("Connection reset"), UnknownHostException(), ProtocolException(), EOFException())
        for (error in transient) assertEquals(2.0, IwlsFetch.retryDelaySeconds(Outcome.Failure(error), 1), error.toString())
        val final = listOf(SSLException("expired"), SSLHandshakeException("bad cert"), CancellationException(), IOException("other"), IllegalStateException())
        for (error in final) assertNull(IwlsFetch.retryDelaySeconds(Outcome.Failure(error), 1), error.toString())
        assertEquals(listOf(2.0, 4.0, 8.0, null), (1..4).map { IwlsFetch.retryDelaySeconds(Outcome.Failure(EOFException()), it) })
    }

    @Test fun ignoresRetryAfterValuesNoClientCanSleepFor() {
        for (header in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -5.0)) {
            assertEquals(60.0, IwlsFetch.retryDelaySeconds(Outcome.Status(429), 1, header))
            assertEquals(2.0, IwlsFetch.retryDelaySeconds(Outcome.Status(503), 1, header))
        }
        assertEquals(600.0, IwlsFetch.retryDelaySeconds(Outcome.Status(429), 1, 3600.0))
    }

    @Test fun readsRetryAfterAsDecimalSecondsOnly() {
        assertEquals(90.0, IwlsFetch.parseRetryAfter("90"))
        assertEquals(100.0, IwlsFetch.parseRetryAfter("1e2"))
        assertEquals(0.5, IwlsFetch.parseRetryAfter(".5"))
        for (header in listOf(null, "", " 5", "nan", "Infinity", "0b1", "0x10", "Wed, 21 Oct 2026 07:28:00 GMT")) {
            assertNull(IwlsFetch.parseRetryAfter(header), header.toString())
        }
    }

    @Test fun clientErrorsArePermanentAndTheRestTransient() {
        assertIs<IwlsException.Permanent>(IwlsFetch.terminalError(404)).also { assertEquals("HTTP 404", it.message) }
        assertIs<IwlsException.Transient>(IwlsFetch.terminalError(429)).also { assertEquals("HTTP 429", it.message) }
        assertIs<IwlsException.Transient>(IwlsFetch.terminalError(503))
        assertIs<IwlsException.Transient>(IwlsFetch.terminalError(302))
    }
}
