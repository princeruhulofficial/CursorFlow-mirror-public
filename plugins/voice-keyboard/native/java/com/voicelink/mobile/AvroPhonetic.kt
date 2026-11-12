package com.voicelink.mobile

/**
 * Avro-compatible Bangla phonetic transliteration.
 *
 * Original Cursor Flow implementation of the publicly documented Avro Phonetic
 * mapping scheme (type Latin -> Bangla Unicode). Not a copy of Avro Keyboard
 * (MPL-2.0) or OpenBangla Keyboard (GPL-3.0) source code.
 *
 * Includes conjunct / juktakkhor formation via hasanta (্) rules.
 */
object AvroPhonetic {
  private val vowels = linkedMapOf(
    "o" to "অ", "a" to "আ", "i" to "ই", "I" to "ঈ", "u" to "উ", "U" to "ঊ",
    "e" to "এ", "OI" to "ঐ", "O" to "ও", "OU" to "ঔ",
  )
  private val kar = linkedMapOf(
    "a" to "া", "i" to "ি", "I" to "ী", "u" to "ু", "U" to "ূ",
    "e" to "ে", "OI" to "ৈ", "O" to "ো", "OU" to "ৌ",
  )
  // Longer keys first for greedy match
  private val consonants = linkedMapOf(
    "kh" to "খ", "gh" to "ঘ", "Ng" to "ঙ", "ch" to "চ", "Ch" to "ছ", "zh" to "ঝ",
    "th" to "থ", "Th" to "ঠ", "dh" to "ধ", "Dh" to "ঢ", "ph" to "ফ", "bh" to "ভ",
    "sh" to "শ", "Sh" to "ষ", "Rh" to "ঢ়", "rh" to "ড়", "ng" to "ং",
    "k" to "ক", "g" to "গ", "c" to "চ", "j" to "জ", "T" to "ট", "t" to "ত",
    "D" to "ড", "d" to "দ", "n" to "ন", "N" to "ণ", "p" to "প", "f" to "ফ",
    "b" to "ব", "v" to "ভ", "m" to "ম", "z" to "য", "r" to "র", "l" to "ল",
    "S" to "শ", "s" to "স", "h" to "হ", "y" to "য়", "Y" to "য়", "w" to "ও",
    "R" to "ড়", "J" to "য",
  )
  private val specials = linkedMapOf(
    ":" to "ঃ", "^" to "ঁ", "." to "।", "`" to "়",
  )
  private val conjunctSeeds = linkedMapOf(
    // Common juktakkhor triggered by explicit patterns (k+khanda etc.)
    "kT" to "ক্ট", "kt" to "ক্ত", "kR" to "ক্র", "kr" to "ক্র", "kl" to "ক্ল",
    "kk" to "ক্ক", "gg" to "গ্গ", "gm" to "গ্ম", "gd" to "গ্দ",
    "ngk" to "ঙ্ক", "ngg" to "ঙ্গ",
    "cc" to "চ্চ", "cch" to "চ্ছ", "jj" to "জ্জ", "jjh" to "জ্ঝ",
    "TT" to "ট্ট", "dd" to "দ্দ", "dg" to "দ্গ", "db" to "দ্ব", "dm" to "দ্ম",
    "nn" to "ন্ন", "nt" to "ন্ত", "nd" to "ন্দ", "ndh" to "ন্ধ", "nm" to "ন্ম",
    "pp" to "প্প", "pr" to "প্র", "pl" to "প্ল",
    "bd" to "ব্দ", "bb" to "ব্ব", "bl" to "ব্ল", "br" to "ব্র",
    "mb" to "ম্ব", "mm" to "ম্ম", "mr" to "ম্র", "ml" to "ম্ল",
    "ll" to "ল্ল", "lk" to "ল্ক", "lg" to "ল্গ", "lp" to "ল্প",
    "ss" to "স্স", "st" to "স্ত", "sth" to "স্থ", "sn" to "স্ন", "sp" to "স্প",
    "sk" to "স্ক", "sf" to "স্ফ",
    "hN" to "হ্ণ", "hn" to "হ্ন", "hm" to "হ্ম", "hl" to "হ্ল",
    "kSh" to "ক্ষ", "kS" to "ক্ষ", "kkh" to "ক্ষ",
    "nj" to "ঞ্জ", "nc" to "ঞ্চ", "nch" to "ঞ্ছ",
  )

  private val consKeys = (conjunctSeeds.keys + consonants.keys).sortedByDescending { it.length }
  private val vowelKeys = (vowels.keys + kar.keys).distinct().sortedByDescending { it.length }

  /** Convert Latin phonetic input to Bangla. Processes whole buffer. */
  fun transliterate(input: String): String {
    if (input.isEmpty()) return ""
    val out = StringBuilder()
    var i = 0
    var lastWasConsonant = false
    while (i < input.length) {
      val ch = input[i]
      if (ch == ' ') {
        out.append(' ')
        lastWasConsonant = false
        i++
        continue
      }
      // Specials
      val sp = specials.keys.firstOrNull { input.startsWith(it, i) }
      if (sp != null) {
        out.append(specials[sp])
        lastWasConsonant = false
        i += sp.length
        continue
      }
      // Explicit hasanta
      if (ch == '|' || (ch == 'g' && i + 1 < input.length && input[i + 1] == '`')) {
        // skip rare
      }
      // Conjunct seeds
      val cj = consKeys.firstOrNull { input.startsWith(it, i) && conjunctSeeds.containsKey(it) }
      if (cj != null && conjunctSeeds.containsKey(cj)) {
        out.append(conjunctSeeds[cj])
        lastWasConsonant = true
        i += cj.length
        continue
      }
      // Consonant
      val ck = consonants.keys.sortedByDescending { it.length }.firstOrNull { input.startsWith(it, i) }
      if (ck != null) {
        // Implicit o (অ) is inherent — just emit consonant
        // Double consonant → hasanta join when same letter repeated intentionally via "kk" already handled
        out.append(consonants[ck])
        lastWasConsonant = true
        i += ck.length
        continue
      }
      // Vowel / kar
      val vk = vowelKeys.firstOrNull { input.startsWith(it, i) }
      if (vk != null) {
        if (lastWasConsonant) {
          // 'o' after consonant is inherent — skip (অ-kar is invisible)
          if (vk == "o") {
            // inherent vowel: do nothing
          } else {
            out.append(kar[vk] ?: "")
          }
        } else {
          out.append(vowels[vk] ?: kar[vk] ?: "")
        }
        lastWasConsonant = false
        i += vk.length
        continue
      }
      // Pass through unknown (digits, punctuation)
      out.append(ch)
      lastWasConsonant = false
      i++
    }
    return out.toString()
  }

  /** Incremental preview: transliterate composing Latin buffer. */
  fun preview(composingLatin: String): String = transliterate(composingLatin)
}
