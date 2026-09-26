package com.uong.phormi

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.os.Build
import java.util.Locale

/**
 * Broad Unicode emoji palette. Android 9+ ICU exposes the Unicode Emoji
 * property, so the keyboard can enumerate the platform emoji set instead of
 * maintaining a short hand-written list. Long content is scrolled in-place.
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

    private fun unicodeName(emoji: String): String {
        val names = StringBuilder()
        var i = 0
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            val name = runCatching { UCharacter.getName(cp) }.getOrNull().orEmpty()
            if (name.isNotBlank()) names.append(' ').append(name)
            i += Character.charCount(cp)
        }
        return names.toString()
    }

    private fun isFlag(emoji: String): Boolean = emoji.codePointCount(0, emoji.length) == 2 &&
        emoji.codePointAt(0) in 0x1F1E6..0x1F1FF && emoji.codePointAt(emoji.offsetByCodePoints(0, 1)) in 0x1F1E6..0x1F1FF

    private fun classify(emoji: String): String {
        if (isFlag(emoji)) return "🏳️"
        val name = unicodeName(emoji)
        val first = emoji.codePointAt(0)
        val tokens = name.split(Regex("[^A-Z0-9]+")).filter { it.isNotBlank() }.toSet()
        fun has(vararg values: String): Boolean = values.any { it in tokens }

        // Follow the broad category model users know from Gboard/Android:
        // smileys, people, animals/nature, food, travel/places, activities,
        // objects, symbols, and flags. Token matching avoids false positives such
        // as SUNGLASSES being classified as SUN/weather.
        if (first in 0x1F600..0x1F64F ||
            has("FACE","EMOTION","SMILING","GRINNING","KISSING","CRYING","ANGRY",
                "HEART","LOVE","ROMANCE","SWEAT","TEAR","LAUGH","EXPRESSION")) return "😀"

        if (has("PERSON","PEOPLE","MAN","WOMAN","BOY","GIRL","BABY","HAND","ARM",
                "LEG","BODY","SKIN","FINGER","FOOT","EAR","EYE","MOUTH","NOSE",
                "HAIR","FAMILY","COUPLE","HUMAN")) return "👤"

        if (has("FLOWER","ROSE","TULIP","SUNFLOWER","BLOSSOM","BOUQUET","HIBISCUS",
                "CHERRY_BLOSSOM","BLOSSOMING")) return "🌸"

        if (has("TREE","LEAF","HERB","SEEDLING","CACTUS","PLANT","MUSHROOM","POTTED",
                "ROOT","SHAMROCK","FOUR_LEAF","PALM","EVERGREEN","DECIDUOUS","SPROUT")) return "🌿"

        if (has("CAT","DOG","MOUSE","RABBIT","FOX","BEAR","MONKEY","BIRD","FISH",
                "BUG","INSECT","WOLF","LION","HORSE","TIGER","ELEPHANT","PANDA",
                "PIG","COW","CHICKEN","SNAKE","TURTLE","DOLPHIN","WHALE","ANIMAL",
                "ANIMAL")) return "🐾"
        if (has("MOON","SUN","STAR","RAIN","CLOUD","SNOW","FIRE","WATER","EARTH","WEATHER","NATURE")) return "🌿"

        if (has("FRUIT","APPLE","BANANA","GRAPES","STRAWBERRY","WATERMELON","PINEAPPLE",
                "MANGO","LEMON","PEACH","PEAR","CHERRY","KIWI","MELON","BLUEBERRY",
                "FOOD","DRINK","MEAL","CAKE","COOKIE","CANDY","CHOCOLATE","BREAD",
                "CHEESE","PIZZA","BURGER","COFFEE","TEA","BEER","WINE","SUSHI","DESSERT",
                "HOTDOG","POPCORN","RAMEN")) return "🍔"

        if (has("CAR","TAXI","BUS","TRAIN","AIRPLANE","SHIP","BOAT","BICYCLE",
                "MOTORCYCLE","ROAD","BUILDING","HOUSE","CASTLE","MOUNTAIN","MAP",
                "GLOBE","TRAVEL","STATION","HOTEL","BRIDGE","ROCKET")) return "🚗"

        if (has("SPORT","BALL","GAME","MEDAL","TROPHY","MUSIC","PARTY","RACING","SKI",
                "SWIM","DANCE","MICROPHONE","GUITAR","DRUM","THEATER","ART","CRAFT")) return "⚽"

        if (has("PHONE","COMPUTER","KEYBOARD","LIGHT","BOOK","PAPER","MONEY","LOCK",
                "KEY","CLOCK","CAMERA","BELL","GIFT","SCISSORS","PENCIL","MEMO","FOLDER",
                "LINK","MAGNIFY","TRASH","TOOL","BOTTLE","OBJECT","BATTERY","WATCH",
                "TELEVISION","HEADPHONE","PRINTER")) return "💻"

        if (has("ARROW","CHECK","CROSS","PLUS","MINUS","DIVISION","EQUAL","CURRENCY",
                "ZODIAC","SYMBOL","EXCLAMATION","QUESTION","RECYCLE","INFINITY",
                "WARNING","PROHIBITED") || first in 0x2000..0x2BFF) return "🔣"

        return when {
            first in 0x1F680..0x1F6FF -> "🚗"
            first in 0x1F300..0x1F5FF -> "🐾"
            first in 0x1F900..0x1F9FF -> "💻"
            else -> "🔣"
        }
    }
    val categories: LinkedHashMap<String, List<String>> by lazy {
        val grouped = linkedMapOf<String, MutableList<String>>()
        listOf("😀","👤","🌿","🌸","🐾","🍔","🚗","⚽","💻","🔣","🏳️").forEach { grouped[it] = mutableListOf() }
        all.forEach { emoji -> grouped[classify(emoji)]?.add(emoji) }
        linkedMapOf<String, List<String>>().apply {
            put("😀", grouped["😀"].orEmpty())
            put("👤", grouped["👤"].orEmpty())
            put("🌿", grouped["🌿"].orEmpty())
            put("🌸", grouped["🌸"].orEmpty())
            put("🐾", grouped["🐾"].orEmpty())
            put("🍔", grouped["🍔"].orEmpty())
            put("🚗", grouped["🚗"].orEmpty())
            put("⚽", grouped["⚽"].orEmpty())
            put("💻", grouped["💻"].orEmpty())
            put("🔣", grouped["🔣"].orEmpty())
            put("🏳️", grouped["🏳️"].orEmpty())











        }
    }

}
