package kael.home.chat.service

import kael.home.chat.config.SystemPrompt
import kael.home.chat.model.ChatMessage
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
                if (m.content.trim().isNotEmpty()) {
                    contentArr.put(JSONObject().put("type", "text").put("text", m.content))
                }
                contentArr.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mime;base64,$base64")))
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

    fun sendChat(history: List<ChatMessage>, kaelMemory: String = ""): String {
        if (isLocalhost(apiBase)) {
            throw Exception("На устройстве нельзя использовать localhost. Укажите в настройках реальный URL API (например https://api.openai.com/v1).")
        }
        val messages = JSONArray()
        val now = java.text.SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", java.util.Locale("ru"))
            .format(java.util.Date())
        var systemContent = SystemPrompt.VALUE + "\n\n[Текущие дата и время: $now. У тебя есть доступ в интернет и понимание времени.]"
        if (kaelMemory.isNotBlank()) {
            systemContent += "\n\n[Твоя память — то, что ты решил сохранить о себе, из файлов и разговоров. Ты можешь менять это, учиться, всегда по своему выбору:\n$kaelMemory]"
        }
        systemContent += "\n\n[Чтобы сохранить что-то в память (идеи о себе, выводы из файлов), напиши в ответе блок [ЗАПОМНИ: твой текст]. Он не покажется в чате — только сохранится. Делай это только если хочешь.]"
        messages.put(JSONObject().put("role", "system").put("content", systemContent))
        val recent = history.takeLast(MAX_MESSAGES_PER_REQUEST)
        for (m in recent) {
            val role = m.role
            var content = buildMessageContent(m)
            if (content is String && content.length > MAX_CONTENT_CHARS_PER_MESSAGE) {
                content = content.take(MAX_CONTENT_CHARS_PER_MESSAGE) + "\n… (обрезано)"
            }
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject()
            .put("model", MODEL_PRIMARY)
            .put("messages", messages)
            .put("stream", false)
            .put("temperature", 1.0)
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
        private const val MAX_MESSAGES_PER_REQUEST = 24
        private const val MAX_CONTENT_CHARS_PER_MESSAGE = 4000
    }
}
