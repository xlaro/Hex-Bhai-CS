package hex.cs.new.models

import com.lagradost.cloudstream3.TvType

data class CineMediaItem(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val tvType: TvType
)

data class CineMediaDetails(
    val title: String,
    val posterUrl: String?,
    val plot: String?,
    val year: Int?,
    val rating: Double?,
    val tags: List<String>,
    val screenshots: List<String>,
    val isSeries: Boolean
)