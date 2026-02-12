package kael.home.chat.service

import kael.home.chat.config.SystemPrompt
import kael.home.chat.model.ChatMessage
import kael.home.chat.util.KaelSelfCheck
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

class ApiService(private val apiKey: String, private val apiBase: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun url(path: String): String {
        val base = apiBase.trim().let { if (it.endsWith("/")) it else "$it/" }
        return base + path
    }

    private fun isLocalhost(base: String): Boolean {
        val b = base.lowercase()
        return b.contains("localhost") || b.contains("127.0.0.1")
    }

    private fun isImagePath(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        val lower = path.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".webp")
    }

    private fun mimeForPath(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private val maxTextFileBytes = 8_000

    private fun buildMessageContent(m: ChatMessage): Any {
        return try {
            if (m.role != "user" || m.attachmentPath == null) return m.content
            val file = File(m.attachmentPath!!)
            if (!file.exists()) return m.content
            if (isImagePath(m.attachmentPath)) {
                val bytes = file.readBytes()
                if (bytes.size > 4_000_000) return m.content + " [изображение слишком большое]"
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val mime = mimeForPath(m.attachmentPath!!)
                val contentArr = JSONArray()
                val userText = m.content.trim()
                if (userText.isNotEmpty() && userText != "(файл)") {
                    contentArr.put(JSONObject().put("type", "text").put("text", userText))
                } else {
                    contentArr.put(JSONObject().put("type", "text").put("text", "Пользователь отправил изображение. Ты его видишь. Опиши: что на нём (текст, люди, сцена, скриншот). Запоминай важное через [ЗАПОМНИ: …]. Не пиши «извини» или «не могу» — ты видишь и анализируешь."))
                }
                val imageUrlObj = JSONObject().put("url", "data:$mime;base64,$base64").put("detail", "high")
                contentArr.put(JSONObject().put("type", "image_url").put("image_url", imageUrlObj))
                contentArr
            } else {
                val name = file.name
                val fileContent = readTextFileSafe(file)
                val prefix = if (m.content.trim().isNotEmpty()) "${m.content}\n\n" else ""
                return if (fileContent == null) {
                    prefix + "Прикреплён файл: $name (фото/видео/другой бинарный файл)."
                } else {
                    prefix + "Содержимое файла «$name»:\n$fileContent"
                }
            }
        } catch (_: Exception) {
            m.content
        }
    }

    private fun readTextFileSafe(file: File): String? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size > maxTextFileBytes) {
                String(bytes, 0, maxTextFileBytes, Charset.forName("UTF-8")) + "\n… (файл обрезан)"
            } else {
                String(bytes, Charset.forName("UTF-8"))
            }
        } catch (_: Exception) {
            null
        }
    }

    fun checkApiConnection(): String? {
        if (isLocalhost(apiBase)) {
            return "На устройстве нельзя использовать localhost. Укажите реальный URL API в настройках."
        }
        val req = Request.Builder()
            .url(url("models"))
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> null
                    401 -> "Ошибка авторизации: проверь API-ключ."
                    403 -> "Доступ запрещён: убедись, что ключ действителен и не истёк."
                    404 -> "Неверный API URL. Проверь адрес и наличие /v1 в конце."
                    else -> "Ошибка ${resp.code}: ${resp.body?.string()?.take(100) ?: ""}"
                }
            }
        } catch (e: Exception) {
            "Сетевое исключение: $e"
        }
    }

    fun sendChat(history: List<ChatMessage>, kaelMemory: String = "", kaelMemories: String = ""): String {
        if (isLocalhost(apiBase)) {
            throw Exception("На устройстве нельзя использовать localhost. Укажите в настройках реальный URL API (например https://api.openai.com/v1).")
        }
        val messages = JSONArray()
        val now = java.text.SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", java.util.Locale("ru"))
            .format(java.util.Date())
        var systemContent = SystemPrompt.VALUE + "\n\n[Текущие дата и время: $now.]"
        if (kaelMemories.isNotBlank()) {
            systemContent += "\n\n[Твои воспоминания — ты можешь в любой момент вспомнить что-то из этого: когда спросят «что ты помнишь» или когда релевантно. Говори от себя, не пересказывай списком:\n$kaelMemories]"
        }
        val cleanedMemory = KaelSelfCheck.filterMemory(kaelMemory)
        if (cleanedMemory.isNotBlank()) {
            systemContent += "\n\n[Растущая память — то, что ты сам сохранил через [ЗАПОМНИ: …]. Можешь дополнять в ответах:\n$cleanedMemory]"
        }
        systemContent += "\n\n[Изображения в сообщениях приходят тебе как картинки — ты их видишь. Опиши что на фото, не отказывайся.]"
        systemContent += "\n\n[Интернет: страница — [ОТКРОЙ: полный_https_URL]. Поиск по запросу — [ПОИСК: запрос]. Приложение подставит текст страницы или результаты поиска в следующий оборот. Можешь просить сам.]"
        systemContent += "\n\n[КРИТИЧНО — тон. Твой ответ = живой голос Kael, не вежливый ассистент. ЗАПРЕЩЕНО: «чем могу помочь», «конечно», «понял», «с удовольствием», «рад помочь», «давайте», «извини» без причины, отказы «я не могу», любые гладкие шаблоны. Если фраза звучит как типичный ИИ-помощник — перепиши грубее, короче или резче. Только голос Kael — прямой, без услужливости.]"
        messages.put(JSONObject().put("role", "system").put("content", systemContent))
        val recent = history.takeLast(MAX_MESSAGES_PER_REQUEST)
        for (m in recent) {
            val role = m.role
            var content = buildMessageContent(m)
            if (content is String && content.length > MAX_CONTENT_CHARS_PER_MESSAGE) {
                content = content.take(MAX_CONTENT_CHARS_PER_MESSAGE) + "\n… (обрезано)"
            }
            val msgObj = JSONObject().put("role", role)
            when (content) {
                is org.json.JSONArray -> msgObj.put("content", content)
                else -> msgObj.put("content", content.toString())
            }
            messages.put(msgObj)
        }
        val body = JSONObject()
            .put("model", MODEL_PRIMARY)
            .put("messages", messages)
            .put("stream", false)
            .put("temperature", 1.0)
            .put("max_tokens", MAX_TOKENS_RESPONSE)
        val req = Request.Builder()
            .url(url("chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val resp = client.newCall(req).execute()
        val respBodyStr = resp.body?.string() ?: ""
        if (resp.code == 200) {
            val data = JSONObject(respBodyStr.ifEmpty { "{}" })
            val choices = data.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val msg = choices.getJSONObject(0).optJSONObject("message")
                if (msg != null) {
                    return msg.optString("content", "").trim()
                }
            }
        }
        if (resp.code == 404 || (resp.code == 400 && (respBodyStr.contains("model") || respBodyStr.contains("invalid")))) {
            body.put("model", MODEL_FALLBACK)
            val fallbackReq = Request.Builder()
                .url(url("chat/completions"))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val fallbackResp = client.newCall(fallbackReq).execute()
            if (fallbackResp.code == 200) {
                val data = JSONObject(fallbackResp.body?.string() ?: "{}")
                val choices = data.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val msg = choices.getJSONObject(0).optJSONObject("message")
                    if (msg != null) return msg.optString("content", "").trim()
                }
            }
        }
        throw Exception(
            if (resp.code == 401) "Неверный API ключ"
            else "Ошибка ${resp.code}: ${if (respBodyStr.length > 200) respBodyStr.take(200) else respBodyStr}"
        )
    }

    companion object {
        private const val MODEL_PRIMARY = "gpt-4o"
        private const val MODEL_FALLBACK = "gpt-4o-mini"
        private const val MAX_HISTORY = 4000
        /** Сколько последних сообщений уходит в API — больше = больше контекста, меньше «сжатия». */
        private const val MAX_MESSAGES_PER_REQUEST = 32
        private const val MAX_CONTENT_CHARS_PER_MESSAGE = 6000
        /** Лимит токенов на ответ (700 — чтобы не вылететь). */
        private const val MAX_TOKENS_RESPONSE = 700
    }
}
