package it.lumetv.app.data

import it.lumetv.core.CatalogPolicy
import it.lumetv.core.M3uParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CatalogSyncTest {
    private val sample get() = javaClass.classLoader!!.getResource("channels-2026-10-25.m3u")!!.readText()
    private class Store(var content: String? = null) : CatalogStore {
        override var lastSuccess = 0L
        override var lastAttempt = 0L
        var failWrite = false
        override fun read() = content
        override fun write(text: String) { if (failWrite) throw IOException("disk full"); content = text }
    }
    private class Network(var body: String) : Transport {
        var calls = 0
        var fail = false
        override suspend fun get(url: String, headers: Map<String, String>): String {
            assertEquals(CatalogSync.SOURCE_URL, url)
            calls++
            if (fail) throw IOException("offline")
            return body
        }
        override suspend fun post(url: String, data: JSONObject, headers: Map<String, String>) = error("unexpected")
    }

    @Test fun `first launch uses bundle then stores new channel names without changing identity`() = runTest {
        val store = Store()
        val network = Network(sample.replace(",Rai 1\n", ",Rai Uno\n"))
        val sync = CatalogSync(network, store, { sample }, { 100_000L })
        val old = sync.local().first { it.name == "Rai 1" }
        val updated = sync.refresh()!!.first { it.name == "Rai Uno" }
        assertEquals(old.id, updated.id)
        assertEquals(updated, sync.local().first { it.id == old.id })
        assertEquals(100_000L, store.lastSuccess)
    }

    @Test fun `resume and concurrent refreshes fetch once per day but manual refresh works`() = runTest {
        var time = 100_000L
        val store = Store()
        val network = Network(sample)
        val sync = CatalogSync(network, store, { sample }, { time })
        List(4) { async { sync.refresh() } }.awaitAll()
        assertEquals(1, network.calls)
        time += CatalogSync.REFRESH_INTERVAL - 1
        assertNull(sync.refresh())
        time++
        assertNotNull(sync.refresh())
        assertNotNull(sync.refresh(force = true))
        assertEquals(3, network.calls)
    }

    @Test fun `offline and partial responses preserve cache and wait one hour`() = runTest {
        var time = 100_000L
        val store = Store(sample)
        val network = Network("#EXTM3U\n#EXTINF:-1,broken\nhttps://example.org/live.m3u8")
        val sync = CatalogSync(network, store, { error("valid cache expected") }, { time })
        assertFails { sync.refresh() }
        assertEquals(sample, store.content)
        assertEquals(0L, store.lastSuccess)
        assertNull(sync.refresh())
        assertEquals(1, network.calls)
        time += CatalogSync.RETRY_INTERVAL
        network.fail = true
        assertFails { sync.refresh() }
        assertEquals(sample, store.content)
        assertEquals(126, sync.local().size)
    }

    @Test fun `corrupt cache falls back to bundle and requests an update`() = runTest {
        val store = Store("<html>proxy error</html>").apply { lastSuccess = 90_000L }
        val network = Network(sample)
        val sync = CatalogSync(network, store, { sample }, { 100_000L })
        assertEquals(126, sync.local().size)
        assertNotNull(sync.refresh())
        assertEquals(1, network.calls)
    }

    @Test fun `failed disk write never records successful refresh`() = runTest {
        val store = Store(sample).apply { failWrite = true }
        val sync = CatalogSync(Network(sample), store, { sample }, { 100_000L })
        assertFails { sync.refresh() }
        assertEquals(0L, store.lastSuccess)
        assertEquals(sample, store.content)
    }

    @Test fun `truncated response after valid entries cannot replace the cache`() = runTest {
        val store = Store(sample)
        val sync = CatalogSync(Network(sample + "\n#EXTINF:-1,Unfinished\n"), store, { sample }, { 100_000L })
        assertFails { sync.refresh() }
        assertEquals(sample, store.content)
    }

    @Test fun `clock correction does not suppress updates indefinitely`() = runTest {
        val store = Store(sample).apply { lastSuccess = 200_000L; lastAttempt = 200_000L }
        val network = Network(sample)
        assertNotNull(CatalogSync(network, store, { sample }, { 100_000L }).refresh())
    }

    @Test fun `bundle old cache and automatic or manual updates never restore RSI`() = runTest {
        assertEquals(4, M3uParser.parse(sample).count(CatalogPolicy::isExcluded))
        val network = Network(sample)
        val bundled = CatalogSync(network, Store(), { sample }, { 100_000L }).local()
        assertEquals(126, bundled.size)
        assertFalse(bundled.any(CatalogPolicy::isExcluded))

        var time = 100_000L
        val store = Store(sample).apply { lastSuccess = time }
        val sync = CatalogSync(network, store, { error("valid cache expected") }, { time })
        assertEquals(bundled, sync.local())
        assertNull(sync.refresh())
        assertEquals(0, network.calls)

        time += CatalogSync.REFRESH_INTERVAL
        assertEquals(bundled, sync.refresh())
        assertEquals(155, M3uParser.parse(store.content!!).size)
        assertFalse(M3uParser.parse(store.content!!).any(CatalogPolicy::isExcluded))
        assertEquals(bundled, sync.refresh(force = true))
        assertEquals(bundled, sync.local())
        assertEquals(2, network.calls)
    }

    @Test fun `an incomplete excluded entry cannot hide a truncated response`() = runTest {
        val store = Store(sample)
        val response = sample + "\n#EXTINF:-1 tvg-id=\"rsila1\",RSI La1\n"
        val sync = CatalogSync(Network(response), store, { sample }, { 100_000L })
        assertFails { sync.refresh() }
        assertEquals(sample, store.content)
        assertEquals(0L, store.lastSuccess)
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        try { block(); fail("Failure expected") } catch (_: IOException) { }
    }
}
