package com.voicelink.mobile

/**
 * Hindi phonetic transliteration (Latin -> Devanagari).
 * Original Cursor Flow mapping; not sourced from a third-party IME.
 */
object HindiPhonetic {
  private val vowels = linkedMapOf(
    "aa" to "आ", "ii" to "ई", "uu" to "ऊ", "ai" to "ऐ", "au" to "औ",
    "a" to "अ", "i" to "इ", "u" to "उ", "e" to "ए", "o" to "ओ",
    "Ri" to "ऋ",
  )
  private val matra = linkedMapOf(
    "aa" to "ा", "ii" to "ी", "uu" to "ू", "ai" to "ै", "au" to "ौ",
    "i" to "ि", "u" to "ु", "e" to "े", "o" to "ो", "Ri" to "ृ",
  )
  private val consonants = linkedMapOf(
    "kh" to "ख", "gh" to "घ", "ch" to "च", "Ch" to "छ", "jh" to "झ",
    "th" to "थ", "Th" to "ठ", "dh" to "ध", "Dh" to "ढ", "ph" to "फ", "bh" to "भ",
    "sh" to "श", "Sh" to "ष", "ng" to "ङ", "ny" to "ञ",
    "k" to "क", "g" to "ग", "c" to "च", "j" to "ज", "T" to "ट", "t" to "त",
    "D" to "ड", "d" to "द", "n" to "न", "N" to "ण", "p" to "प", "f" to "फ",
    "b" to "ब", "v" to "व", "m" to "म", "y" to "य", "r" to "र", "l" to "ल",
    "L" to "ळ", "s" to "स", "h" to "ह", "w" to "व", "z" to "ज़", "q" to "क़",
  )
  private val specials = linkedMapOf(
    "M" to "ं", "H" to "ः", "." to "।" , "^" to "ँ",
  )
  private val cKeys = consonants.keys.sortedByDescending { it.length }
  private val vKeys = (vowels.keys + matra.keys).distinct().sortedByDescending { it.length }

  fun transliterate(input: String): String {
    if (input.isEmpty()) return ""
    val out = StringBuilder()
    var i = 0
    var lastCons = false
    while (i < input.length) {
      if (input[i] == ' ') {
        out.append(' '); lastCons = false; i++; continue
      }
      val sp = specials.keys.firstOrNull { input.startsWith(it, i) }
      if (sp != null) {
        out.append(specials[sp]); lastCons = false; i += sp.length; continue
      }
      val ck = cKeys.firstOrNull { input.startsWith(it, i) }
      if (ck != null) {
        out.append(consonants[ck]); lastCons = true; i += ck.length; continue
      }
      val vk = vKeys.firstOrNull { input.startsWith(it, i) }
      if (vk != null) {
        if (lastCons) {
          if (vk == "a") {
            // inherent
          } else {
            out.append(matra[vk] ?: "")
          }
        } else {
          out.append(vowels[vk] ?: "")
        }
        lastCons = false
        i += vk.length
        continue
      }
      out.append(input[i]); lastCons = false; i++
    }
    return out.toString()
  }

  fun preview(composingLatin: String): String = transliterate(composingLatin)
}
