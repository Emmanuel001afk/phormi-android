package com.uong.phormi

/** Small offline seed lexicon for keyboard suggestions/corrections. It is deliberately conservative. */
object PhormiKeyboardLexicon {
    private val corrections = mapOf(
        "teh" to "the", "adn" to "and", "taht" to "that", "thier" to "their", "recieve" to "receive",
        "seperate" to "separate", "definately" to "definitely", "occured" to "occurred", "becuase" to "because",
        "becouse" to "because", "dont" to "don't", "cant" to "can't", "wont" to "won't", "isnt" to "isn't",
        "didnt" to "didn't", "doesnt" to "doesn't", "shouldnt" to "shouldn't", "couldnt" to "couldn't",
        "wouldnt" to "wouldn't", "im" to "I'm", "ive" to "I've", "ill" to "I'll", "id" to "I'd",
        "youre" to "you're", "youve" to "you've", "theyre" to "they're", "thats" to "that's",
        "whats" to "what's", "wheres" to "where's", "lets" to "let's", "alot" to "a lot"
    )
    private val words = (corrections.values + listOf(
        "about","after","again","always","android","another","answer","anything","because","before","browser","build",
        "change","chat","choose","clear","close","computer","could","create","download","everything","feature","first",
        "foundation","from","good","have","help","hello","important","internet","keyboard","language","learn","like",
        "local","message","more","never","new","now","page","phone","please","privacy","project","quick","really",
        "search","security","settings","should","site","something","start","system","text","that","their","there",
        "these","thing","this","through","today","together","understand","update","use","very","want","website",
        "what","when","where","which","while","with","work","would","you","your"
    )).map { it.lowercase() }.distinct()

    fun correctWord(word: String): String? = corrections[word.lowercase()]

    fun suggestions(prefix: String, limit: Int = 5): List<String> {
        val p = prefix.trim().lowercase()
        if (p.length < 2) return emptyList()
        return words.asSequence()
            .filter { it.startsWith(p) && it != p }
            .sortedWith(compareBy<String> { it.length }.thenBy { it })
            .take(limit)
            .toList()
    }
}
