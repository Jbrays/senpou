package app.senpou.extension.es.manhwaes

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
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
import rx.Observable
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

/**
 * Mirror / clone stack of Manhwa-Latino (Madara).
 * Same search strategy: native /search/… often 429 in-app; fall back to list scan.
 */
@Source
abstract class ManhwaEs : Madara() {
    override val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es"))

    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val sendViewCount = false
    override val fetchGenres = false

    private val searchPathToken = "1788a865"
    private val searchPageSize = 24
    private val listPagesToScan = 6

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

            val response = chain.proceed(newRequest)

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
        .rateLimit(1, 2.seconds)
        .build()

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

        val searchHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        return GET(url, searchHeaders)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return fetchPopularManga(page)
        }

        return Observable.fromCallable {
            tryNativeSearch(page, trimmed, filters)?.let { return@fromCallable it }
            scanListsForQuery(trimmed, page)
        }
    }

    private fun tryNativeSearch(page: Int, query: String, filters: FilterList): MangasPage? {
        return try {
            val response = client.newCall(searchMangaRequest(page, query, filters)).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            searchMangaParse(response)
        } catch (_: Exception) {
            null
        }
    }

    private fun scanListsForQuery(query: String, page: Int): MangasPage {
        val needle = normalize(query)
        val found = linkedMapOf<String, SManga>()

        fun ingest(request: Request) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return
                    val document = response.asJsoup()
                    document.select(popularMangaSelector()).forEach { element ->
                        val manga = runCatching { popularMangaFromElement(element) }.getOrNull() ?: return@forEach
                        if (normalize(manga.title).contains(needle)) {
                            found.putIfAbsent(manga.url, manga)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        for (p in 1..listPagesToScan) {
            ingest(latestUpdatesRequest(p))
        }
        for (p in 1..listPagesToScan) {
            ingest(popularMangaRequest(p))
        }

        val all = found.values.toList()
        val from = (page - 1) * searchPageSize
        if (from >= all.size) {
            return MangasPage(emptyList(), false)
        }
        val to = min(from + searchPageSize, all.size)
        return MangasPage(all.subList(from, to), to < all.size)
    }

    private fun normalize(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return decomposed.replace("\\p{Mn}+".toRegex(), "")
    }

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
