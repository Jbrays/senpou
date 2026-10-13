package app.senpou.extension.es.manhwalatino

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.ManhwaSearch
import keiyoushi.network.rateLimit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class ManhwaLatino : Madara() {
    override val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es"))

    // These sites replaced classic WordPress search with /search/{token}/{query}/.
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val sendViewCount = false
    override val fetchGenres = false

    // Original Keiyoushi client: 1 request every 2s + image Content-Type fix
    override val client: OkHttpClient by lazy {
        super.client.newBuilder()
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
    }

    private val searchHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Primary URL shape used by the website UI.
        return prettySearchRequest(page, query.trim())
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return fetchPopularManga(page)
        }

        // Propagate HTTP/Cloudflare errors. A failed request is not an empty search.
        return Observable.fromCallable {
            ManhwaSearch.execute(client, prettySearchRequest(page, trimmed)).use(::searchMangaParse)
        }
    }

    private fun prettySearchRequest(page: Int, query: String): Request = ManhwaSearch.request(baseUrl, searchHeaders, page, query)

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val entries = parseSearchEntries(document)
        val hasNext = entries.isNotEmpty() && (
            document.selectFirst("div.nav-previous, a.nextpostslink, .pagination .next, a.next.page-numbers") != null ||
                document.selectFirst("div.pagination > span.current + a, div.pagination > span.current + span") != null
            )
        return MangasPage(entries, hasNext)
    }

    /** Try several Madara/search result layouts. */
    private fun parseSearchEntries(document: Document): List<SManga> {
        val selectors = listOf(
            searchMangaSelector(),
            popularMangaSelector(),
            "div.c-tabs-item__content",
            "div.page-item-detail",
            ".manga__item",
        )
        for (selector in selectors) {
            val elements = document.select(selector)
            if (elements.isEmpty()) continue
            val parsed = elements.mapNotNull { el ->
                runCatching {
                    when {
                        el.selectFirst("div.post-title a, h3 a, h5 a, a") != null &&
                            (
                                el.hasClass("c-tabs-item__content") || el.hasClass("page-item-detail") ||
                                    el.hasClass("manga__item") || el.selectFirst(".post-title") != null
                                ) -> {
                            // Prefer Madara helpers when structure matches
                            runCatching { searchMangaFromElement(el) }.getOrElse {
                                runCatching { popularMangaFromElement(el) }.getOrElse {
                                    mangaFromLooseElement(el)
                                }
                            }
                        }
                        else -> mangaFromLooseElement(el)
                    }
                }.getOrNull()
            }.filter { it.title.isNotBlank() && it.url.isNotBlank() }
                .distinctBy { it.url }
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun mangaFromLooseElement(element: Element): SManga? {
        val a = element.selectFirst("div.post-title a, h3 a, h5 a, a[href*=/manga/], a[href*=/manhwa/], a[href*=/comic/], a")
            ?: return null
        val href = a.attr("abs:href").ifBlank { return null }
        val title = a.text().ifBlank { a.attr("title") }.ifBlank { return null }
        return SManga.create().apply {
            setUrlWithoutDomain(href)
            this.title = title
            thumbnail_url = element.selectFirst("img")?.let { imageFromElement(it) }
        }
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
