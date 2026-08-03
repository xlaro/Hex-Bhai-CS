android {
    namespace = "hex.cs.provider"

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

version = 1

cloudstream {
    description = "CineFreek Provider"
    authors = listOf("Hex")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "AsianDrama")
    requiresResources = false
    language = "en"
    iconUrl = "https://cinefreak.net/wp-content/uploads/2024/08/CineFeak.webp"
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}