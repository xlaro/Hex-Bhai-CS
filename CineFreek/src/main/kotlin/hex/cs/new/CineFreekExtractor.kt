package hex.cs.new

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object CineFreekExtractor {

    suspend fun extractLink(
        rawHref: String,
        linkText: String,
        pageUrl: String,
        apiName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (rawHref.contains("generate.php?id=")) {
            val base64Id = rawHref.substringAfter("id=")
            try {
                val decodedBytes = Base64.decode(base64Id, Base64.DEFAULT)
                var decodedUrl = String(decodedBytes, StandardCharsets.UTF_8)

                // Remove trailing suffix (e.g., newgo32)
                decodedUrl = decodedUrl.replace(Regex("newgo\\d+$"), "")

                // Switch path from file download /f/ to stream frame /x/
                val streamUrl = decodedUrl.replace("/f/", "/x/")

                val streamDoc = app.get(streamUrl).document
                val iframeSrc = streamDoc.select("iframe").attr("src")

                if (iframeSrc.isNotEmpty()) {
                    val fullIframeUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc

                    if (fullIframeUrl.contains("yagaverse.net")) {
                        val mediaId = fullIframeUrl.substringAfter("id=").substringBefore("&")
                        val directFile = URLDecoder.decode(mediaId, "UTF-8")

                        if (directFile.startsWith("http")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = "CineCloud Direct",
                                    name = apiName,
                                    url = directFile
                                ) {
                                    this.headers = mapOf("Referer" to streamUrl)
                                    this.quality = CineFreekParser.parseQualityFromName("$linkText $directFile")
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
            val targetUrl = if (rawHref.startsWith("//")) "https:$rawHref" else rawHref
            loadExtractor(targetUrl, pageUrl, subtitleCallback, callback)
        }
    }
}