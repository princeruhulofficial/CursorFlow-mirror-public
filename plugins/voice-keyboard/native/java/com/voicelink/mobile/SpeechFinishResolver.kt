package com.voicelink.mobile

/**
 * Pure helpers for the tap-to-finish speech state machine.
 * Live partials are shown in the UI; on finish we merge them with finals.
 * Across SpeechRecognizer auto-restarts, committed segments must accumulate
 * so long dictation keeps every segment, not only the last restart.
 */
object SpeechFinishResolver {
  fun resolveHeardText(committed: String, latestPartial: String): String {
    val base = committed.trim()
    val partial = latestPartial.trim()
    if (base.isEmpty()) return partial
    if (partial.isEmpty()) return base
    if (base == partial) return base
    if (partial.startsWith(base)) return partial
    if (base.endsWith(partial)) return base
    return "$base $partial".trim()
  }

  fun flushPartialBeforeRestart(committed: String, latestPartial: String): Pair<String, String> {
    val merged = resolveHeardText(committed, latestPartial)
    return merged to ""
  }

  fun appendFinalSegment(committed: String, finalChunk: String): String {
    val chunk = finalChunk.trim()
    if (chunk.isEmpty()) return committed.trim()
    val base = committed.trim()
    if (base.isEmpty()) return chunk
    if (base == chunk) return base
    if (base.endsWith(chunk)) return base
    if (chunk.startsWith(base)) return chunk
    return "$base $chunk".trim()
  }

  fun shouldRestartListening(userWantsListening: Boolean, finishing: Boolean, sessionMatches: Boolean): Boolean =
    userWantsListening && !finishing && sessionMatches

  fun canCommit(text: String, sessionMatches: Boolean, alreadyCommitted: Boolean, fieldAllowed: Boolean): Boolean =
    text.isNotBlank() && sessionMatches && !alreadyCommitted && fieldAllowed
}

class DictationSegments {
  val finals = ArrayList<String>()
  var partial: String = ""
  private var lastFlushed = false
  private var lastFinalAt = 0L

  fun addFinal(chunk: String, now: Long = System.currentTimeMillis()): String? {
    val c = chunk.trim()
    partial = ""
    if (c.isEmpty()) return null
    val last = finals.lastOrNull()
    if (last != null && lastFlushed) {
      lastFlushed = false
      if (c == last) return null
      if (c.startsWith(last)) {
        finals[finals.size - 1] = c
        lastFinalAt = now
        return c.removePrefix(last).trim().ifEmpty { null }
      }
    } else if (last != null && c == last && now - lastFinalAt < 1_500) {
      return null
    }
    finals.add(c)
    lastFlushed = false
    lastFinalAt = now
    return c
  }

  fun flushPartial(): String? {
    val p = partial.trim()
    partial = ""
    if (p.isEmpty()) return null
    if (finals.lastOrNull() == p) return null
    finals.add(p)
    lastFlushed = true
    return p
  }

  fun plain(): String = (finals + partial.trim()).filter { it.isNotBlank() }.joinToString(" ")
  fun marked(): String = FlowRulePass.joinSegments(finals + partial.trim())
  fun isEmpty(): Boolean = finals.isEmpty() && partial.isBlank()
  fun clear() { finals.clear(); partial = ""; lastFlushed = false; lastFinalAt = 0L }
}
