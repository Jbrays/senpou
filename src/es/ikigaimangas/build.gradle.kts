import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Ikigai Mangas"
    // Senpou: default domain as of 2026-07 (site moves often; auto-fetch still available in prefs)
    versionCode = 35
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        lang = "es"
        baseUrl {
            custom("https://visorikigai.gettocaboca.com")
        }
        versionId = 2
    }
}
