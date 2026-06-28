package com.typefix.keyboard.ime

/**
 * Maps the emotional intent of a typed message to a GIF search term, so the GIF
 * panel can suggest a fitting reaction (e.g. "that meeting killed me" -> tired)
 * instead of requiring an explicit search. Offline; the IME can also refine this
 * with the LLM when a backend is available.
 */
object ReactionIntent {

    private val rules: List<Pair<List<String>, String>> = listOf(
        listOf("killed me", "exhausted", "so tired", "drained", "burnt out", "burned out", "long day") to "exhausted",
        listOf("tired", "sleepy", "need sleep", "no sleep") to "tired",
        listOf("lol", "lmao", "hilarious", "so funny", "dying", "dead 💀", "haha") to "laughing",
        listOf("congrats", "congratulations", "we won", "closed the", "nailed it", "promotion") to "celebrate",
        listOf("excited", "let's go", "lets go", "pumped", "can't wait", "cant wait") to "excited",
        listOf("love", "adorable", "cute", "miss you") to "love",
        listOf("sad", "bummed", "heartbroken", "rough day", "down") to "sad",
        listOf("angry", "furious", "annoyed", "frustrated", "ugh") to "angry",
        listOf("shocked", "omg", "no way", "unbelievable", "mind blown", "wtf") to "shocked",
        listOf("nervous", "anxious", "stressed", "worried", "panic") to "nervous",
        listOf("thank you", "thanks", "grateful", "appreciate") to "thank you",
        listOf("this is fine", "everything is fine", "disaster", "on fire") to "this is fine",
        listOf("confused", "what is happening", "huh", "lost") to "confused",
        listOf("good morning", "morning") to "good morning",
        listOf("goodnight", "good night", "bed time", "bedtime") to "good night",
        listOf("facepalm", "smh", "really?", "seriously") to "facepalm",
        listOf("party", "celebrate", "weekend", "friday") to "party",
        listOf("eye roll", "whatever", "as if") to "eye roll",
        listOf("hungry", "starving", "food") to "hungry",
        listOf("waiting", "still waiting", "any update") to "waiting",
        listOf("bored", "boring", "meh") to "bored",
    )

    /** Returns a GIF search term for [text], or null if no clear intent. */
    fun gifQueryFor(text: String): String? {
        val lower = text.lowercase()
        if (lower.isBlank()) return null
        for ((keys, term) in rules) {
            if (keys.any { lower.contains(it) }) return term
        }
        return null
    }
}
