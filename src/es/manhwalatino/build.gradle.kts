import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Senpou Manhwa-Latino"
    // Senpou: drop artificial rate limit (problem was bad search URLs, not volume)
    versionCode = 17
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"
    theme = "madara"

    source {
        lang = "es"
        baseUrl = "https://manhwa-latino.com"
    }
}
