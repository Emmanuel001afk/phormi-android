package com.uong.phormi

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.os.Build
import java.util.Locale

/**
 * Broad Unicode emoji palette with standard, non-overlapping emoji sectors.
 * The keyboard renders each sector inside its own fixed scrolling viewport.
 */
object PhormiKeyboardEmoji {
    private val unicode17Additions = listOf("🫪", "🫯", "🫝", "🫍", "🫈", "🪊", "🛘", "🪎", "🧑‍🩰")

    private val specialSequences = listOf(
        "❤️", "❤️‍🔥", "❤️‍🩹", "☺️", "☹️", "☠️", "❣️", "👁️‍🗨️",
        "😶‍🌫️", "😮‍💨", "😵‍💫", "🫨", "🙂‍↔️", "🙂‍↕️",
        "🫱🏻‍🫲🏿", "🫱🏽‍🫲🏾", "👨‍⚕️", "👩‍⚕️", "👨‍🎓", "👩‍🎓",
        "👨‍💻", "👩‍💻", "👨‍🍳", "👩‍🍳", "👨‍🚀", "👩‍🚀", "👨‍🎨", "👩‍🎨",
        "👮‍♂️", "👮‍♀️", "🕵️‍♂️", "🕵️‍♀️", "💂‍♂️", "💂‍♀️",
        "🏃‍♂️", "🏃‍♀️", "🚶‍♂️", "🚶‍♀️", "🧑‍🤝‍🧑", "👩‍❤️‍👨",
        "👨‍❤️‍👨", "👩‍❤️‍👩", "👨‍👩‍👧‍👦", "👨‍👩‍👦", "👩‍👩‍👧", "👨‍👨‍👦",
        "🏳️‍🌈", "🏳️‍⚧️", "🏴‍☠️"
    )

    private fun codePointEmoji(cp: Int): String = String(Character.toChars(cp))

    private fun unicodeEmojiCodePoints(): List<Int> {
        if (Build.VERSION.SDK_INT < 28) return emptyList()
        val result = ArrayList<Int>()
        fun scan(start: Int, end: Int) {
            for (cp in start..end) {
                if (UCharacter.hasBinaryProperty(cp, UProperty.EMOJI) && cp !in 0x1F1E6..0x1F1FF) result.add(cp)
            }
        }
        scan(0, 0x2FFF)
        scan(0x1F000, 0x1FAFF)
        return result
    }

    private fun modifierBases(): List<Int> {
        if (Build.VERSION.SDK_INT < 28) return emptyList()
        val result = ArrayList<Int>()
        for (cp in 0x1F000..0x1FAFF) {
            if (UCharacter.hasBinaryProperty(cp, UProperty.EMOJI_MODIFIER_BASE)) result.add(cp)
        }
        return result
    }

    private fun countryFlags(): List<String> = Locale.getISOCountries().mapNotNull { code ->
        if (code.length != 2) return@mapNotNull null
        codePointEmoji(0x1F1E6 + (code[0] - 'A')) + codePointEmoji(0x1F1E6 + (code[1] - 'A'))
    }

    private val all: List<String> by lazy {
        val result = LinkedHashSet<String>()
        unicodeEmojiCodePoints().forEach { result.add(codePointEmoji(it)) }
        val modifiers = intArrayOf(0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF)
        modifierBases().forEach { base ->
            val root = codePointEmoji(base)
            modifiers.forEach { modifier -> result.add(root + codePointEmoji(modifier)) }
        }
        result.addAll(countryFlags())
        result.addAll(unicode17Additions)
        result.addAll(specialSequences)
        result.removeAll(listOf("#", "*", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"))
        result.toList()
    }

    private fun inRange(emoji: String, start: Int, end: Int): Boolean {
        val cp = emoji.codePointAt(0)
        return cp in start..end
    }

    private fun flagsOnly(): List<String> = countryFlags()

    val categories: LinkedHashMap<String, List<String>> by lazy {
        val people = all.filter { inRange(it, 0x1F440, 0x1F9FF) }
        val animals = all.filter { inRange(it, 0x1F400, 0x1F43F) || inRange(it, 0x1F980, 0x1F9AE) }
        val food = all.filter { inRange(it, 0x1F32D, 0x1F37F) }
        val travel = all.filter { inRange(it, 0x1F680, 0x1F6FF) || inRange(it, 0x1F5FB, 0x1F5FF) }
        val activities = all.filter { inRange(it, 0x1F3A0, 0x1F3FF) }
        val objects = all.filter { inRange(it, 0x1F4A0, 0x1F4FF) || inRange(it, 0x1F9F0, 0x1FAFF) }
        val symbols = all.filter { val cp = it.codePointAt(0); cp in 0x2000..0x2BFF || cp in 0x1F100..0x1F2FF }
        val emotion = all.filter { inRange(it, 0x1F600, 0x1F64F) || inRange(it, 0x1F910, 0x1F92F) || inRange(it, 0x1F970, 0x1F979) }
        linkedMapOf(
            "🕘" to all.take(60),
            "😀" to emotion,
            "🧑" to people,
            "🐾" to animals,
            "🍔" to food,
            "🚗" to travel,
            "⚽" to activities,
            "💡" to objects,
            "🔣" to symbols,
            "🇳🇬" to flagsOnly()
        )
    }
}
