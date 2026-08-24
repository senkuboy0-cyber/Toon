package com.tooniboy

import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import INFER_TYPE_PLACEHOLDER
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

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

            // Subtitles from packed JS if present
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
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
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
// Key 2: CLOUDY → cloudy.upns.one  (VidStack)
// ─────────────────────────────────────────────────────────────
class Cloudy : VidStack() {
    override var name = "Cloudy"
    override var mainUrl = "https://cloudy.upns.one"
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
        val embedId = url.substringAfterLast("/")
        var sidValue = embedId
        var host = getBaseUrl(app.get(url).url)

        try {
            val pageText = app.get(url).text
            val finalId = Regex("""FinalID\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
            val myKey = Regex("""myKey\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
            val idType = Regex("""idType\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1) ?: "imdbid"

            if (finalId != null && myKey != null) {
                val apiUrl = if (url.contains("/tv/")) {
                    val season = Regex(""/tv/\d+/(\d+)/"").find(url)?.groupValues?.get(1) ?: "1"
                    val episode = Regex(""/tv/\d+/\d+/(\d+)"").find(url)?.groupValues?.get(1) ?: "1"
                    "$mainUrl/myseriesapi?tmdbid=$finalId&season=$season&epname=$episode&key=$myKey"
                } else {
                    "$mainUrl/mymovieapi?$idType=$finalId&key=$myKey"
                }
                sidValue = app.get(apiUrl).text.let {
                    JsonParser.parseString(it).asJsonObject["data"]?.asJsonArray
                        ?.takeIf { arr -> arr.size() > 0 }?.get(0)?.asJsonObject
                        ?.get("fileslug")?.asString ?: embedId
                }
            }
        } catch (e: Exception) {
            Log.e(name, "key extraction failed: ${e.message}")
        }

        val responseText = try {
            app.post("$host/embedhelper.php", data = mapOf("sid" to sidValue)).text
        } catch (e: Exception) {
            Log.e(name, "embedhelper failed: ${e.message}")
            return
        }

        val root = JsonParser.parseString(responseText).takeIf { it.isJsonObject }?.asJsonObject ?: return
        val siteUrls = root["siteUrls"]?.asJsonObject ?: return
        val siteFriendlyNames = root["siteFriendlyNames"]?.asJsonObject

        val decodedMresult = when {
            root["mresult"]?.isJsonObject == true -> root["mresult"]!!.asJsonObject
            root["mresult"]?.isJsonPrimitive == true -> try {
                JsonParser.parseString(base64Decode(root["mresult"]!!.asString)).asJsonObject
            } catch (e: Exception) {
                return
            }
            else -> return
        }

        siteUrls.keySet().intersect(decodedMresult.keySet()).forEach { key ->
            val base = siteUrls[key]?.asString?.trimEnd('/') ?: return@forEach
            val path = decodedMresult[key]?.asString?.trimStart('/') ?: return@forEach
            val fullUrl = "$base/$path"
            val friendly = siteFriendlyNames?.get(key)?.asString ?: key

            try {
                when (friendly) {
                    "StreamHG", "EarnVids" -> loadExtractor(fullUrl, referer ?: mainUrl, subtitleCallback, callback)
                    else -> loadExtractor(fullUrl, referer ?: mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e(name, "Failed $friendly at $fullUrl: ${e.message}")
            }
        }
    }

    private fun getBaseUrl(url: String): String =
        URI(url).let { "${it.scheme}://${it.host}" }
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
        val m3u8 = doc.selectFirst("#video_player[data-hash]")
            ?.attr("data-hash")
            ?.takeIf { it.contains(".m3u8") }
            ?: doc.selectFirst("[data-hash]")?.attr("data-hash")?.takeIf { it.contains(".m3u8") }
            ?: return

        callback(
            newExtractorLink(name, name, url = m3u8, type = ExtractorLinkType.M3U8) {
                this.referer = mainUrl
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
