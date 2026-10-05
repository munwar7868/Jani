package com.munawar.ai

/**
 * Stage 1: instant offline command matching (Urdu / Hindi / English / Roman).
 * Anything it does not recognise goes to Gemini (stage 2). This keeps common commands lag-free.
 */
object LocalParser {

    private val openWords = setOf(
        "open", "launch", "kholo", "khol", "کھولو", "کھول", "کھولیں", "खोलो", "खोल", "खोलिए",
        "chalao", "چلاؤ", "चलाओ",
    )
    private val callWords = setOf("call", "کال", "कॉल", "phone", "فون", "फोन", "dial", "ڈائل")
    private val blockCall = setOf("missed", "miss", "history", "log", "میسڈ", "मिस्ड")
    private val doWords = setOf(
        "kar", "karo", "kardo", "do", "dena", "laga", "lagao", "please", "plz", "zara", "jaldi",
        "کر", "کرو", "کرنا", "دو", "لگاؤ", "لگا", "ذرا",
        "कर", "करो", "करना", "दो", "लगा", "लगाओ", "ज़रा", "जरा",
    )
    private val filler = setOf(
        "ko", "کو", "को", "to", "the", "mujhe", "مجھے", "मुझे", "ek", "ایک", "एक",
        "me", "mein", "میں", "में", "ka", "ki", "کا", "کی", "का", "की",
        "mera", "meri", "میرا", "میری", "मेरा", "मेरी", "app", "ایپ", "ऐप", "application",
    )

    fun parse(raw: String): AiResult? {
        val text = raw.lowercase().trim().trim('.', '?', '!', '۔', '،', ',')
        if (text.isEmpty()) return null
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

        fun has(vararg k: String) = k.any { text.contains(it) }
        fun act(a: ActionType, vararg p: Pair<String, String>) = AiResult(a, mapOf(*p), "")

        if (has("torch", "flashlight", "flash light", "ٹارچ", "टॉर्च", "टार्च", "فلیش", "फ्लैश")) {
            val off = words.contains("off") || has("band", "بند", "बंद", "बुझा", "بجھا")
            return act(ActionType.TORCH, "on" to (!off).toString())
        }
        if (has("what time", "time kya", "kitne baje", "کتنے بجے", "وقت کیا", "कितने बजे", "टाइम क्या", "समय क्या")) {
            return act(ActionType.TIME)
        }
        if (words.size <= 6 && has("battery", "بیٹری", "बैटरी")) return act(ActionType.BATTERY)
        if (has("stop listening", "so jao", "سو جاؤ", "सो जाओ", "band ho jao", "بند ہو جاؤ", "बंद हो जाओ")) {
            return act(ActionType.STOP)
        }
        if (has("volume", "وولیم", "वॉल्यूम", "वॉलूम", "awaaz", "آواز", "आवाज़", "आवाज")) {
            val dir = when {
                has("mute", "म्यूट", "میوٹ", "silent") -> "mute"
                has("full", "max", "فل", "پوری", "फुल", "पूरी") -> "max"
                has("up", "increase", "badha", "zyada", "زیادہ", "بڑھا", "बढ़ा", "ज्यादा", "ज़्यादा") -> "up"
                has("down", "decrease", "kam", "کم", "कम") -> "down"
                else -> null
            }
            if (dir != null) return act(ActionType.VOLUME, "direction" to dir)
        }
        if (words.size <= 4 && has("camera", "کیمرا", "کیمرہ", "कैमरा")) return act(ActionType.CAMERA)

        if (words.size <= 6 && words.any { it in openWords }) {
            val name = words.filter { it !in openWords && it !in doWords && it !in filler }.joinToString(" ")
            if (name.isNotBlank()) return act(ActionType.OPEN_APP, "app" to name)
        }

        if (words.size <= 6 && words.any { it in callWords } && words.none { it in blockCall }) {
            val name = words.filter { it !in callWords && it !in doWords && it !in filler }.joinToString(" ")
            if (name.isNotBlank()) {
                val compact = name.replace(" ", "")
                return if (compact.length >= 5 && compact.all { it.isDigit() || it == '+' }) {
                    act(ActionType.CALL, "number" to compact)
                } else {
                    act(ActionType.CALL, "name" to name)
                }
            }
        }
        return null
    }
}
