package it.lumetv.app.integration

import it.lumetv.app.data.Programme
import it.lumetv.app.data.ProgrammeWindow
import it.lumetv.core.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

sealed interface LaunchRequest {
    data class ChannelId(val id: String) : LaunchRequest
    data object Last : LaunchRequest
    data object Catalog : LaunchRequest
    data object Favorites : LaunchRequest
    data object Guide : LaunchRequest
    data object Integration : LaunchRequest
    data object Invalid : LaunchRequest

    companion object {
        fun parse(value: String?): LaunchRequest = try {
            val uri = URI(value.orEmpty())
            if (value == null || value.length > 128 || uri.scheme != "lumetv" || uri.rawUserInfo != null ||
                uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null) Invalid
            else if (uri.host == "channel" && Regex("/[a-f0-9]{24}").matches(uri.rawPath.orEmpty())) ChannelId(uri.rawPath.drop(1))
            else if (uri.rawPath.orEmpty().isNotEmpty()) Invalid
            else when (uri.host) {
                "last" -> Last; "catalog" -> Catalog; "favorites" -> Favorites; "guide" -> Guide
                "integration" -> Integration; else -> Invalid
            }
        } catch (_: Exception) { Invalid }
    }
}

/** Explicit requests replace pending requests and are consumed only after the local catalog and player are ready. */
class LaunchQueue {
    var pending: LaunchRequest? = null
        private set
    fun replace(request: LaunchRequest) { pending = request }
    fun consume(ready: Boolean): LaunchRequest? = if (!ready) null else pending.also { pending = null }
}

internal object IntegrationIdentity {
    const val AURA_PACKAGE = "it.aura.tv"
    fun permitted(packages: Set<String>, currentCertificates: Set<String>, configured: String): Boolean {
        val pins = configured.split(',').map(String::trim).filter(String::isNotBlank).toSet()
        // Every current signer must be explicitly configured. A trusted historical signer is insufficient.
        return packages == setOf(AURA_PACKAGE) && pins.isNotEmpty() &&
            pins.all { Regex("[a-f0-9]{64}").matches(it) } && currentCertificates.isNotEmpty() &&
            currentCertificates.all { it in pins }
    }
}

internal object IntegrationContract {
    const val AUTHORITY = "it.lumetv.app.integration"
    const val MAX_BYTES = 512 * 1024
    const val MAX_CHANNELS = 500
    val capabilities = listOf("open_channel", "open_last", "open_catalog", "open_favorites", "open_guide", "integration_settings")

    fun metadata(version: String, allowed: Boolean) = JSONObject()
        .put("protocolVersion", 1).put("appVersion", version)
        .put("status", if (allowed) "ready" else "permission_required")
        .put("capabilities", JSONArray(capabilities))

    fun snapshot(version: String, allowed: Boolean, channels: List<Channel>, favoriteIds: Set<String>,
                 programmes: Map<String, List<Programme>>, catalogUpdatedAt: Long?, guideUpdatedAt: Long?,
                 lastChannelId: String?, lastPlayedAt: Long?, now: Long): String {
        val json = metadata(version, allowed)
            .put("catalogUpdatedAt", if (allowed) catalogUpdatedAt?.takeIf { it >= 0 } ?: JSONObject.NULL else JSONObject.NULL)
            .put("guideUpdatedAt", if (allowed) guideUpdatedAt?.takeIf { it >= 0 } ?: JSONObject.NULL else JSONObject.NULL)
        val rows = JSONArray()
        json.put("channels", rows).put("lastChannelId", JSONObject.NULL).put("lastPlayedAt", JSONObject.NULL)
        if (!allowed) return json.toString()
        val selected = channels.distinctBy { it.id }.filter { Regex("[a-f0-9]{24}").matches(it.id) }
            .mapNotNull { channel -> displayText(channel.name, 160)?.let { channel to it } }
            .sortedByDescending { (channel, _) -> channel.id == lastChannelId || channel.id in favoriteIds }.take(MAX_CHANNELS)
        var bytes = json.toString().toByteArray(Charsets.UTF_8).size + 128
        val included = mutableSetOf<String>()
        selected.forEach { (channel, name) ->
            val cleanProgrammes = programmes[channel.epgId].orEmpty().mapNotNull { programme ->
                displayText(programme.title, 300)?.let { programme.copy(title = it) }
            }
            val window = ProgrammeWindow.at(cleanProgrammes, now)
            val item = JSONObject().put("id", channel.id).put("name", name)
                .put("number", channel.number.takeIf { it in 0..999_999 } ?: JSONObject.NULL)
                .put("family", displayText(channel.family, 100) ?: JSONObject.NULL)
                .put("logo", safeLogo(channel.logo) ?: JSONObject.NULL).put("favorite", channel.id in favoriteIds)
                .put("now", programme(window.current)).put("next", programme(window.next))
            val size = item.toString().toByteArray(Charsets.UTF_8).size + 1
            if (bytes + size <= MAX_BYTES) { rows.put(item); bytes += size; included += channel.id }
        }
        if (lastChannelId in included) {
            json.put("lastChannelId", lastChannelId).put("lastPlayedAt", lastPlayedAt?.takeIf { it >= 0 } ?: JSONObject.NULL)
        }
        return json.toString()
    }

    private fun programme(value: Programme?): Any = value?.let {
        JSONObject().put("title", it.title).put("startMs", it.start).put("endMs", it.end)
    } ?: JSONObject.NULL

    /** The wire contract uses bounded, nonempty single-line text; malformed feed text must not reject the whole snapshot. */
    private fun displayText(value: String, maximum: Int): String? {
        val cleaned = value.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(maximum)
        // String limits are UTF-16 units on both Android clients. Never split a supplementary code point.
        return cleaned.dropLast(if (cleaned.lastOrNull()?.isHighSurrogate() == true) 1 else 0).trim().takeIf { it.isNotBlank() }
    }

    fun safeLogo(value: String): String? = try {
        val uri = URI(value)
        val host = uri.host.orEmpty().lowercase()
        value.takeIf { it.length <= 2048 && uri.scheme == "https" && host.contains('.') &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port == 443) && !host.endsWith(".local") &&
            !host.endsWith(".localhost") && !host.matches(Regex("[0-9.]+")) && ':' !in host }
    } catch (_: Exception) { null }
}
