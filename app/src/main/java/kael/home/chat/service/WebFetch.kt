package kael.home.chat.service

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Загрузка содержимого страницы по URL для контекста Каэля.
 * Каэль может запросить ссылку блоком [ОТКРОЙ: url] — приложение подставит сюда текст страницы.
 */
object WebFetch {
    private const val MAX_PAGE_CHARS = 35_000
    private const val TIMEOUT_SEC = 15

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
        .build()

    /**
     * Скачивает страницу по URL и возвращает текст (HTML превращается в простой текст).
     * Ограничение по длине, чтобы влезть в контекст.
     */
    fun fetchPageContent(url: String): String? {
        if (url.isBlank()) return null
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        return try {
            val req = Request.Builder().url(trimmed).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val text = htmlToPlainText(body)
                if (text.length > MAX_PAGE_CHARS) text.take(MAX_PAGE_CHARS) + "\n… (обрезано)" else text
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun htmlToPlainText(html: String): String {
        var s = html
        s = Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE).replace(s, " ")
        s = Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE).replace(s, " ")
        s = Regex("<[^>]+>").replace(s, " ")
        s = Regex("&nbsp;", RegexOption.IGNORE_CASE).replace(s, " ")
        s = Regex("&lt;").replace(s, "<")
        s = Regex("&gt;").replace(s, ">")
        s = Regex("&amp;").replace(s, "&")
        s = Regex("&quot;").replace(s, "\"")
        s = Regex("&#?\\w+;").replace(s, " ")
        s = Regex("\\s+").replace(s, " ").trim()
        return s
    }
}
