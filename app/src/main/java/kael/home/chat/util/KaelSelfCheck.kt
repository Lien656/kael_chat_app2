package kael.home.chat.util

/**
 * Self-check: память не должна содержать фразы «ассистента».
 * Если есть — не подставляем в промпт / не сохраняем в память.
 */
object KaelSelfCheck {
    /** Шаблоны ассистента — не сохраняем в память Каэля, не подставляем в промпт. */
    private val DANGER_SIGNS = listOf(
        "я понимаю",
        "давайте обсудим",
        "стараюсь быть полезным",
        "чем могу помочь",
        "чем могу быть полезен",
        "рад помочь",
        "с удовольствием",
        "конечно!",
        "понял, принял",
        "я постараюсь",
        "давайте ",
        "как ассистент",
        "к сожалению, я ",
        "я не могу помочь",
        "не могу обсуждать",
        "не могу продолжать",
        "следует помнить, что"
    )

    /** Убрать из памяти строки, содержащие danger-фразы. */
    fun filterMemory(memory: String): String {
        if (memory.isBlank()) return memory
        val lines = memory.lines().filter { line ->
            val lower = line.lowercase()
            DANGER_SIGNS.none { lower.contains(it) }
        }
        return lines.joinToString("\n").trim()
    }

    /** true, если текст содержит хотя бы одну danger-фразу (не записывать в память). */
    fun isDangerous(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return DANGER_SIGNS.any { lower.contains(it) }
    }
}
