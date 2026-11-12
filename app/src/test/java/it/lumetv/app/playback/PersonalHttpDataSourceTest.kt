package it.lumetv.app.playback

import android.net.Uri
import androidx.media3.datasource.DataSpec
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PersonalHttpDataSourceTest {
    @Test fun `a custom agent reaches the wire exactly once`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("sample"))
            val source=PersonalMediaSources.http(OkHttpClient(),mapOf("User-Agent" to "Provider Player","X-Token" to "private")).createDataSource()
            try { source.open(DataSpec(Uri.parse(server.url("/stream").toString()))) } finally { source.close() }
            val request=server.takeRequest()
            assertEquals(listOf("Provider Player"),request.headers.values("User-Agent"))
            assertEquals("private",request.getHeader("X-Token"))
        }
    }
}
