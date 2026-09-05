package com.tooniboy

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.Gson
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

data class ToonMedia(
    val url: String,
    val poster: String? = null,
    val title: String? = null,
)

data class EpisodeData(val url: String, val trtype: Int = 2)

// ─── TMDB Data Classes ───
data class TmdbImages(
    @JsonProperty("logos") val logos: ArrayList<TmdbImage>? = null,
    @JsonProperty("backdrops") val backdrops: ArrayList<TmdbImage>? = null
)
data class TmdbImage(
    @JsonProperty("file_path") val filePath: String? = null,
    @JsonProperty("iso_639_1") val lang: String? = null
)
data class TmdbFind(
    @JsonProperty("movie_results") val movies: ArrayList<TmdbResult>? = null,
    @JsonProperty("tv_results") val tvShows: ArrayList<TmdbResult>? = null
)
data class TmdbResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("genre_ids") val genreIds: ArrayList<Int>? = null
)
data class TmdbSearch(
    @JsonProperty("results") val results: ArrayList<TmdbResult>? = null
)
data class TmdbDetails(
    val id: Int?,
    val type: String?,
    val logo: String?,
    val backdrop: String?
)

open class Tooniboy : MainAPI() {
    override var mainUrl = "https://tooniboy.co"
    override var name = "Tooniboy"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
    )

    // ─── TMDB ───────────────────────────────────────────────────
    private val TMDB_API = "https://api.themoviedb.org/3"
    private val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private val TMDB_IMG = "https://image.tmdb.org/t/p/original"

    private fun cleanForTmdb(title: String): String {
        var t = title.replace(Regex("Watch Online", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+\\d+[x\u00d7]\\d+.*"), "")
        t = t.replace(Regex("\\s+Episode\\s+\\d+.*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+Season\\s+\\d+.*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+(?:in\\s+)?(?:hindi|tamil|telugu|english|japanese)\\s*(?:dub(?:bed)?)?\\s*$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+dub(?:bed)?\\s*$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s*fan\\s*dub.*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s*fandub.*", RegexOption.IGNORE_CASE), "")
        t = t.substringBefore("(").substringBefore("[").trim()
        return t.ifBlank { title }
    }

    private fun normalizeTitle(s: String?): String =
        (s ?: "").replace(Regex("[^a-zA-Z0-9]"), "").lowercase()

    private fun getResultYear(result: TmdbResult): Int? {
        val d = result.releaseDate ?: result.firstAirDate ?: return null
        return if (d.contains("-")) d.substringBefore("-").toIntOrNull() else null
    }

    private fun yearMatches(tmdbYear: Int?, siteYear: Int?): Boolean {
        if (siteYear == null || tmdbYear == null) return true
        return kotlin.math.abs(tmdbYear - siteYear) <= 1
    }

    private fun pickBestResult(candidates: List<TmdbResult>, siteYear: Int?): TmdbResult? {
        if (candidates.isEmpty()) return null
        if (siteYear != null) {
            val matched = candidates.filter { yearMatches(getResultYear(it), siteYear) }
            if (matched.isNotEmpty())
                return matched.firstOrNull { it.genreIds?.contains(16) == true } ?: matched[0]
        }
        return candidates[0]
    }

    private suspend fun fetchTmdbAssets(document: Document?, rawTitle: String, isSeries: Boolean, year: Int?): TmdbDetails {
        return try {
            val title = cleanForTmdb(rawTitle)
            if (title.isBlank()) return TmdbDetails(null, null, null, null)
            var tmdbId: Int? = null
            var mediaType = if (isSeries) "tv" else "movie"
            val safeTitle = URLEncoder.encode(title, "UTF-8")
            val validResults = app.get("$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$safeTitle")
                .parsedSafe<TmdbSearch>()?.results
                ?.filter { it.mediaType == "movie" || it.mediaType == "tv" }.orEmpty()
            val normTitle = normalizeTitle(title)
            pickBestResult(validResults.filter { normalizeTitle(it.title) == normTitle || normalizeTitle(it.name) == normTitle }, year)
                ?.also { tmdbId = it.id; it.mediaType?.let { m -> mediaType = m } }
            if (tmdbId == null && normTitle.length >= 6)
                pickBestResult(validResults.filter { normalizeTitle(it.title).ifEmpty { normalizeTitle(it.name) }.startsWith(normTitle) }, year)
                    ?.also { tmdbId = it.id; it.mediaType?.let { m -> mediaType = m } }
            if (tmdbId == null && document != null) {
                val imdbId = document.select("a[href*='imdb.com/title']")
                    .mapNotNull { it.attr("href").substringAfter("title/", "").substringBefore("/").takeIf { id -> id.startsWith("tt") } }
                    .firstOrNull()
                if (imdbId != null) {
                    val find = app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id").parsedSafe<TmdbFind>()
                    val chosen = if (isSeries) find?.tvShows?.firstOrNull() ?: find?.movies?.firstOrNull()
                                 else find?.movies?.firstOrNull() ?: find?.tvShows?.firstOrNull()
                    chosen?.also { tmdbId = it.id; mediaType = it.mediaType ?: if (isSeries) "tv" else "movie" }
                }
            }
            if (tmdbId == null) return TmdbDetails(null, null, null, null)
            val images = app.get("$TMDB_API/$mediaType/$tmdbId/images?api_key=$TMDB_KEY").parsedSafe<TmdbImages>()
            val logo = images?.logos?.filter { !(it.filePath ?: "").endsWith(".svg", true) }
                ?.let { l -> l.firstOrNull { it.lang == "en" } ?: l.firstOrNull { it.lang == null } ?: l.firstOrNull { it.lang == "ja" } ?: l.firstOrNull() }
                ?.filePath?.let { "$TMDB_IMG$it" }
            val backdrop = images?.backdrops
                ?.let { b -> b.firstOrNull { it.lang == null } ?: b.firstOrNull { it.lang == "en" } ?: b.firstOrNull() }
                ?.filePath?.let { "$TMDB_IMG$it" }
            TmdbDetails(tmdbId, mediaType, logo, backdrop)
        } catch (e: Exception) {
            Log.e("Tooniboy", "TMDB failed: ${e.message}")
            TmdbDetails(null, null, null, null)
        }
    }

    // ─── Helpers ────────────────────────────────────────────────

    private fun Element.getImageSrc(): String? {
        val img = selectFirst("img") ?: return null
        val src = img.attr("data-src").ifEmpty { img.attr("src") }
        return if (src.isEmpty()) null else fixUrl(src)
    }

    private fun cleanTitle(t: String) = t.replace(Regex("\\s+"), " ").trim()

    private fun detectType(href: String) =
        if (href.contains("/movies/") || href.contains("/movie/")) TvType.Movie else TvType.TvSeries

    private fun isMovieUrl(url: String) = url.contains("/movies/") || url.contains("/movie/")

    private fun Element.toSearchResult(tvType: TvType): SearchResponse? {
        val anchor = selectFirst("a[href*='/series/'], a[href*='/movies/'], a[href*='/movie/']") ?: return null
        val href = fixUrl(anchor.attr("href"))
        val title = cleanTitle(
            selectFirst("h2.Title, div.Title, h2")?.text()
                ?: selectFirst("img")?.attr("alt")?.replace(Regex("^Image\\s*"), "")
                ?: return null
        )
        if (title.isBlank()) return null
        val poster = getImageSrc()
        return newMovieSearchResponse(title, Gson().toJson(ToonMedia(href, poster, title)), tvType) {
            this.posterUrl = poster
        }
    }

    private fun parseCardList(document: Document): MutableList<SearchResponse> {
        val home = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (el in document.select("li.TPostMv, div.TPost.B, article.TPost.B")) {
            val href = el.selectFirst("a[href]")?.attr("href") ?: continue
            if (!seen.add(href)) continue
            el.toSearchResult(detectType(href))?.let { home.add(it) }
        }
        return home
    }

    private fun parseDuration(text: String?) =
        if (text.isNullOrBlank()) null else Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()

    // ─── CF warm-up headers ─────────────────────────────────────
    // Sending these headers with every tooniboy.co request makes
    // Cloudflare treat the request as a normal browser visit and
    // issue/refresh the cf_clearance cookie so trembed URLs work
    // even after the app has been closed and reopened.
    private val cfHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xhtml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "https://tooniboy.co/"
    )

    // ─── Main Page ──────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "series" to "Series",
        "movies" to "Movies",
        "category/language/hindi" to "Hindi",
        "category/animation" to "Animation",
        "category/adventure" to "Adventure",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val url = when {
            path == "series" -> "$mainUrl/series/"
            path == "movies" -> "$mainUrl/movies/"
            else -> "$mainUrl/$path/"
        } + (if (page > 1) "page/$page/" else "")
        val document = app.get(url, headers = cfHeaders).document
        val home = parseCardList(document)
        val hasNext = document.selectFirst("nav.wp-pagenavi a, a.next.page-numbers, link[rel=next], .pagination .next") != null
        return newHomePageResponse(request.name, home, hasNext)
    }

    // ─── Search ─────────────────────────────────────────────────

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page <= 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
        val document = app.get(url, headers = cfHeaders).document
        val results = parseCardList(document)
        val hasNext = document.selectFirst("nav.wp-pagenavi a, a.next.page-numbers") != null
        return newSearchResponseList(results, hasNext)
    }

    // ─── Load (Detail) ──────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val media = try { Gson().fromJson(url, ToonMedia::class.java) } catch (e: Exception) { ToonMedia(url) }
        val actualUrl = media.url
        val movie = isMovieUrl(actualUrl)
        val document = app.get(actualUrl, headers = cfHeaders).document
        val rawTitle = media.title ?: cleanTitle(
            document.selectFirst("h1.Title")?.text()
                ?: document.selectFirst("title")?.text()?.replace(" - Tooniboy", "")
                ?: "Unknown"
        )
        val background = fixUrlNull(document.selectFirst("figure.Objf img.TPostBg")?.attr("src"))
        val poster = media.poster ?: background
        val description = extractDescription(document)
        val year = document.selectFirst("span.Date")?.text()?.trim()?.toIntOrNull()
        val rating = document.selectFirst("div.post-ratings span")?.text()?.trim()?.toDoubleOrNull()
        val duration = document.selectFirst("span.Time")?.text()?.trim()
        val recommendations = parseRecommendations(document)
        val seasonLinks = document.select("section.SeasonBx .Title a[href*='/season/']")
            .map { fixUrl(it.attr("href")) }.filter { it.isNotBlank() }
        val isSeries = !movie && seasonLinks.isNotEmpty()
        val tmdb = fetchTmdbAssets(document, rawTitle, isSeries, year)
        return if (isSeries) {
            loadSeries(media, document, rawTitle, poster, background, description, year, rating, seasonLinks, recommendations, tmdb)
        } else {
            newMovieLoadResponse(rawTitle, url, TvType.Movie, Gson().toJson(EpisodeData(actualUrl, trtype = 1))) {
                this.posterUrl = poster
                this.backgroundPosterUrl = tmdb.backdrop ?: background ?: poster
                this.plot = description
                this.year = year
                this.score = Score.from10(rating)
                this.duration = parseDuration(duration)
                this.recommendations = recommendations
                this.logoUrl = tmdb.logo
            }
        }
    }

    private fun extractDescription(document: Document): String? {
        val descDiv = document.selectFirst("div.Description") ?: return null
        var html = descDiv.html()
        html = html.substringBefore("""<p class="Genre">""")
            .substringBefore("""<p class="Cast">""").substringBefore("""<p class="Tags">""")
        for (p in Jsoup.parse(html).select("p")) {
            if (p.hasClass("Genre") || p.hasClass("Cast") || p.hasClass("Tags")) continue
            val clone = p.clone()
            clone.select("img,script,style").remove()
            val text = clone.text().trim()
            if (text.length > 20) return text
        }
        return document.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifBlank { null }
    }

    private fun parseRecommendations(document: Document): List<SearchResponse> {
        val recs = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        try {
            val header = document.select("div.Top .Title").firstOrNull {
                it.text().contains("More titles like this", ignoreCase = true)
                    || it.text().contains("More like this", ignoreCase = true)
                    || it.text().contains("Related", ignoreCase = true)
            }
            val section = header?.parents()?.firstOrNull { p ->
                p.select("a[href*='/series/'], a[href*='/movies/']").isNotEmpty()
            }
            val cards = section?.select("div.TPost.B") ?: document.select("div.MovieListTop div.TPost.B")
            for (el in cards) {
                val href = el.selectFirst("a[href*='/series/'], a[href*='/movies/'], a[href*='/movie/']")?.attr("href") ?: continue
                if (!seen.add(href)) continue
                el.toSearchResult(detectType(href))?.let { recs.add(it) }
            }
        } catch (e: Exception) { Log.e("Tooniboy", "recommendations failed: ${e.message}") }
        return recs
    }

    private suspend fun loadSeries(
        media: ToonMedia, document: Document, title: String, poster: String?,
        background: String?, description: String?, year: Int?, rating: Double?,
        seasonUrls: List<String>, recommendations: List<SearchResponse>, tmdb: TmdbDetails
    ): LoadResponse {
        val episodes = mutableListOf<Episode>()
        val seasonSlugRegex = Regex("/season/(.+)-(\\d+)/?$")
        for ((index, seasonUrl) in seasonUrls.withIndex()) {
            val seasonNum = seasonSlugRegex.find(seasonUrl)?.groupValues?.get(2)?.toIntOrNull() ?: (index + 1)
            val seasonDoc = try { app.get(seasonUrl, headers = cfHeaders).document }
                catch (e: Exception) { Log.e("Tooniboy", "season $seasonNum failed: ${e.message}"); null } ?: continue
            val rows = seasonDoc.select("div.TPTblCn table tbody tr")
            if (rows.isNotEmpty()) {
                for (row in rows) {
                    val epNum = row.selectFirst("td span.Num")?.text()?.trim()?.toIntOrNull() ?: continue
                    val epLink = row.selectFirst("td.MvTbImg a[href], td.MvTbTtl a[href]")?.attr("href") ?: continue
                    val epThumb = row.selectFirst("td.MvTbImg img")?.let { row.getImageSrc() }
                    val epName = row.selectFirst("td.MvTbTtl a")?.text()?.trim().orEmpty().ifBlank { "Episode $epNum" }
                    episodes.add(newEpisode(Gson().toJson(EpisodeData(fixUrl(epLink), trtype = 2))) {
                        this.name = epName; this.posterUrl = epThumb; this.season = seasonNum; this.episode = epNum
                    })
                }
            } else {
                var fallbackEp = 1
                for (el in seasonDoc.select("article.TPost, li.TPostMv")) {
                    val href = el.selectFirst("a[href*='/episode/']")?.attr("href") ?: continue
                    episodes.add(newEpisode(Gson().toJson(EpisodeData(fixUrl(href), trtype = 2))) {
                        this.name = cleanTitle(el.selectFirst("h2.Title")?.text() ?: "Episode $fallbackEp")
                        this.season = seasonNum; this.episode = fallbackEp
                    })
                    fallbackEp++
                }
            }
        }
        return newTvSeriesLoadResponse(title, Gson().toJson(media), TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = tmdb.backdrop ?: background ?: poster
            this.plot = description; this.year = year; this.score = Score.from10(rating)
            this.recommendations = recommendations; this.logoUrl = tmdb.logo
        }
    }

    // ─── Load Links ──────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = try {
            Gson().fromJson(data, EpisodeData::class.java)
        } catch (e: Exception) {
            Log.e("Tooniboy", "parse failed: ${e.message}"); return false
        }

        // Step 1: Hit the homepage first so Cloudflare sets/refreshes cf_clearance cookie.
        // Without this, trembed requests after app restart get blocked by Cloudflare and
        // return an empty/challenge page instead of the iframe — causing missing video URLs.
        try {
            app.get(mainUrl, headers = cfHeaders)
            Log.d("Tooniboy", "CF warm-up done")
        } catch (e: Exception) {
            Log.w("Tooniboy", "CF warm-up failed (continuing anyway): ${e.message}")
        }

        // Step 2: Fetch the episode page with CF headers so the cookie carries over.
        val document = try {
            app.get(epData.url, headers = cfHeaders).document
        } catch (e: Exception) {
            Log.e("Tooniboy", "episode page failed: ${e.message}"); return false
        }

        val serverButtons = document.select("button[data-key][data-id]")
        val firstButton = serverButtons.firstOrNull()

        val trtype = when {
            firstButton?.attr("data-typ") == "movie" -> 1
            isMovieUrl(epData.url) -> 1
            else -> if (epData.trtype == 1 || epData.trtype == 2) epData.trtype else 2
        }

        var success = false

        // Default player (Zephyrflick / as-cdn)
        document.selectFirst("div.Video.on > iframe[src]")?.attr("src")?.takeIf { it.isNotBlank() }?.let { src ->
            try {
                val resolved = resolveDefaultPlayer(src)
                loadExtractor(resolved ?: src, epData.url, subtitleCallback, callback)
                success = true
            } catch (e: Exception) { Log.e("Tooniboy", "default player failed: ${e.message}") }
        }

        // trembed servers — per-button trid + CF headers + 300ms stagger
        for ((index, btn) in serverButtons.withIndex()) {
            val key = btn.attr("data-key").toIntOrNull() ?: continue
            val trid = btn.attr("data-id").ifBlank { firstButton?.attr("data-id") } ?: continue
            val label = btn.text().trim().ifBlank { "Server ${key + 1}" }

            if (index > 0) delay(300L)

            try {
                val embedDoc = app.get(
                    "$mainUrl/?trembed=$key&trid=$trid&trtype=$trtype",
                    headers = cfHeaders
                ).document

                val iframeSrc = embedDoc.selectFirst("iframe[src]")?.attr("src")?.replace("&amp;", "&")
                if (!iframeSrc.isNullOrBlank()) {
                    loadExtractor(iframeSrc, epData.url, subtitleCallback, callback)
                    success = true
                    Log.d("Tooniboy", "[$label] $iframeSrc")
                } else {
                    Log.w("Tooniboy", "[$label] key=$key trid=$trid -> no iframe")
                }
            } catch (e: Exception) {
                Log.e("Tooniboy", "[$label] failed: ${e.message}")
            }
        }

        return success
    }

    private suspend fun resolveDefaultPlayer(src: String): String? {
        return try {
            if (src.contains("as-cdn")) src
            else {
                app.get(src).document.selectFirst("iframe[src]")?.attr("src")
                    ?.takeIf { it.contains("as-cdn") || it.contains("zephyrflick") || it.contains("awstream") }
            }
        } catch (e: Exception) { null }
    }
}
