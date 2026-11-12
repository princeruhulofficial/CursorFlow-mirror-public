package com.voicelink.mobile

import java.text.Normalizer

/**
 * Real-time spoken edit commands for the keyboard (mirror of
 * supabase/functions/_shared/live-edit.ts fastLiveEdit). English and Bangla.
 */
object LiveEdit {
  data class Result(val text: String, val edited: Boolean, val removed: List<String>, val needsLlm: Boolean)

  private fun n(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)
  private const val L = "\\p{L}\\p{M}\\p{N}"
  private const val PRE = "(?<![$L])"
  private const val POST = "(?![$L])"
  private fun rx(src: String) = Regex("(?iu)" + n(src))

  private const val BN_DEL = "(?:মুছে\\s*(?:দাও|দেও|দিন|দে|ফেলো|ফেল|ফালাও)|কেটে\\s*(?:দাও|দিন|দে|ফেলো|ফেল)|বাদ\\s*(?:দাও|দিন|দে|দেও)|" +
    "(?:ডিলিট|রিমুভ|remove|delete|erase)\\s*(?:করে|কইরা|কর)\\s*(?:দাও|দিন|দে|দেও|ফেলো|ফেল)?|(?:ডিলিট|রিমুভ)\\s*(?:করো|কর|দাও))"
  private const val BN_WRONG = "(?:ভুল\\s*(?:হয়েছে|হইছে|হইসে|হয়েছে|হলো|হল|আছে|হয়ে\\s*গেছে)?)"
  private const val BN_THIS = "(?:এই|এ|ওই)\\s*(?:শব্দ|ওয়ার্ড|ওয়ার্ড|word)\\s*(?:টা|টি|টাকে|টিকে)?"
  private const val FILL = "(?:[\\s,।.!]*(?:তুমি|আপনি|প্লিজ|please|এটা|ওটা|ওইটা|এইটা)?)*[\\s,।.!]*"

  private enum class Op { CLEAR, LAST_SENTENCE, LAST_WORD, SCRATCH, REMOVE_WORD, REPLACE }
  private val rules: List<Pair<Op, Regex>> by lazy {
    listOf(
      Op.CLEAR to rx("$PRE(?:(?:delete|clear|erase|remove)\\s+(?:everything|all of it|it all)|start over)$POST"),
      Op.CLEAR to rx("$PRE(?:সব|সবকিছু|সবগুলো|পুরোটা|পুরা\\s*টা)\\s*(?:টা|গুলো)?$FILL$BN_DEL"),
      Op.LAST_SENTENCE to rx("$PRE(?:delete|remove|erase|scratch|drop)\\s+(?:the\\s+)?(?:last|previous)\\s+(?:sentence|line)$POST"),
      Op.LAST_SENTENCE to rx("$PRE(?:শেষ|আগের|লাস্ট|শেষের)\\s*(?:বাক্য|লাইন|sentence|line)\\s*(?:টা|টি)?$FILL$BN_DEL"),
      Op.LAST_WORD to rx("$PRE(?:delete|remove|erase|drop)\\s+(?:the\\s+)?(?:last|previous)\\s+word$POST"),
      Op.LAST_WORD to rx("$PRE(?:শেষ|আগের|লাস্ট|শেষের)\\s*(?:শব্দ|ওয়ার্ড|word)\\s*(?:টা|টি)?$FILL$BN_DEL"),
      Op.LAST_WORD to rx("$PRE$BN_THIS$FILL(?:$BN_WRONG$FILL(?:$BN_DEL)?|$BN_DEL)"),
      Op.LAST_WORD to rx("$PRE(?:that|this|the last)\\s+word\\s+(?:is|was)\\s+wrong(?:[\\s,.]*(?:please\\s+)?(?:remove|delete)\\s+it)?$POST"),
      Op.REPLACE to rx("$PRE(?:replace|change)\\s+(?:the\\s+word\\s+)?[\"“']?([$L'’-]+)[\"”']?\\s+(?:with|to)\\s+[\"“']?([$L'’-]+(?:\\s+[$L'’-]+)?)[\"”']?$POST"),
      Op.REMOVE_WORD to rx("$PRE(?:delete|remove|erase)\\s+the\\s+word\\s+[\"“']?([$L'’-]+)[\"”']?$POST"),
      Op.REMOVE_WORD to rx("$PRE([\\p{L}\\p{M}]+)\\s*(?:শব্দ|ওয়ার্ড|word)\\s*(?:টা|টি|টাকে|টিকে)$FILL(?:$BN_WRONG$FILL)?$BN_DEL"),
      Op.SCRATCH to rx("$PRE(?:scratch that|delete that|forget that|undo that|strike that|remove that)$POST"),
      Op.SCRATCH to rx("$PRE(?:এটা|ওটা|এইটা|ওইটা|এটুকু)?\\s*$BN_DEL"),
    )
  }
  private val llmCues by lazy {
    rx("$PRE(?:i meant|i mean|no wait|actually|instead|make it (?:more )?(?:formal|casual|shorter|polite|professional|friendly)|rephrase|wrong|correct that|fix that|not\\s+[$L]+\\s*,|sorry)$POST|" +
      "ভুল|মানে|বদলে|বদলাও|পরিবর্তন|ফরমাল|ঠিক\\s*কর|সরি|আসলে|না[,،]|বলতে\\s*চেয়েছিলাম|বলতে\\s*চাইছি")
  }
  private val joiners by lazy { rx("^[\\s,।.!]*(?:(?:and|then|okay|ok|so|write|type|আর|তারপর|লেখো|লিখো|ঠিক আছে)[\\s,]+)*") }

  private fun squash(s: String) = s.replace(Regex("[ \\t]+"), " ").replace(Regex("\\s+([,.!?।])"), "$1").trim()
  private fun join(a: String, b: String) = squash(listOf(a.trim(), b.trim()).filter { it.isNotEmpty() }.joinToString(" "))

  private fun dropLastSentence(text: String): Pair<String, String> {
    val t = text.trim().trimEnd(',', ' ')
    val body = t.trimEnd('.', '!', '?', '।')
    val cut = body.indexOfLast { it == '.' || it == '!' || it == '?' || it == '।' || it == '\n' }
    return squash(t.substring(0, cut + 1)) to squash(t.substring(cut + 1))
  }

  private fun dropLastWord(text: String): Pair<String, String> {
    val m = Regex("(?s)^(.*?)\\s*([$L'’-]+)([^$L]*)$").find(text.trim()) ?: return text.trim() to ""
    val tailPunct = m.groupValues[3].trim().takeIf { Regex("[.!?।]").containsMatchIn(it) }.orEmpty()
    return squash(m.groupValues[1] + tailPunct) to m.groupValues[2]
  }

  private fun lastOccurrence(text: String, word: String): MatchResult? =
    Regex("(?iu)$PRE${Regex.escape(n(word))}$POST").findAll(text).lastOrNull()

  fun fast(current: String, segment: String): Result {
    val cur = n(current)
    val seg = n(segment)
    for ((op, re) in rules) {
      val m = re.find(seg) ?: continue
      val before = seg.substring(0, m.range.first).trimEnd(',', ' ')
      val after = joiners.replace(seg.substring(m.range.last + 1), "")
      var base = join(cur, before)
      val removed = mutableListOf<String>()
      when (op) {
        Op.CLEAR -> { if (base.isNotEmpty()) removed.add(base); base = "" }
        Op.LAST_SENTENCE -> { val (k, g) = dropLastSentence(base); if (g.isNotEmpty()) removed.add(g); base = k }
        Op.LAST_WORD -> { val (k, g) = dropLastWord(base); if (g.isNotEmpty()) removed.add(g); base = k }
        Op.SCRATCH -> if (before.isNotBlank()) { removed.add(before.trim()); base = squash(cur) } else {
          val (k, g) = dropLastSentence(base); if (g.isNotEmpty()) removed.add(g); base = k
        }
        Op.REMOVE_WORD, Op.REPLACE -> {
          val hit = lastOccurrence(base, m.groupValues[1]) ?: return Result(join(cur, seg), false, emptyList(), true)
          removed.add(hit.value)
          val repl = if (op == Op.REPLACE) m.groupValues[2] else ""
          base = squash(base.substring(0, hit.range.first) + repl + base.substring(hit.range.last + 1))
        }
      }
      if (after.isNotBlank()) {
        val rest = fast(base, after)
        return Result(rest.text, true, removed + rest.removed, false)
      }
      return Result(base, true, removed, false)
    }
    return Result(join(cur, seg), false, emptyList(), llmCues.containsMatchIn(seg))
  }

  fun applyAll(text: String): String = runCatching { fast("", text).text }.getOrDefault(text)
}
