package app.senpou.extension.es.manhwaes

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

/** Mirror of Manhwa-Latino search strategy. */
@Source
abstract class ManhwaEs : Madara() {
    override val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es"))

    /**
     * Search notes (from user + failed server-side probes):
     * - Classic `?s=&post_type=wp-manga` → 410 on site
     * - Pretty `/search/{token}/{query}/` works in browser (user-confirmed URL)
     * - From this build machine Cloudflare blocks everything with 403, so endpoints
     *   cannot be validated here; the phone has CF cookies and can reach browse.
     * Strategy order on device: pretty URL → live AJAX title search → load_more AJAX
     * → short list-title scan.
     */
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val sendViewCount = false
    override val fetchGenres = false

    /** From website search rewrite, e.g. /search/1788a865/cunada/ */
    private val searchPathToken = "1788a865"

    private val searchPageSize = 24
    private val listPagesToScan = 4

    // Original Keiyoushi client: 1 request every 2s + image Content-Type fix
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

    private val searchHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Primary URL shape used by the website UI.
        return prettySearchRequest(page, query)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return fetchPopularManga(page)
        }

        return Observable.fromCallable {
            // 1) Pretty search URL (what the browser uses)
            tryHtmlSearch(prettySearchRequest(page, trimmed), page)?.let { return@fromCallable it }

            // 2) Madara live title search (JSON/HTML autocomplete endpoint)
            tryLiveTitleSearch(trimmed)?.let { return@fromCallable it }

            // 3) madara_load_more content-search
            tryLoadMoreSearch(page, trimmed, filters)?.let { return@fromCallable it }

            // 4) Last resort: titles currently on latest/popular pages
            scanListsForQuery(trimmed, page)
        }
    }

    private fun prettySearchRequest(page: Int, query: String): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("search")
            addPathSegment(searchPathToken)
            addPathSegment(query)
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
            addPathSegment("")
        }.build()
        return GET(url, searchHeaders)
    }

    private fun tryHtmlSearch(request: Request, page: Int): MangasPage? {
        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val document = response.asJsoup()
            val entries = parseSearchEntries(document)
            if (entries.isEmpty() && page == 1) {
                // Successful HTTP but no items we could parse → try next strategy
                return null
            }
            val hasNext = entries.isNotEmpty() && (
                document.selectFirst("div.nav-previous, a.nextpostslink, .pagination .next, a.next.page-numbers") != null ||
                    document.selectFirst("div.pagination > span.current + a, div.pagination > span.current + span") != null
                )
            MangasPage(entries, hasNext)
        } catch (_: Exception) {
            null
        }
    }

    /** WP-Manga live search used by many Madara themes (autocomplete). */
    private fun tryLiveTitleSearch(query: String): MangasPage? {
        return try {
            val body = FormBody.Builder()
                .add("action", "wp-manga-search-manga")
                .add("title", query)
                .build()
            val response = client.newCall(
                POST("$baseUrl/wp-admin/admin-ajax.php", xhrHeaders, body),
            ).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val text = response.body.string()
            if (text.isBlank() || text == "0" || text == "[]") {
                return null
            }

            // Some sites return JSON array of {title, url, type}
            if (text.trimStart().startsWith("[")) {
                return parseLiveSearchJson(text)
            }

            val document = Jsoup.parse(text, baseUrl)
            val entries = parseSearchEntries(document)
            if (entries.isEmpty()) null else MangasPage(entries, false)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLiveSearchJson(text: String): MangasPage? = try {
        // Minimal JSON parse without assuming full kotlinx setup shape
        // Expected-ish: [{"title":"...","url":"..."}] or with "data"
        val entries = mutableListOf<SManga>()
        val objectRegex = Regex("""\{[^{}]+\}""")
        for (obj in objectRegex.findAll(text)) {
            val chunk = obj.value
            val title = Regex(""""(?:title|name)"\s*:\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1)
                ?: continue
            val url = Regex(""""(?:url|link|permalink)"\s*:\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1)
                ?: continue
            entries += SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(url.replace("\\/", "/"))
            }
        }
        if (entries.isEmpty()) null else MangasPage(entries.distinctBy { it.url }, false)
    } catch (_: Exception) {
        null
    }

    private fun tryLoadMoreSearch(page: Int, query: String, filters: FilterList): MangasPage? {
        return try {
            val response = client.newCall(searchLoadMoreRequest(page, query, filters)).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val body = response.body.string()
            if (body.isBlank() || body == "0") {
                return null
            }
            val document = Jsoup.parse(body, baseUrl)
            val entries = parseSearchEntries(document)
            if (entries.isEmpty() && page == 1) return null
            MangasPage(entries, entries.isNotEmpty() && document.selectFirst(".no-posts") == null)
        } catch (_: Exception) {
            null
        }
    }

    /** Try several Madara/search result layouts. */
    private fun parseSearchEntries(document: Document): List<SManga> {
        val selectors = listOf(
            searchMangaSelector(),
            popularMangaSelector(),
            "div.c-tabs-item__content",
            "div.page-item-detail",
            "div.row.c-tabs-item div",
            ".manga__item",
            "div.post-title",
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
