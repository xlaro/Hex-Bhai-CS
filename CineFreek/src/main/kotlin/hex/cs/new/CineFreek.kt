package hex.cs.new

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile

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
        val items = CineFreekParser.parseSearchItems(document)

        val homePageItems = items.map { item ->
            if (item.tvType == TvType.TvSeries) {
                newTvSeriesSearchResponse(item.title, item.url, item.tvType) {
                    this.posterUrl = item.posterUrl
                }
            } else {
                newMovieSearchResponse(item.title, item.url, item.tvType) {
                    this.posterUrl = item.posterUrl
                }
            }
        }

        return newHomePageResponse(
            list = HomePageList("Latest Releases", homePageItems),
            hasNext = document.select("a.next, .nav-links a:contains(Next), a.next.page-numbers").isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        val items = CineFreekParser.parseSearchItems(document)

        return items.map { item ->
            if (item.tvType == TvType.TvSeries) {
                newTvSeriesSearchResponse(item.title, item.url, item.tvType) {
                    this.posterUrl = item.posterUrl
                }
            } else {
                newMovieSearchResponse(item.title, item.url, item.tvType) {
                    this.posterUrl = item.posterUrl
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val details = CineFreekParser.parseDetails(document, url)

        return if (details.isSeries) {
            val episodes = mutableListOf<Episode>()
            val epElements = document.select(".entry-content a[href*='episode'], .episodes-list a, ul.episodes li, .download-links-div .movie-title")

            epElements.forEachIndexed { index, epEl ->
                val epHref = epEl.selectFirst("a")?.attr("href") ?: epEl.attr("href") ?: url
                val epName = epEl.text().ifEmpty { "Episode ${index + 1}" }

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epName
                        this.episode = index + 1
                    }
                )
            }

            newTvSeriesLoadResponse(details.title, url, TvType.TvSeries, episodes) {
                this.posterUrl = details.posterUrl
                this.plot = details.plot
                this.year = details.year
                details.rating?.let { this.score = Score.from10(it.toFloat()) }
                this.tags = details.tags
                this.backgroundPosterUrl = details.screenshots.firstOrNull()
            }
        } else {
            newMovieLoadResponse(details.title, url, TvType.Movie, url) {
                this.posterUrl = details.posterUrl
                this.plot = details.plot
                this.year = details.year
                details.rating?.let { this.score = Score.from10(it.toFloat()) }
                this.tags = details.tags
                this.backgroundPosterUrl = details.screenshots.firstOrNull()
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
            if (href.isNotBlank()) {
                CineFreekExtractor.extractLink(
                    rawHref = href,
                    linkText = link.text(),
                    pageUrl = data,
                    apiName = this.name,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
        }

        return true
    }
}