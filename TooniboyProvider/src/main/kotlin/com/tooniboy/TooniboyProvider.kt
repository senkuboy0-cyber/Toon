package com.tooniboy

import com.google.gson.Gson
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class ToonMedia(
    val url: String,
    val poster: String? = null,
    val title: String? = null,
)

/**
 * Watchable page URL + its type.
 * trtype: 1 = movie, 2 = episode (toronites embed system)
 */
data class EpisodeData(val url: String, val trtype: Int = 2)

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

    // ─── Helpers ────────────────────────────────────────────────

    private fun Element.getImageSrc(): String? {
        val img = this.selectFirst("img") ?: return null
        val src = img.attr("data-src").ifEmpty { img.attr("src") }
        if (src.isEmpty()) return null
        return fixUrl(src)
    }

    private fun cleanTitle(title: String): String {
        return title.replace(Regex("\\s+"), " ").trim()
            .replace("&amp;", "&")
    }

    /**
     * Detects type from URL. NOTE: movies use PLURAL "/movies/".
     */
    private fun detectType(href: String): TvType = when {
        href.contains("/movies/") -> TvType.Movie
        href.contains("/movie/") -> TvType.Movie   // safety for singular too
        else -> TvType.TvSeries
    }

    private fun isMovieUrl(url: String): Boolean =
        url.contains("/movies/") || url.contains("/movie/")

    /**
     * Universal card parser for toroflix theme.
     * Cards: <li class="TPostMv ..."><article class="TPost B"><a href>...
     */
    private fun Element.toSearchResult(tvType: TvType): SearchResponse? {
        val anchor = this.selectFirst("a[href*='/series/'], a[href*='/movies/'], a[href*='/movie/']")
            ?: return null
        val href = fixUrl(anchor.attr("href"))

        val title = cleanTitle(
            this.selectFirst("h2.Title, div.Title, h2")?.text()
                ?: this.selectFirst("img")?.attr("alt")?.replace(Regex("^Image\\s*"), "")
                ?: return null
        )
        if (title.isBlank()) return null

        val poster = this.getImageSrc()

        return newMovieSearchResponse(title, Gson().toJson(ToonMedia(href, poster, title)), tvType) {
            this.posterUrl = poster
        }
    }

    private fun parseCardList(document: Document): MutableList<SearchResponse> {
        val home = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        val elements = document.select(
            "li.TPostMv, div.TPost.B, article.TPost.B"
        )
        for (el in elements) {
            val href = el.selectFirst("a[href]")?.attr("href") ?: continue
            if (!seen.add(href)) continue

            el.toSearchResult(detectType(href))?.let { home.add(it) }
        }
        return home
    }

    private fun parseDuration(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

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

        val document = app.get(url).document
        val home = parseCardList(document)

        val hasNext = document.selectFirst(
            "nav.wp-pagenavi a, a.next.page-numbers, link[rel=next], .pagination .next"
        ) != null

        return newHomePageResponse(request.name, home, hasNext)
    }

    // ─── Search ─────────────────────────────────────────────────

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page <= 1) {
            "$mainUrl/?s=$query"
        } else {
            "$mainUrl/page/$page/?s=$query"
        }
        val document = app.get(url).document
        val results = parseCardList(document)

        val hasNext = document.selectFirst("nav.wp-pagenavi a, a.next.page-numbers") != null
        return newSearchResponseList(results, hasNext)
    }

    // ─── Load (Detail) ──────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val media = try {
            Gson().fromJson(url, ToonMedia::class.java)
        } catch (e: Exception) {
            ToonMedia(url)
        }

        val actualUrl = media.url
        val movie = isMovieUrl(actualUrl)
        val document = app.get(actualUrl).document

        // ── Title ──
        val rawTitle = media.title
            ?: cleanTitle(
                document.selectFirst("h1.Title")?.text()
                    ?: document.selectFirst("title")?.text()?.replace(" - Tooniboy", "")
                    ?: "Unknown"
            )

        // ── Poster & Background ──
        val background = fixUrlNull(document.selectFirst("figure.Objf img.TPostBg")?.attr("src"))
        val poster = media.poster ?: background

        // ── Description ──
        val description = extractDescription(document)

        // ── Meta ──
        val year = document.selectFirst("span.Date")?.text()?.trim()?.toIntOrNull()
        val rating = document.selectFirst("div.post-ratings span")?.text()?.trim()?.toDoubleOrNull()
        val duration = document.selectFirst("span.Time")?.text()?.trim()

        // ── Recommendations ──
        val recommendations = parseRecommendations(document)

        // ── Series detection: real season links required ──
        val seasonLinks = document.select("section.SeasonBx .Title a[href*='/season/']")
            .map { fixUrl(it.attr("href")) }
            .filter { it.isNotBlank() }
        val isSeries = !movie && seasonLinks.isNotEmpty()

        return if (isSeries) {
            loadSeries(media, document, rawTitle, poster, background, description, year, rating, seasonLinks, recommendations)
        } else {
            // Movies play directly from their own page; trtype=1
            newMovieLoadResponse(rawTitle, url, TvType.Movie, Gson().toJson(EpisodeData(actualUrl, trtype = 1))) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background ?: poster
                this.plot = description
                this.year = year
                this.score = Score.from10(rating)
                this.duration = parseDuration(duration)
                this.recommendations = recommendations
            }
        }
    }

    /**
     * Robust description extraction. Some entries (e.g. Demon Slayer)
     * have an image-only first paragraph; real text lives in later ones.
     */
    private fun extractDescription(document: Document): String? {
        val descDiv = document.selectFirst("div.Description") ?: return null

        var html = descDiv.html()
        html = html.substringBefore("""<p class="Genre">""")
            .substringBefore("""<p class="Cast">""")
            .substringBefore("""<p class="Tags">""")

        val candidates = Jsoup.parse(html).select("p")
        for (p in candidates) {
            if (p.hasClass("Genre") || p.hasClass("Cast") || p.hasClass("Tags")) continue
            val clone = p.clone()
            clone.select("img").remove()          // strip images (Demon Slayer case)
            clone.select("script,style").remove()
            val text = clone.text().trim()
            if (text.length > 20) return text
        }

        document.selectFirst("meta[name=description]")?.attr("content")?.let {
            if (it.isNotBlank()) return it.trim()
        }
        return null
    }

    /**
     * Parses ONLY the "More titles like this" carousel.
     */
    private fun parseRecommendations(document: Document): List<SearchResponse> {
        val recs = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        try {
            val header = document.select("div.Top .Title").firstOrNull {
                it.text().contains("More titles like this", ignoreCase = true)
                    || it.text().contains("More like this", ignoreCase = true)
                    || it.text().contains("Related", ignoreCase = true)
            }

            val section: Element? = header?.parents()?.firstOrNull { parent ->
                parent.select("a[href*='/series/'], a[href*='/movies/']").isNotEmpty()
            }

            val cards = section?.select("div.TPost.B")
                ?: document.select("div.MovieListTop div.TPost.B")

            for (el in cards) {
                val anchor = el.selectFirst("a[href*='/series/'], a[href*='/movies/'], a[href*='/movie/']")
                    ?: continue
                val href = anchor.attr("href")
                if (!seen.add(href)) continue

                el.toSearchResult(detectType(href))?.let { recs.add(it) }
            }
        } catch (e: Exception) {
            Log.e("Tooniboy", "recommendations failed: ${e.message}")
        }

        return recs
    }

    private suspend fun loadSeries(
        media: ToonMedia,
        document: Document,
        title: String,
        poster: String?,
        background: String?,
        description: String?,
        year: Int?,
        rating: Double?,
        seasonUrls: List<String>,
        recommendations: List<SearchResponse>
    ): LoadResponse {
        val episodes = mutableListOf<Episode>()
        val seasonSlugRegex = Regex("/season/(.+)-(\\d+)/?$")

        for ((index, seasonUrl) in seasonUrls.withIndex()) {
            val match = seasonSlugRegex.find(seasonUrl)
            val seasonNum = match?.groupValues?.get(2)?.toIntOrNull() ?: (index + 1)

            val seasonDoc = try {
                app.get(seasonUrl).document
            } catch (e: Exception) {
                Log.e("Tooniboy", "Failed to load season $seasonNum: ${e.message}")
                null
            } ?: continue

            val rows = seasonDoc.select("div.TPTblCn table tbody tr")
            if (rows.isNotEmpty()) {
                for (row in rows) {
                    val epNum = row.selectFirst("td span.Num")?.text()?.trim()?.toIntOrNull() ?: continue
                    val epLink = row.selectFirst("td.MvTbImg a[href], td.MvTbTtl a[href]")?.attr("href") ?: continue
                    val epThumb = row.selectFirst("td.MvTbImg img")?.let { row.getImageSrc() }
                    val epName = row.selectFirst("td.MvTbTtl a")?.text()?.trim().orEmpty()
                        .ifBlank { "Episode $epNum" }

                    episodes.add(
                        newEpisode(Gson().toJson(EpisodeData(fixUrl(epLink), trtype = 2))) {
                            this.name = epName
                            this.posterUrl = epThumb
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                }
            } else {
                var fallbackEp = 1
                for (el in seasonDoc.select("article.TPost, li.TPostMv")) {
                    val href = el.selectFirst("a[href*='/episode/']")?.attr("href") ?: continue
                    val name = cleanTitle(el.selectFirst("h2.Title")?.text() ?: "Episode $fallbackEp")
                    episodes.add(
                        newEpisode(Gson().toJson(EpisodeData(fixUrl(href), trtype = 2))) {
                            this.name = name
                            this.season = seasonNum
                            this.episode = fallbackEp
                        }
                    )
                    fallbackEp++
                }
            }
        }

        return newTvSeriesLoadResponse(title, Gson().toJson(media), TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background ?: poster
            this.plot = description
            this.year = year
            this.score = Score.from10(rating)
            this.recommendations = recommendations
        }
    }

    // ─── Load Links (Servers) ───────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = try {
            Gson().fromJson(data, EpisodeData::class.java)
        } catch (e: Exception) {
            Log.e("Tooniboy", "Failed to parse episode data: ${e.message}")
            return false
        }

        val document = app.get(epData.url).document

        // ── Server buttons carry data-typ (movie|episode), key and id ──
        val serverButtons = document.select("button[data-key][data-id]")
        val firstButton: Element? = serverButtons.firstOrNull()

        // trtype: movies use 1, episodes use 2. Detect from page, fallback to stored value.
        val trtype = when {
            firstButton != null && firstButton.attr("data-typ") == "movie" -> 1
            isMovieUrl(epData.url) -> 1
            else -> if (epData.trtype == 1 || epData.trtype == 2) epData.trtype else 2
        }

        val trid = firstButton?.attr("data-id")
            ?: document.selectFirst("[data-id]")?.attr("data-id")
            ?: Regex("""trid=(\d+)""").find(document.html())?.groupValues?.get(1)

        var success = false

        // ── Default player (VidStreamX → animedekho/as-cdn embed) ──
        val defaultIframe = document.selectFirst("div.Video.on > iframe[src]")
        defaultIframe?.attr("src")?.takeIf { it.isNotBlank() }?.let { src ->
            try {
                val resolved = resolveDefaultPlayer(src)
                if (!resolved.isNullOrEmpty()) {
                    loadExtractor(resolved, subtitleCallback, callback)
                    success = true
                } else {
                    loadExtractor(src, epData.url, subtitleCallback, callback)
                    success = true
                }
            } catch (e: Exception) {
                Log.e("Tooniboy", "Default player failed: ${e.message}")
            }
        }

        // ── trembed servers ──
        if (trid != null) {
            for (btn in serverButtons) {
                val key = btn.attr("data-key").toIntOrNull() ?: continue
                val label = btn.text().trim().ifBlank { "Server ${key + 1}" }
                try {
                    val embedDoc = app.get("$mainUrl/?trembed=$key&trid=$trid&trtype=$trtype").document
                    val iframeSrc = embedDoc.selectFirst("iframe[src]")?.attr("src")
                        ?.replace("&amp;", "&")
                    if (!iframeSrc.isNullOrBlank()) {
                        loadExtractor(iframeSrc, epData.url, subtitleCallback, callback)
                        success = true
                        Log.d("Tooniboy", "[$label] $iframeSrc")
                    }
                } catch (e: Exception) {
                    Log.e("Tooniboy", "Server key=$key ($label) failed: ${e.message}")
                }
            }
        }

        return success
    }

    /**
     * Default VidStreamX player resolves through animedekho.app embed
     * to an as-cdn*.top video page handled by the Zephyrflick extractor.
     */
    private suspend fun resolveDefaultPlayer(src: String): String? {
        return try {
            if (src.contains("as-cdn")) {
                src
            } else {
                val innerDoc = app.get(src).document
                innerDoc.selectFirst("iframe[src]")?.attr("src")
                    ?.takeIf { it.contains("as-cdn") || it.contains("zephyrflick") || it.contains("awstream") }
            }
        } catch (e: Exception) {
            null
        }
    }
}
