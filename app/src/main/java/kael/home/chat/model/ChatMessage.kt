package kael.home.chat.model

import org.json.JSONObject

data class ChatMessage(
    val role: String, // "user" | "assistant"
    val content: String,
    val attachmentPath: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val displayName: String get() = if (role == "user") "Lien" else "Kael"
    val isUser: Boolean get() = role == "user"

    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        put("content", content)
        if (attachmentPath != null) put("attachmentPath", attachmentPath)
        put("ts", timestamp)
    }

    companion object {
        fun fromJson(obj: JSONObject): ChatMessage {
            val ts = if (obj.has("ts")) obj.getLong("ts") else System.currentTimeMillis()
            return ChatMessage(
                role = obj.optString("role", "user"),
                content = obj.optString("content", ""),
                attachmentPath = if (obj.has("attachmentPath")) obj.getString("attachmentPath") else null,
                timestamp = ts
            )
        }
    }
}
