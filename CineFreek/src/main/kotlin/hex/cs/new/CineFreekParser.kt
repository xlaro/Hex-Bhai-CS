package hex.cs.new

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.Qualities
import hex.cs.new.models.CineMediaDetails
import hex.cs.new.models.CineMediaItem
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object CineFreekParser {

    private const val HOME_SELECTORS = "a.movie-card, article.post-item, div.ml-item, div.content-main article, div.poster-container"

    fun parseSearchItems(document: Document): List<CineMediaItem> {
        return document.select(HOME_SELECTORS).mapNotNull { element ->
            parseMediaItem(element)
        }.distinctBy { it.url }
    }

    private fun parseMediaItem(element: Element): CineMediaItem? {
        val href = fixUrlNull(
            element.attr("href").ifEmpty { element.selectFirst("a")?.attr("href") }
        ) ?: return null

        val title = element.select("h3.movie-card-title, h2, h3, .entry-title, a.title").text().trim().ifEmpty {
            element.attr("aria-label").replace(" details", "", ignoreCase = true).trim().ifEmpty {
                element.select("img").attr("alt").trim()
            }
        }
        if (title.isEmpty()) return null

        // Handles fallback images (Lazy load / data-src / srcset)
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

        return CineMediaItem(title, href, posterUrl, tvType)
    }

    fun parseDetails(document: Document, pageUrl: String): CineMediaDetails {
        val rawTitle = document.select("h1.page-title, h1.entry-title, h1.title").text().trim()
        val title = if (rawTitle.isNotEmpty()) {
            rawTitle.replace(Regex("(?i)\\|.*|\\–.*|Download.*"), "").trim()
        } else {
            document.select("title").text().split("|").firstOrNull()?.trim() ?: "Unknown"
        }

        val poster = fixUrlNull(
            document.select(".poster-container img, .poster-image img, .entry-content img, article img")
                .firstOrNull { 
                    it.attr("src").contains("tmdb.org") || 
                    it.attr("src").contains("cineimg") || 
                    it.hasClass("wp-post-image") 
                }?.attr("src")
                ?: document.select(".poster-container img, .poster-image img, article img").attr("src")
        )

        val bodyText = document.text()
        val ratingMatch = Regex("IMDb Rating:?\\s*([0-9\\.]+)/10").find(bodyText)
        val rating = ratingMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull()

        val yearMatch = Regex("(19|20)\\d{2}").find(title) ?: Regex("(19|20)\\d{2}").find(bodyText)
        val year = yearMatch?.value?.toIntOrNull()

        val plot = document.select(".entry-content p:contains(Plot), .entry-content p:contains(Storyline), .synopsis, .description")
            .firstOrNull()?.text()?.replace(Regex("(?i)Plot Summary / Storyline:?"), "")?.trim()
            ?: document.select(".entry-content p").firstOrNull { it.text().length > 30 }?.text()?.trim()

        val tags = document.select(".breadcrumb-separator ~ a, .badge a, .entry-content a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val screenshots = document.select(".screenshot-container img, .screenshot-item img").mapNotNull {
            fixUrlNull(it.attr("src").ifEmpty { it.attr("data-src") })
        }

        val isSeries = title.contains("Season", ignoreCase = true) ||
                document.select(".episode-list, a[href*='episode'], ul.episodes, .dlbtn-container:contains(Episode)").isNotEmpty() ||
                pageUrl.contains("/series/") ||
                pageUrl.contains("/tv/")

        return CineMediaDetails(
            title = title,
            posterUrl = poster,
            plot = plot,
            year = year,
            rating = rating,
            tags = tags,
            screenshots = screenshots,
            isSeries = isSeries
        )
    }

    fun parseQualityFromName(name: String): Int {
        return when {
            name.contains("2160p") || name.contains("4K") -> Qualities.P2160.value
            name.contains("1080p") -> Qualities.P1080.value
            name.contains("720p") -> Qualities.P720.value
            name.contains("480p") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return "https://cinefreak.net$url"
        return url
    }
}