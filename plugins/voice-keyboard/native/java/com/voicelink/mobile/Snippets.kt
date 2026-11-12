package com.voicelink.mobile

import android.content.Context
import org.json.JSONArray

/** Text shortcuts saved in the app ("my email" -> the saved address), applied to live and final keyboard text. */
object Snippets {
  private const val PREFS = "cursorflow_snippets"
  private const val KEY = "json"
  @Volatile private var cachedJson: String? = null
  @Volatile private var cached: List<Pair<Regex, String>> = emptyList()

  fun save(context: Context, json: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.take(40_000)).apply()
    cachedJson = null
  }

  private fun rules(context: Context): List<Pair<Regex, String>> {
    val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
    if (json == cachedJson) return cached
    val pairs = mutableListOf<Pair<String, String>>()
    runCatching {
      val arr = JSONArray(json)
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val expansion = o.optString("expansion").trim()
        if (expansion.isEmpty()) continue
        o.optString("trigger").split(',', '،').map { it.trim() }.filter { it.length >= 2 }.forEach { pairs.add(it to expansion) }
      }
    }
    val built = pairs.sortedByDescending { it.first.length }.map { (trigger, expansion) ->
      val words = trigger.split(Regex("\\s+")).joinToString("[\\s,]+") { Regex.escape(it) }
      Regex("(^|[^\\p{L}\\p{M}\\p{N}@])$words(?![\\p{L}\\p{M}\\p{N}])[.।]?", setOf(RegexOption.IGNORE_CASE)) to expansion
    }
    cached = built
    cachedJson = json
    return built
  }

  fun apply(context: Context, text: String): String {
    if (text.isBlank()) return text
    var out = text
    for ((re, expansion) in runCatching { rules(context) }.getOrDefault(emptyList())) {
      out = re.replace(out) { m -> m.groupValues[1] + expansion }
    }
    return out
  }
}
