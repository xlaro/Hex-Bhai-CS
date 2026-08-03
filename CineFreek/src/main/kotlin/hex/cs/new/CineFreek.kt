package hex.cs.new

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.SubtitleFile
import org.jsoup.nodes.Element

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

        val homeItems = document.select("a.movie-card, article.post-item, div.ml-item").mapNotNull { element ->
            toSearchResult(element)
        }

        return newHomePageResponse(
            list = HomePageList("Latest Releases", homeItems),
            hasNext = document.select("a.next, .nav-links a:contains(Next)").isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("a.movie-card, article.post-item, div.ml-item").mapNotNull { element ->
            toSearchResult(element)
        }
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val href = fixUrlNull(element.attr("href").ifEmpty { element.selectFirst("a")?.attr("href") }) ?: return null
        val title = element.select("h3.movie-card-title, h2, h3, .entry-title").text().trim().ifEmpty {
            element.attr("aria-label").replace(" details", "").trim()
        }
        if (title.isEmpty()) return null

        val posterUrl = fixUrlNull(
            element.select("img").attr("src").ifEmpty {
                element.select("img").attr("data-src")
            }
        )

        val isSeries = element.select(".series-info-bottom, .status-ep").isNotEmpty() ||
                       title.contains("Season", ignoreCase = true) ||
                       title.contains("Episode", ignoreCase = true) ||
                       href.contains("/series/") || 
                       href.contains("/tv/")

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

        val title = document.select("h1.entry-title, h1.title").text().trim().ifEmpty {
            document.select("title").text().split("|").firstOrNull()?.trim() ?: "Unknown"
        }
        val poster = fixUrlNull(
            document.select(".poster img, .entry-content img, article img").attr("src")
        )
        val description = document.select(".entry-content p, .synopsis, .description").firstOrNull()?.text()?.trim()

        val isSeries = title.contains("Season", ignoreCase = true) || 
                       document.select(".episode-list, a[href*='episode'], ul.episodes").isNotEmpty() ||
                       url.contains("/series/") || 
                       url.contains("/tv/")

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            
            document.select(".entry-content a[href*='episode'], .episodes-list a, ul.episodes li").forEachIndexed { index, epLink ->
                val targetElement = if (epLink.tagName() == "li") epLink.selectFirst("a") else epLink
                val epHref = fixUrlNull(targetElement?.attr("href")) ?: return@forEachIndexed
                val epName = targetElement?.text()?.ifEmpty { "Episode ${index + 1}" } ?: "Episode ${index + 1}"
                
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
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
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

        document.select("a[href*='gdrive'], a[href*='stream'], a.maxbutton, .entry-content a.btn, iframe").forEach { link ->
            val targetUrl = fixUrlNull(if (link.tagName() == "iframe") link.attr("src") else link.attr("href")) ?: return@forEach
            loadExtractor(targetUrl, data, subtitleCallback, callback)
        }

        return true
    }
}
