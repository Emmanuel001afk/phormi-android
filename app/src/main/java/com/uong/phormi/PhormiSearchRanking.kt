package com.uong.phormi

import java.util.Locale

/**
 * Deterministic, on-device merging of provider results.
 *
 * Every responding provider contributes equally: earlier positions contribute more,
 * cross-provider agreement adds a bonus, and actual query terms matter. A small
 * domain-diversity pass then prevents one site from crowding out independent answers.
 */
object PhormiSearchRanking {
    private const val MAX_RESULTS = 30

    fun merge(raw: List<PhormiSearchResult>, query: String): List<PhormiSearchResult> {
        val grouped = linkedMapOf<String, MutableList<PhormiSearchResult>>()
        raw.forEach { result ->
            val url = PhormiSearchUrls.webUrlOrNull(result.url) ?: return@forEach
            grouped.getOrPut(PhormiSearchUrls.canonical(url)) { mutableListOf() }.add(result.copy(url = url))
        }
        val queryTokens = query.lowercase(Locale.US)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .distinct()

        val scored = grouped.values.map { group ->
            val best = group.minByOrNull { it.rank } ?: group.first()
            val snippet = group.maxByOrNull { it.snippet.length }?.snippet.orEmpty()
            val sourceNames = group.map { it.source }.distinct()
            val engineContribution = group.sumOf { (80 - (it.rank * 7)).coerceAtLeast(15) }
            val agreementBonus = (sourceNames.size - 1) * 24
            val searchable = "${best.title} $snippet".lowercase(Locale.US)
            val queryMatchBonus = queryTokens.sumOf { token ->
                when {
                    Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(searchable) -> 35
                    searchable.contains(token) -> 15
                    else -> 0
                }
            }
            best.copy(
                snippet = snippet,
                source = sourceNames.joinToString(" · "),
                rank = engineContribution + agreementBonus + queryMatchBonus
            )
        }.sortedByDescending { it.rank }.take(MAX_RESULTS)

        // Compute the diversity penalty in one stable pass. Sorting with a comparator
        // that mutates domain counts (the old approach) can invoke the comparator a
        // different number of times per platform and produce non-deterministic order.
        val domainCounts = mutableMapOf<String, Int>()
        val adjusted = scored.map { result ->
            val domain = PhormiSearchUrls.host(result.url)
            val seen = domainCounts.getOrDefault(domain, 0)
            domainCounts[domain] = seen + 1
            result to (result.rank - seen * 8)
        }
        return adjusted.sortedByDescending { it.second }
            .mapIndexed { index, pair -> pair.first.copy(rank = index) }
    }
}
