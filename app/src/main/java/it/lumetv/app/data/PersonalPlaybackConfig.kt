package it.lumetv.app.data

import android.util.Base64
import it.lumetv.core.DrmScheme
import it.lumetv.core.DrmSpec
import it.lumetv.core.PlaylistEntry
import it.lumetv.core.PlaylistFormatException
import it.lumetv.core.PlaylistHeaders
import it.lumetv.core.ResolvedStream
import it.lumetv.core.PlaylistManifestTypes
import it.lumetv.core.PlaylistCapabilities
import it.lumetv.core.LicenseRequestSpec
import it.lumetv.core.LicenseChallengeEncoding
import it.lumetv.core.LicenseResponseEncoding
import it.lumetv.core.HlsKeySpec
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Translates a bounded, private playlist entry. It never fetches licenses or exposes keys to catalog UI. */
internal object PersonalPlaybackConfig {
    private const val PREFIX = "inputstream.adaptive."
    fun validate(entry:PlaylistEntry,allowHttp:Boolean,settings:PlaylistSettings=PlaylistSettings()):DrmSpec? = configuration(entry,allowHttp,settings,null).drm

    fun parse(entry: PlaylistEntry, allowHttp: Boolean, settings:PlaylistSettings=PlaylistSettings(), resolved:ResolvedStream?=null): ResolvedStream {
        if(entry.localplay!=null && resolved==null)invalid("Il canale richiede il resolver localplay prima della riproduzione.")
        return configuration(entry,allowHttp,settings,resolved)
    }
    private fun configuration(entry:PlaylistEntry,allowHttp:Boolean,settings:PlaylistSettings,base:ResolvedStream?):ResolvedStream {
        val providerManaged=entry.localplay!=null
        val streamUrl=base?.url ?: entry.url
        val streamUri=runCatching {URI(streamUrl)}.getOrNull()
        if((entry.localplay==null || base!=null) && (streamUri==null || streamUri.host.isNullOrBlank() || streamUri.rawUserInfo!=null || streamUri.rawFragment!=null || streamUrl.length>8192 || !PersonalPlaylistNetwork.allowed(streamUri,allowHttp)))
            invalid("L’indirizzo dello stream non è valido oppure richiede il consenso HTTP della lista.")
        val p = entry.properties
        val listed = PlaylistHeaders.merge(entry.headers, query(p[PREFIX + "common_headers"],true), jsonHeaders(p["exthttp"]), query(p["lumetv.pipe_headers"]))
        val common=PlaylistHeaders.merge(base?.headers.orEmpty(),listed)
        val streamOnly = query(p[PREFIX + "stream_headers"],true)
        val manifestBase = if (PREFIX + "manifest_headers" in p) PlaylistHeaders.merge(base?.headers.orEmpty(),base?.manifestHeaders.orEmpty(),listed, query(p[PREFIX + "manifest_headers"],true))
            else PlaylistHeaders.merge(base?.headers.orEmpty(),base?.manifestHeaders.orEmpty(),listed, streamOnly)
        val manifest=PlaylistHeaders.merge(manifestBase,settings.rule(PlaylistResource.MANIFEST).headers)
        val media = PlaylistHeaders.merge(common, streamOnly,settings.rule(PlaylistResource.MEDIA).headers)
        if (!providerManaged && p[PREFIX + "manifest_type"]?.trim()?.lowercase(Locale.ROOT)?.let { it !in setOf("hls","mpd","dash") }==true)
            invalid("Il tipo di manifest richiesto dalla voce non è supportato.")
        if(listOf("inputstream","inputstreamaddon").any { p[it]?.let { name -> name!="inputstream.adaptive" }==true })
            invalid("La voce richiede un componente esterno non disponibile.")
        // A typed provider route supplies its own format and protection. Static list hints
        // may describe an older stream and must not turn unprotected HLS into ClearKey.
        val mime=if(providerManaged)base?.mimeType else p["mimetype"]?.takeIf(String::isNotBlank) ?: entry.mimeType ?: PlaylistManifestTypes.mime(p[PREFIX+"manifest_type"]) ?: PlaylistManifestTypes.mime(p[PREFIX+"file_type"]) ?: base?.mimeType
        val hls=(mime?.contains("mpegurl",true)==true || streamUri?.path.orEmpty().endsWith(".m3u8",true))
        val keyFields=if(providerManaged)null else p[PREFIX+"license_key"]?.split('|')
        val key=if(providerManaged)base?.hlsKey?.let {it.copy(headers=PlaylistHeaders.merge(it.headers,settings.rule(PlaylistResource.KEY).headers))}
        else if(p[PREFIX+"license_type"].isNullOrBlank() && p[PREFIX+"drm"].isNullOrBlank() && p[PREFIX+"drm_legacy"].isNullOrBlank() && hls && keyFields?.size==4 && keyFields.drop(2).all(String::isBlank)) {
            val parameters=keyFields[0].removePrefix("?").removePrefix("&")
            if(parameters.length>8192 || parameters.any { it.isISOControl() || it in "#|" } || "://" in parameters)
                invalid("I parametri per la chiave HLS non sono validi.")
            HlsKeySpec(parameters,PlaylistHeaders.merge(media,query(keyFields[1]),settings.rule(PlaylistResource.KEY).headers))
        } else null
        val providerDrm=base?.drm?.let {it.copy(headers=PlaylistHeaders.merge(it.headers,settings.rule(PlaylistResource.LICENSE).headers))}
        val drm = if(providerManaged)providerDrm else drm(if(key!=null)p-(PREFIX+"license_key") else p, common,streamUrl, allowHttp,settings.rule(PlaylistResource.LICENSE).headers) ?: providerDrm
        if(drm?.scheme==DrmScheme.CLEARKEY && hls)
            invalid("ClearKey personale richiede uno stream DASH con cifratura CENC.")
        return ResolvedStream(streamUrl, mime, media, drm, personal = true, personalAllowHttp = allowHttp, manifestHeaders = manifest,
            seekable=entry.metadata.seekable,hlsKey=key ?: HlsKeySpec(headers=PlaylistHeaders.merge(media,settings.rule(PlaylistResource.KEY).headers)))
    }

    private fun drm(p: Map<String, String>, common: Map<String, String>, streamUrl: String?, allowHttp: Boolean,
                    overrides:Map<String,String>): DrmSpec? {
        val supported=setOf(PREFIX+"license_type",PREFIX+"license_key",PREFIX+"drm_legacy",PREFIX+"drm")
        if(p.keys.any { it in PlaylistCapabilities.unsupportedDrmProperties || ("license" in it || "drm" in it) && it !in supported })
            invalid("La voce richiede proprietà DRM aggiuntive non supportate in questa versione.")
        var type = p[PREFIX + "license_type"].orEmpty()
        var value = p[PREFIX + "license_key"].orEmpty()
        var licenseHeaders = emptyMap<String, String>()
        var request=LicenseRequestSpec()
        p[PREFIX + "drm_legacy"]?.let { raw ->
            val fields = raw.split('|')
            if (fields.size > 3) invalid("La configurazione DRM della voce non è supportata.")
            type = fields[0]; value = fields.getOrElse(1) { "" }; licenseHeaders = query(fields.getOrNull(2))
        }
        p[PREFIX + "drm"]?.let { raw ->
            val root = json(raw)
            if (root.length() != 1) invalid("La selezione tra più sistemi DRM non è supportata.")
            type = root.keys().next()
            val config = root.optJSONObject(type) ?: invalid("La configurazione DRM non è valida.")
            if (config.keys().asSequence().any { it != "license" && it != "priority" }) invalid("La configurazione DRM avanzata non è supportata.")
            val license = config.optJSONObject("license") ?: invalid("Mancano i dati della licenza DRM.")
            if (license.keys().asSequence().any { it !in setOf("server_url", "req_headers", "keyids", "use_http_get_request", "req_params", "req_data", "wrapper", "unwrapper", "unwrapper_params") }) invalid("La configurazione della licenza DRM non è supportata.")
            value = license.optJSONObject("keyids")?.toString() ?: license.optString("server_url")
            value += license.optString("req_params")
            licenseHeaders = when (val h = license.opt("req_headers")) {
                null, JSONObject.NULL -> emptyMap(); is String -> query(h); is JSONObject -> jsonHeaders(h.toString())
                else -> invalid("Gli header della licenza non sono validi.")
            }
            fun operations(name:String,allowed:Set<String>) = license.optString(name).split(',').map(String::trim).filter { it.isNotBlank() && it!="none" }.also { values ->
                if(values.size>3 || values.any { it !in allowed })invalid("La trasformazione della licenza richiesta non è supportata.")
            }
            val wrappers=operations("wrapper",setOf("base64","urlenc"))
            val unwrappers=operations("unwrapper",setOf("base64","json"))
            if(unwrappers.count { it=="json" }>1)invalid("La trasformazione JSON della licenza non è supportata.")
            val params=license.optJSONObject("unwrapper_params")
            if(params?.keys()?.asSequence()?.any { it!="path_data" }==true)invalid("Le opzioni di estrazione della licenza non sono supportate.")
            val path=params?.optString("path_data").orEmpty().split('/').filter(String::isNotBlank)
            if("json" in unwrappers && (path.isEmpty() || path.size>16 || path.any { !Regex("[A-Za-z0-9_.-]{1,100}").matches(it) }))invalid("Il percorso JSON della licenza non è valido.")
            val template=license.optString("req_data").takeIf(String::isNotBlank)?.let { encoded ->
                val decoded=runCatching { Base64.decode(encoded,Base64.DEFAULT).toString(Charsets.UTF_8) }.getOrElse { invalid("I dati della richiesta di licenza non sono validi.") }
                if(decoded.length>32*1024 || Regex("\\{[A-Z][A-Z0-9-]*\\}").findAll(decoded).any { it.value !in setOf("{CHA-RAW}","{CHA-B64}","{CHA-B64U}") })
                    invalid("La richiesta di licenza usa segnaposto non supportati.")
                if("{CHA-RAW}" in decoded && decoded!="{CHA-RAW}")invalid("Il challenge binario deve occupare l’intero corpo della richiesta.")
                decoded
            }
            request=LicenseRequestSpec(method=if(license.optBoolean("use_http_get_request"))"GET" else "POST",responsePath=path,template=template,wrappers=wrappers,unwrappers=unwrappers)
        }
        if (type.isBlank() && value.isBlank()) return null
        if(type.isBlank()) {
            if(PREFIX+"drm" in p || PREFIX+"drm_legacy" in p)invalid("Manca il tipo di sistema DRM nella configurazione dichiarata.")
            val response=try {clearKeys(value.trim())}catch(_:PlaylistFormatException){invalid("Manca il tipo DRM e i dati non sono una configurazione ClearKey valida.")}
            return DrmSpec("",scheme=DrmScheme.CLEARKEY,clearKeyResponse=response,multiSession=true)
        }
        val scheme = when (type.trim().lowercase(Locale.ROOT).removePrefix("urn:uuid:")) {
            "clearkey", "org.w3.clearkey", "e2719d58-a985-b3c9-781a-b030af78d30e" -> DrmScheme.CLEARKEY
            "widevine", "com.widevine.alpha", "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" -> DrmScheme.WIDEVINE
            else -> invalid("Il sistema DRM della voce non è supportato. Sono disponibili ClearKey e Widevine.")
        }
        value=value.trim()
        if (value.isBlank()) invalid("Mancano i dati della licenza DRM; le licenze implicite nel manifest non sono ancora supportate.")
        if (scheme == DrmScheme.CLEARKEY && !value.startsWith("https://", true) && !value.startsWith("http://", true)) {
            return DrmSpec("", scheme = scheme, clearKeyResponse = clearKeys(value), multiSession = true)
        }
        val fields = value.split('|')
        if(fields.size>4)invalid("La configurazione della licenza contiene troppi campi.")
        if(fields.size>=3) {
            val body=fields[2].trim()
            val challenge=when(body) { "", "R{SSM}" -> LicenseChallengeEncoding.RAW; "b{SSM}" -> LicenseChallengeEncoding.BASE64; "B{SSM}" -> LicenseChallengeEncoding.BASE64_URL
                else -> invalid("La trasformazione del challenge della licenza non è supportata.") }
            val response=fields.getOrElse(3){""}.trim()
            val match=Regex("(JB|BJ|J)([A-Za-z0-9_./-]{1,256})").matchEntire(response)
            val encoding=when { response.isBlank() || response=="R" -> LicenseResponseEncoding.RAW; response=="B" -> LicenseResponseEncoding.BASE64
                match?.groupValues?.get(1)=="JB" -> LicenseResponseEncoding.JSON_BASE64; match?.groupValues?.get(1)=="BJ" -> LicenseResponseEncoding.BASE64_JSON
                match?.groupValues?.get(1)=="J" -> LicenseResponseEncoding.JSON; else -> invalid("La trasformazione della risposta di licenza non è supportata.") }
            val path=match?.groupValues?.get(2)?.split('/')?.filter(String::isNotBlank).orEmpty()
            if(path.size>16)invalid("Il percorso della risposta di licenza non è valido.")
            request=LicenseRequestSpec(if(body.isEmpty())"GET" else "POST",challenge,encoding,path)
        }
        val url = fields[0]
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null || url.length > 8192 || !PersonalPlaylistNetwork.allowed(uri, allowHttp))
            invalid("L’indirizzo della licenza richiede HTTPS oppure il consenso HTTP della lista.")
        val sameOrigin = runCatching { streamUrl!=null && origin(URI(streamUrl)) == origin(uri) }.getOrDefault(false)
        val inherited = if (sameOrigin) common else common.filterKeys { it.equals("User-Agent", true) }
        val headers = PlaylistHeaders.merge(inherited, licenseHeaders, query(fields.getOrNull(1)),overrides)
        if(request.method=="GET" && (request.template!=null || request.wrappers.isNotEmpty()))invalid("Una licenza GET non può usare un corpo di richiesta.")
        return DrmSpec(url, headers, scheme, multiSession = true,request=request)
    }

    private fun clearKeys(raw: String): String {
        if (raw.length > 32 * 1024) invalid("La configurazione ClearKey supera il limite previsto.")
        val value = if (raw.startsWith("data:application/json;base64,", true)) {
            try { Base64.decode(raw.substringAfter(','), Base64.DEFAULT).toString(Charsets.UTF_8) }
            catch (_: Exception) { invalid("I dati ClearKey non sono validi.") }
        } else raw
        val keys = linkedMapOf<String, String>()
        fun add(id: ByteArray, key: ByteArray) {
            if (id.size != 16 || key.size != 16) invalid("ClearKey richiede identificativi e chiavi di 128 bit.")
            fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val kid = encode(id); val k = encode(key)
            if (keys[kid]?.let { it != k } == true) invalid("La configurazione contiene chiavi ClearKey in conflitto.")
            keys[kid] = k
            if (keys.size > 32) invalid("La voce contiene più di 32 chiavi ClearKey.")
        }
        if (value.trimStart().startsWith('{')) {
            val root = json(value)
            val array = root.optJSONArray("keys")
            if (array != null) {
                if (array.length() > 32 || root.optString("type", "temporary") != "temporary") invalid("La configurazione ClearKey non è supportata.")
                for (i in 0 until array.length()) {
                    val k = array.optJSONObject(i) ?: invalid("I dati ClearKey non sono validi.")
                    if (k.optString("kty") != "oct") invalid("Il tipo di chiave ClearKey non è supportato.")
                    add(base64(k.optString("kid")), base64(k.optString("k")))
                }
            } else {
                if (root.length() > 32) invalid("La voce contiene più di 32 chiavi ClearKey.")
                root.keys().forEach { id -> add(hex(id), hex(root.opt(id) as? String ?: invalid("I dati ClearKey non sono validi."))) }
            }
        } else {
            val pairs = value.split(',')
            if (pairs.size > 32) invalid("La voce contiene più di 32 chiavi ClearKey.")
            pairs.forEach { pair ->
                val parts = pair.trim().split(':')
                if (parts.size != 2) invalid("La voce ClearKey richiede una coppia identificativo:chiave valida.")
                add(hex(parts[0]), hex(parts[1]))
            }
        }
        if (keys.isEmpty()) invalid("La configurazione ClearKey non contiene chiavi.")
        return JSONObject().put("keys", JSONArray().apply { keys.forEach { (id, key) -> put(JSONObject().put("kty", "oct").put("kid", id).put("k", key)) } })
            .put("type", "temporary").toString()
    }

    private fun hex(value: String): ByteArray {
        val text = value.trim().replace("-", "")
        if (!Regex("[0-9a-fA-F]{32}").matches(text)) invalid("Identificativo o chiave ClearKey non valido: usa 32 caratteri esadecimali.")
        return ByteArray(16) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    private fun base64(value: String): ByteArray = try {
        if (!Regex("[A-Za-z0-9_+/=-]{20,24}").matches(value)) invalid("I dati ClearKey non sono validi.")
        Base64.decode(value.replace('+', '-').replace('/', '_'), Base64.URL_SAFE or Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) { invalid("I dati ClearKey non sono validi.") }
    private fun json(raw: String): JSONObject = try {
        if (raw.length > 32 * 1024) invalid("La configurazione della voce supera il limite previsto.")
        var depth=0;var quoted=false;var escaped=false
        raw.forEach { char ->
            if(escaped)escaped=false else if(quoted && char=='\\')escaped=true else if(char=='\"')quoted=!quoted
            else if(!quoted) { if(char=='{' || char=='[')depth++;if(char=='}' || char==']')depth--;if(depth>16 || depth<0)invalid("La struttura JSON della voce non è supportata.") }
        }
        JSONObject(raw)
    } catch (_: org.json.JSONException) { invalid("La configurazione JSON della voce non è valida.") }
    private fun jsonHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = json(raw)
        if (obj.length() > 32) invalid("La voce contiene troppi header HTTP.")
        return PlaylistHeaders.merge(obj.keys().asSequence().associateWith { obj.opt(it) as? String ?: invalid("Gli header HTTP devono contenere testo.") })
    }
    private fun query(raw: String?,allowUserAgent:Boolean=false): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        if(allowUserAgent && raw.none {it in "=&|"} && Regex("^(?:Mozilla|HbbTV|Dalvik|okhttp|VLC|Lavf|Kodi)/[A-Za-z0-9][ -~]{0,4094}$",RegexOption.IGNORE_CASE).matches(raw.trim()))
            return PlaylistHeaders.merge(mapOf("User-Agent" to raw.trim()))
        if (raw.length > 32 * 1024 || raw.split('&').size > 32) invalid("La voce contiene troppi header HTTP.")
        fun decode(value: String) = try { URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { invalid("La codifica degli header non è valida.") }
        val fields = raw.split('&').associate { part ->
            if ('=' !in part) invalid("Gli header HTTP della voce non sono validi.")
            decode(part.substringBefore('=')) to decode(part.substringAfter('='))
        }
        return PlaylistHeaders.merge(fields)
    }
    private fun origin(uri: URI) = Triple(uri.scheme.lowercase(Locale.ROOT), uri.host.lowercase(Locale.ROOT), if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80)
    private fun invalid(message: String): Nothing = throw PlaylistFormatException(message)
}
