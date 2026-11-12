package com.voicelink.mobile

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Production-oriented glide / swipe typing decoder.
 * Shape matching inspired by SHARK2 / elastic matching literature
 * (Kristensson & Zhai; original Cursor Flow implementation, not a port).
 *
 * Scores candidates by:
 *  1) first/last key agreement
 *  2) subsequence coverage of the gesture char stream
 *  3) elastic path-length similarity vs word ideal length
 *  4) dictionary frequency prior
 */
object GlideDecoder {
  data class Point(val x: Float, val y: Float)

  /** Smooth a raw gesture with a short moving average (path smoothing). */
  fun smoothPath(raw: List<Point>, window: Int = 3): List<Point> {
    if (raw.size <= 2) return raw
    val w = max(1, window)
    val out = ArrayList<Point>(raw.size)
    for (i in raw.indices) {
      var sx = 0f
      var sy = 0f
      var n = 0
      for (j in max(0, i - w)..min(raw.lastIndex, i + w)) {
        sx += raw[j].x
        sy += raw[j].y
        n++
      }
      out.add(Point(sx / n, sy / n))
    }
    return out
  }

  /** Decode from ordered key labels visited during the gesture. */
  fun decode(
    chars: List<Char>,
    lang: String,
    learned: List<String>,
    limit: Int = 3,
  ): List<String> {
    if (chars.size < 2) return emptyList()
    val seq = chars.joinToString("").lowercase()
    val first = seq.first()
    val last = seq.last()
    val pool = LinkedHashSet<String>()
    learned.forEach { if (it.length >= 2) pool.add(it) }
    SuggestEngine.wordList(lang).forEach { w ->
      if (w.length in 2..24) {
        val lw = w.lowercase()
        if (lw.first() == first && lw.last() == last) pool.add(w)
      }
    }
    if (pool.isEmpty()) return emptyList()

    data class Scored(val word: String, val score: Double)
    val scored = ArrayList<Scored>(pool.size)
    for (word in pool) {
      val s = scoreWord(seq, word.lowercase(), lang)
      if (s > Double.NEGATIVE_INFINITY / 4) scored.add(Scored(word, s))
    }
    return scored.sortedByDescending { it.score }.map { it.word }.distinct().take(limit)
  }

  /**
   * Decode using geometric key centers when available (proximity scoring).
   * [idealCenters] maps lowercase letter -> key center; [path] is smoothed gesture.
   */
  fun decodeGeometric(
    path: List<Point>,
    idealCenters: Map<Char, Point>,
    lang: String,
    learned: List<String>,
    limit: Int = 3,
  ): List<String> {
    if (path.size < 3 || idealCenters.isEmpty()) return emptyList()
    val smoothed = smoothPath(path)
    val nearest = smoothed.map { p ->
      idealCenters.minByOrNull { (_, c) -> dist2(p, c) }?.key ?: '?'
    }
    // Collapse consecutive duplicates
    val collapsed = ArrayList<Char>()
    for (c in nearest) {
      if (c == '?') continue
      if (collapsed.isEmpty() || collapsed.last() != c) collapsed.add(c)
    }
    val fromShape = decode(collapsed, lang, learned, limit * 2)
    // Re-rank with elastic geometric distance when possible
    data class Scored(val word: String, val score: Double)
    val scored = fromShape.map { word ->
      val ideal = word.lowercase().mapNotNull { idealCenters[it] }
      val geo = if (ideal.size >= 2) elasticDistance(smoothed, ideal) else 1e6
      val freq = SuggestEngine.frequency(word, lang)
      Scored(word, -geo + freq * 0.35)
    }.sortedByDescending { it.score }
    return scored.map { it.word }.distinct().take(limit)
  }

  private fun scoreWord(seq: String, word: String, lang: String): Double {
    if (word.length < 2) return Double.NEGATIVE_INFINITY
    if (word.first() != seq.first() || word.last() != seq.last()) return Double.NEGATIVE_INFINITY
    // Subsequence coverage (elastic along char stream)
    var i = 0
    var covered = 0
    for (c in word) {
      val idx = seq.indexOf(c, i)
      if (idx < 0) return Double.NEGATIVE_INFINITY
      covered++
      i = idx + 1
    }
    val lengthPenalty = abs(word.length - estimateIdealLength(seq.length)).toDouble()
    val skipPenalty = (seq.length - covered).toDouble() * 0.15
    val freq = SuggestEngine.frequency(word, lang)
    return covered * 2.0 - lengthPenalty * 0.4 - skipPenalty + freq * 0.5
  }

  private fun estimateIdealLength(gestureChars: Int): Int =
    // Gestures usually visit ~1.4 keys per letter on average
    max(2, (gestureChars / 1.35f).toInt())

  /** Normalized elastic matching distance between gesture path and ideal polyline. */
  fun elasticDistance(path: List<Point>, ideal: List<Point>): Double {
    if (path.isEmpty() || ideal.isEmpty()) return 1e6
    val a = resample(path, 24)
    val b = resample(ideal, 24)
    var sum = 0.0
    for (i in a.indices) sum += sqrt(dist2(a[i], b[i]).toDouble())
    return sum / a.size
  }

  private fun resample(pts: List<Point>, n: Int): List<Point> {
    if (pts.size == 1) return List(n) { pts[0] }
    val lengths = DoubleArray(pts.size)
    for (i in 1 until pts.size) {
      lengths[i] = lengths[i - 1] + sqrt(dist2(pts[i - 1], pts[i]).toDouble())
    }
    val total = lengths.last().coerceAtLeast(1e-3)
    val out = ArrayList<Point>(n)
    for (k in 0 until n) {
      val target = total * k / (n - 1).coerceAtLeast(1)
      var i = 1
      while (i < lengths.size && lengths[i] < target) i++
      val i0 = (i - 1).coerceAtLeast(0)
      val i1 = i.coerceAtMost(pts.lastIndex)
      val seg = (lengths[i1] - lengths[i0]).coerceAtLeast(1e-6)
      val t = ((target - lengths[i0]) / seg).toFloat()
      out.add(
        Point(
          pts[i0].x + (pts[i1].x - pts[i0].x) * t,
          pts[i0].y + (pts[i1].y - pts[i0].y) * t,
        ),
      )
    }
    return out
  }

  private fun dist2(a: Point, b: Point): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return dx * dx + dy * dy
  }
}
