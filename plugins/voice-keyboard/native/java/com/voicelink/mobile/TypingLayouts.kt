package com.voicelink.mobile

/**
 * Keyboard layout definitions for the built-in typing panel.
 * Original Cursor Flow layouts. Bangla Probhat / Hindi InScript positions
 * follow publicly documented national layout charts (not copied from AOSP/FlorisBoard sources).
 */
object TypingLayouts {
  data class KeyDef(
    val label: String,
    val code: String = label,
    val widthWeight: Float = 1f,
    val alternates: List<String> = emptyList(),
    val isAction: Boolean = false,
  )

  enum class Page { LETTERS, NUMBERS, SYMBOLS, EMOJI }
  enum class ShiftState { OFF, ONCE, LOCKED }

  /** Input method within a language: phonetic transliteration or fixed national layout. */
  enum class InputMode { PHONETIC, FIXED }

  data class Lang(
    val id: String,
    val label: String,
    val short: String,
    val supportsPhonetic: Boolean = false,
    val fixedLabel: String = "",
  )

  val languages = listOf(
    Lang("en", "English", "EN"),
    Lang("bn", "বাংলা", "বাং", supportsPhonetic = true, fixedLabel = "Probhat"),
    Lang("hi", "हिन्दी", "हिं", supportsPhonetic = true, fixedLabel = "InScript"),
  )

  /** Only English, Bangla and Hindi are supported; anything else stored earlier becomes English. */
  fun supportedId(id: String?): String = languages.firstOrNull { it.id == id }?.id ?: "en"

  fun letters(lang: String, shift: ShiftState, mode: InputMode = InputMode.PHONETIC): List<List<KeyDef>> {
    val upper = shift != ShiftState.OFF
    val rows = when (lang) {
      "bn" -> if (mode == InputMode.FIXED) banglaProbhat(shift) else qwertyLetters(lang, upper)
      "hi" -> if (mode == InputMode.FIXED) hindiInScript(shift) else qwertyLetters(lang, upper)
      else -> qwertyLetters(lang, upper)
    }
    return listOf(numberRow(lang == "bn" && mode == InputMode.FIXED)) + rows
  }

  private val englishDigitAlts = mapOf(
    "1" to listOf("!", "¹", "½"), "2" to listOf("@", "²"), "3" to listOf("#", "³"), "4" to listOf("$", "₹", "৳"),
    "5" to listOf("%"), "6" to listOf("^"), "7" to listOf("&"), "8" to listOf("*"), "9" to listOf("("), "0" to listOf(")", "°"),
  )

  fun numberRow(bangla: Boolean): List<KeyDef> {
    val latin = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    if (!bangla) return latin.map { KeyDef(it, alternates = englishDigitAlts[it].orEmpty()) }
    val bn = listOf("১", "২", "৩", "৪", "৫", "৬", "৭", "৮", "৯", "০")
    return bn.mapIndexed { i, d -> KeyDef(d, alternates = listOf(latin[i]) + englishDigitAlts[latin[i]].orEmpty()) }
  }

  private fun qwertyLetters(lang: String, upper: Boolean): List<List<KeyDef>> {
    fun L(c: String, alts: List<String> = emptyList()) =
      KeyDef(
        if (upper) c.uppercase() else c.lowercase(),
        if (upper) c.uppercase() else c.lowercase(),
        alternates = if (upper) alts.map { it.uppercase() } else alts,
      )

    val row1 = listOf(
      L("q"), L("w"), L("e", listOf("é", "è", "ê", "ë", "ē")), L("r"), L("t"),
      L("y", listOf("ý", "ÿ")), L("u", listOf("ú", "ù", "û", "ü")), L("i", listOf("í", "ì", "î", "ï")),
      L("o", listOf("ó", "ò", "ô", "ö", "õ", "œ")), L("p"),
    )
    val row2 = listOf(
      L("a", listOf("á", "à", "â", "ä", "ã", "å", "æ")), L("s", listOf("ß", "ś", "š")), L("d"),
      L("f"), L("g"), L("h"), L("j"), L("k"), L("l"),
    )
    val row3Core = listOf(
      L("z"), L("x"), L("c", listOf("ç", "ć", "č")), L("v"), L("b"), L("n", listOf("ñ", "ń")), L("m"),
    )
    val shiftKey = KeyDef(if (upper) "⇧" else "⇧", "SHIFT", widthWeight = 1.4f, isAction = true)
    val delKey = KeyDef("⌫", "DEL", widthWeight = 1.4f, isAction = true)
    return listOf(row1, row2, listOf(shiftKey) + row3Core + listOf(delKey), bottomRow(lang = lang))
  }

  private fun banglaProbhat(shift: ShiftState): List<List<KeyDef>> {
    val shifted = shift != ShiftState.OFF
    fun K(label: String, vararg alts: String) = KeyDef(label, alternates = alts.toList())
    val r1 = if (!shifted)
      listOf(K("দ"), K("ূ"), K("ী"), K("র", "্র", "র্"), K("ট"), K("এ"), K("ু"), K("ি"), K("ও"), K("প"), K("ে", "ৈ"))
    else
      listOf(K("ধ"), K("ঊ"), K("ঈ"), K("ড়"), K("ঠ"), K("ঐ"), K("উ"), K("ই"), K("ঔ"), K("ফ"), K("ৈ"))
    val r2 = if (!shifted)
      listOf(K("া"), K("স"), K("ড"), K("ত", "ৎ"), K("গ"), K("হ"), K("জ"), K("ক"), K("ল"), K("্", "্য", "্র"), K("ো", "ৌ"))
    else
      listOf(K("অ", "আ"), K("ষ"), K("ঢ"), K("থ"), K("ঘ"), K("ঃ"), K("ঝ"), K("খ"), K("ং"), K("ঁ"), K("ৌ"))
    val r3 = if (!shifted)
      listOf(K("য়"), K("শ"), K("চ"), K("আ"), K("ব"), K("ন", "ঞ"), K("ম"), K("ৎ"))
    else
      listOf(K("য", "্য"), K("ঢ়"), K("ছ"), K("ঋ", "ৃ"), K("ভ"), K("ণ"), K("ঙ"), K("ঞ"))
    val shiftKey = KeyDef("⇧", "SHIFT", 1.3f, isAction = true)
    val delKey = KeyDef("⌫", "DEL", 1.3f, isAction = true)
    return listOf(r1, r2, listOf(shiftKey) + r3 + listOf(delKey), bottomRow(lang = "bn"))
  }

  private fun hindiInScript(shift: ShiftState): List<List<KeyDef>> {
    val shifted = shift != ShiftState.OFF
    val r1 = if (!shifted)
      listOf("ौ", "ै", "ा", "ी", "ू", "ब", "ह", "ग", "द", "ज", "ड").map { KeyDef(it) }
    else
      listOf("औ", "ऐ", "आ", "ई", "ऊ", "भ", "ङ", "घ", "ध", "झ", "ढ").map { KeyDef(it) }
    val r2 = if (!shifted)
      listOf("ो", "े", "्", "ि", "ु", "प", "र", "क", "त", "च", "ट").map { KeyDef(it) }
    else
      listOf("ओ", "ए", "अ", "इ", "उ", "फ", "ऱ", "ख", "थ", "छ", "ठ").map { KeyDef(it) }
    val r3 = if (!shifted)
      listOf("ं", "म", "न", "व", "ल", "स", "य").map { KeyDef(it, alternates = if (it == "स") listOf("श", "ष") else emptyList()) }
    else
      listOf("ँ", "ण", "ऩ", "ज़", "ळ", "श", "य़").map { KeyDef(it) }
    val shiftKey = KeyDef("⇧", "SHIFT", 1.3f, isAction = true)
    val delKey = KeyDef("⌫", "DEL", 1.3f, isAction = true)
    return listOf(r1, r2, listOf(shiftKey) + r3 + listOf(delKey), bottomRow(lang = "hi"))
  }

  fun numbers(): List<List<KeyDef>> = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { KeyDef(it) },
    listOf("@", "#", "$", "%", "&", "-", "+", "(", ")").map { KeyDef(it) },
    listOf(
      KeyDef("=\\<", "SYMBOLS", 1.4f, isAction = true),
      KeyDef("/"), KeyDef("\""), KeyDef("'"), KeyDef(":"), KeyDef(";"), KeyDef("!"), KeyDef("?"),
      KeyDef("⌫", "DEL", 1.2f, isAction = true),
    ),
    bottomRow(numbersMode = true),
  )

  fun symbols(): List<List<KeyDef>> = listOf(
    listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "△").map { KeyDef(it) },
    listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\").map { KeyDef(it) },
    listOf(
      KeyDef("123", "NUMBERS", 1.4f, isAction = true),
      KeyDef("©"), KeyDef("®"), KeyDef("™"), KeyDef("℅"), KeyDef("["), KeyDef("]"), KeyDef("¡"), KeyDef("¿"),
      KeyDef("⌫", "DEL", 1.2f, isAction = true),
    ),
    bottomRow(numbersMode = true),
  )

  private val emojiSources = listOf(
    "😊" to "😀😁😂🤣😃😄😅😆😉😊😋😎😍😘🥰😗😙😚🙂🤗🤩🤔🤨😐😑😶🙄😏😣😥😮🤐😯😪😫🥱😴😌😛😜😝🤤😒😓😔😕🙃🤑😲☹️🙁😖😞😟😤😢😭😦😧😨😩🤯😬😰😱🥵🥶😳🤪😵🤠🥳😇🥺🤭🤫🤥😷🤒🤕🤢🤮🤧😈👿💀👻👽🤖💩",
    "👍" to "👍👎👌✌️🤞🤟🤘🤙👈👉👆👇☝️✋🤚🖐️🖖👋🤝🙏👏🙌👐🤲💪✍️🤳💅👀👁️👂👃🧠🦷👄💋🙋🙆🙅🤷🤦🙇💁🧑‍💻👨‍👩‍👧🫶🫡🫠",
    "❤️" to "❤️🧡💛💚💙💜🖤🤍🤎💔❣️💕💞💓💗💖💘💝💟✨⭐🌟💫🔥💯✅❌❗❓⚡💥🎉🎊🎈🎁🏆🥇",
    "🐶" to "🐶🐱🐭🐹🐰🦊🐻🐼🐨🐯🦁🐮🐷🐸🐵🐔🐧🐦🦆🦅🦉🐴🦄🐝🦋🐌🐞🐢🐍🐙🐠🐬🐳🦈🐘🦒🌸🌹🌺🌻🌼🌷🌱🌲🌴🍀🍁🌈☀️🌙⛅🌧️❄️🌊",
    "🍔" to "🍎🍊🍋🍌🍉🍇🍓🍒🍑🥭🍍🥥🥝🍅🥑🍆🥔🥕🌽🌶️🥒🍞🧀🥚🍳🥞🥓🍗🍖🌭🍔🍟🍕🥪🌮🌯🍜🍝🍛🍣🍚🍰🎂🍩🍪🍫🍬🍭🍦☕🍵🥤🧃🍺",
    "⚽" to "⚽🏀🏈⚾🎾🏐🏏🏸🥊🎮🎯🎲🎵🎶🎤🎧🎬📷📱💻⌨️🖥️🖨️💡📚📖✏️📝📌📎✂️🔒🔑🏠🚗🚕🚌🚲✈️🚀⏰⌛📅💰💳🎒👕👗👟🕌🇧🇩",
  )

  private val emojiCats: List<Pair<String, List<String>>> by lazy {
    emojiSources.map { (icon, src) -> icon to splitGraphemes(src) }
  }

  fun splitGraphemes(s: String): List<String> {
    val out = ArrayList<String>()
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(s)
    var start = it.first()
    var end = it.next()
    while (end != java.text.BreakIterator.DONE) {
      out.add(s.substring(start, end))
      start = end; end = it.next()
    }
    return out
  }

  var emojiCategory = 0
  val emojiRecents = ArrayList<String>()

  fun rememberEmoji(e: String) {
    emojiRecents.remove(e)
    emojiRecents.add(0, e)
    while (emojiRecents.size > 32) emojiRecents.removeAt(emojiRecents.size - 1)
  }

  fun emojiRows(): List<List<KeyDef>> {
    val list = if (emojiCategory < 0) emojiRecents.toList()
      else emojiCats[emojiCategory.coerceIn(0, emojiCats.size - 1)].second
    val cols = 8
    val grid = list.take(cols * 5).chunked(cols).map { row -> row.map { KeyDef(it, "EMO:$it") } }.toMutableList()
    if (grid.isEmpty()) grid.add(listOf(KeyDef(" ", "NOOP", 8f, isAction = true)))
    val tabs = ArrayList<KeyDef>()
    tabs.add(KeyDef("ABC", "LETTERS", 1.4f, isAction = true))
    tabs.add(KeyDef("🕘", "EMOCAT:-1", 1f, isAction = true))
    emojiCats.forEachIndexed { i, (icon, _) -> tabs.add(KeyDef(icon, "EMOCAT:$i", 1f, isAction = true)) }
    tabs.add(KeyDef("⌫", "DEL", 1.4f, isAction = true))
    return grid + listOf(tabs)
  }

  private fun bottomRow(numbersMode: Boolean = false, lang: String = "en"): List<KeyDef> {
    val modeKey = when (lang) {
      "bn", "hi" -> KeyDef("অা/ক", "INPUT_MODE", 1.2f, isAction = true)
      else -> KeyDef("😊", "EMOJI", 1.1f, isAction = true)
    }
    return listOf(
      KeyDef(if (numbersMode) "ABC" else "?123", if (numbersMode) "LETTERS" else "NUMBERS", 1.5f, isAction = true),
      KeyDef("🌐", "LANG", 1.1f, isAction = true),
      KeyDef(",", ",", 1f),
      KeyDef("space", "SPACE", 3.8f, isAction = true),
      if (lang == "bn" && !numbersMode) KeyDef("।", "।", 1f, alternates = listOf(".", "॥", "…", "?", "!"))
      else KeyDef(".", ".", 1f, alternates = listOf("@", "/", "?", "!", "…")),
      modeKey,
      KeyDef("↵", "ENTER", 1.5f, isAction = true),
    )
  }
}
