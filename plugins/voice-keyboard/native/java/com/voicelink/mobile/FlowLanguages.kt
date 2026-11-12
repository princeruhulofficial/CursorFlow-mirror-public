package com.voicelink.mobile

/** Translate targets (Flow AI) and the cloud speech list. Names are English; labels are native. */
object FlowLanguages {
  data class Lang(val name: String, val native: String, val code: String)

  val translate: List<Lang> = listOf(
    Lang("English", "English", "en"), Lang("Bangla", "বাংলা", "bn"), Lang("Hindi", "हिन्दी", "hi"),
    Lang("Urdu", "اردو", "ur"), Lang("Arabic", "العربية", "ar"), Lang("Spanish", "Español", "es"),
    Lang("French", "Français", "fr"), Lang("German", "Deutsch", "de"), Lang("Portuguese", "Português", "pt"),
    Lang("Italian", "Italiano", "it"), Lang("Dutch", "Nederlands", "nl"), Lang("Russian", "Русский", "ru"),
    Lang("Ukrainian", "Українська", "uk"), Lang("Polish", "Polski", "pl"), Lang("Czech", "Čeština", "cs"),
    Lang("Slovak", "Slovenčina", "sk"), Lang("Romanian", "Română", "ro"), Lang("Hungarian", "Magyar", "hu"),
    Lang("Bulgarian", "Български", "bg"), Lang("Croatian", "Hrvatski", "hr"), Lang("Bosnian", "Bosanski", "bs"),
    Lang("Macedonian", "Македонски", "mk"), Lang("Greek", "Ελληνικά", "el"), Lang("Turkish", "Türkçe", "tr"),
    Lang("Persian", "فارسی", "fa"), Lang("Hebrew", "עברית", "he"), Lang("Swedish", "Svenska", "sv"),
    Lang("Danish", "Dansk", "da"), Lang("Norwegian", "Norsk", "nb"), Lang("Finnish", "Suomi", "fi"),
    Lang("Catalan", "Català", "ca"), Lang("Chinese (Simplified)", "简体中文", "zh"),
    Lang("Chinese (Traditional)", "繁體中文", "zh-TW"), Lang("Cantonese", "粵語", "yue"),
    Lang("Japanese", "日本語", "ja"), Lang("Korean", "한국어", "ko"), Lang("Thai", "ไทย", "th"),
    Lang("Vietnamese", "Tiếng Việt", "vi"), Lang("Indonesian", "Bahasa Indonesia", "id"),
    Lang("Malay", "Bahasa Melayu", "ms"), Lang("Filipino", "Filipino", "fil"), Lang("Marathi", "मराठी", "mr"),
    Lang("Tamil", "தமிழ்", "ta"), Lang("Telugu", "తెలుగు", "te"),
  )

  /** Codes the cloud speech model lists. Anything else stays on the on-device recognizer. */
  val cloudSpeechCodes = setOf(
    "en", "bn", "be", "bs", "bg", "ca", "hr", "cs", "da", "nl", "et", "fi", "fr", "gl", "de", "el", "hu", "is", "id", "it", "ja", "kn", "lv", "mk", "ms", "ml", "nb", "pl", "pt", "ro", "ru", "sk", "es", "sv", "tr", "uk", "vi", "hy", "az", "yue", "fil", "ka", "gu", "hi", "kk", "lt", "mt", "zh", "mr", "ne", "or", "fa", "sr", "sl", "sw", "ta", "te",
    "ar", "ko", "th", "ur", "he",
  )

  fun byName(name: String): Lang? = translate.firstOrNull { it.name.equals(name, true) || it.native == name }

  fun label(name: String): String = byName(name)?.native ?: name

  /** Speech hint cycle on long-press of the language side of the pill. Auto is the default. */
  val speechHints = listOf("Auto-detect", "English", "বাংলা", "हिन्दी")

  fun hintLocale(hint: String): String? = when (hint) {
    "English" -> "en-US"; "বাংলা" -> "bn-BD"; "हिन्दी" -> "hi-IN"
    "Español" -> "es-ES"; "Français" -> "fr-FR"; "Auto-detect", "" -> null
    else -> codeLocale(hint)
  }

  fun hintShort(hint: String): String = when (hint) {
    "English" -> "EN"; "বাংলা" -> "BN"; "हिन्दी" -> "HI"; "Español" -> "ES"; "Français" -> "FR"; "Auto-detect", "" -> "Auto"
    else -> if (byCode(hint) != null) hint.substringBefore('-').uppercase() else "Auto"
  }

  /** One row of the keyboard language list: stored value and what the row shows. */
  data class SpeechChoice(val value: String, val label: String, val sub: String)

  fun byCode(code: String): Lang? = translate.firstOrNull { it.code.equals(code, true) }

  /** Auto first, then English and Bangla, then every other supported speech language A to Z. */
  val speechChoices: List<SpeechChoice> by lazy {
    val head = listOf(
      SpeechChoice("Auto-detect", "Auto", "Detects the language you speak"),
      SpeechChoice("English", "English", "English"),
      SpeechChoice("বাংলা", "বাংলা", "Bangla"),
      SpeechChoice("हिन्दी", "हिन्दी", "Hindi"),
    )
    // The keyboard supports only English, Bangla and Hindi; other languages are not listed.
    head
  }

  /** Stored dictation language, limited to the supported ones (anything else is Auto). */
  fun supportedSpeech(value: String?): String = speechChoices.firstOrNull { it.value == value }?.value ?: "Auto-detect"

  /** Keeps the names the app and server already know; other languages store their code. */
  fun storedValue(lang: Lang): String = when (lang.code) {
    "en" -> "English"; "bn" -> "বাংলা"; "hi" -> "हिन्दी"; "es" -> "Español"; "fr" -> "Français"; else -> lang.code
  }

  private fun codeLocale(code: String): String? = when (code.lowercase()) {
    "zh" -> "zh-CN"; "yue" -> "yue-Hant-HK"; "fil" -> "fil-PH"; "nb" -> "nb-NO"; "ar" -> "ar-SA"; "pt" -> "pt-BR"
    "ur" -> "ur-PK"; "fa" -> "fa-IR"; "ms" -> "ms-MY"; "sv" -> "sv-SE"; "da" -> "da-DK"; "el" -> "el-GR"
    "cs" -> "cs-CZ"; "ja" -> "ja-JP"; "ko" -> "ko-KR"; "vi" -> "vi-VN"; "uk" -> "uk-UA"; "ca" -> "ca-ES"
    else -> byCode(code)?.let { "${it.code}-${it.code.uppercase()}" }
  }

  /** Guess the spoken language from the script of the recognized text. */
  fun detectFromText(text: String, fallbackCode: String): String {
    var bn = 0; var deva = 0; var arab = 0; var cjk = 0; var kana = 0; var hangul = 0; var cyr = 0; var thai = 0; var latin = 0
    for (c in text) {
      when (Character.UnicodeBlock.of(c)) {
        Character.UnicodeBlock.BENGALI -> bn++
        Character.UnicodeBlock.DEVANAGARI -> deva++
        Character.UnicodeBlock.ARABIC -> arab++
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS -> cjk++
        Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA -> kana++
        Character.UnicodeBlock.HANGUL_SYLLABLES -> hangul++
        Character.UnicodeBlock.CYRILLIC -> cyr++
        Character.UnicodeBlock.THAI -> thai++
        Character.UnicodeBlock.BASIC_LATIN, Character.UnicodeBlock.LATIN_1_SUPPLEMENT -> if (c.isLetter()) latin++
        else -> Unit
      }
    }
    val best = listOf("bn" to bn, "hi" to deva, "ar" to arab, "zh" to cjk, "ja" to kana, "ko" to hangul, "ru" to cyr, "th" to thai, "latin" to latin)
      .maxByOrNull { it.second } ?: return fallbackCode
    if (best.second == 0) return fallbackCode
    if (best.first == "ja" || (best.first == "zh" && kana > 0)) return "ja"
    return if (best.first == "latin") (if (fallbackCode.length in 2..3) fallbackCode else "en") else best.first
  }
}
