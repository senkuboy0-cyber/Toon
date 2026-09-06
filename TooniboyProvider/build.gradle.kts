plugins {
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.14.0")
}

cloudstream {
    language = "hi"
    authors = listOf("senkuboy0-cyber")
    description = "Tooniboy: The best place for Hindi & Multi-language Anime, Cartoons and Movies."
    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "Cartoon",
        "TvSeries",
        "Movie"
    )
    isCrossPlatform = false
    requiresResources = false
    iconUrl = "https://tooniboy.co/wp-content/uploads/2024/03/cropped-tooniboy-high-resolution-logo-transparent-7.png"
}
