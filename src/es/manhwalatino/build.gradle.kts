import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Senpou Manhwa-Latino"
    // Senpou: restore original Keiyoushi rateLimit(1, 2.seconds)
    versionCode = 22
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"
    theme = "madara"

    source {
        lang = "es"
        baseUrl = "https://manhwa-latino.com"
    }
}
