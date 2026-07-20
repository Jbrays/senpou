import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Senpou Manhwa-ES"
    // Senpou: slower rate limit + no view-count POSTs (HTTP 429)
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"
    theme = "madara"

    source {
        lang = "es"
        baseUrl = "https://manhwa-es.com"
    }
}
