import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Senpou Manhwa-Latino"
    // Senpou: HTML-only rate limit 2/2s (unlimited images) to avoid Popular 429
    versionCode = 18
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"
    theme = "madara"

    source {
        lang = "es"
        baseUrl = "https://manhwa-latino.com"
    }
}
