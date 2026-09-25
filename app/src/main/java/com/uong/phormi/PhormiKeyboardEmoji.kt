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

        // Keep the broad categories stable, but classify by Unicode names/ranges
        // before falling back. This avoids the old "everything else -> computer"
        // bucket that made unrelated emojis appear in the wrong tab.
        if (first in 0x1F600..0x1F64F || name.contains("FACE") || name.contains("EMOTION") ||
            name.contains("SMILING") || name.contains("GRINNING") || name.contains("KISSING") ||
            name.contains("CRYING") || name.contains("ANGRY")) return "😀"

        if (name.contains("PERSON") || name.contains("PEOPLE") || name.contains("MAN") ||
            name.contains("WOMAN") || name.contains("BOY") || name.contains("GIRL") ||
            name.contains("BABY") || name.contains("HAND") || name.contains("ARM") ||
            name.contains("LEG") || name.contains("BODY") || name.contains("SKIN")) return "👤"

        if (name.contains("CAT") || name.contains("DOG") || name.contains("MOUSE") ||
            name.contains("RABBIT") || name.contains("FOX") || name.contains("BEAR") ||
            name.contains("MONKEY") || name.contains("BIRD") || name.contains("FISH") ||
            name.contains("BUG") || name.contains("INSECT") || name.contains("WOLF") ||
            name.contains("LION") || name.contains("HORSE") || name.contains("TIGER") ||
            name.contains("ELEPHANT") || name.contains("PANDA") || name.contains("PIG") ||
            name.contains("COW") || name.contains("CHICKEN") || name.contains("SNAKE") ||
            name.contains("TURTLE") || name.contains("DOLPHIN") || name.contains("WHALE") ||
            name.contains("ANIMAL")) return "🐾"

        if (name.contains("FLOWER") || name.contains("TREE") || name.contains("LEAF") ||
            name.contains("HERB") || name.contains("SEEDLING") || name.contains("CACTUS") ||
            name.contains("PLANT") || name.contains("MUSHROOM") || name.contains("ROSE") ||
            name.contains("BLOSSOM") || name.contains("TULIP") || name.contains("SUNFLOWER") ||
            name.contains("MOON") || name.contains("SUN") || name.contains("STAR") ||
            name.contains("RAIN") || name.contains("CLOUD") || name.contains("SNOW") ||
            name.contains("FIRE") || name.contains("WATER") || name.contains("EARTH") ||
            name.contains("WEATHER")) return "🌿"

        if (name.contains("FRUIT") || name.contains("APPLE") || name.contains("BANANA") ||
            name.contains("GRAPES") || name.contains("STRAWBERRY") || name.contains("WATERMELON") ||
            name.contains("PINEAPPLE") || name.contains("MANGO") || name.contains("LEMON") ||
            name.contains("PEACH") || name.contains("PEAR") || name.contains("CHERR") ||
            name.contains("KIWI") || name.contains("MELON") || name.contains("BLUEBERR")) return "🍎"

        if (name.contains("FOOD") || name.contains("DRINK") || name.contains("MEAL") ||
            name.contains("CAKE") || name.contains("COOKIE") || name.contains("CANDY") ||
            name.contains("CHOCOLATE") || name.contains("BREAD") || name.contains("CHEESE") ||
            name.contains("PIZZA") || name.contains("BURGER") || name.contains("COFFEE") ||
            name.contains("TEA") || name.contains("BEER") || name.contains("WINE") ||
            name.contains("BOTTLE") || name.contains("SUSHI")) return "🍔"

        if (name.contains("CAR") || name.contains("BUS") || name.contains("TRAIN") ||
            name.contains("AIRPLANE") || name.contains("SHIP") || name.contains("BOAT") ||
            name.contains("BICYCLE") || name.contains("MOTORCYCLE") || name.contains("ROAD") ||
            name.contains("BUILDING") || name.contains("HOUSE") || name.contains("CASTLE") ||
            name.contains("MOUNTAIN") || name.contains("MAP") || name.contains("GLOBE") ||
            name.contains("TRAVEL") || name.contains("STATION")) return "🚗"

        if (name.contains("SPORT") || name.contains("BALL") || name.contains("GAME") ||
            name.contains("MEDAL") || name.contains("TROPHY") || name.contains("MUSIC") ||
            name.contains("PARTY") || name.contains("RACING") || name.contains("SKI") ||
            name.contains("SWIM") || name.contains("DANCE") || name.contains("MICROPHONE") ||
            name.contains("GUITAR") || name.contains("DRUM") || name.contains("THEATER")) return "⚽"

        if (name.contains("ARROW") || name.contains("CHECK") || name.contains("CROSS") ||
            name.contains("PLUS") || name.contains("MINUS") || name.contains("DIVISION") ||
            name.contains("EQUAL") || name.contains("CURRENCY") || name.contains("ZODIAC") ||
            name.contains("SYMBOL") || name.contains("HEART") || name.contains("EXCLAMATION") ||
            name.contains("QUESTION") || name.contains("RECYCLE") || first in 0x2000..0x2BFF) return "🔣"

        if (name.contains("PHONE") || name.contains("COMPUTER") || name.contains("KEYBOARD") ||
            name.contains("LIGHT") || name.contains("BOOK") || name.contains("PAPER") ||
            name.contains("MONEY") || name.contains("LOCK") || name.contains("KEY") ||
            name.contains("CLOCK") || name.contains("CAMERA") || name.contains("BELL") ||
            name.contains("GIFT") || name.contains("SCISSORS") || name.contains("PENCIL") ||
            name.contains("MEMO") || name.contains("FOLDER") || name.contains("LINK") ||
            name.contains("MAGNIFY") || name.contains("TRASH") || name.contains("TOOL") ||
            name.contains("OBJECT")) return "💻"

        // Use the Unicode block as a safer final fallback than assigning every
        // unknown emoji to a semantically unrelated category.
        return when {
            first in 0x1F680..0x1F6FF -> "🚗"
            first in 0x1F300..0x1F5FF -> "🌿"
            first in 0x1F900..0x1F9FF -> "💻"
            else -> "🔣"
        }
    }
    val categories: LinkedHashMap<String, List<String>> by lazy {
        val grouped = linkedMapOf<String, MutableList<String>>()
        listOf("😀","👤","🐾","🌿","🍎","🍔","🚗","⚽","💻","🔣","🏳️").forEach { grouped[it] = mutableListOf() }
        all.forEach { emoji -> grouped[classify(emoji)]?.add(emoji) }
        linkedMapOf<String, List<String>>().apply {
            put("😀", grouped["😀"].orEmpty())
            put("👤", grouped["👤"].orEmpty())
            put("🐾", grouped["🐾"].orEmpty())
            put("🌿", grouped["🌿"].orEmpty())
            put("🍎", grouped["🍎"].orEmpty())
            put("🍔", grouped["🍔"].orEmpty())
            put("🚗", grouped["🚗"].orEmpty())
            put("⚽", grouped["⚽"].orEmpty())
            put("💻", grouped["💻"].orEmpty())
            put("🔣", grouped["🔣"].orEmpty())
            put("🏳️", grouped["🏳️"].orEmpty())
        }
    }

}
