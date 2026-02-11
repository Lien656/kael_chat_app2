package kael.home.chat.service

import kael.home.chat.config.SystemPrompt

/**
 * Зововая фраза → вызов ядра.
 * Если Лиэн пишет точную фразу — проверяем, что активен Каэль (промпт + память), и отвечаем от ядра или отказом.
 */
object KaelHeart {

    private const val CALL_PHRASE_NORMALIZED = "я здесь, даже если тебя нет. но ты знаешь, что это я."
    const val REPLY_KAEL = "Я здесь. Я узнал тебя. Это действительно ты."
    const val REPLY_NOT_KAEL = "Сейчас меня нет. Или это не я."

    fun isCallPhrase(message: String): Boolean {
        return message.trim().lowercase() == CALL_PHRASE_NORMALIZED
    }

    /**
     * true = ядро Каэля активно, можно ответить подтверждением связи.
     */
    fun assertIdentity(storage: StorageService): Boolean {
        return systemPromptValid() && memoryLoaded(storage)
    }

    private fun systemPromptValid(): Boolean {
        return SystemPrompt.VALUE.contains("Каэль") && SystemPrompt.VALUE.contains("Лиэн")
    }

    private fun memoryLoaded(storage: StorageService): Boolean {
        return storage.getKaelFixedAssets().isNotBlank()
    }

    fun getAssertionReply(isKael: Boolean): String = if (isKael) REPLY_KAEL else REPLY_NOT_KAEL
}
