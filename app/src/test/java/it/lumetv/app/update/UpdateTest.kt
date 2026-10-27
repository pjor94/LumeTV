package it.lumetv.app.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class UpdateTest {
    private val bytes = "APK fixture".toByteArray()
    private val update = UpdateInfo("0.4.0-dev", 6, 23, true, bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    private fun release(info: UpdateInfo = update, manifest: Boolean = true): JSONObject {
        fun asset(name: String, size: Long) = JSONObject().put("name", name).put("state", "uploaded")
            .put("size", size).put("browser_download_url", UpdatePolicy.assetUrl(info.versionName, name))
        val assets = JSONArray().put(asset(info.apkName, info.size).put("digest", "sha256:${info.sha256}"))
        if (manifest) assets.put(asset("update.json", 500))
        return JSONObject().put("draft", false).put("prerelease", info.prerelease).put("tag_name", "v${info.versionName}").put("assets", assets)
    }
    private class Store : UpdateStore {
        override var lastSuccess = 0L
        override var lastAttempt = 0L
        override var cached: String? = null
    }
    private inner class Feed(var releases: List<UpdateInfo> = listOf(update)) : UpdateFeed {
        val requests = mutableListOf<String>()
        var fail = false
        var invalidManifest = false
        override suspend fun text(url: String, limit: Int): String {
            requests += url
            if (fail) throw IOException("offline")
            if (url == UpdatePolicy.FEED_URL) return JSONArray(releases.map { release(it) }).toString()
            if (invalidManifest) return "{}"
            return releases.single { UpdatePolicy.assetUrl(it.versionName, "update.json") == url }.json()
        }
    }

    @Test fun `prereleases drafts and legacy releases follow channel selection`() {
        val stable = update.copy(versionName = "1.0.0", prerelease = false)
        val feed = JSONArray().put(release()).put(release(stable)).put(release().put("draft", true))
            .put(release(update.copy(versionName = "0.3.0-dev"), manifest = false)).toString()
        assertEquals(listOf(update.versionName, stable.versionName), UpdateRelease.parseFeed(feed, true).map { it.version })
        assertEquals(listOf(stable.versionName), UpdateRelease.parseFeed(feed, false).map { it.version })
    }

    @Test fun `metadata must match the release asset and repository`() {
        val published = UpdateRelease.parseFeed(JSONArray().put(release()).toString(), true).single()
        assertEquals(update, UpdateInfo.parse(update.json(), published))
        for (bad in listOf(published.copy(version = "0.5.0"), published.copy(prerelease = false),
            published.copy(size = update.size + 1), published.copy(digest = "sha256:" + "0".repeat(64)))) {
            rejects { UpdateInfo.parse(update.json(), bad) }
        }
        val changedUrl = release()
        changedUrl.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.org/update.apk")
        rejects { UpdateRelease.parseFeed(JSONArray().put(changedUrl).toString(), true) }
        rejects { UpdatePolicy.assetUrl("../../evil", "update.json") }
        rejects { UpdatePolicy.assetUrl("0.4.0", "../../file.apk") }
    }

    @Test fun `malformed and unbounded metadata cannot be installed`() {
        val changes = listOf<(JSONObject) -> Unit>(
            { it.put("schema", 2) }, { it.put("applicationId", "another.app") },
            { it.put("versionCode", -1) }, { it.put("versionCode", 6.5) }, { it.put("versionCode", "6") },
            { it.put("versionCode", 2147483648L) }, { it.put("minSdk", 0) }, { it.put("prerelease", "true") },
            { it.getJSONObject("apk").put("size", UpdatePolicy.MAX_APK_SIZE + 1) },
            { it.getJSONObject("apk").put("name", "another.apk") }, { it.getJSONObject("apk").put("sha256", "bad") },
        )
        for (change in changes) rejects { UpdateInfo.parse(JSONObject(update.json()).also(change).toString()) }
    }

    @Test fun `version code prevents equal versions and downgrades and sdk filters compatibility`() {
        assertTrue(update.eligible(5, 23, true))
        assertFalse(update.eligible(6, 36, true))
        assertFalse(update.eligible(7, 36, true))
        assertFalse(update.eligible(5, 22, true))
        assertFalse(update.eligible(5, 36, false))
    }

    @Test fun `checks persist candidates throttle successful calls and allow manual refresh`() = runTest {
        var time = 100_000L
        val store = Store()
        val feed = Feed()
        val repository = UpdateRepository(feed, store, 5, 36) { time }
        List(4) { async { assertEquals(update, repository.refresh(true)) } }.awaitAll()
        assertEquals(2, feed.requests.size)
        assertEquals(update, UpdateRepository(feed, store, 5, 36).available(true))
        time += UpdatePolicy.CHECK_INTERVAL - 1
        repository.refresh(true)
        assertEquals(2, feed.requests.size)
        time++
        repository.refresh(true)
        repository.refresh(true, force = true)
        assertEquals(6, feed.requests.size)
    }

    @Test fun `offline and invalid responses preserve known update and back off`() = runTest {
        var time = 100_000L
        val store = Store()
        val feed = Feed()
        val repository = UpdateRepository(feed, store, 5, 36) { time }
        repository.refresh(true)
        time += UpdatePolicy.CHECK_INTERVAL
        feed.fail = true
        try { repository.refresh(true); fail() } catch (_: IOException) { }
        assertEquals(update, repository.available(true))
        val requests = feed.requests.size
        repository.refresh(true)
        assertEquals(requests, feed.requests.size)
        time += UpdatePolicy.RETRY_INTERVAL
        feed.fail = false; feed.invalidManifest = true
        try { repository.refresh(true); fail() } catch (_: Exception) { }
        assertEquals(update, repository.available(true))
        assertEquals(100_000L, store.lastSuccess)
    }

    @Test fun `highest compatible code wins even when feed order is different`() = runTest {
        val newer = update.copy(versionName = "0.5.0", versionCode = 8)
        val unsupported = newer.copy(versionName = "0.6.0", versionCode = 9, minSdk = 37)
        val feed = Feed(listOf(update, unsupported, newer))
        val repository = UpdateRepository(feed, Store(), 5, 36) { 100_000L }
        assertEquals(newer, repository.refresh(true))
    }

    @Test fun `numbered zero series release updates the installed local build without a preview channel`() = runTest {
        val numbered = update.copy(versionName = "0.3.1", versionCode = 7, prerelease = false)
        val beta = update.copy(versionName = "0.4.0-beta.1", versionCode = 8)
        val repository = UpdateRepository(Feed(listOf(beta, numbered)), Store(), 6, 36) { 100_000L }
        assertEquals(numbered, repository.refresh(includePrereleases = false))
        assertEquals(numbered, repository.available(includePrereleases = false))
    }

    @Test fun `switching channels removes stale candidates and rechecks immediately`() = runTest {
        val store = Store()
        val feed = Feed()
        val repository = UpdateRepository(feed, store, 5, 36) { 100_000L }
        repository.refresh(true)
        assertNull(repository.available(false))
        repository.invalidate()
        assertNull(repository.refresh(false))
        assertEquals(3, feed.requests.size)
        assertNull(store.cached)
    }

    @Test fun `clock corrections do not freeze checks and installed versions invalidate old cache`() = runTest {
        val store = Store().apply { lastSuccess = 200_000L; lastAttempt = 200_000L; cached = update.json() }
        val feed = Feed()
        val repository = UpdateRepository(feed, store, 6, 36) { 100_000L }
        assertNull(repository.available(true))
        assertNull(repository.refresh(true))
        assertEquals(2, feed.requests.size)
    }

    @Test fun `download requires complete bytes and matching checksum and removes failed files`() {
        val directory = java.nio.file.Files.createTempDirectory("lume-update-test").toFile()
        try {
            val file = File(directory, "update.apk")
            val progress = mutableListOf<Int>()
            copyVerifiedApk(bytes.inputStream(), file, update, {}, progress::add)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(100, progress.last())
            for (bad in listOf(bytes.dropLast(1).toByteArray(), bytes + 1, "BAD fixture".toByteArray())) {
                rejects { copyVerifiedApk(bad.inputStream(), file, update, {}, {}) }
                assertFalse(file.exists())
            }
            try { copyVerifiedApk(bytes.inputStream(), file, update, { throw CancellationException() }, {}); fail() }
            catch (_: CancellationException) { assertFalse(file.exists()) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun `apk identity rejects different signers packages debuggable builds and forged metadata`() {
        val installed = ApkIdentity("it.lumetv.app", 5, "0.3.0-dev", false, 23, setOf("trusted certificate"))
        val archive = installed.copy(versionCode = 6, versionName = update.versionName)
        verifyApkIdentity(installed, archive, update, 36)
        for (bad in listOf(archive.copy(applicationId = "evil.app"), archive.copy(versionCode = 5),
            archive.copy(versionCode = 7), archive.copy(versionName = "9.9.9"), archive.copy(debuggable = true),
            archive.copy(minSdk = 35), archive.copy(certificates = setOf("another certificate")), archive.copy(certificates = emptySet()))) {
            rejects { verifyApkIdentity(installed, bad, update, 36) }
        }
        rejects { verifyApkIdentity(installed.copy(certificates = emptySet()), archive, update, 36) }
        rejects { verifyApkIdentity(installed, archive, update, 22) }
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Invalid update should be rejected") }
        catch (_: UpdateException) { }
    }
}
