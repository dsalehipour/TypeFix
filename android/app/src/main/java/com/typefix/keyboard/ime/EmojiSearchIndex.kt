package com.typefix.keyboard.ime

/**
 * Offline, fuzzy emoji search. Each group maps search terms (including synonyms)
 * to emojis, and [search] matches on substrings in both directions so users
 * don't have to type the exact keyword ("celebr" finds 🎉, "pup" finds 🐶).
 * The IME augments this with LLM-based semantic search when a model is ready.
 */
object EmojiSearchIndex {

    private val groups: List<Pair<List<String>, List<String>>> = listOf(
        listOf("happy", "smile", "smiley", "glad", "joy", "grin") to listOf("😀", "😄", "😁", "🙂", "😊", "😃"),
        listOf("laugh", "lol", "haha", "funny", "rofl", "lmao") to listOf("😂", "🤣", "😆", "😹"),
        listOf("love", "heart", "adore", "crush", "romance", "valentine") to listOf("❤️", "😍", "🥰", "😘", "💕", "💖"),
        listOf("sad", "cry", "tears", "unhappy", "depressed", "down") to listOf("😢", "😭", "🥺", "😔", "☹️"),
        listOf("angry", "mad", "rage", "furious", "annoyed") to listOf("😠", "😡", "🤬", "😤"),
        listOf("wow", "shock", "surprise", "omg", "amazed", "mind blown") to listOf("😮", "😲", "🤯", "😱"),
        listOf("cool", "sunglasses", "awesome", "swag") to listOf("😎", "🆒", "🕶️"),
        listOf("wink", "flirt", "playful") to listOf("😉", "😜", "😏"),
        listOf("silly", "goofy", "zany", "wacky", "crazy", "tongue") to listOf("🤪", "😜", "😝", "😛", "🤓"),
        listOf("think", "thinking", "hmm", "idea", "consider") to listOf("🤔", "💭", "🧐", "💡"),
        listOf("sleep", "tired", "sleepy", "yawn", "zzz", "bored") to listOf("😴", "🥱", "😪", "💤"),
        listOf("sick", "ill", "fever", "nausea", "mask") to listOf("🤒", "🤢", "🤮", "😷"),
        listOf("scared", "fear", "afraid", "nervous", "anxious") to listOf("😨", "😰", "😬", "🫣"),
        listOf("celebrate", "celebration", "party", "congrats", "congratulations", "yay", "hooray") to listOf("🎉", "🥳", "🎊", "🙌", "👏"),
        listOf("birthday", "bday", "cake", "candles") to listOf("🎂", "🍰", "🎈", "🥳"),
        listOf("thanks", "thank you", "grateful", "pray", "please", "blessed") to listOf("🙏", "💐", "🤝"),
        listOf("ok", "okay", "thumbs up", "good", "approve", "yes", "agree") to listOf("👍", "👌", "✅", "🆗"),
        listOf("no", "thumbs down", "bad", "disapprove", "reject") to listOf("👎", "🙅", "❌"),
        listOf("clap", "applause", "bravo", "well done") to listOf("👏", "🙌"),
        listOf("wave", "hi", "hello", "bye", "goodbye") to listOf("👋", "🤚"),
        listOf("strong", "muscle", "flex", "gym", "workout") to listOf("💪", "🏋️", "🦾"),
        listOf("fire", "lit", "hot", "flame", "burn") to listOf("🔥", "♨️"),
        listOf("hundred", "100", "perfect", "score", "facts") to listOf("💯"),
        listOf("star", "sparkle", "shine", "magic", "shiny", "glitter") to listOf("⭐", "✨", "🌟", "💫"),
        listOf("money", "cash", "rich", "dollar", "pay", "wealth") to listOf("💰", "💵", "🤑", "💸"),
        listOf("food", "eat", "hungry", "meal", "yum") to listOf("🍽️", "😋", "🍕", "🍔", "🍟"),
        listOf("pizza") to listOf("🍕"),
        listOf("burger", "hamburger") to listOf("🍔"),
        listOf("coffee", "espresso", "latte", "caffeine") to listOf("☕"),
        listOf("tea", "matcha") to listOf("🍵", "🫖"),
        listOf("beer", "drink", "cheers", "pub", "alcohol") to listOf("🍺", "🍻"),
        listOf("wine", "champagne", "toast") to listOf("🍷", "🥂"),
        listOf("dog", "puppy", "pup", "doggo") to listOf("🐶", "🐕"),
        listOf("cat", "kitty", "kitten", "meow") to listOf("🐱", "🐈"),
        listOf("heart eyes", "in love") to listOf("😍", "🥰"),
        listOf("sun", "sunny", "summer", "warm") to listOf("☀️", "🌞"),
        listOf("rain", "rainy", "storm", "umbrella") to listOf("🌧️", "☔", "⛈️"),
        listOf("snow", "snowman", "cold", "freezing", "winter", "ice") to listOf("❄️", "⛄", "🥶"),
        listOf("rainbow", "pride", "lgbt") to listOf("🌈"),
        listOf("travel", "trip", "flight", "plane", "vacation", "holiday") to listOf("✈️", "🧳", "🏖️"),
        listOf("car", "drive", "vehicle") to listOf("🚗", "🚙"),
        listOf("rocket", "launch", "space", "startup", "ship") to listOf("🚀"),
        listOf("home", "house") to listOf("🏠"),
        listOf("music", "song", "note", "melody") to listOf("🎵", "🎶", "🎧"),
        listOf("game", "gaming", "controller", "play") to listOf("🎮", "🕹️"),
        listOf("trophy", "win", "winner", "champion", "award", "first") to listOf("🏆", "🥇"),
        listOf("phone", "mobile", "call") to listOf("📱", "📞"),
        listOf("computer", "laptop", "code", "work", "dev") to listOf("💻", "⌨️", "🖥️"),
        listOf("camera", "photo", "picture") to listOf("📷", "📸"),
        listOf("time", "clock", "late", "deadline") to listOf("⏰", "🕒"),
        listOf("calendar", "schedule", "date", "meeting") to listOf("📅", "🗓️"),
        listOf("check", "done", "complete", "correct", "tick") to listOf("✅", "☑️"),
        listOf("cross", "wrong", "cancel", "x") to listOf("❌", "✖️"),
        listOf("warning", "caution", "alert", "careful") to listOf("⚠️", "🚨"),
        listOf("question", "confused", "what") to listOf("❓", "🤷"),
        listOf("idea", "lightbulb", "bright") to listOf("💡"),
        listOf("poop", "crap", "shit") to listOf("💩"),
        listOf("skull", "dead", "dying", "death") to listOf("💀", "☠️"),
        listOf("ghost", "spooky", "halloween", "boo") to listOf("👻"),
        listOf("alien", "ufo") to listOf("👽", "🛸"),
        listOf("robot", "ai", "bot") to listOf("🤖"),
        listOf("flower", "rose", "bloom", "spring") to listOf("🌸", "🌹", "💐"),
        listOf("plant", "tree", "nature", "green") to listOf("🌱", "🌳", "🍀"),
        listOf("moon", "night", "goodnight") to listOf("🌙", "🌛"),
        listOf("ok hand", "perfect hand", "chef") to listOf("👌", "🤌"),
        listOf("peace", "victory", "two") to listOf("✌️"),
        listOf("cross fingers", "luck", "hope", "lucky") to listOf("🤞", "🍀"),
        listOf("eyes", "look", "watching", "see") to listOf("👀"),
        listOf("hot face", "sweating", "heat") to listOf("🥵"),
        listOf("party face", "celebrate face") to listOf("🥳"),
    )

    fun search(query: String, limit: Int = 24): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = LinkedHashSet<String>()
        for ((keys, emojis) in groups) {
            val hit = keys.any { key ->
                // The `q.contains(key)` rule needs a length floor, otherwise a short
                // key matches as a substring of an unrelated word (e.g. "ill" in
                // "silly" → sick emojis).
                key == q || key.contains(q) || (key.length >= 4 && q.contains(key)) ||
                    tokens.any { t -> t.length >= 2 && (key.startsWith(t) || key.contains(t)) }
            }
            if (hit) out.addAll(emojis)
            if (out.size >= limit) break
        }
        return out.take(limit)
    }
}
