package com.uong.phormi

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.os.Build
import java.util.Locale

/**
 * Platform-aware Unicode emoji palette. Android 8+ ICU exposes the Unicode Emoji
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
        val result = ArrayList<Int>()
        fun scan(start: Int, end: Int) {
            for (cp in start..end) {
                if (UCharacter.hasBinaryProperty(cp, UProperty.EMOJI) && cp !in 0x1F1E6..0x1F1FF && cp !in 0x1F3FB..0x1F3FF) result.add(cp)
            }
        }
        scan(0, 0x2FFF)
        scan(0x1F000, 0x1FAFF)
        return result
    }

    private fun modifierBases(): List<Int> {
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
        result.addAll(countryFlags())
        result.addAll(unicode17Additions)
        result.addAll(specialSequences)
        result.addAll((0..9).map { "$it\uFE0F\u20E3" } + listOf("#️⃣", "*️⃣"))
        result.removeAll(listOf("#", "*", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"))
        val paint = android.graphics.Paint().apply { textSize = 32f }
        result.filter { paint.hasGlyph(it) }
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

    private val names by lazy { all.associateWith { unicodeName(it).lowercase(Locale.ROOT) } }
    fun description(emoji: String): String = unicodeName(emoji).trim().lowercase(Locale.ROOT).ifBlank { "Emoji" }
    fun withTone(emoji: String, tone: Int): String {
        if (tone !in 1..5 || emoji.codePoints().anyMatch { it in 0x1F3FB..0x1F3FF }) return emoji
        val output = StringBuilder()
        var i = 0
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i); output.appendCodePoint(cp); i += Character.charCount(cp)
            if (UCharacter.hasBinaryProperty(cp, UProperty.EMOJI_MODIFIER_BASE)) {
                if (i < emoji.length && emoji.codePointAt(i) == 0xFE0F) i += 1
                output.appendCodePoint(0x1F3FA + tone)
            }
        }
        val value = output.toString()
        return if (android.graphics.Paint().hasGlyph(value)) value else emoji
    }
    fun toneVariants(emoji: String): List<String> = (listOf(emoji) + (1..5).map { withTone(emoji, it) }).distinct()
    fun search(query: String): List<String> {
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return all.take(50)
        val aliases = mapOf("laugh" to "joy", "lol" to "joy", "love" to "heart", "thanks" to "folded hands", "happy" to "smil", "sad" to "cry", "party" to "party", "dog" to "dog", "cat" to "cat")
        return all.filter { emoji -> terms.all { term -> names[emoji].orEmpty().contains(term) || names[emoji].orEmpty().contains(aliases[term] ?: term) || emoji == term } }.take(160)
    }

    private fun isFlag(emoji: String): Boolean = emoji.codePointCount(0, emoji.length) == 2 &&
        emoji.codePointAt(0) in 0x1F1E6..0x1F1FF && emoji.codePointAt(emoji.offsetByCodePoints(0, 1)) in 0x1F1E6..0x1F1FF

    private fun classify(emoji: String): String {
        if (isFlag(emoji)) return "🏳️"

        val name = unicodeName(emoji)
        val first = emoji.codePointAt(0)
        val tokens = name.split(Regex("[^A-Z0-9]+")).filter { it.isNotBlank() }.toSet()
        fun has(vararg values: String): Boolean = values.any { it in tokens }

        // Keep the order close to the standard emoji keyboard taxonomy:
        // smileys, people, animals/nature, food, activity, travel/places,
        // objects, symbols, flags.
        if (first in 0x1F600..0x1F64F ||
            has("FACE","EMOTION","SMILING","GRINNING","KISSING","CRYING","ANGRY","LAUGH","EXPRESSION")) return "😀"

        if (has("MAN","WOMAN","BOY","GIRL","BABY","PERSON","PEOPLE","HAND","ARM","LEG","BODY","SKIN","FINGER","FOOT","EAR","EYE","MOUTH","NOSE","HAIR","FAMILY","COUPLE","HUMAN","PROFESSION","WORKER","POLICE","GUARD","MEDICAL","STUDENT","TEACHER","COOK","SCIENTIST","TECHNOLOGIST")) return "👤"

        if (has("CAT","DOG","MOUSE","RABBIT","FOX","BEAR","MONKEY","BIRD","FISH","BUG","INSECT","WOLF","LION","HORSE","TIGER","ELEPHANT","PANDA","PIG","COW","CHICKEN","SNAKE","TURTLE","DOLPHIN","WHALE","ANIMAL","BAT","OTTER","FROG","OCTOPUS","BUTTERFLY","MAMMAL","AMPHIBIAN","REPTILE","MARINE","FLOWER","ROSE","TULIP","SUNFLOWER","BLOSSOM","HIBISCUS","CHERRY_BLOSSOM","LOTUS","BOUQUET","DAISY","TREE","LEAF","HERB","SEEDLING","CACTUS","PLANT","MUSHROOM","POTTED","ROOT","SHAMROCK","PALM","EVERGREEN","DECIDUOUS","SPROUT","SEED","FERN","NATURE")) return "🐾"

        if (has("FRUIT","APPLE","BANANA","GRAPES","STRAWBERRY","WATERMELON","PINEAPPLE","MANGO","LEMON","PEACH","PEAR","CHERRY","KIWI","MELON","BLUEBERRY","VEGETABLE","CARROT","POTATO","CORN","CUCUMBER","BROCCOLI","GARLIC","ONION","BREAD","CHEESE","PIZZA","BURGER","SUSHI","RAMEN","CAKE","COOKIE","CANDY","CHOCOLATE","COFFEE","TEA","DRINK","FOOD","MEAL","DESSERT","PLATE","BOWL","HOTDOG","POPCORN","ICE_CREAM")) return "🍔"

        if (has("SPORT","BALL","GAME","MEDAL","TROPHY","RACING","SKI","SWIM","DANCE","MICROPHONE","GUITAR","DRUM","THEATER","ART","CRAFT","MUSIC","PARTY","ACTIVITY","CARD","PUZZLE","CHESS")) return "⚽"

        if (has("CAR","TAXI","BUS","TRAIN","AIRPLANE","SHIP","BOAT","BICYCLE","MOTORCYCLE","ROAD","BUILDING","HOUSE","CASTLE","MOUNTAIN","MAP","GLOBE","TRAVEL","STATION","HOTEL","BRIDGE","ROCKET","SIGN","LOCATION","PLACE","TRANSPORT","CITY","COUNTRY","BEACH","CAMPING")) return "🚗"

        if (has("PHONE","COMPUTER","KEYBOARD","LIGHT","BOOK","PAPER","MONEY","LOCK","KEY","CLOCK","CAMERA","BELL","GIFT","SCISSORS","PENCIL","MEMO","FOLDER","LINK","MAGNIFY","TRASH","TOOL","BOTTLE","BATTERY","WATCH","TELEVISION","HEADPHONE","PRINTER","OBJECT","OFFICE","HOUSEHOLD","CLOTHING","SHOE","BAG","MAIL","MAILBOX")) return "💻"

        if (has("ARROW","CHECK","CROSS","PLUS","MINUS","DIVISION","EQUAL","CURRENCY","ZODIAC","SYMBOL","EXCLAMATION","QUESTION","RECYCLE","INFINITY","WARNING","PROHIBITED","RELIGION","PEACE","RADIOACTIVE","BIOHAZARD","HEART","LOVE") || first in 0x2000..0x2BFF) return "🔣"

        return "🔣"
    }

    val categories: LinkedHashMap<String, List<String>> by lazy {
        val order = listOf("😀","👤","🐾","🍔","⚽","🚗","💻","🔣","🏳️")
        val grouped = linkedMapOf<String, MutableList<String>>()
        order.forEach { grouped[it] = mutableListOf() }
        all.forEach { emoji -> grouped.getValue(classify(emoji)).add(emoji) }
        LinkedHashMap<String, List<String>>().apply {
            order.forEach { put(it, grouped.getValue(it)) }
        }
    }

}
