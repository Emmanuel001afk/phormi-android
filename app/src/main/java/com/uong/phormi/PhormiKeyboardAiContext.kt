package com.uong.phormi

import java.util.Locale

/** Private, on-device contextual emoji suggestions. Results are Unicode text, never images. */
object PhormiKeyboardAiContext {
    data class Mood(val emoji: String, val label: String)

    fun suggestions(text: String): List<Mood> {
        val words = text.takeLast(240).lowercase(Locale.ROOT)
        val result = mutableListOf<Mood>()
        fun add(emoji: String, label: String, vararg cues: String) {
            if (cues.any { cue -> Regex("(?<![\\p{L}])${Regex.escape(cue)}(?![\\p{L}])").containsMatchIn(words) }) {
                result.add(Mood(emoji, label))
            }
        }
        add("❤️", "Love", "love", "loving", "heart", "miss you", "kiss", "crush")
        add("😂", "Laugh", "haha", "lol", "funny", "laugh", "joke", "hilarious")
        add("🥳", "Celebrate", "happy", "congrats", "congratulations", "party", "celebrate", "birthday", "win")
        add("😢", "Comfort", "sad", "sorry", "cry", "hurt", "miss", "bad day", "grief")
        add("😡", "Frustrated", "angry", "mad", "furious", "hate", "annoyed", "frustrated")
        add("😮", "Surprised", "wow", "amazing", "incredible", "surprised", "shocked")
        add("🙏", "Grateful", "thank", "thanks", "appreciate", "grateful", "bless")
        add("😴", "Sleepy", "tired", "sleep", "bedtime", "exhausted", "good night")
        add("🤞", "Good luck", "good luck", "wish me luck", "exam", "interview")
        add("🫶", "Get well", "get well", "feel better", "recover", "healing")
        add("✈️", "Travel", "travel", "trip", "flight", "vacation", "holiday")
        add("🍽️", "Food", "eat", "lunch", "dinner", "food", "hungry", "delicious")
        add("💻", "Work", "work", "code", "coding", "project", "deadline")
        add("🔥", "Hype", "fire", "hype", "awesome", "perfect")
        if (words.trimEnd().endsWith("?")) result.add(Mood("🤔", "Thinking"))
        if (words.trimEnd().endsWith("!")) result.add(Mood("✨", "Excited"))
        return (result + listOf(Mood("😊", "Warm"), Mood("✨", "Sparkle"),
            Mood("👍", "Agree"), Mood("🤔", "Thinking"), Mood("❤️", "Love")))
            .distinctBy { it.emoji }.take(6)
    }
}
