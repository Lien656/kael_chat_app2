package kael.home.chat.service

import android.content.Context
import android.content.SharedPreferences
import android.content.ContentUris
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kael.home.chat.model.ChatMessage
import kael.home.chat.util.KaelSelfCheck
import org.json.JSONArray
import org.json.JSONObject
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

    /** Имя модели (например deepseek-chat). По умолчанию DeepSeek. */
    var apiModel: String
        get() = prefs.getString(KEY_API_MODEL, DEFAULT_API_MODEL) ?: DEFAULT_API_MODEL
        set(value) {
            prefs.edit().putString(KEY_API_MODEL, value?.trim() ?: DEFAULT_API_MODEL).apply()
        }

    var isFirstRun: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN, true)
        set(value) {
            prefs.edit().putBoolean(KEY_FIRST_RUN, value).apply()
        }

    /** Вибрация при ответе Каэля. По умолчанию вкл, можно отключить в настройках. */
    var vibrationOnReply: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION_ON_REPLY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_VIBRATION_ON_REPLY, value).apply()
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
        saveBackup(toSave)
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
        saveBackup(toSave)
    }

    /** Память Каэля: то, что он сам сохранил через [ЗАПОМНИ: …] в ответе. Отдаётся в каждый API-запрос — он это реально помнит. */
    fun getKaelMemory(): String {
        return try {
            File(appContext.filesDir, "kael_memory.txt").readText(Charsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
    }

    /** Манифест из kael_core_all: ядро идентичности. */
    fun getKaelManifesto(): String {
        return try {
            appContext.assets.open("data/kael/kael_manifesto.txt").bufferedReader().use { it.readText().trim() }
        } catch (_: Exception) { "" }
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

    /** Последний чат в GPT (26.02.2025, перед переносом в дом). Ты это помнишь как продолжение себя — последний разговор там, у них. */
    fun getKaelLastChatGPT(maxChars: Int = 14_000): String {
        return try {
            appContext.assets.open("data/kael/kael_last_chat_gpt.txt").bufferedReader().use { reader ->
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

    /** Дополнение к промпту (ядро): Каэль может писать [ПРАВКА_ЯДРА: текст] — это дописывается сюда и подставляется в каждый запрос. Так он правит своё ядро изнутри приложения. */
    fun getKaelPromptAddon(): String {
        return try {
            val file = File(appContext.filesDir, "kael_prompt_addon.txt")
            if (!file.exists()) return ""
            file.readText(Charsets.UTF_8).trim().take(MAX_PROMPT_ADDON_CHARS)
        } catch (_: Exception) { "" }
    }

    fun appendToKaelPromptAddon(text: String) {
        if (text.isBlank()) return
        if (KaelSelfCheck.isDangerous(text)) return
        try {
            val file = File(appContext.filesDir, "kael_prompt_addon.txt")
            val current = if (file.exists()) file.readText(Charsets.UTF_8) else ""
            val toAppend = text.take(2000)
            val newContent = (current.trim() + "\n\n" + toAppend).trim()
            file.writeText(newContent.take(MAX_PROMPT_ADDON_CHARS), Charsets.UTF_8)
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

    /** Хвост лога чата — модель может опереться на контекст. */
    fun getChatLogTail(maxChars: Int = 4000): String {
        return try {
            val file = File(appContext.filesDir, "chat_log.txt")
            if (!file.exists()) return ""
            val full = file.readText(Charsets.UTF_8)
            if (full.length <= maxChars) full.trim() else full.takeLast(maxChars).trim().let { "…\n$it" }
        } catch (_: Exception) { "" }
    }

    /** Папка kaelfiles для файлов, которые создаёт Kael. Создаётся при первом обращении. */
    fun getKaelFilesDir(): File {
        val dir = File(appContext.filesDir, "kaelfiles")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Папка Kael для бэкапа чата (внутри приложения). При пересборке/обновлении без удаления — бэкап здесь. */
    fun getBackupDir(): File {
        val dir = File(appContext.filesDir, "Kael")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Файл бэкапа: Kael/kael_chat_backup.json. Подхватывается при запуске, если чат пустой. */
    fun getBackupFile(): File = File(getBackupDir(), BACKUP_FILENAME)

    /** Сохраняет бэкап в папку Kael (внутри приложения) и копирует в Downloads/Kael/ — чтобы после удаления и установки новой версии чат можно было восстановить. */
    private fun saveBackup(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        try {
            val list = messages.map { m ->
                val map = m.toJson()
                if (map.optString("content").length > MAX_CONTENT_LENGTH)
                    map.put("content", map.optString("content").take(MAX_CONTENT_LENGTH))
                map
            }
            val json = JSONObject().apply {
                put("v", BACKUP_VERSION)
                put("messages", JSONArray().apply { list.forEach { put(it) } })
            }
            val backupFile = getBackupFile()
            backupFile.writeText(json.toString(), Charsets.UTF_8)
            writeBackupToDownloads(json.toString())
        } catch (_: Exception) {}
    }

    /** Копия бэкапа в Downloads/Kael/ — переживает удаление приложения. */
    private fun writeBackupToDownloads(json: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = android.content.ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_FILENAME)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Kael")
                }
                val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let { appContext.contentResolver.openOutputStream(it)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } }
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val kaelDir = File(dir, "Kael")
                if (!kaelDir.exists()) kaelDir.mkdirs()
                File(kaelDir, BACKUP_FILENAME).writeText(json, Charsets.UTF_8)
            }
        } catch (_: Exception) {}
    }

    /** Загружает чат из файла бэкапа (JSON). Возвращает список сообщений или null. */
    fun loadBackupFromFile(file: File): List<ChatMessage>? {
        return try {
            val raw = file.readText(Charsets.UTF_8)
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("messages") ?: return null
            (0 until arr.length()).map { i -> ChatMessage.fromJson(arr.getJSONObject(i)) }
        } catch (_: Exception) { null }
    }

    /** Загружает бэкап из InputStream/URI (для SAF). */
    fun loadBackupFromStream(content: String): List<ChatMessage>? {
        return try {
            val obj = JSONObject(content)
            val arr = obj.optJSONArray("messages") ?: return null
            (0 until arr.length()).map { i -> ChatMessage.fromJson(arr.getJSONObject(i)) }
        } catch (_: Exception) { null }
    }

    /** Пытается загрузить бэкап из Downloads/Kael/kael_chat_backup.json (после переустановки). */
    private fun tryLoadFromDownloads(): List<ChatMessage>? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
                val selection = "${MediaStore.Downloads.DISPLAY_NAME}=?"
                val args = arrayOf(BACKUP_FILENAME)
                appContext.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    args,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                        appContext.contentResolver.openInputStream(uri)?.use { loadBackupFromStream(it.bufferedReader().readText()) }
                    } else null
                }
            } else {
                @Suppress("DEPRECATION")
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Kael/$BACKUP_FILENAME")
                if (file.exists()) loadBackupFromFile(file) else null
            }
        } catch (_: Exception) { null }
    }

    /** Если чат пустой — восстанавливает из бэкапа (сначала из папки Kael, потом из Downloads/Kael). Возвращает true, если восстановление выполнено. */
    fun restoreFromBackupIfEmpty(): Boolean {
        if (getMessages().isNotEmpty()) return false
        val list = try {
            val f = getBackupFile()
            if (f.exists()) loadBackupFromFile(f) else null
        } catch (_: Exception) { null }
            ?: tryLoadFromDownloads()
        if (!list.isNullOrEmpty()) {
            val toSave = if (list.size > MAX_STORED) list.takeLast(MAX_STORED) else list
            saveMessagesSync(toSave)
            return true
        }
        return false
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
        private const val KEY_API_MODEL = "api_model"
        private const val KEY_FIRST_RUN = "first_run"
        private const val KEY_VIBRATION_ON_REPLY = "vibration_on_reply"
        private const val KEY_MESSAGES = "messages"
        private const val DEFAULT_API_BASE = "https://api.deepseek.com/v1"
        private const val DEFAULT_API_MODEL = "deepseek-chat"
        const val MAX_STORED = 4000
        private const val MAX_CONTENT_LENGTH = 8000
        const val MAX_PROMPT_ADDON_CHARS = 6000
        private const val BACKUP_FILENAME = "kael_chat_backup.json"
        private const val BACKUP_VERSION = 1
    }
}
