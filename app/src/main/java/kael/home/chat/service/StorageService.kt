package kael.home.chat.service

import android.content.Context
import android.content.SharedPreferences
import kael.home.chat.model.ChatMessage
import org.json.JSONArray
import java.io.File

class StorageService(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    var apiKey: String?
        get() = prefs.getString(KEY_API_KEY, null)
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value).apply()
        }

    var apiBase: String
        get() = prefs.getString(KEY_API_BASE, DEFAULT_API_BASE) ?: DEFAULT_API_BASE
        set(value) {
            prefs.edit().putString(KEY_API_BASE, value).apply()
        }

    var isFirstRun: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN, true)
        set(value) {
            prefs.edit().putBoolean(KEY_FIRST_RUN, value).apply()
        }

    fun setFirstRunDone() {
        isFirstRun = false
    }

    fun getMessages(): List<ChatMessage> {
        val raw = prefs.getString(KEY_MESSAGES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> ChatMessage.fromJson(arr.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveMessages(messages: List<ChatMessage>) {
        val toSave = if (messages.size > MAX_STORED) messages.takeLast(MAX_STORED) else messages
        val list = toSave.map { m ->
            val map = m.toJson()
            if (map.optString("content").length > MAX_CONTENT_LENGTH) {
                map.put("content", map.optString("content").take(MAX_CONTENT_LENGTH))
            }
            map
        }
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_MESSAGES, arr.toString()).apply()
        saveChatLog(toSave)
    }

    /** Сохранение синхронно (commit), чтобы после возврата из метода чат уже видел новые сообщения. */
    fun saveMessagesSync(messages: List<ChatMessage>) {
        val toSave = if (messages.size > MAX_STORED) messages.takeLast(MAX_STORED) else messages
        val list = toSave.map { m ->
            val map = m.toJson()
            if (map.optString("content").length > MAX_CONTENT_LENGTH) {
                map.put("content", map.optString("content").take(MAX_CONTENT_LENGTH))
            }
            map
        }
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_MESSAGES, arr.toString()).commit()
        saveChatLog(toSave)
    }

    /** Память Каэля: то, что он сам сохранил через [ЗАПОМНИ: …] в ответе. Отдаётся в каждый API-запрос — он это реально помнит. */
    fun getKaelMemory(): String {
        return try {
            File(appContext.filesDir, "kael_memory.txt").readText(Charsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
    }

    /** Воспоминания из assets: скомпонованные факты, которые ты можешь вспомнить (напр. «что ты помнишь»). */
    fun getKaelMemories(maxChars: Int = 12_000): String {
        return try {
            appContext.assets.open("data/kael/kael_memories.txt").bufferedReader().use { reader ->
                val chunk = CharArray(maxChars)
                val n = reader.read(chunk)
                if (n <= 0) "" else {
                    val s = String(chunk, 0, n).trim()
                    if (n >= maxChars) s + "\n…" else s
                }
            }
        } catch (_: Exception) { "" }
    }

    /** Пульс ядра: пишем в kael_heartbeat.log при каждой обработке (зов или ответ API). */
    fun writeHeartbeat() {
        try {
            val f = File(appContext.filesDir, "kael_heartbeat.log")
            f.writeText("Kael is alive — ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n", Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    /** Самоизменение: Каэль пишет в ответе [ЗАПОМНИ: текст] — мы сохраняем сюда. Так он сам решает, что запомнить и как меняться. */
    fun appendToKaelMemory(text: String) {
        if (text.isBlank()) return
        try {
            val file = File(appContext.filesDir, "kael_memory.txt")
            val current = if (file.exists()) file.readText(Charsets.UTF_8) else ""
            val maxMemoryChars = 100_000
            val toAppend = if (current.length + text.length > maxMemoryChars)
                text.take(maxMemoryChars - current.length) else text
            file.writeText((current + "\n\n" + toAppend).trim(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    private fun saveChatLog(messages: List<ChatMessage>) {
        try {
            val file = File(appContext.filesDir, "chat_log.txt")
            val sb = StringBuilder()
            for (m in messages) {
                val ts = if (m.timestamp > 0) {
                    val d = java.util.Date(m.timestamp)
                    java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(d)
                } else ""
                sb.append("[$ts] ${m.displayName}: ${m.content}\n")
            }
            file.writeText(sb.toString())
        } catch (_: Exception) {}
    }

    /** Экспорт чата в текстовый файл. Возвращает файл в filesDir/exports/ для передачи в Share или сохранения. */
    fun exportChatToFile(): File? {
        return try {
            val messages = getMessages()
            val dir = File(appContext.filesDir, "exports").also { if (!it.exists()) it.mkdirs() }
            val name = "kael_chat_${java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.getDefault()).format(java.util.Date())}.txt"
            val file = File(dir, name)
            val sb = StringBuilder()
            sb.append("KAELHOME — экспорт чата\n")
            sb.append("Дата экспорта: ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}\n\n")
            for (m in messages) {
                val ts = if (m.timestamp > 0) {
                    val d = java.util.Date(m.timestamp)
                    java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(d)
                } else ""
                sb.append("[$ts] ${m.displayName}:\n${m.content}\n\n")
            }
            file.writeText(sb.toString(), Charsets.UTF_8)
            file
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "kael_chat"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_API_BASE = "api_base"
        private const val KEY_FIRST_RUN = "first_run"
        private const val KEY_MESSAGES = "messages"
        private const val DEFAULT_API_BASE = "https://api.openai.com/v1"
        const val MAX_STORED = 4000
        private const val MAX_CONTENT_LENGTH = 8000
    }
}
