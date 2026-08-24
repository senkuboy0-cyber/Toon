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
    val isSeries: Boolean = false
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

    // ─── Helpers ────────────────────────────────────────────────

    private fun Element.getImageSrc(): String? {
        val img = this.selectFirst("img") ?: return null
        val src = img.attr("data-src").ifEmpty {
            img.attr("src")
        }
        if (src.isEmpty()) return null
        return fixUrl(src)
    }

    private fun cleanTitle(title: String): String {
        return title.replace(Regex("\\s+"), " ").trim()
    }

    private fun Element.toSearchResult(tvType: TvType): SearchResponse? {
        val anchor = this.selectFirst("a[href*='/series/'], a[href*='/movie/']") ?: return null
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

    // ─── Main Page ──────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "series" to "Series",
        "movies" to "Movies",
        "category/anime" to "Anime",
        "category/anime/anime-series" to "Anime Series",
        "category/animation" to "Animation",
        "category/language/hindi" to "Hindi",
        "category/language/english" to "English",
        "category/language/japanese" to "Japanese",
        "category/networks/netflix" to "NetFlix",
        "category/networks/crunchyroll" to "Crunchyroll",
        "category/networks/disney" to "Disney",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val url = when {
            path == "series" -> "$mainUrl/series"
            path == "movies" -> "$mainUrl/movies"
            else -> "$mainUrl/$path"
        } + (if (page > 1) "/page/$page/" else "/")

        val document = app.get(url).document

        val home = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        val elements = document.select(
            "article.TPost.B, div.TPostMv, .MovieList article, .VideoList article"
        )
        for (el in elements) {
            val href = el.selectFirst("a[href]")?.attr("href") ?: continue
            if (!seen.add(href)) continue

            val type = when {
                href.contains("/movie/") -> TvType.Movie
                else -> TvType.TvSeries
            }
            el.toSearchResult(type)?.let { home.add(it) }
        }

        val hasNext = document.selectFirst("a.next.page-numbers, link[rel=next], .wp-pagenavi .next") != null

        return newHomePageResponse(request.name, home, hasNext)
    }

    // ─── Search ─────────────────────────────────────────────────

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/page/$page/?s=$query"
        val document = app.get(url).document

        val results = mutableListOf<SearchResponse>()
        val elements = document.select("article.TPost.B, div.TPostMv, .MovieList article")

        for (el in elements) {
            val type = when {
                el.selectFirst("span.Qlty")?.text()?.contains("Movie", ignoreCase = true) == true -> TvType.Movie
                else -> TvType.TvSeries
            }
            el.toSearchResult(type)?.let { results.add(it) }
        }

        return newSearchResponseList(results, results.isNotEmpty())
    }

    // ─── Load (Detail) ──────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val media = try {
            Gson().fromJson(url, ToonMedia::class.java)
        } catch (e: Exception) {
            ToonMedia(url)
        }

        val actualUrl = media.url
        val document = app.get(actualUrl).document

        // ── Title ──
        val rawTitle = media.title
            ?: document.selectFirst("h1.Title")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.replace(" - Tooniboy", "")?.trim()
            ?: "Unknown"

        // ── Poster & Background ──
        val background = fixUrlNull(document.selectFirst("figure.Objf img.TPostBg")?.attr("src"))
        val poster = media.poster ?: background

        // ── Description ──
        val description = document.selectFirst("div.Description > p")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        // ── Year / Rating / Duration ──
        val year = document.selectFirst("span.Date")?.text()?.trim()?.toIntOrNull()
        val rating = document.selectFirst("div.post-ratings span")?.text()?.trim()?.toDoubleOrNull()
        val duration = document.selectFirst("span.Time")?.text()?.trim()
        val quality = document.selectFirst("span.Qlty")?.text()?.trim()

        // ── Genres ──
        val genres = document.select("p.Genre a").mapNotNull { it.text().trim().takeIf { t -> t.isNotEmpty() } }

        // ── Cast ──
        val cast = document.select("p.Cast a").mapNotNull { it.text().trim().takeIf { t -> t.isNotEmpty() } }

        // ── Tags ──
        val tags = document.select("p.Tags a").mapNotNull { it.text().trim().takeIf { t -> t.isNotEmpty() } }

        // ── Recommendations ("More titles like this") ──
        val recommendations = mutableListOf<SearchResponse>()
        for (el in document.select("div.MovieListTop article.TPost.B, div.MovieList article.TPost.B")) {
            el.toSearchResult(TvType.TvSeries)?.let { recommendations.add(it) }
        }

        // ── Determine series vs movie ──
        val seasonLinks = document.select("section.SeasonBx .Title a[href*='/season/']")
        val isSeries = actualUrl.contains("/series/") && seasonLinks.isNotEmpty()

        return if (isSeries) {
            loadSeries(
                media, document, rawTitle, poster, background, description,
                year, rating, genres, cast, recommendations
            )
        } else {
            newMovieLoadResponse(rawTitle, url, TvType.Movie, Gson().toJson(ToonMedia(actualUrl, poster, rawTitle))) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background ?: poster
                this.plot = description
                this.year = year
                this.score = Score.from10(rating)
                this.duration = parseDuration(duration)
                this.tags = genres.ifEmpty { tags }
                this.actors = cast.map { ActorData(Actor(it)) }
                this.recommendations = recommendations
                this.comingSoon = quality?.equals("ON AIR", ignoreCase = true) == false && description.isNullOrBlank()
            }
        }
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
        genres: List<String>,
        cast: List<String>,
        recommendations: List<SearchResponse>
    ): LoadResponse {
        val episodes = mutableListOf<Episode>()
        val seriesSlugRegex = Regex("/season/(.+)-(\\d+)/?$")

        val seasons = document.select("section.SeasonBx .Title a[href*='/season/']")
        val seasonUrls = LinkedHashSet<String>()

        for (a in seasons) {
            val href = fixUrl(a.attr("href"))
            seasonUrls.add(href)
        }

        // Fallback: derive from series slug if no explicit links found
        if (seasonUrls.isEmpty()) {
            val slug = media.url.trimEnd('/').substringAfterLast('/')
            seasonUrls.add("$mainUrl/season/$slug-1/")
        }

        for ((index, seasonUrl) in seasonUrls.withIndex()) {
            val match = seriesSlugRegex.find(seasonUrl)
            val seasonNum = match?.groupValues?.get(2)?.toIntOrNull() ?: (index + 1)

            val seasonDoc = try {
                app.get(seasonUrl).document
            } catch (e: Exception) {
                Log.e("Tooniboy", "Failed to load season $seasonNum: ${e.message}")
                null
            } ?: continue

            // Episode table rows (toroflix theme: TPTblCn table)
            val rows = seasonDoc.select("div.TPTblCn table tbody tr")
            if (rows.isNotEmpty()) {
                for (row in rows) {
                    val epNum = row.selectFirst("td span.Num")?.text()?.trim()?.toIntOrNull() ?: continue
                    val epLink = row.selectFirst("td.MvTbImg a[href], td.MvTbTtl a[href]")?.attr("href") ?: continue
                    val epThumb = row.selectFirst("td.MvTbImg img")?.let { row.fixUrlFromImg(it) }
                    val epName = row.selectFirst("td.MvTbTtl a")?.text()?.trim().orEmpty()
                        .ifBlank { "Episode $epNum" }

                    episodes.add(
                        newEpisode(Gson().toJson(EpisodeData(fixUrl(epLink)))) {
                            this.name = epName
                            this.posterUrl = epThumb
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                }
            } else {
                // Fallback: episode articles
                var fallbackEp = 1
                for (el in seasonDoc.select("article.TPost, li.TPostMv")) {
                    val href = el.selectFirst("a[href*='/episode/']")?.attr("href") ?: continue
                    val name = cleanTitle(el.selectFirst("h2.Title")?.text() ?: "Episode $fallbackEp")
                    episodes.add(
                        newEpisode(Gson().toJson(EpisodeData(fixUrl(href)))) {
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
            this.tags = genres
            this.actors = cast.map { ActorData(Actor(it)) }
            this.recommendations = recommendations
        }
    }

    private fun Element.fixUrlFromImg(img: Element): String? {
        val src = img.attr("data-src").ifEmpty { img.attr("src") }
        return if (src.isEmpty()) null else fixUrl(src)
    }

    private fun parseDuration(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val min = Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        return min
    }

    // ─── Load Links (Servers) ───────────────────────────────────

    data class EpisodeData(val url: String)

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

        // ── Server buttons: key + trid ──
        val serverButtons = document.select("button[data-key][data-id]")
        val trid = serverButtons.firstOrNull()?.attr("data-id")
            ?: document.selectFirst("[data-id]")?.attr("data-id")

        var success = false

        // ── Default player (VidStreamX → animedekho embed → as-cdn26) ──
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

        // ── trembed servers (key 0..8) ──
        if (trid != null) {
            for (btn in serverButtons) {
                val key = btn.attr("data-key").toIntOrNull() ?: continue
                val label = btn.text().trim().ifBlank { "Server ${key + 1}" }
                try {
                    val embedUrl = "$mainUrl/?trembed=$key&trid=$trid&trtype=2"
                    val embedDoc = app.get(embedUrl).document
                    val iframeSrc = embedDoc.selectFirst("iframe[src]")?.attr("src")
                    if (!iframeSrc.isNullOrBlank()) {
                        val fixed = iframeSrc.replace("&amp;", "&")
                        loadExtractor(fixed, epData.url, subtitleCallback, callback)
                        success = true
                        Log.d("Tooniboy", "[$label] $fixed")
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
