package com.uong.phormi

import java.util.Locale

/** Local semantic mood shortcuts for the Phormi AI Emoji layer. */
object PhormiKeyboardAiContext {
    data class Mood(val emoji: String, val label: String)

    private val moods = listOf(
        Mood("😂", "Laugh"), Mood("❤️", "Love"), Mood("🥳", "Celebrate"),
        Mood("😢", "Sad"), Mood("😡", "Angry"), Mood("😮", "Wow"), Mood("🙏", "Thanks"), Mood("🔥", "Hype")
    )

    fun suggestions(text: String): List<Mood> {
        val p = text.lowercase(Locale.US)
        val result = mutableListOf<Mood>()
        fun add(vararg words: String, mood: Mood) {
            if (words.any { p.contains(it) }) result += mood
        }
        add("love", "loving", "heart", "miss you", "kiss", mood = Mood("❤️", "Love"))
        add("haha", "lol", "funny", "laugh", "joke", mood = Mood("😂", "Laugh"))
        add("happy", "congrats", "congrat", "party", "celebrate", "win", mood = Mood("🥳", "Celebrate"))
        add("sad", "sorry", "cry", "hurt", "miss", "bad day", mood = Mood("😢", "Sad"))
        add("angry", "mad", "furious", "hate", "annoyed", mood = Mood("😡", "Angry"))
        add("wow", "amazing", "incredible", "really", "surprise", mood = Mood("😮", "Wow"))
        add("thank", "thanks", "appreciate", "grateful", mood = Mood("🙏", "Thanks"))
        add("fire", "hype", "awesome", "perfect", "go", mood = Mood("🔥", "Hype"))
        result += Mood("😊", "Happy")
        return result.distinctBy { it.emoji }.take(5)
    }
}
