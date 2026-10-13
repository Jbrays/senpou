package keiyoushi.network

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

/** Search route shared by Manhwa-Latino and Manhwa-ES. */
object ManhwaSearch {
    // Route supplied from both websites on 2026-10-13. This token can rotate.
    private const val TOKEN = "8864a5c8"

    fun request(baseUrl: String, headers: Headers, page: Int, query: String): Request {
        val slug = Normalizer.normalize(query.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace("\\s+".toRegex(), "-")
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addPathSegment(TOKEN)
            .addPathSegment(slug)
            .apply {
                if (page > 1) {
                    addPathSegment("page")
                    addPathSegment(page.toString())
                }
            }
            .addPathSegment("")
            .build()
        return Request.Builder().url(url).headers(headers).build()
    }

    /** Caller owns a successful response; HTTP failures must never become empty results. */
    fun execute(client: OkHttpClient, request: Request): Response {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val message = if (response.code == 403) {
                "HTTP 403: abre la fuente en WebView y completa el desafío si aparece."
            } else {
                "HTTP ${response.code} al buscar en ${request.url.host}"
            }
            response.close()
            throw IOException(message)
        }
        return response
    }
}
