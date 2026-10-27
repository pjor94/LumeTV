package it.lumetv.app.update

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal class UpdateException(message: String) : IOException(message)

/** Only release assets in the project's repository can become download URLs. */
internal object UpdatePolicy {
    const val REPOSITORY = "pjor94/LumeTV"
    const val FEED_URL = "https://api.github.com/repos/$REPOSITORY/releases?per_page=100"
    const val MAX_APK_SIZE = 128 * 1024 * 1024L
    const val CHECK_INTERVAL = 6 * 60 * 60 * 1000L
    const val RETRY_INTERVAL = 60 * 60 * 1000L
    private val version = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?")

    fun validVersion(name: String) = name.length <= 100 && version.matches(name)
    fun assetUrl(version: String, name: String): String {
        if (!validVersion(version) || name !in setOf("update.json", "LumeTV-$version.apk")) invalid()
        return "https://github.com/$REPOSITORY/releases/download/v$version/$name"
    }
    fun recent(saved: Long, now: Long, interval: Long) = saved > 0 && now >= saved && now - saved < interval
    fun invalid(): Nothing = throw UpdateException("I dati dell’aggiornamento non sono validi. Riprova più tardi.")
}

internal data class UpdateRelease(val version: String, val prerelease: Boolean, val size: Long, val digest: String?) {
    val manifestUrl get() = UpdatePolicy.assetUrl(version, "update.json")

    companion object {
        fun parseFeed(text: String, includePrereleases: Boolean): List<UpdateRelease> {
            if (text.length > 2 * 1024 * 1024) UpdatePolicy.invalid()
            val array = JSONArray(text)
            return (0 until array.length()).mapNotNull { index ->
                val release = array.getJSONObject(index)
                if (release.optBoolean("draft", true)) return@mapNotNull null
                val prerelease = release.getBoolean("prerelease")
                if (prerelease && !includePrereleases) return@mapNotNull null
                val tag = release.getString("tag_name")
                val version = tag.removePrefix("v")
                if (tag != "v$version" || !UpdatePolicy.validVersion(version)) return@mapNotNull null
                val assets = release.getJSONArray("assets")
                fun asset(name: String): JSONObject? = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .singleOrNull { it.optString("name") == name && it.optString("state") == "uploaded" }
                // Releases predating the updater do not have machine-readable metadata.
                val manifest = asset("update.json") ?: return@mapNotNull null
                val apk = asset("LumeTV-$version.apk") ?: return@mapNotNull null
                if (manifest.getString("browser_download_url") != UpdatePolicy.assetUrl(version, "update.json") ||
                    apk.getString("browser_download_url") != UpdatePolicy.assetUrl(version, "LumeTV-$version.apk")) UpdatePolicy.invalid()
                val size = apk.positiveInteger("size", UpdatePolicy.MAX_APK_SIZE)
                UpdateRelease(version, prerelease, size, apk.optString("digest").takeIf { it.isNotEmpty() })
            }
        }
    }
}

internal data class UpdateInfo(
    val versionName: String, val versionCode: Int, val minSdk: Int, val prerelease: Boolean,
    val size: Long, val sha256: String,
) {
    val apkName get() = "LumeTV-$versionName.apk"
    val apkUrl get() = UpdatePolicy.assetUrl(versionName, apkName)
    fun eligible(installedCode: Int, sdk: Int, includePrereleases: Boolean) =
        versionCode > installedCode && minSdk <= sdk && (includePrereleases || !prerelease)

    fun json(): String = JSONObject().put("schema", 1).put("applicationId", "it.lumetv.app")
        .put("versionName", versionName).put("versionCode", versionCode).put("minSdk", minSdk)
        .put("prerelease", prerelease).put("apk", JSONObject().put("name", apkName).put("size", size).put("sha256", sha256)).toString()

    companion object {
        fun parse(text: String, release: UpdateRelease? = null): UpdateInfo {
            if (text.length > 16 * 1024) UpdatePolicy.invalid()
            val json = JSONObject(text)
            if (json.positiveInteger("schema", 1) != 1L || json.getString("applicationId") != "it.lumetv.app") UpdatePolicy.invalid()
            val version = json.getString("versionName")
            if (!UpdatePolicy.validVersion(version)) UpdatePolicy.invalid()
            val apk = json.getJSONObject("apk")
            val info = UpdateInfo(version, json.positiveInteger("versionCode", Int.MAX_VALUE.toLong()).toInt(),
                json.positiveInteger("minSdk", 1000).toInt(), json.get("prerelease") as? Boolean ?: UpdatePolicy.invalid(),
                apk.positiveInteger("size", UpdatePolicy.MAX_APK_SIZE), apk.getString("sha256").lowercase())
            if (apk.getString("name") != info.apkName || !Regex("[a-f0-9]{64}").matches(info.sha256)) UpdatePolicy.invalid()
            if (release != null && (release.version != info.versionName || release.prerelease != info.prerelease ||
                    release.size != info.size || release.digest?.let { it != "sha256:${info.sha256}" } == true)) UpdatePolicy.invalid()
            return info
        }
    }
}

private fun JSONObject.positiveInteger(key: String, maximum: Long): Long {
    val value = get(key)
    if (value !is Int && value !is Long) UpdatePolicy.invalid()
    return (value as Number).toLong().also { if (it !in 1..maximum) UpdatePolicy.invalid() }
}

internal data class ApkIdentity(val applicationId: String, val versionCode: Long, val versionName: String?,
    val debuggable: Boolean, val minSdk: Int?, val certificates: Set<String>)

internal fun verifyApkIdentity(installed: ApkIdentity, archive: ApkIdentity, info: UpdateInfo, sdk: Int) {
    if (archive.applicationId != installed.applicationId || archive.versionCode != info.versionCode.toLong() ||
        archive.versionCode <= installed.versionCode || archive.versionName != info.versionName || info.minSdk > sdk ||
        archive.debuggable || archive.minSdk?.let { it != info.minSdk } == true) UpdatePolicy.invalid()
    if (installed.certificates.isEmpty() || archive.certificates != installed.certificates) {
        throw UpdateException("La firma dell’aggiornamento è diversa da quella installata. Le build di sviluppo o personalizzate non possono ricevere le APK ufficiali.")
    }
}
