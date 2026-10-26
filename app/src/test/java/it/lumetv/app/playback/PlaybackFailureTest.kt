package it.lumetv.app.playback

import androidx.media3.common.PlaybackException
import it.lumetv.app.data.SourceException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

class PlaybackFailureTest {
    @Test fun `network failures and temporary server responses can retry`() {
        assertTrue(PlaybackFailure.fromSource(IOException("offline")).recoverable)
        for (status in listOf(408, 500, 502, 503, 504)) {
            assertTrue(PlaybackFailure.fromSource(SourceException("temporary", status)).recoverable)
        }
    }

    @Test fun `denied access rate limiting changed APIs and TLS failures remain terminal`() {
        for (status in listOf(0, 400, 401, 403, 404, 429)) {
            val failure = PlaybackFailure.fromSource(SourceException("provider message", status))
            assertFalse(failure.recoverable)
            assertEquals("provider message", failure.message)
        }
        assertFalse(PlaybackFailure.fromSource(SSLHandshakeException("certificate")).recoverable)
        assertFalse(PlaybackFailure.fromSource(IllegalArgumentException("changed response")).recoverable)
    }

    @Test fun `player network failure and expired live window can retry`() {
        for (code in listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)) {
            assertTrue(PlaybackFailure.fromPlayer(PlaybackException("temporary", null, code)).recoverable)
        }
    }

    @Test fun `DRM codec and TLS failures do not trigger automatic reopens`() {
        for (code in listOf(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)) {
            assertFalse(PlaybackFailure.fromPlayer(PlaybackException("unsupported", null, code)).recoverable)
        }
        assertFalse(PlaybackFailure.fromPlayer(PlaybackException("TLS", SSLHandshakeException("certificate"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)).recoverable)
    }
}
