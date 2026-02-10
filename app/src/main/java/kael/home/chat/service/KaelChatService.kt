package kael.home.chat.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kael.home.chat.ChatActivity
import kael.home.chat.R
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
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (chatOnScreen) "" else getString(R.string.app_name))
            .setContentText(if (chatOnScreen) "" else getString(R.string.typing))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(if (chatOnScreen) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
            .setVisibility(if (chatOnScreen) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        requestJob = scope.launch {
            try {
                val storage = StorageService(this@KaelChatService)
                val key = storage.apiKey ?: return@launch
                val api = ApiService(key, storage.apiBase)
                val history = storage.getMessages()
                val kaelMemory = storage.getKaelMemory().take(8000)
                var reply = withContext(Dispatchers.IO) { api.sendChat(history, kaelMemory) }
                val memoryBlock = Regex("\\[ЗАПОМНИ:\\s*([\\s\\S]*?)\\]").find(reply)
                if (memoryBlock != null) {
                    val toRemember = memoryBlock.groupValues.getOrNull(1)?.trim() ?: ""
                    if (toRemember.isNotEmpty() && !KaelSelfCheck.isDangerous(toRemember)) storage.appendToKaelMemory(toRemember)
                    val stripped = reply.replace(memoryBlock.value, "").trim().replace(Regex("\\n{3,}"), "\n\n")
                    if (stripped.isNotEmpty()) reply = stripped
                }
                val assistantMsg = kael.home.chat.model.ChatMessage(role = "assistant", content = reply)
                val updated = (history + assistantMsg).takeLast(StorageService.MAX_STORED)
                storage.saveMessagesSync(updated)
                val open = PendingIntent.getActivity(
                    this@KaelChatService, 0,
                    Intent(this@KaelChatService, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val showDoneNotif = !kael.home.chat.ChatActivity.isChatOnScreen
                if (showDoneNotif) {
                    val doneNotif = NotificationCompat.Builder(this@KaelChatService, CHANNEL_ID)
                        .setContentTitle("Kael")
                        .setContentText(reply.take(80).let { if (it.length == 80) "$it…" else it })
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .build()
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID_DONE, doneNotif)
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
