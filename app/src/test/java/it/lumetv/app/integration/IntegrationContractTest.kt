package it.lumetv.app.integration

import it.lumetv.app.data.Programme
import it.lumetv.core.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IntegrationContractTest {
    private val id = "0123456789abcdef01234567"
    private val certificate = "a".repeat(64)
    private fun channel(id: String = this.id, name: String = "Canale prova") = Channel(id, name, 1, "test", "TV",
        "https://images.example.org/logo.png", "https://secret.invalid/live?token=private", "direct", "secret-key", mapOf("Authorization" to "secret-header"))

    @Test fun acceptsOnlyContractRoutesAndOpaqueIds() {
        assertEquals(LaunchRequest.ChannelId(id), LaunchRequest.parse("lumetv://channel/$id"))
        assertEquals(LaunchRequest.Last, LaunchRequest.parse("lumetv://last"))
        assertEquals(LaunchRequest.Catalog, LaunchRequest.parse("lumetv://catalog"))
        assertEquals(LaunchRequest.Favorites, LaunchRequest.parse("lumetv://favorites"))
        assertEquals(LaunchRequest.Guide, LaunchRequest.parse("lumetv://guide"))
        assertEquals(LaunchRequest.Integration, LaunchRequest.parse("lumetv://integration"))
        listOf(null, "", "https://channel/$id", "lumetv://channel/$id?stream=x", "lumetv://channel/$id#x",
            "lumetv://channel/$id/", "lumetv://channel/%30${id.drop(1)}", "lumetv://user@catalog",
            "lumetv://catalog:443", "lumetv://guide/", "lumetv://channel/../../last", "lumetv://channel/${id.uppercase()}")
            .forEach { assertEquals(it, LaunchRequest.Invalid, LaunchRequest.parse(it)) }
    }

    @Test fun latestExplicitRequestWinsAndIsConsumedOnceAfterReadiness() {
        val queue = LaunchQueue()
        queue.replace(LaunchRequest.Last)
        assertNull(queue.consume(false))
        queue.replace(LaunchRequest.ChannelId(id))
        assertEquals(LaunchRequest.ChannelId(id), queue.consume(true))
        assertNull(queue.consume(true))
        queue.replace(LaunchRequest.Invalid)
        queue.replace(LaunchRequest.Guide)
        assertEquals(LaunchRequest.Guide, queue.consume(true))
    }

    @Test fun identityRejectsWrongPackageSharedUidEmptyPinsAndUntrustedAdditionalSigner() {
        val aura = setOf("it.aura.tv")
        assertTrue(IntegrationIdentity.permitted(aura, setOf(certificate), certificate))
        assertFalse(IntegrationIdentity.permitted(setOf("it.evil.app"), setOf(certificate), certificate))
        assertFalse(IntegrationIdentity.permitted(aura + "it.evil.app", setOf(certificate), certificate))
        assertFalse(IntegrationIdentity.permitted(aura, emptySet(), certificate))
        assertFalse(IntegrationIdentity.permitted(aura, setOf(certificate), ""))
        assertFalse(IntegrationIdentity.permitted(aura, setOf(certificate), "malformed"))
        assertFalse(IntegrationIdentity.permitted(aura, setOf(certificate, "b".repeat(64)), certificate))
        assertTrue(IntegrationIdentity.permitted(aura, setOf(certificate, "b".repeat(64)), "$certificate,${"b".repeat(64)}"))
    }

    @Test fun revokedConsentReturnsNoPersonalDataOrTimestamps() {
        val json = JSONObject(IntegrationContract.snapshot("test", false, listOf(channel()), setOf(id),
            mapOf("test" to listOf(Programme("Private title", 1, 100))), 10, 20, id, 30, 50))
        assertEquals("permission_required", json.getString("status"))
        assertEquals(0, json.getJSONArray("channels").length())
        listOf("lastChannelId", "lastPlayedAt", "catalogUpdatedAt", "guideUpdatedAt").forEach { assertTrue(json.isNull(it)) }
        assertFalse(json.toString().contains("Private title"))
        val capabilities = IntegrationContract.metadata("test", true)
        assertEquals(setOf("protocolVersion", "appVersion", "status", "capabilities"), capabilities.keys().asSequence().toSet())
    }

    @Test fun snapshotExposesOnlyMetadataAndCurrentProgrammeWindow() {
        val json = JSONObject(IntegrationContract.snapshot("test", true, listOf(channel()), setOf(id),
            mapOf("test" to listOf(Programme("Scaduto", 1, 20), Programme("Ora", 20, 80), Programme("Dopo", 80, 120))),
            10, 20, id, 30, 50))
        val row = json.getJSONArray("channels").getJSONObject(0)
        assertEquals(setOf("id", "name", "number", "family", "logo", "favorite", "now", "next"), row.keys().asSequence().toSet())
        assertEquals("Ora", row.getJSONObject("now").getString("title"))
        assertEquals("Dopo", row.getJSONObject("next").getString("title"))
        assertEquals(20L, row.getJSONObject("now").getLong("startMs"))
        assertEquals(80L, row.getJSONObject("now").getLong("endMs"))
        assertEquals(id, json.getString("lastChannelId"))
        assertEquals(30L, json.getLong("lastPlayedAt"))
        assertFalse(json.toString().contains("secret"))
        val later = JSONObject(IntegrationContract.snapshot("test", true, listOf(channel()), emptySet(),
            mapOf("test" to listOf(Programme("Scaduto", 1, 20))), null, null, "f".repeat(24), 30, 50))
        assertTrue(later.getJSONArray("channels").getJSONObject(0).isNull("now"))
        assertTrue(later.isNull("lastChannelId"))
        assertTrue(later.isNull("lastPlayedAt"))
    }

    @Test fun payloadBoundsIncludeUtf8AndPrioritizeFavoritesAndLast() {
        val channels = (1..800).map { channel("%024x".format(it), "視".repeat(200)) }
        val target = channels.last().id
        val programmes = mapOf("test" to listOf(Programme("😀".repeat(300), 1, 1000), Programme("語".repeat(300), 1000, 2000)))
        val text = IntegrationContract.snapshot("test", true, channels, setOf(target), programmes, 1, 1, target, 1, 100)
        val json = JSONObject(text)
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= IntegrationContract.MAX_BYTES)
        assertTrue(json.getJSONArray("channels").length() <= 500)
        assertEquals(target, json.getJSONArray("channels").getJSONObject(0).getString("id"))
        assertEquals(target, json.getString("lastChannelId"))
    }

    @Test fun logoNeverExportsCredentialsQueryLocalAddressesOrUnsupportedSchemes() {
        assertEquals("https://images.example.org/logo.png", IntegrationContract.safeLogo("https://images.example.org/logo.png"))
        listOf("http://images.example.org/a", "https://user:secret@example.org/a", "https://example.org/a?token=secret",
            "https://example.org/a#secret", "content://private/a", "file:///secret", "https://127.0.0.1/a",
            "https://192.168.1.1/a", "https://[::1]/a", "https://nas.local/a", "https://host.localhost/a", "https://example.org:444/a")
            .forEach { assertNull(it, IntegrationContract.safeLogo(it)) }
    }

    @Test fun normalizesFeedTextAndInvalidNumbersForTheStrictConsumer() {
        val channels = listOf(
            channel(name = "  Canale\nprova\tTV\u0000  ").copy(number = -1),
            channel("%024x".format(2), "   \n\t\u007f  "),
            channel("%024x".format(3), "x" + "😀".repeat(150)).copy(number = 1_000_000),
            channel("%024x".format(4), "Nome valido").copy(number = 999_999),
        )
        val json = JSONObject(IntegrationContract.snapshot("0.3.0-dev", true, channels, emptySet(),
            mapOf("test" to listOf(Programme("  Ora\ncon\ttitolo\u0085  ", 1, 100),
                Programme("\u0000\t\n", 100, 200), Programme("Prossimo", 200, 300))), -1, -1, id, -1, 50))
        val rows = json.getJSONArray("channels")
        assertEquals(3, rows.length())
        val first = rows.getJSONObject(0)
        assertEquals("Canale prova TV", first.getString("name"))
        assertTrue(first.isNull("number"))
        assertEquals("Ora con titolo", first.getJSONObject("now").getString("title"))
        assertEquals("Prossimo", first.getJSONObject("next").getString("title"))
        assertTrue(rows.getJSONObject(1).isNull("number"))
        assertEquals(999_999, rows.getJSONObject(2).getInt("number"))
        listOf("catalogUpdatedAt", "guideUpdatedAt", "lastPlayedAt").forEach { assertTrue(json.isNull(it)) }
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val name = row.getString("name")
            assertTrue(name.length <= 160 && name.isNotBlank() && name.none(Char::isISOControl))
            assertFalse(name.last().isHighSurrogate())
        }
    }

    @Test fun stressSnapshotKeepsAllFieldsWithinConsumerContract() {
        val channels = (1..800).map { index ->
            channel("%024x".format(index), "\u0000\n" + "語😀\t".repeat(200))
                .copy(number = if (index % 2 == 0) Int.MIN_VALUE else Int.MAX_VALUE)
        }
        val jsonText = IntegrationContract.snapshot("0.3.0-dev", true, channels, emptySet(),
            mapOf("test" to listOf(Programme("\n\t" + "語😀\t".repeat(300), 1, 100))), null, null, null, null, 50)
        val json = JSONObject(jsonText)
        assertTrue(jsonText.toByteArray(Charsets.UTF_8).size <= IntegrationContract.MAX_BYTES)
        val rows = json.getJSONArray("channels")
        assertTrue(rows.length() in 1..500)
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            for ((field, maximum) in mapOf("name" to 160, "family" to 100)) {
                val value = row.getString(field)
                assertTrue(value.isNotBlank() && value.length <= maximum && value.none(Char::isISOControl))
            }
            assertTrue(row.isNull("number"))
            val programme = row.getJSONObject("now")
            val title = programme.getString("title")
            assertTrue(title.isNotBlank() && title.length <= 300 && title.none(Char::isISOControl))
            assertFalse(title.last().isHighSurrogate())
            assertTrue(programme.getLong("startMs") >= 0 && programme.getLong("endMs") > programme.getLong("startMs"))
        }
    }
}
