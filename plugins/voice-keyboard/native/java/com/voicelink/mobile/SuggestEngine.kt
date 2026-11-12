package com.voicelink.mobile

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/**
 * Frequency-dictionary suggestion / autocorrect / next-word engine.
 * Dictionaries: hermitdave/FrequencyWords (MIT) packed as assets/dicts/{lang}.txt.gz
 * Loaded lazily per language; kept in memory after first use.
 */
object SuggestEngine {
  @Volatile private var appContext: Context? = null
  private val lexicons = ConcurrentHashMap<String, LinkedHashMap<String, Int>>()
  private val bigrams = ConcurrentHashMap<String, LinkedHashMap<String, Int>>()
  private val loading = ConcurrentHashMap.newKeySet<String>()

  fun init(context: Context) {
    if (appContext == null) appContext = context.applicationContext
  }

  fun ensureLoaded(lang: String) {
    val key = normalizeLang(lang)
    if (lexicons.containsKey(key)) return
    val ctx = appContext ?: return
    // Serialized: a second caller waits for the load in progress instead of seeing an empty
    // lexicon (the prefetch thread and the first keystroke used to race, leaving no suggestions).
    synchronized(this) { if (!lexicons.containsKey(key)) load(ctx, key) }
  }

  private fun load(ctx: Context, key: String) {
    if (!loading.add(key)) return
    try {
      val map = LinkedHashMap<String, Int>(32768)
      openGzip(ctx, "dicts/$key.txt.gz")?.use { reader ->
        reader.forEachLine { line ->
          val parts = line.split('\t')
          if (parts.isEmpty()) return@forEachLine
          val w = parts[0].trim()
          if (w.isEmpty() || w.length > 40) return@forEachLine
          val f = parts.getOrNull(1)?.toIntOrNull() ?: 1
          map[w] = f
        }
      }
      if (map.isNotEmpty()) lexicons[key] = map
      if (key == "en") {
        val bg = LinkedHashMap<String, Int>(128)
        openGzip(ctx, "dicts/en_bigrams.txt.gz")?.use { reader ->
          reader.forEachLine { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@forEachLine
            bg[parts[0].trim().lowercase()] = parts[1].toIntOrNull() ?: 1
          }
        }
        if (bg.isNotEmpty()) bigrams["en"] = bg
      }
    } catch (_: Throwable) {
      // fall through to empty; callers still have learned words
    } finally {
      loading.remove(key)
    }
  }

  private fun openGzip(ctx: Context, assetPath: String): BufferedReader? =
    runCatching {
      BufferedReader(InputStreamReader(GZIPInputStream(ctx.assets.open(assetPath)), Charsets.UTF_8))
    }.getOrNull()

  private fun normalizeLang(lang: String) = when (lang.lowercase()) {
    "bn", "bangla", "bengali" -> "bn"
    "hi", "hindi" -> "hi"
    else -> "en"
  }

  fun lexicon(lang: String): Map<String, Int> {
    val key = normalizeLang(lang)
    ensureLoaded(key)
    return lexicons[key] ?: emptyMap()
  }

  fun wordList(lang: String): Collection<String> = lexicon(lang).keys

  fun suggest(
    prefix: String,
    lang: String,
    learned: List<String>,
    limit: Int = 3,
    previousWord: String = "",
  ): List<String> {
    val key = normalizeLang(lang)
    ensureLoaded(key)
    val p = prefix.trim()
    val pool = lexicon(key)
    if (p.isEmpty()) {
      return nextWordSuggestions(previousWord, key, learned, limit)
    }
    val lower = if (key == "en") p.lowercase() else p
    val starts = ArrayList<Pair<String, Double>>(64)
    for (w in learned) {
      if (w.startsWith(lower, ignoreCase = true) && !w.equals(p, true)) {
        starts.add(w to (1_000_000.0 + (pool[w.lowercase()] ?: pool[w] ?: 50)))
      }
    }
    for ((w, f) in pool) {
      if (w.startsWith(lower, ignoreCase = true) && !w.equals(p, true)) {
        starts.add(w to f.toDouble())
      }
      if (starts.size > 400) break
    }
    if (starts.isNotEmpty()) {
      return starts.sortedByDescending { it.second }.map { it.first }.distinct().take(limit)
    }
    // edit-distance autocorrect candidates
    return pool.asSequence()
      .filter { abs(it.key.length - p.length) <= 1 }
      .map { it.key to editDistance(lower, it.key.lowercase()) }
      .filter { it.second in 1..2 }
      .sortedWith(compareBy({ it.second }, { -(pool[it.first] ?: 0) }))
      .map { it.first }
      .distinct()
      .take(limit)
      .toList()
  }

  fun nextWordSuggestions(previousWord: String, lang: String, learned: List<String>, limit: Int): List<String> {
    val key = normalizeLang(lang)
    ensureLoaded(key)
    val prev = previousWord.trim().lowercase()
    // Start of a field or sentence: offer the most common words so the bar is useful at once.
    if (prev.isEmpty()) return (learned.filter { it.isNotBlank() }.take(1) + (lexicons[key]?.keys?.take(limit + 1) ?: emptyList())).distinct().take(limit)
    val out = ArrayList<String>()
    val bg = bigrams[key]
    if (bg != null) {
      bg.entries
        .filter { it.key.startsWith("$prev ") }
        .sortedByDescending { it.value }
        .map { it.key.substringAfter(' ') }
        .forEach { if (it.isNotEmpty() && it !in out) out.add(it) }
    }
    // Soft fallback: high-frequency short function words after common starters
    if (out.size < limit && key == "en") {
      val soft = when (prev) {
        "i" -> listOf("am", "have", "will", "would", "can", "need")
        "the" -> listOf("best", "first", "same", "other", "next")
        "to" -> listOf("be", "the", "do", "get", "make")
        "a" -> listOf("lot", "new", "good", "great", "little")
        else -> emptyList()
      }
      soft.forEach { if (it !in out) out.add(it) }
    }
    learned.filter { it.isNotBlank() }.take(2).forEach { if (it !in out) out.add(it) }
    return out.take(limit)
  }

  fun autocorrect(token: String, lang: String, learned: List<String>): String? {
    val t = token.trim()
    if (t.length < 2) return null
    val key = normalizeLang(lang)
    ensureLoaded(key)
    val pool = lexicon(key)
    val lower = t.lowercase()
    if (pool.containsKey(lower) || pool.containsKey(t) || learned.any { it.equals(t, true) }) return null
    val best = pool.asSequence()
      .filter { abs(it.key.length - t.length) <= 1 }
      .map { it.key to editDistance(lower, it.key.lowercase()) }
      .filter { it.second == 1 }
      .minWithOrNull(compareBy({ it.second }, { -(pool[it.first] ?: 0) }))
      ?: return null
    return if (t[0].isUpperCase()) best.first.replaceFirstChar { it.uppercase() } else best.first
  }

  /** Legacy single-best glide resolve kept for callers; prefer GlideDecoder. */
  fun resolveGlide(chars: List<Char>, lang: String, learned: List<String>): String? =
    resolveGlideCandidates(chars, lang, learned, 1).firstOrNull()

  fun resolveGlideCandidates(
    chars: List<Char>,
    lang: String,
    learned: List<String>,
    limit: Int = 3,
  ): List<String> {
    if (chars.size < 2) return emptyList()
    return GlideDecoder.decode(chars, normalizeLang(lang), learned, limit)
  }

  fun frequency(word: String, lang: String): Double {
    val key = normalizeLang(lang)
    ensureLoaded(key)
    val f = lexicon(key)[word.lowercase()] ?: lexicon(key)[word] ?: 0
    return if (f <= 0) 0.0 else ln(1.0 + f.toDouble())
  }

  private fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    if (abs(a.length - b.length) > 2) return 99
    val m = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
      var prev = m[0]
      m[0] = i
      for (j in 1..b.length) {
        val cur = m[j]
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        m[j] = min(m[j] + 1, min(m[j - 1] + 1, prev + cost))
        prev = cur
      }
    }
    return m[b.length]
  }
}
