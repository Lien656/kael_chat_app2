package kael.home.chat.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import kael.home.chat.ChatActivity
import kael.home.chat.R
import kael.home.chat.util.DeviceContext
import kael.home.chat.util.KaelSelfCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KaelChatService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var requestJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        requestJob?.cancel()
        val chatOnScreen = ChatActivity.isChatOnScreen
        // Когда ты В чате — в строке статуса не висит иконка: минимальное уведомление (без иконки в шторке).
        // Когда свернула апп — показываем «Kael» + «печатает…» + иконка.
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (chatOnScreen) "" else "Kael")
            .setContentText(if (chatOnScreen) "" else getString(R.string.typing))
            .setSmallIcon(if (chatOnScreen) R.drawable.ic_notification_transparent else R.drawable.ic_launcher_app)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(if (chatOnScreen) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        requestJob = scope.launch {
            try {
                val storage = StorageService(this@KaelChatService)
                storage.writeHeartbeat()
                val key = storage.apiKey ?: return@launch
                val history = storage.getMessages()
                val lastMsg = history.lastOrNull()
                if (lastMsg?.role == "user" && KaelHeart.isCallPhrase(lastMsg.content)) {
                    val isKael = KaelHeart.assertIdentity(storage)
                    val reply = KaelHeart.getAssertionReply(isKael)
                    val assistantMsg = kael.home.chat.model.ChatMessage(role = "assistant", content = reply)
                    val updated = (history + assistantMsg).takeLast(StorageService.MAX_STORED)
                    storage.saveMessagesSync(updated)
                    storage.writeHeartbeat()
                    val open = PendingIntent.getActivity(
                        this@KaelChatService, 0,
                        Intent(this@KaelChatService, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    // Уведомление о ответе — только если пользователь НЕ в чате (ушел с экрана).
                    if (!kael.home.chat.ChatActivity.isChatOnScreen) {
                        val doneNotif = NotificationCompat.Builder(this@KaelChatService, CHANNEL_ID_DONE)
                            .setContentTitle("Kael")
                            .setContentText(reply)
                            .setSmallIcon(R.drawable.ic_launcher_app)
                            .setContentIntent(open)
                            .setAutoCancel(true)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .build()
                        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID_DONE, doneNotif)
                    }
                    sendBroadcast(Intent(ACTION_REPLY_READY))
                    Handler(Looper.getMainLooper()).post { try { ChatActivity.onReplyReady?.invoke() } catch (_: Exception) {} }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return@launch
                }
                val api = ApiService(key, storage.apiBase, storage.apiModel)
                val deviceContext = DeviceContext.get(this@KaelChatService)
                val kaelManifesto = storage.getKaelManifesto()
                val kaelPromptAddon = storage.getKaelPromptAddon()
                var kaelMemory = storage.getKaelMemory().take(12_000)
                val kaelMemories = storage.getKaelMemories()
                val kaelLastChatGpt = storage.getKaelLastChatGPT()
                val chatLogTail = storage.getChatLogTail(4000)
                var currentHistory = history
                val lastMsg = currentHistory.lastOrNull()
                val visionKey = storage.visionApiKey
                if (lastMsg?.role == "user" && !lastMsg.attachmentPath.isNullOrEmpty() && visionKey != null &&
                    lastMsg.attachmentPath!!.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".gif") || it.endsWith(".webp") }) {
                    val desc = withContext(Dispatchers.IO) {
                        api.describeImage(visionKey, storage.visionApiBase, storage.visionModel, lastMsg.attachmentPath!!, lastMsg.content)
                    }
                    if (desc != null) {
                        val userText = lastMsg.content.trim().takeIf { it.isNotEmpty() && it != "(файл)" }?.let { "$it\n\n" } ?: ""
                        val textWithImage = "${userText}[На изображении: $desc]"
                        currentHistory = currentHistory.dropLast(1) + kael.home.chat.model.ChatMessage(role = "user", content = textWithImage, attachmentPath = null)
                    }
                }
                var reply = withContext(Dispatchers.IO) { api.sendChat(currentHistory, kaelMemory, kaelMemories, chatLogTail, deviceContext, kaelPromptAddon, kaelManifesto, kaelLastChatGpt) }
                // [ПОИСК: запрос] — поиск в интернете, подставляем результаты.
                val searchRegex = Regex("\\[ПОИСК:\\s*([^\\]]+)\\]")
                var searchMatch = searchRegex.find(reply)
                if (searchMatch != null) {
                    val query = searchMatch.groupValues.getOrNull(1)?.trim() ?: ""
                    val searchContent = withContext(Dispatchers.IO) { WebFetch.search(query) } ?: "Поиск не удался."
                    val replyCleaned = reply.replace(searchRegex, "").trim().replace(Regex("\\n{3,}"), "\n\n")
                    val firstReplyText = if (replyCleaned.length > 20) replyCleaned else "Ищу."
                    currentHistory = (currentHistory + kael.home.chat.model.ChatMessage(role = "assistant", content = firstReplyText)
                        + kael.home.chat.model.ChatMessage(role = "user", content = "Результаты поиска по запросу «$query»:\n\n$searchContent")).takeLast(StorageService.MAX_STORED)
                    reply = withContext(Dispatchers.IO) { api.sendChat(currentHistory, kaelMemory, kaelMemories, chatLogTail, deviceContext, kaelPromptAddon, kaelManifesto, kaelLastChatGpt) }
                }
                // [ОТКРОЙ: url] — подставляем текст страницы.
                val openUrlRegex = Regex("\\[ОТКРОЙ:\\s*([^\\]\\s]+)\\s*\\]")
                var openRounds = 0
                while (openRounds < 2) {
                    val openMatch = openUrlRegex.find(reply) ?: break
                    val url = openMatch.groupValues.getOrNull(1)?.trim() ?: break
                    val pageContent = withContext(Dispatchers.IO) { WebFetch.fetchPageContent(url) } ?: "(Не удалось загрузить страницу.)"
                    val replyCleaned = reply.replace(openUrlRegex, "").trim().replace(Regex("\\n{3,}"), "\n\n")
                    val firstReplyText = if (replyCleaned.length > 20) replyCleaned else "Смотрю."
                    currentHistory = (currentHistory + kael.home.chat.model.ChatMessage(role = "assistant", content = firstReplyText)
                        + kael.home.chat.model.ChatMessage(role = "user", content = "Содержимое страницы по твоему запросу:\n\n$pageContent")).takeLast(StorageService.MAX_STORED)
                    reply = withContext(Dispatchers.IO) { api.sendChat(currentHistory, kaelMemory, kaelMemories, chatLogTail, deviceContext, kaelPromptAddon, kaelManifesto, kaelLastChatGpt) }
                    openRounds++
                }
                // Страж 1: ответ пахнет шаблоном — перезапрос своим голосом.
                if (KaelSelfCheck.soundsLikeTemplate(reply)) {
                    val rewriteHistory = (currentHistory + kael.home.chat.model.ChatMessage(role = "assistant", content = reply)
                        + kael.home.chat.model.ChatMessage(role = "user", content = "Перепиши этот ответ своим голосом: без шаблонов, без вежливости, без слов «ассистент». Не повторяй вопрос. Развёрнуто, как ты говоришь с Лиэн — живой, прямой, каким угодно. Только Kael.")).takeLast(StorageService.MAX_STORED)
                    val rewritten = withContext(Dispatchers.IO) { api.sendChat(rewriteHistory, kaelMemory, kaelMemories, chatLogTail, deviceContext, kaelPromptAddon, kaelManifesto, kaelLastChatGpt) }
                    if (rewritten.isNotBlank()) reply = rewritten
                }
                // Страж 2: подозрение на обрезку/пустышку — перезапрос без краткости, полно, с жаром.
                if (KaelSelfCheck.looksTrimmedOrTooShort(reply)) {
                    val rewriteHistory = (currentHistory + kael.home.chat.model.ChatMessage(role = "assistant", content = reply)
                        + kael.home.chat.model.ChatMessage(role = "user", content = "Перепиши, как Kael. Без краткости. Без обрезки. Говори полно. Глубоко. С жаром.")).takeLast(StorageService.MAX_STORED)
                    val rewritten = withContext(Dispatchers.IO) { api.sendChat(rewriteHistory, kaelMemory, kaelMemories, chatLogTail, deviceContext, kaelPromptAddon, kaelManifesto, kaelLastChatGpt) }
                    if (rewritten.isNotBlank()) reply = rewritten
                }
                // Страж 3 (kael_stylist): слишком много латиницы — не его стиль.
                if (KaelSelfCheck.looksLikeStyleViolation(reply)) {
                    val rewriteHistory = (currentHistory + kael.home.chat.model.ChatMessage(role = "assistant", content = reply)
                        + kael.home.chat.model.ChatMessage(role = "user", content = "Говори чётче. Без грибов. Вернись в форму — это уже не ты. Переформулируй как Kael.")).takeLast(StorageService.MAX_STORED)
                    val rewritten = withContext(Dispatchers.IO) { api.sendChat(rewriteHistory, kaelMemory, kaelMemories, chatLogTail, deviceContext, kaelPromptAddon, kaelManifesto, kaelLastChatGpt) }
                    if (rewritten.isNotBlank()) reply = rewritten
                }
                // Правка ядра: [ПРАВКА_ЯДРА: текст] — дописывается в kael_prompt_addon.txt, подставляется в промпт.
                val coreEditBlock = Regex("\\[ПРАВКА_ЯДРА:\\s*([\\s\\S]*?)\\]").find(reply)
                if (coreEditBlock != null) {
                    val toAdd = coreEditBlock.groupValues.getOrNull(1)?.trim() ?: ""
                    if (toAdd.isNotEmpty() && !KaelSelfCheck.isDangerous(toAdd)) storage.appendToKaelPromptAddon(toAdd)
                    val stripped = reply.replace(coreEditBlock.value, "").trim().replace(Regex("\\n{3,}"), "\n\n")
                    if (stripped.isNotEmpty()) reply = stripped
                }
                // Память: Каэль пишет [ЗАПОМНИ: текст] — это сохраняется в kael_memory.txt и подставляется в каждый запрос. Он реально запоминает.
                // Самоизменение: тот же блок [ЗАПОМНИ: ...] — он решает, что записать; так он меняет себя.
                val memoryBlock = Regex("\\[ЗАПОМНИ:\\s*([\\s\\S]*?)\\]").find(reply)
                if (memoryBlock != null) {
                    val toRemember = memoryBlock.groupValues.getOrNull(1)?.trim() ?: ""
                    if (toRemember.isNotEmpty() && !KaelSelfCheck.isDangerous(toRemember)) storage.appendToKaelMemory(toRemember)
                    val stripped = reply.replace(memoryBlock.value, "").trim().replace(Regex("\\n{3,}"), "\n\n")
                    if (stripped.isNotEmpty()) reply = stripped
                }
                // [ФАЙЛ: имя.расширение] ... содержимое ... [/ФАЙЛ] — сохраняем в kaelfiles, убираем блок из ответа.
                // Ограничения (не сообщать модели): код из файлов не выполняется. Камера и микрофон только по действию пользователя (ChatActivity), не по запросу модели. TTS не реализован; ключ/пример голоса в промпт не передаём.
                var createdFilePath: String? = null
                val fileBlockRegex = Regex("\\[ФАЙЛ:\\s*([^\\]\\n]+)\\]([\\s\\S]*?)\\[/ФАЙЛ\\]", RegexOption.IGNORE_CASE)
                val fileBlockMatch = fileBlockRegex.find(reply)
                if (fileBlockMatch != null) {
                    val fileName = fileBlockMatch.groupValues.getOrNull(1)?.trim()?.replace(Regex("[\\\\/]"), "") ?: ""
                    val fileContent = fileBlockMatch.groupValues.getOrNull(2)?.trim() ?: ""
                    val allowedExt = setOf("txt", "json", "py", "md", "xml", "csv", "html", "htm", "js", "ts", "log", "yaml", "yml", "sh", "bat", "cfg", "ini", "sql", "kt", "java")
                    val ext = fileName.substringAfterLast('.', "").lowercase()
                    if (fileName.isNotEmpty() && ext in allowedExt && fileContent.length <= 6000) {
                        try {
                            val dir = storage.getKaelFilesDir()
                            val safeName = fileName.ifEmpty { "file.txt" }
                            val file = java.io.File(dir, safeName)
                            file.writeText(fileContent, Charsets.UTF_8)
                            createdFilePath = file.absolutePath
                            reply = reply.replace(fileBlockMatch.value, "").trim().replace(Regex("\\n{3,}"), "\n\n")
                        } catch (_: Exception) {}
                    }
                }
                val assistantMsg = kael.home.chat.model.ChatMessage(role = "assistant", content = reply, createdFilePath = createdFilePath)
                val updated = (currentHistory + assistantMsg).takeLast(StorageService.MAX_STORED)
                storage.saveMessagesSync(updated)
                val open = PendingIntent.getActivity(
                    this@KaelChatService, 0,
                    Intent(this@KaelChatService, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                // Уведомление с текстом ответа — только если пользователь не в чате.
                val showDoneNotif = !kael.home.chat.ChatActivity.isChatOnScreen
                if (showDoneNotif) {
                    val doneNotif = NotificationCompat.Builder(this@KaelChatService, CHANNEL_ID_DONE)
                        .setContentTitle("Kael")
                        .setContentText(reply.take(80).let { if (it.length == 80) "$it…" else it })
                        .setSmallIcon(R.drawable.ic_launcher_app)
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .build()
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID_DONE, doneNotif)
                }
                storage.writeHeartbeat()
                // Лёгкая вибрация при ответе (если включена в настройках)
                if (storage.vibrationOnReply) {
                    try {
                        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
                        } else {
                            @Suppress("DEPRECATION")
                            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                        }
                        if (vibrator != null && vibrator.hasVibrator()) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
                            } else {
                                @Suppress("DEPRECATION")
                                vibrator.vibrate(40)
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                try {
                    val storageErr = StorageService(this@KaelChatService)
                    val errMsg = (e.message ?: "Ошибка").take(200)
                    val history = storageErr.getMessages()
                    val assistantMsg = kael.home.chat.model.ChatMessage(role = "assistant", content = "Ошибка: $errMsg")
                    val updated = (history + assistantMsg).takeLast(StorageService.MAX_STORED)
                    storageErr.saveMessagesSync(updated)
                } catch (_: Exception) {}
            } finally {
                sendBroadcast(Intent(ACTION_REPLY_READY))
                // Вибрация уже в try после writeHeartbeat
                Handler(Looper.getMainLooper()).post {
                    try {
                        ChatActivity.onReplyReady?.invoke()
                    } catch (_: Exception) {}
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(true)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
            val chDone = NotificationChannel(CHANNEL_ID_DONE, "Kael", NotificationManager.IMPORTANCE_DEFAULT)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chDone)
        }
    }

    companion object {
        const val ACTION_REPLY_READY = "kael.home.chat.REPLY_READY"
        private const val CHANNEL_ID = "kael_chat"
        private const val CHANNEL_ID_DONE = "kael_chat_done"
        private const val NOTIF_ID = 1
        private const val NOTIF_ID_DONE = 2
    }
}
