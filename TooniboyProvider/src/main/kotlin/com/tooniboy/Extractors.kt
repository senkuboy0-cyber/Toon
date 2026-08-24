package com.tooniboy

import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ─────────────────────────────────────────────────────────────
// ★ Default: VidStreamX → as-cdn26.top  (AWSStream pattern)
// ─────────────────────────────────────────────────────────────
class Zephyrflick : AWSStream() {
    override val name = "Zephyrflick"
    override val mainUrl = "https://as-cdn26.top"
    override val requiresReferer = true
}

open class AWSStream : ExtractorApi() {
    override val name = "AWSStream"
    override val mainUrl = "https://z.awstream.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extractedHash = url.substringAfterLast("/")
        val doc = app.get(url).document
        val m3u8Url = "$mainUrl/player/index.php?data=$extractedHash&do=getVideo"
        val header = mapOf("x-requested-with" to "XMLHttpRequest")
        val formdata = mapOf("hash" to extractedHash, "r" to mainUrl)

        val response = app.post(m3u8Url, headers = header, data = formdata).parsedSafe<Response>()
        response?.videoSource?.let { m3u8 ->
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = ""
                    this.quality = Qualities.P1080.value
                }
            )

            val extractedPack = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().orEmpty()
            JsUnpacker(extractedPack).unpack()?.let { unpacked ->
                Regex("\"kind\"\\s*:\\s*\"captions\"\\s*,\\s*\"file\"\\s*:\\s*\"(https.*?\\.srt)\"")
                    .find(unpacked)
                    ?.groupValues
                    ?.get(1)
                    ?.let { subtitleUrl ->
                        subtitleCallback.invoke(
                            SubtitleFile(
                                "English",
                                subtitleUrl
                            )
                        )
                    }
            }
        }
    }

    data class Response(
        val hls: Boolean,
        val videoImage: String,
        val videoSource: String,
        val securedLink: String,
        val downloadLinks: List<Any?>,
        val attachmentLinks: List<Any?>,
        val ck: String,
    )
}

// ─────────────────────────────────────────────────────────────
// Key 0: SHORT → abyssplayer.com  (enc-dec.app decrypt)
// ─────────────────────────────────────────────────────────────
class Abyss : ExtractorApi() {
    override var name = "Abyss"
    override var mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Origin" to "https://playhydrax.com",
            "Referer" to "https://playhydrax.com/"
        )

        val document = app.get(url, headers = headers).document
        val scripts = document.select("script").joinToString("\n") { it.data() }

        val encrypted = Regex("const\\s+datas\\s*=\\s*\"([^\"]*)\"")
            .find(scripts)
            ?.groupValues
            ?.getOrNull(1)
            ?: return

        val decrypted = app.post(
            url = "https://enc-dec.app/api/dec-abyss",
            headers = headers,
            requestBody = """
        {
            "text": "$encrypted"
        }
    """.trimIndent().toRequestBody(
                "application/json".toMediaType()
            )
        ).parsedSafe<AbyssResponse>()?.result ?: return

        decrypted.sources
            .filter { it.status }
            .forEach { source ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name [${source.codec.uppercase()}]",
                        url = source.url,
                        type = INFER_TYPE
                    ) {
                        this.quality = getQualityFromName(source.type)
                        this.headers = mapOf(
                            "Referer" to "https://playhydrax.com/"
                        )
                    }
                )
            }
    }

    data class AbyssResponse(
        val status: Long,
        val result: Result,
    )

    data class Result(
        val sources: List<AbyssSource>,
    )

    data class AbyssSource(
        val url: String,
        val size: Long,
        val type: String,
        val codec: String,
        val status: Boolean,
    )
}

// ─────────────────────────────────────────────────────────────
// Key 1: RUBY → rubystm.com  (/dl POST + JS unpack)
// ─────────────────────────────────────────────────────────────
class StreamRuby : ExtractorApi() {
    override var name = "StreamRuby"
    override var mainUrl = "https://rubystm.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileCode = url.substringAfterLast("/e/").substringBefore(".html")
        if (fileCode.isBlank()) return

        app.get("$mainUrl/e/$fileCode.html", referer = referer ?: mainUrl)

        val html = app.post(
            url = "$mainUrl/dl",
            data = mapOf(
                "op" to "embed",
                "file_code" to fileCode,
                "auto" to "1",
                "referer" to (referer ?: "")
            ),
            referer = "$mainUrl/e/$fileCode.html"
        ).text

        val packed = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]+?'\|'\)\)""")
            .find(html)?.value ?: return
        val unpacked = JsUnpacker(packed).unpack() ?: return

        val m3u8 = Regex("""file\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""")
            .find(unpacked)?.groupValues?.get(1) ?: return

        Regex("""file\s*:\s*"(https?://[^"]+_([a-z]{2,3})\.vtt[^"]*)""[\s\S]+?kind\s*:\s*"captions"""")
            .findAll(unpacked).forEach { match ->
                subtitleCallback(SubtitleFile(match.groupValues[2], match.groupValues[1]))
            }

        callback(
            newExtractorLink(source = name, name = name, url = m3u8, type = ExtractorLinkType.M3U8) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Key 2: CLOUDY + GDMirrorbot sub-hosts (upns family)
// Dynamic IV derivation from hostname (upns players ignore
// the static IVs used by generic Vidstack extractors)
// ─────────────────────────────────────────────────────────────
class Cloudy : UpnsPlayer() {
    override var name = "Cloudy"
    override var mainUrl = "https://cloudy.upns.one"
}

open class UpnsPlayer : ExtractorApi() {
    override var name = "Upns"
    override var mainUrl = "https://upns.one"
    override val requiresReferer = true

    companion object {
        private const val AES_KEY = "kiemtienmua911ca"
        private val STATIC_IVS = listOf("1234567890oiuytr", "0123456789abcdef")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to USER_AGENT)
        val baseurl = getBaseUrl(url)

        // id from "#fragment" (e.g. cloudy.upns.one/#tye61y) or last path segment
        val hash = url.substringAfterLast("#").substringBefore("&").substringBefore("?")
            .ifBlank { url.trimEnd('/').substringAfterLast('/') }
        if (hash.isBlank()) return

        val refHost = try {
            URI(referer ?: mainUrl).host ?: mainUrl.removePrefix("https://")
        } catch (e: Exception) {
            mainUrl.removePrefix("https://")
        }

        val apiUrl = "$baseurl/api/v1/video?id=$hash&w=1280&h=720&r=$refHost"

        val encoded = try {
            app.get(apiUrl, headers = headers, referer = referer ?: "$baseurl/").text.trim()
        } catch (e: Exception) {
            Log.e(name, "API failed: ${e.message}")
            return
        }
        if (encoded.isBlank()) return

        // Try dynamic (hostname-derived) IV first, then known static IVs
        val decryptedJson = decryptHex(encoded, AES_KEY.toByteArray(), deriveIv(baseurl))
            ?: STATIC_IVS.firstNotNullOfOrNull { decryptHex(encoded, AES_KEY.toByteArray(), it.toByteArray()) }
            ?: run {
                Log.e(name, "AES decrypt failed for all IVs")
                return
            }

        val obj = try {
            JSONObject(decryptedJson)
        } catch (e: Exception) {
            Log.e(name, "JSON parse failed: ${e.message}")
            return
        }

        // Prefer CDN/native field, then standard ones
        var m3u8 = ""
        for (field in listOf("cfNative", "source", "cf", "hls")) {
            val v = obj.optString(field)
            if (v.isNotBlank()) {
                m3u8 = v.replace("\\/", "/")
                break
            }
        }

        // Fallbacks
        if (m3u8.isEmpty()) {
            for (field in listOf("mp4", "stream", "direct")) {
                val v = obj.optString(field)
                if (v.isNotBlank()) {
                    m3u8 = v.replace("\\/", "/")
                    break
                }
            }
        }

        if (m3u8.isEmpty()) {
            val sources = obj.optJSONArray("sources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val f = sources.optJSONObject(i)?.optString("file").orEmpty()
                    if (f.isNotBlank()) {
                        m3u8 = f.replace("\\/", "/")
                        break
                    }
                }
            }
        }

        if (m3u8.isEmpty()) return

        callback(
            newExtractorLink(name, name, url = m3u8, type = ExtractorLinkType.M3U8) {
                this.referer = "$baseurl/"
                this.quality = Qualities.Unknown.value
            }
        )

        // Subtitles: {"subtitle":{"English":"/sub/en.vtt"}}
        val subtitleSection = Regex("\"subtitle\"\\s*:\\s*\\{(.*?)\\}").find(decryptedJson)?.groupValues?.get(1)
        subtitleSection?.let { section ->
            Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").findAll(section).forEach { match ->
                val lang = match.groupValues[1]
                val rawPath = match.groupValues[2].split("#").firstOrNull().orEmpty()
                if (rawPath.isNotEmpty()) {
                    val subUrl = if (rawPath.startsWith("http")) rawPath else "$baseurl${rawPath.replace("\\/", "/")}"
                    subtitleCallback(SubtitleFile(lang, subUrl))
                }
            }
        }
    }

    /**
     * Replicates the upns player JS: IV is generated from constants plus the
     * first character of the hosting hostname.
     */
    private fun deriveIv(pageUrl: String): ByteArray {
        val e = "https:"
        val r = "$e//"
        val hostname = try {
            URI(pageUrl).host.orEmpty()
        } catch (e: Exception) {
            ""
        }
        val g = e.length * r.length          // 42
        val f = 1
        var b = ""
        for (se in f until 10) {
            b += (se + g).toChar()           // chars 43..51
        }
        val ye = 3 * (hostname.firstOrNull()?.code ?: 0)
        val ve = 111 * f + e.length          // 117
        val p = ve + 4                       // 121
        val x = e.getOrNull(f)?.code ?: 0    // 't' = 116
        val me = x * f - 2                   // 114
        val suffix = String(intArrayOf(g, "111".toInt(), ye, ve, p, x, me), 0, 7)
        val full = b + suffix
        return full.toByteArray(Charsets.UTF_8).sliceArray(0 until 16)
    }

    private fun decryptHex(hex: String, keyBytes: ByteArray, ivBytes: ByteArray): String? {
        return try {
            val clean = hex.trim().removeSurrounding("\"")
            val data = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            String(cipher.doFinal(data))
        } catch (e: Exception) {
            null
        }
    }

    protected fun getBaseUrl(url: String): String =
        try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            mainUrl
        }
}

// ─────────────────────────────────────────────────────────────
// Keys 3-4: SD/HD → gdmirrorbot.nl  (embedhelper sid API)
// Key 5: FHD → fgdmirrorbot.nl
// ─────────────────────────────────────────────────────────────
open class GDMirrorbot : ExtractorApi() {
    override var name = "GDMirrorbot"
    override var mainUrl = "https://gdmirrorbot.nl"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Simple embed: /embed/{sid}
        val sid = url.substringAfterLast("embed/")
        var sidValue = sid

        // Advanced embed with ?key=: resolve fileslug through the API
        if (url.contains("key=")) {
            try {
                var pageText = app.get(url).text
                val finalId = Regex("""FinalID\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
                val myKey = Regex("""myKey\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
                val idType = Regex("""idType\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1) ?: "imdbid"

                if (finalId != null && myKey != null) {
                    val apiUrl = if (url.contains("/tv/")) {
                        val season = Regex("""/tv/\d+/(\d+)/""").find(url)?.groupValues?.get(1) ?: "1"
                        val episode = Regex("""/tv/\d+/\d+/(\d+)""").find(url)?.groupValues?.get(1) ?: "1"
                        "$mainUrl/myseriesapi?tmdbid=$finalId&season=$season&epname=$episode&key=$myKey"
                    } else {
                        "$mainUrl/mymovieapi?$idType=$finalId&key=$myKey"
                    }
                    pageText = app.get(apiUrl).text

                    val embedData = tryParseJson<GDEmbedData>(pageText)
                    sidValue = embedData?.data?.firstOrNull()?.fileslug
                        ?.takeIf { it.isNotBlank() } ?: sid
                }
            } catch (e: Exception) {
                Log.e(name, "key resolution failed: ${e.message}")
            }
        }

        // Host from final redirect (handles fgdmirrorbot etc.)
        val host = try {
            val resp = app.get(url)
            URI(resp.url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            getHost(url)
        }

        val responseText = try {
            app.post("$host/embedhelper.php", data = mapOf("sid" to sidValue)).text
        } catch (e: Exception) {
            Log.e(name, "embedhelper failed: ${e.message}")
            return
        }

        val root = tryParseJson<GDEmbedHelper>(responseText) ?: run {
            Log.e(name, "embedhelper unparsable")
            return
        }
        val siteUrls = root.siteUrls ?: return
        val siteFriendlyNames = root.siteFriendlyNames

        // mresult arrives either as a JSON object or a base64-encoded string
        val rawMresult = root.mresult
        val mresult: Map<String, String> = when (rawMresult) {
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") (rawMresult as Map<String, String>)
            is String -> try {
                val decoded = base64Decode(rawMresult)
                val jo = JsonParser.parseString(decoded).asJsonObject
                jo.keySet().associateWith { jo[it]?.asString.orEmpty() }
            } catch (e: Exception) {
                Log.e(name, "mresult decode failed: ${e.message}")
                return
            }
            else -> {
                Log.e(name, "mresult missing")
                return
            }
        }

        siteUrls.keys.intersect(mresult.keys).forEach { key ->
            val base = siteUrls[key]?.trimEnd('/') ?: return@forEach
            val path = mresult[key]?.trimStart('/') ?: return@forEach
            val fullUrl = "$base/$path"
            val friendlyName = siteFriendlyNames?.get(key) ?: key

            try {
                when {
                    friendlyName.equals("RpmShare", true) ||
                        friendlyName.equals("UpnShare", true) ||
                        friendlyName.equals("StreamP2p", true) ||
                        base.contains("upns.") || base.contains("strp2p.")
                        -> UpnsPlayer().apply {
                            this.name = friendlyName
                            this.mainUrl = getHost(fullUrl)
                        }.getUrl(fullUrl, referer, subtitleCallback, callback)

                    else -> loadExtractor(fullUrl, referer ?: mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e(name, "Failed $friendlyName at $fullUrl: ${e.message}")
            }
        }
    }

    protected fun getHost(url: String): String =
        try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            mainUrl
        }

    data class GDEmbedData(val data: List<GDFileSlug>? = null)
    data class GDFileSlug(val fileslug: String? = null)
    data class GDEmbedHelper(
        val siteUrls: Map<String, String>? = null,
        val siteFriendlyNames: Map<String, String>? = null,
        val mresult: Any? = null,
    )
}

class GDMirrorbotFHD : GDMirrorbot() {
    override var name = "GDMirrorbotFHD"
    override var mainUrl = "https://fgdmirrorbot.nl"
}

// ─────────────────────────────────────────────────────────────
// Key 6: TURBO → emturbovid.com  (data-hash attribute)
// ─────────────────────────────────────────────────────────────
class EmTurboVid : ExtractorApi() {
    override var name = "EmTurboVid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer ?: mainUrl).document

        // Primary: data-hash attribute holding the m3u8
        var m3u8 = doc.selectFirst("#video_player[data-hash]")
            ?.attr("data-hash")
            ?.takeIf { it.contains(".m3u8") }
            ?: doc.selectFirst("[data-hash]")?.attr("data-hash")?.takeIf { it.contains(".m3u8") }

        // Fallback: var urlPlay = '...' script (official Emturbovid pattern)
        if (m3u8 == null) {
            m3u8 = doc.select("script")
                .firstOrNull { it.data().contains("var urlPlay") }
                ?.data()
                ?.substringAfter("var urlPlay = '", "")
                ?.substringBefore("'")
                ?.takeIf { it.startsWith("http") }
        }

        val finalUrl = m3u8 ?: return

        callback(
            newExtractorLink(name, name, url = finalUrl, type = ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.P1080.value
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Key 7: VIDMOLY → vidmoly.net  (jwplayer file regex)
// ─────────────────────────────────────────────────────────────
class VidMolyNet : ExtractorApi() {
    override var name = "VidMolyNet"
    override var mainUrl = "https://vidmoly.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val txt = app.get(url, referer = referer ?: mainUrl).text

        val m3u8 = Regex("""file\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""")
            .find(txt)?.groupValues?.get(1)
            ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(txt)?.value
            ?: return

        // English VTT subtitle if present
        Regex("""file\s*:\s*['"](https[^'"]+\.vtt[^'"]*)['"][\s\S]{0,200}?label\s*:\s*['"]([^'"]*)['"]""")
            .find(txt)?.let { match ->
                subtitleCallback(SubtitleFile(match.groupValues[2].ifBlank { "English" }, match.groupValues[1]))
            }

        callback(
            newExtractorLink(name, name, url = m3u8, type = ExtractorLinkType.M3U8) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Key 8: MULTIQ → blakiteapi.xyz  (API → rumble CDN tar-HLS)
// ─────────────────────────────────────────────────────────────
class Blakite : ExtractorApi() {
    override var name = "Blakite"
    override var mainUrl = "https://blakiteapi.xyz"
    override val requiresReferer = false

    companion object {
        private const val CDN_BASE = "https://hugh.cdn.rumble.cloud/video/"
        private val QUALITY_CODES = listOf("oaa", "baa", "caa", "gaa", "haa")
        private val QUALITY_LABELS = listOf("240p", "360p", "480p", "720p", "1080p")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // URL format: https://blakiteapi.xyz/embed/{tmdbId}/{uniqueId}
        val path = url.substringAfter("$mainUrl/embed/").trimEnd('/')

        val tmdbId: String
        val uniqueId: String?

        if (path.contains("/")) {
            tmdbId = path.substringBefore("/")
            uniqueId = path.substringAfter("/")
        } else {
            tmdbId = path
            uniqueId = null
        }

        val apiUrl = if (uniqueId != null) {
            "$mainUrl/api/get.php?id=$uniqueId&tmdbId=$tmdbId"
        } else {
            "$mainUrl/api/get.php?tmdbId=$tmdbId"
        }

        val json = try {
            // API rejects requests without an embedded-player referer
            app.get(
                apiUrl,
                headers = mapOf(
                    "Referer" to url,
                    "Accept" to "application/json",
                    "User-Agent" to USER_AGENT,
                )
            ).parsedSafe<BlakiteResponse>()
        } catch (e: Exception) {
            Log.e(name, "API failed: ${e.message}")
            return
        }

        val data = json?.takeIf { it.success }?.data ?: run {
            Log.e(name, "API returned no data (access denied or missing content)")
            return
        }
        val dataId = data.dataId ?: return

        if (data.format.equals("M3U8", ignoreCase = true)) {
            // Parse ranges: "39667200-39681123 (240p)\n726598656-726612902 (1080p)"
            val rangeMap = mutableMapOf<String, String>()
            data.ranges?.split("\n")?.forEach { line ->
                val m = Regex("""(\d+-\d+)\s*\(([^)]+)\)""").find(line.trim())
                if (m != null) {
                    rangeMap[m.groupValues[2].trim()] = m.groupValues[1]
                }
            }

            var emitted = false
            for (i in QUALITY_LABELS.indices) {
                val label = QUALITY_LABELS[i]
                val code = QUALITY_CODES[i]
                val range = rangeMap[label] ?: continue

                val streamUrl = CDN_BASE +
                    "$dataId.$code.tar?r_file=chunklist.m3u8&r_type=application%2Fvnd.apple.mpegurl&r_range=$range"

                callback(
                    newExtractorLink(name, "$name [$label]", streamUrl, ExtractorLinkType.M3U8) {
                        this.referer = ""
                        this.quality = getQualityFromName(label)
                    }
                )
                emitted = true
            }

            if (!emitted) {
                val qid = (data.qid ?: QUALITY_LABELS.size).coerceIn(1, QUALITY_LABELS.size)
                for (i in 0 until qid) {
                    val label = QUALITY_LABELS[i]
                    val code = QUALITY_CODES[i]
                    val streamUrl = CDN_BASE + "$dataId.$code.tar?r_file=chunklist.m3u8&r_type=application%2Fvnd.apple.mpegurl"
                    callback(
                        newExtractorLink(name, "$name [$label]", streamUrl, ExtractorLinkType.M3U8) {
                            this.referer = ""
                            this.quality = getQualityFromName(label)
                        }
                    )
                }
            }
        } else {
            val qid = (data.qid ?: 1).coerceIn(1, QUALITY_LABELS.size)
            for (i in 0 until qid) {
                val label = QUALITY_LABELS[i]
                val code = QUALITY_CODES[i]
                val streamUrl = "$CDN_BASE$dataId.$code.mp4"
                callback(
                    newExtractorLink(name, "$name [$label]", streamUrl, INFER_TYPE) {
                        this.referer = ""
                        this.quality = getQualityFromName(label)
                    }
                )
            }
        }
    }

    data class BlakiteResponse(
        val success: Boolean = false,
        val data: BlakiteData? = null,
    )

    data class BlakiteData(
        val animeTitle: String? = null,
        val tmdbId: String? = null,
        val type: String? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val title: String? = null,
        val dataId: String? = null,
        val qid: Int? = null,
        val quality: String? = null,
        val format: String? = null,
        val ranges: String? = null,
    )
}
