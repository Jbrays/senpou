package keiyoushi.network

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ManhwaSearchTest {
    private val headers = Headers.Builder().add("Referer", "https://manhwa-es.com/").build()

    @Test
    fun buildsBothUserSuppliedSearchUrls() {
        for (domain in listOf("manhwa-latino.com", "manhwa-es.com")) {
            val request = ManhwaSearch.request("https://$domain", headers, 1, " ajumma ")
            assertEquals("https://$domain/search/8864a5c8/ajumma/", request.url.toString())
            assertEquals(headers, request.headers)
        }
    }

    @Test
    fun normalizesWordsAndPaginates() {
        val request = ManhwaSearch.request("https://manhwa-es.com", headers, 2, " Mi  Cuñada ")
        assertEquals("https://manhwa-es.com/search/8864a5c8/mi-cunada/page/2/", request.url.toString())
    }

    @Test
    fun queryCannotChangeUrlStructure() {
        val request = ManhwaSearch.request("https://manhwa-es.com", headers, 1, "a/b?c#d")
        assertEquals(listOf("search", "8864a5c8", "a/b?c#d", ""), request.url.pathSegments)
        assertEquals(null, request.url.query)
        assertEquals("", request.url.fragment.orEmpty())
    }

    @Test
    fun propagatesHttpFailures() {
        val request = ManhwaSearch.request("https://manhwa-es.com", headers, 1, "ajumma")
        for (code in listOf(403, 404, 410, 500)) {
            val client = client(code, "Blocked")
            val error = assertThrows(IOException::class.java) { ManhwaSearch.execute(client, request) }
            assertTrue(error.message.orEmpty().contains("HTTP $code"))
        }
    }

    @Test
    fun preservesSuccessfulResponseForParsing() {
        val request = ManhwaSearch.request("https://manhwa-latino.com", headers, 1, "ajumma")
        val html = "<div class='c-tabs-item__content'>Ajumma</div>"
        ManhwaSearch.execute(client(200, html), request).use {
            assertEquals(html, it.body.string())
        }
    }

    @Test
    fun propagatesConnectionFailures() {
        val request = ManhwaSearch.request("https://manhwa-es.com", headers, 1, "ajumma")
        val client = OkHttpClient.Builder().addInterceptor { throw IOException("Offline") }.build()
        val error = assertThrows(IOException::class.java) { ManhwaSearch.execute(client, request) }
        assertEquals("Offline", error.message)
    }

    private fun client(code: Int, html: String) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("Test").body(html.toResponseBody()).build()
    }.build()
}
