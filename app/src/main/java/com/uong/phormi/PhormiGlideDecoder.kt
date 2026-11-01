package com.uong.phormi

import java.text.Normalizer
import java.util.Locale
import kotlin.math.*

/** Offline geometry decoder. Coordinates are in key-width units, not device pixels. */
object PhormiGlideDecoder {
    val languages = setOf("en", "fr", "es", "pt", "de", "it", "id", "tr")
    data class Point(val x: Float, val y: Float)
    data class Candidate(val word: String, val score: Float)
    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
    private fun fold(word: String, locale: Locale): String = Normalizer.normalize(word.lowercase(locale), Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    internal fun sample(points: List<Point>, count: Int = 24): List<Point> {
        if (points.isEmpty()) return emptyList()
        val lengths = FloatArray(points.size)
        for (i in 1 until points.size) lengths[i] = lengths[i - 1] + distance(points[i - 1], points[i])
        val total = lengths.last()
        if (total < 0.001f) return List(count) { points.first() }
        var segment = 1
        return List(count) { i ->
            val target = total * i / (count - 1)
            while (segment < lengths.lastIndex && lengths[segment] < target) segment++
            val span = lengths[segment] - lengths[segment - 1]
            val t = if (span <= 0f) 0f else (target - lengths[segment - 1]) / span
            val a = points[segment - 1]; val b = points[segment]
            Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
    }
    private fun alignment(a: List<Point>, b: List<Point>): Float {
        var previous = FloatArray(b.size + 1) { Float.POSITIVE_INFINITY }.also { it[0] = 0f }
        for (i in a.indices) {
            val next = FloatArray(b.size + 1) { Float.POSITIVE_INFINITY }
            for (j in max(0, i - 5)..min(b.lastIndex, i + 5)) {
                val cost = distance(a[i], b[j])
                next[j + 1] = cost + min(previous[j], min(previous[j + 1], next[j]))
            }
            previous = next
        }
        return previous.last() / a.size
    }
    fun decode(trace: List<Point>, keys: Map<Char, Point>, words: List<String>, locale: Locale, limit: Int = 3): List<Candidate> {
        if (trace.size < 3 || keys.isEmpty() || limit <= 0) return emptyList()
        if (trace.zipWithNext().sumOf { distance(it.first, it.second).toDouble() } < 1.3) return emptyList()
        val path = sample(trace)
        data class Match(val word: String, val rank: Int, val shape: List<Point>, val coarse: Float, val endpoints: Float)
        val shortlist = words.asSequence().mapIndexedNotNull { rank, word ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Gesture cancelled")
            val letters = fold(word, locale)
            if (letters.length !in 2..24) return@mapIndexedNotNull null
            val centers = letters.map { keys[it] ?: return@mapIndexedNotNull null }.fold(mutableListOf<Point>()) { result, point ->
                if (result.lastOrNull() != point) result.add(point)
                result
            }
            if (centers.size < 2) return@mapIndexedNotNull null
            val start = distance(path.first(), centers.first()); val end = distance(path.last(), centers.last())
            if (start > 1.35f || end > 1.35f) return@mapIndexedNotNull null
            val shape = sample(centers)
            val coarse = path.indices.sumOf { distance(path[it], shape[it]).toDouble() }.toFloat() / path.size
            if (coarse > 1.8f) null else Match(word, rank, shape, coarse, start + end)
        }.sortedBy { it.coarse + it.endpoints * 0.35f }.take(96).toList()
        return shortlist.map { match ->
            Candidate(match.word, alignment(path, match.shape) + match.endpoints * 0.35f + ln(1f + match.rank) * 0.014f)
        }.filter { it.score <= 1.15f }.sortedBy { it.score }.take(limit)
    }
}
