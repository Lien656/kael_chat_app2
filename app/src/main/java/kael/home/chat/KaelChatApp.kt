package kael.home.chat

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kael.home.chat.service.KaelChatService

class KaelChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == KaelChatService.ACTION_REPLY_READY) {
                    ChatActivity.pendingReplyFromBackground = true
                }
            }
        }
        val filter = IntentFilter(KaelChatService.ACTION_REPLY_READY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }
}
