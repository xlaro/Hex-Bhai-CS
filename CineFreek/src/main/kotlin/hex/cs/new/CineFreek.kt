package hex.cs.new

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class CineFreek : MainAPI() {
    override var mainUrl = "https://cinefreak.net"
    override var name = "CineFreek"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )
    override var lang = "en"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document

        val selectors = listOf(
            "a.movie-card",
            "article.post-item",
            "div.ml-item",
            "div.content-main article",
            "div.poster-container",
            "div.entry-content div.screenshot-item"
        ).joinToString(", ")

        val homeItems = document.select(selectors).mapNotNull { element ->
            toSearchResult(element)
        }.distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList("Latest Releases", homeItems),
            hasNext = document.select("a.next, .nav-links a:contains(Next), a.next.page-numbers").isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        val selectors = listOf(
            "a.movie-card",
            "article.post-item",
            "div.ml-item",
            "article"
        ).joinToString(", ")

        return document.select(selectors).mapNotNull { element ->
            toSearchResult(element)
        }.distinctBy { it.url }
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val href = fixUrlNull(
            element.attr("href").ifEmpty {
                element.selectFirst("a")?.attr("href")
            }
        ) ?: return null

        val title = element.select("h3.movie-card-title, h2, h3, .entry-title, a.title").text().trim().ifEmpty {
            element.attr("aria-label").replace(" details", "", ignoreCase = true).trim().ifEmpty {
                element.select("img").attr("alt").trim()
            }
        }
        if (title.isEmpty()) return null

        // Image extraction supporting lazy loading
        val imgEl = element.selectFirst("img")
        val posterUrl = fixUrlNull(
            imgEl?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:image") }
                ?: imgEl?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: imgEl?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: imgEl?.attr("srcset")?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
        )

        val isSeries = element.select(".series-info-bottom, .status-ep").isNotEmpty() ||
                title.contains("Season", ignoreCase = true) ||
                title.contains("Episode", ignoreCase = true) ||
                href.contains("/series/") || 
                href.contains("/tv/") ||
                href.contains("/category/web-series/")

        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Clean & extract Title
        val rawTitle = document.select("h1.page-title, h1.entry-title, h1.title").text().trim()
        val title = if (rawTitle.isNotEmpty()) {
            rawTitle.replace(Regex("(?i)\\|.*|\\–.*|Download.*"), "").trim()
        } else {
            document.select("title").text().split("|").firstOrNull()?.trim() ?: "Unknown"
        }

        // Poster image extraction
        val poster = fixUrlNull(
            document.select(".poster-container img, .poster-image img, .entry-content img, article img")
                .firstOrNull { it.attr("src").contains("tmdb.org") || it.attr("src").contains("cineimg") || it.hasClass("wp-post-image") }
                ?.attr("src")
                ?: document.select(".poster-container img, .poster-image img, article img").attr("src")
        )

        // Metadata Parsing
        val bodyText = document.text()
        val ratingMatch = Regex("IMDb Rating:?\\s*([0-9\\.]+)/10").find(bodyText)
        val scoreObj = ratingMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()?.let {
            Score.from10(it)
        }

        val yearMatch = Regex("(19|20)\\d{2}").find(title) ?: Regex("(19|20)\\d{2}").find(bodyText)
        val year = yearMatch?.value?.toIntOrNull()

        val description = document.select(".entry-content p:contains(Plot), .entry-content p:contains(Storyline), .synopsis, .description")
            .firstOrNull()?.text()?.replace(Regex("(?i)Plot Summary / Storyline:?"), "")?.trim()
            ?: document.select(".entry-content p").firstOrNull { it.text().length > 30 }?.text()?.trim()

        val tags = document.select(".breadcrumb-separator ~ a, .badge a, .entry-content a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        // Extract screenshots
        val screenshots = document.select(".screenshot-container img, .screenshot-item img").mapNotNull {
            fixUrlNull(it.attr("src").ifEmpty { it.attr("data-src") })
        }

        val isSeries = title.contains("Season", ignoreCase = true) || 
                       document.select(".episode-list, a[href*='episode'], ul.episodes, .dlbtn-container:contains(Episode)").isNotEmpty() ||
                       url.contains("/series/") || 
                       url.contains("/tv/")

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            
            val epElements = document.select(".entry-content a[href*='episode'], .episodes-list a, ul.episodes li, .download-links-div .movie-title")
            epElements.forEachIndexed { index, epEl ->
                val epHref = fixUrlNull(epEl.selectFirst("a")?.attr("href") ?: epEl.attr("href")) ?: url
                val epName = epEl.text().ifEmpty { "Episode ${index + 1}" }
                
                episodes.add(
                    newEpisode(epHref) {
                        this.name = epName
                        this.episode = index + 1
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = scoreObj
                this.tags = tags
                this.backgroundPosterUrl = screenshots.firstOrNull()
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = scoreObj
                this.tags = tags
                this.backgroundPosterUrl = screenshots.firstOrNull()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCtor: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val downloadButtons = document.select(".dlbtn-container a, a[href*='generate.php'], a.dlbtn, a[href*='gdrive'], iframe")

        downloadButtons.forEach { link ->
            val href = link.attr("href").ifEmpty { link.attr("src") }
            if (href.isBlank()) return@forEach

            if (href.contains("generate.php?id=")) {
                val base64Id = href.substringAfter("id=")
                try {
                    // Safe Base64 decoding (supports URL-safe and missing padding)
                    val cleanBase64 = base64Id.trim().replace(" ", "+")
                    val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT or Base64.URL_SAFE)
                    var decodedUrl = String(decodedBytes, StandardCharsets.UTF_8)

                    // Remove suffix pattern
                    decodedUrl = decodedUrl.replace(Regex("newgo\\d+$"), "")

                    // Switch path /f/ to /x/
                    val streamUrl = decodedUrl.replace("/f/", "/x/")

                    val streamDoc = app.get(streamUrl).document
                    val iframeSrc = streamDoc.select("iframe").attr("src")

                    if (iframeSrc.isNotEmpty()) {
                        val fullIframeUrl = fixUrlNull(iframeSrc) ?: iframeSrc
                        
                        if (fullIframeUrl.contains("yagaverse.net")) {
                            val mediaId = fullIframeUrl.substringAfter("id=").substringBefore("&")
                            val directFile = URLDecoder.decode(mediaId, "UTF-8")
                            
                            if (directFile.startsWith("http")) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = "CineCloud Direct",
                                        name = this.name,
                                        url = directFile
                                    ) {
                                        this.headers = mapOf(
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                                            "Referer" to streamUrl,
                                            "Origin" to "https://stream.yagaverse.net"
                                        )
                                        this.quality = getQualityFromName("${link.text()} $directFile")
                                        this.type = ExtractorLinkType.VIDEO
                                    }
                                )
                            }
                        } else {
                            loadExtractor(fullIframeUrl, streamUrl, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                val targetUrl = fixUrlNull(href) ?: href
                loadExtractor(targetUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }

    private fun getQualityFromName(name: String): Int {
        return when {
            name.contains("2160p") || name.contains("4K") -> Qualities.P2160.value
            name.contains("1080p") -> Qualities.P1080.value
            name.contains("720p") -> Qualities.P720.value
            name.contains("480p") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }
}