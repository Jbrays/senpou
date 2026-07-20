package app.senpou.extension.es.manhwaes

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * Mirror / clone stack of Manhwa-Latino (Madara).
 * Kept as a separate extension so both domains can be used at once.
 */
@Source
abstract class ManhwaEs : Madara() {
    override val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es"))

    // Same custom search path as Manhwa-Latino (classic Madara search → 410).
    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override val sendViewCount = false

    private val searchPathToken = "1788a865"

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return popularMangaRequest(page)
        }

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("search")
            addPathSegment(searchPathToken)
            addPathSegment(trimmed)
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
            addPathSegment("")
        }.build()

        return GET(url, headers)
    }

    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()

            val isImageRequest = request.url.toString().substringBefore("?").let {
                it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) ||
                    it.endsWith(".png", true) || it.endsWith(".webp", true)
            }

            val newRequest = if (isImageRequest) {
                request.newBuilder().removeHeader("Accept-Encoding").build()
            } else {
                request
            }

            var attempt = 0
            var response = chain.proceed(newRequest)
            while (response.code == 429 && attempt < 3) {
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                response.close()
                val waitMs = ((retryAfter ?: 0L).coerceIn(0L, 30L) * 1000L)
                    .coerceAtLeast((attempt + 1) * 2500L)
                try {
                    Thread.sleep(waitMs)
                } catch (_: InterruptedException) {
                    break
                }
                attempt++
                response = chain.proceed(newRequest)
            }

            if (isImageRequest && response.header("Content-Type")?.contains("application/octet-stream", true) == true) {
                val orgBody = response.body
                val newBody = orgBody.source().asResponseBody("image/jpeg".toMediaType())
                return@addInterceptor response.newBuilder()
                    .header("Content-Type", "image/jpeg")
                    .body(newBody)
                    .build()
            }

            return@addInterceptor response
        }
        .rateLimit(1, 4.seconds)
        .build()

    override val useNewChapterEndpoint = true

    override val chapterUrlSelector = "div.mini-letters > a"

    override val mangaDetailsSelectorStatus = "div.post-content_item:contains(Estado del comic) > div.summary-content"
    override val mangaDetailsSelectorDescription = "div.post-content_item:contains(Resumen) div.summary-container"
    override val pageListParseSelector = "div.page-break img.wp-manga-chapter-img"

    private val chapterListNextPageSelector = "div.pagination > span.current + span"

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaUrl = response.request.url
        var document = response.asJsoup()
        launchIO { countViews(document) }

        val chapterList = mutableListOf<SChapter>()
        var page = 1

        do {
            val chapterElements = document.select(chapterListSelector())
            if (chapterElements.isEmpty()) break
            chapterList.addAll(chapterElements.map { chapterFromElement(it) })

            val hasNextPage = document.selectFirst(chapterListNextPageSelector) != null
            if (hasNextPage) {
                page++
                val nextPageUrl = mangaUrl.newBuilder().setQueryParameter("t", page.toString()).build()
                document = client.newCall(GET(nextPageUrl, headers)).execute().asJsoup()
            } else {
                break
            }
        } while (true)

        return chapterList
    }

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()

        with(element) {
            selectFirst(chapterUrlSelector)!!.let { urlElement ->
                chapter.url = urlElement.attr("abs:href").let {
                    it.substringBefore("?style=paged") + if (!it.endsWith(chapterUrlSuffix)) chapterUrlSuffix else ""
                }
                chapter.name = urlElement.wholeText().substringAfter("\n").trim()
            }

            chapter.date_upload = selectFirst("img:not(.thumb)")?.attr("alt")?.let { parseRelativeDate(it) }
                ?: selectFirst("span a")?.attr("title")?.let { parseRelativeDate(it) }
                ?: parseChapterDate(selectFirst(chapterDateSelector())?.text())
        }

        return chapter
    }
}
