package io.openwaters.slackwater.iwls

import io.openwaters.slackwater.iwls.IwlsFetch.Outcome
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Fetches the contract's inputs for fitting on this device. Every call is cancellable. */
class IwlsClient internal constructor(
    private val transport: IwlsTransport,
    internal val pacer: IwlsPacer,
    private val sleep: suspend (Double) -> Unit,
) {
    constructor(transport: IwlsTransport = HttpUrlConnectionTransport(), pacer: IwlsPacer = sharedPacer) :
        this(transport, pacer, { seconds -> delay(ceil(seconds * 1_000).toLong()) })

    suspend fun stationList(): List<IwlsStation> = IwlsFetch.decodeStations(get(IwlsFetch.stationsUrl))

    suspend fun metadata(stationId: String): IwlsMetadata = IwlsFetch.decodeMetadata(get(IwlsFetch.metadataUrl(stationId)))

    /** One fit-series chunk on the 15-minute grid. */
    suspend fun series(code: String, stationId: String, chunk: ChsChunk): List<ChsSample> =
        IwlsFetch.onGrid(IwlsFetch.decode(get(IwlsFetch.dataUrl(code, stationId, chunk))))

    private suspend fun get(url: URL): String {
        var attempt = 0
        while (true) {
            attempt += 1
            pacer.await()
            var failure: Exception? = null
            val response = try {
                transport.get(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failure that arrives because the caller cancelled is the cancellation, not a retry.
                currentCoroutineContext().ensureActive()
                failure = e
                null
            }
            // Only a 200 supplies a body; decoding errors are not retried.
            if (response != null && response.status == 200) return response.body.orEmpty()
            val outcome = if (response != null) Outcome.Status(response.status) else Outcome.Failure(failure!!)
            val retryAfter = response?.let { IwlsFetch.parseRetryAfter(it.retryAfter) }
            val seconds = IwlsFetch.retryDelaySeconds(outcome, attempt, retryAfter) ?: throw when (outcome) {
                is Outcome.Status -> IwlsFetch.terminalError(outcome.code)
                is Outcome.Failure -> outcome.error
            }
            sleep(seconds)
        }
    }

    companion object {
        /** Shared by every client built with the default pacer, so separate callers cannot double the request rate. */
        val sharedPacer = IwlsPacer()
    }
}
