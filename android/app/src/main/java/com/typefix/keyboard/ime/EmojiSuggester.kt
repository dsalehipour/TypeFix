package com.typefix.keyboard.ime

/**
 * Instant, offline emoji suggestions based on the text the user just typed.
 * This is the fast path shown immediately; the IME may then asynchronously
 * replace these with richer LLM-based suggestions when a model is available.
 */
object EmojiSuggester {

    // Keyword (substring) -> emojis, roughly ordered by how evocative they are.
    private val keywordEmojis: List<Pair<List<String>, List<String>>> = listOf(
        listOf("love", "loved", "loving", "❤", "crush", "adore") to listOf("❤️", "😍", "🥰", "😘"),
        listOf("haha", "lol", "lmao", "funny", "hilarious", "joke") to listOf("😂", "🤣", "😆", "😄"),
        listOf("happy", "glad", "yay", "excited", "great", "awesome", "amazing") to listOf("😄", "😁", "🤩", "🎉"),
        listOf("sad", "cry", "crying", "upset", "unhappy", "depressed") to listOf("😢", "😭", "🥺", "😔"),
        listOf("angry", "mad", "furious", "annoyed", "ugh") to listOf("😠", "😡", "😤", "🙄"),
        listOf("congrat", "congrats", "well done", "promotion", "win", "won", "winner") to listOf("🎉", "🥳", "👏", "🙌"),
        listOf("birthday", "bday", "cake", "party") to listOf("🎂", "🥳", "🎉", "🎈"),
        listOf("thank", "thanks", "thx", "grateful", "appreciate") to listOf("🙏", "😊", "💛", "🤝"),
        listOf("food", "eat", "eating", "hungry", "dinner", "lunch", "breakfast") to listOf("🍽️", "😋", "🍕", "🍔"),
        listOf("coffee", "espresso", "latte", "tea") to listOf("☕", "🫖", "😌"),
        listOf("beer", "drink", "drinks", "wine", "cheers", "bar") to listOf("🍺", "🍻", "🍷", "🥂"),
        listOf("fire", "lit", "🔥", "hot", "amazing") to listOf("🔥", "💯", "🤩"),
        listOf("ok", "okay", "got it", "sounds good", "yes", "yeah", "yep") to listOf("👍", "👌", "✅", "🙂"),
        listOf("no", "nope", "nah") to listOf("👎", "🙅", "❌"),
        listOf("work", "working", "meeting", "deadline", "busy") to listOf("💼", "💻", "📅", "😮‍💨"),
        listOf("home", "house") to listOf("🏠", "🛋️"),
        listOf("travel", "trip", "flight", "vacation", "holiday") to listOf("✈️", "🧳", "🏖️", "🌴"),
        listOf("car", "drive", "driving") to listOf("🚗", "🛣️"),
        listOf("music", "song", "listening", "concert") to listOf("🎵", "🎧", "🎸"),
        listOf("sleep", "tired", "sleepy", "bed", "goodnight", "night") to listOf("😴", "🛌", "🌙", "💤"),
        listOf("morning", "good morning") to listOf("🌅", "☀️", "☕"),
        listOf("sick", "ill", "fever", "cough") to listOf("🤒", "🤧", "😷"),
        listOf("money", "pay", "paid", "cash", "rich") to listOf("💰", "💸", "🤑"),
        listOf("idea", "think", "thinking", "smart") to listOf("💡", "🤔", "🧠"),
        listOf("dog", "puppy") to listOf("🐶", "🐕"),
        listOf("cat", "kitty", "kitten") to listOf("🐱", "🐈"),
        listOf("rain", "raining", "storm") to listOf("🌧️", "☔", "⛈️"),
        listOf("snow", "cold", "freezing", "winter") to listOf("❄️", "🥶", "⛄"),
        listOf("sun", "sunny", "warm", "summer") to listOf("☀️", "😎", "🏖️"),
        listOf("game", "gaming", "play", "win") to listOf("🎮", "🕹️", "🏆"),
        listOf("heart", "miss you", "miss u") to listOf("❤️", "🥹", "🫶"),
        listOf("sorry", "apolog", "my bad") to listOf("🙏", "😔", "🥺"),
        listOf("good luck", "luck", "fingers crossed") to listOf("🍀", "🤞", "✨"),
        listOf("wow", "omg", "whoa", "unbelievable") to listOf("😮", "😲", "🤯"),
    )

    private val popular = listOf("😂", "❤️", "👍", "🙏", "😊", "🔥", "🎉", "😍")

    fun suggest(text: String, limit: Int = 8): List<String> {
        val lower = text.lowercase()
        val out = LinkedHashSet<String>()
        if (lower.isNotBlank()) {
            for ((keys, emojis) in keywordEmojis) {
                if (keys.any { lower.contains(it) }) out.addAll(emojis)
                if (out.size >= limit) break
            }
            if (lower.contains("!")) out.add("🎉")
            if (lower.contains("?")) out.add("🤔")
        }
        for (e in popular) {
            if (out.size >= limit) break
            out.add(e)
        }
        return out.take(limit)
    }
}
