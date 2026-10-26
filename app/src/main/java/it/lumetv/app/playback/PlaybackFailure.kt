package it.lumetv.app.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import it.lumetv.app.data.SourceException
import java.io.IOException
import javax.net.ssl.SSLException

data class PlaybackFailure(val message: String, val recoverable: Boolean) {
    companion object {
        fun fromSource(error: Exception): PlaybackFailure {
            val recoverable = when (error) {
                is SourceException -> error.status == 408 || error.status in 500..599
                is SSLException -> false
                is IOException -> true
                else -> false
            }
            return PlaybackFailure((error as? SourceException)?.message
                ?: "Non riesco ad aprire la diretta. Controlla la connessione e riprova.", recoverable)
        }

        fun fromPlayer(error: PlaybackException): PlaybackFailure {
            val causes = generateSequence<Throwable>(error) { it.cause }.toList()
            val httpStatus = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
            val recoverable = when {
                causes.any { it is SSLException } -> false
                httpStatus != null -> httpStatus in setOf(401, 403, 404, 408, 410) || httpStatus in 500..599
                else -> error.errorCode in setOf(
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    PlaybackException.ERROR_CODE_TIMEOUT,
                    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
                )
            }
            val message = when {
                httpStatus == 429 -> "Il servizio richiede una pausa. Riprova più tardi."
                httpStatus in setOf(401, 403) -> "L’emittente non autorizza questo flusso dalla rete attuale."
                else -> when (error.errorCode) {
                    PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED -> "L’emittente non ha rilasciato la licenza DRM. Riprova o scegli un altro canale."
                    PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED, PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR -> "La TV non riesce a inizializzare la riproduzione protetta."
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Il formato video di questo canale non è supportato dalla TV."
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Connessione assente. Controlla la rete e riprova."
                    else -> "La diretta si è interrotta. Premi OK per riprovare o Indietro per scegliere un altro canale."
                }
            }
            // A license request may itself fail with HTTP 403 or 500: do not retry DRM errors.
            return PlaybackFailure(message, recoverable && error.errorCode !in 6000..6999)
        }
    }
}
