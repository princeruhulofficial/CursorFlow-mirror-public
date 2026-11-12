package com.voicelink.mobile

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.json.JSONObject

/**
 * Context-aware formatting from EditorInfo only (no Accessibility required).
 * Privacy: only the app package and field type are read, never the text inside the app.
 */
object InputContextResolver {

  enum class FieldKind { MESSAGE, SUBJECT, SEARCH, NAME, EMAIL, PHONE, NUMBER, URL, PASSWORD, MULTILINE, GENERIC }

  enum class AppStyle {
    CASUAL_MESSAGING, EMAIL, NOTES, PROFESSIONAL, SEARCH_TERM, CORE_VALUE, CODE, NATURAL
  }

  data class DictationContext(
    val packageName: String,
    val hintText: String,
    val fieldKind: FieldKind,
    val appStyle: AppStyle,
    val formatStyle: String,
    val disableVoice: Boolean,
    val styleNotes: String,
  ) {
    /** chat, email, document, code, search, social or unknown. Only the app identity, never its content. */
    val category: String get() = when {
      fieldKind in setOf(FieldKind.SEARCH, FieldKind.URL, FieldKind.EMAIL, FieldKind.NAME, FieldKind.PHONE, FieldKind.NUMBER, FieldKind.SUBJECT) -> "search"
      appStyle == AppStyle.CODE -> "code"
      appStyle == AppStyle.EMAIL -> "email"
      appStyle == AppStyle.NOTES -> "document"
      appStyle == AppStyle.CASUAL_MESSAGING -> "chat"
      appStyle == AppStyle.SEARCH_TERM || appStyle == AppStyle.CORE_VALUE -> "search"
      packageName.contains("linkedin") || packageName.contains("twitter") || packageName.contains("facebook.katana") -> "social"
      else -> "unknown"
    }

    fun toJson(): JSONObject = JSONObject().apply {
      put("packageName", packageName)
      put("hintText", hintText.take(120))
      put("fieldKind", fieldKind.name.lowercase())
      put("appStyle", appStyle.name.lowercase())
      put("formatStyle", formatStyle)
      put("styleNotes", styleNotes)
      put("category", category)
    }
  }

  private val messagingPkgs = listOf(
    "whatsapp", "telegram", "signal", "messenger", "instagram", "snapchat", "discord",
    "viber", "line.", "kakao", "messages", "sms", "conversation", "imessage",
    "slack", "teams", "skype", "wechat", "googlechat", "android.apps.dynamite",
  )
  private val notesPkgs = listOf(
    "keep", "notion", "apps.docs", "office.word", "microsoft.office", "evernote", "onenote",
    "obsidian", "notes", "standardnotes", "joplin", "simplenote", "todoist", "ticktick",
  )
  private val mailPkgs = listOf("gmail", "com.google.android.gm", "com.microsoft.office.outlook", "ch.protonmail", "outlook", "mail", "yahoo.mobile.client.android.mail", "superhuman", "spark")
  private val proPkgs = listOf("linkedin", "asana", "jira", "confluence", "workplace")
  private val codePkgs = listOf("com.termux", "acode", "com.aide.ui", "rhmsoft.code", "duy.ide")

  fun resolve(
    editorInfo: EditorInfo?,
    userOverrideStyle: String?,
  ): DictationContext {
    val pkg = editorInfo?.packageName.orEmpty()
    val hint = editorInfo?.hintText?.toString().orEmpty()
    val fieldKind = detectFieldKind(editorInfo, hint)
    val disableVoice = fieldKind == FieldKind.PASSWORD || !VoiceInputTargetPolicy.allows(editorInfo)
    val preset = when {
      !userOverrideStyle.isNullOrBlank() -> overrideToAppStyle(userOverrideStyle)
      fieldKind == FieldKind.SEARCH || fieldKind == FieldKind.NAME -> AppStyle.SEARCH_TERM
      fieldKind == FieldKind.EMAIL || fieldKind == FieldKind.PHONE || fieldKind == FieldKind.NUMBER || fieldKind == FieldKind.URL -> AppStyle.CORE_VALUE
      fieldKind == FieldKind.SUBJECT -> AppStyle.EMAIL
      matches(pkg, codePkgs) -> AppStyle.CODE
      matches(pkg, mailPkgs) || fieldKind == FieldKind.MULTILINE && matches(pkg, mailPkgs) -> AppStyle.EMAIL
      matches(pkg, notesPkgs) -> AppStyle.NOTES
      matches(pkg, messagingPkgs) -> AppStyle.CASUAL_MESSAGING
      matches(pkg, proPkgs) -> AppStyle.PROFESSIONAL
      else -> AppStyle.NATURAL
    }
    val formatStyle = when (preset) {
      AppStyle.EMAIL -> if (fieldKind == FieldKind.SUBJECT) "natural" else "email"
      AppStyle.NOTES -> "bullets"
      AppStyle.PROFESSIONAL -> "natural"
      AppStyle.CASUAL_MESSAGING -> "natural"
      AppStyle.SEARCH_TERM, AppStyle.CORE_VALUE, AppStyle.CODE -> "natural"
      AppStyle.NATURAL -> "natural"
    }
    val notes = styleNotes(preset, fieldKind, hint)
    return DictationContext(
      packageName = pkg,
      hintText = hint,
      fieldKind = fieldKind,
      appStyle = preset,
      formatStyle = formatStyle,
      disableVoice = disableVoice,
      styleNotes = notes,
    )
  }

  /** Options for the instant rule pass: chat stays casual, notes get bullets, one-line fields stay one line. */
  fun ruleOptions(ctx: DictationContext): FlowRulePass.Options {
    val multiline = ctx.fieldKind == FieldKind.MULTILINE
    val style = when (ctx.appStyle) {
      AppStyle.CASUAL_MESSAGING -> FlowRulePass.Style.CHAT
      AppStyle.EMAIL -> if (ctx.fieldKind == FieldKind.SUBJECT) FlowRulePass.Style.NATURAL else FlowRulePass.Style.EMAIL
      AppStyle.NOTES -> FlowRulePass.Style.NOTES
      AppStyle.CODE, AppStyle.SEARCH_TERM, AppStyle.CORE_VALUE -> FlowRulePass.Style.LITERAL
      else -> if (ctx.category == "search") FlowRulePass.Style.LITERAL else FlowRulePass.Style.NATURAL
    }
    // Email greeting/paragraphs need a multi-line body; a one-line field never gets newlines.
    return FlowRulePass.Options(multiline = multiline, style = style)
  }

  private fun matches(pkg: String, markers: List<String>): Boolean {
    val p = pkg.lowercase()
    return markers.any { p.contains(it) }
  }

  private fun overrideToAppStyle(value: String): AppStyle = when (value.lowercase()) {
    "casual", "messaging", "casual_messaging" -> AppStyle.CASUAL_MESSAGING
    "email", "mail" -> AppStyle.EMAIL
    "professional", "pro", "bullets" -> AppStyle.PROFESSIONAL
    "search" -> AppStyle.SEARCH_TERM
    "core", "core_value" -> AppStyle.CORE_VALUE
    else -> AppStyle.NATURAL
  }

  private fun detectFieldKind(editorInfo: EditorInfo?, hint: String): FieldKind {
    if (editorInfo == null) return FieldKind.GENERIC
    val inputType = editorInfo.inputType
    val cls = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    if (cls == InputType.TYPE_CLASS_TEXT) {
      when (variation) {
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> return FieldKind.PASSWORD
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> return FieldKind.EMAIL
        InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> return FieldKind.NAME
        InputType.TYPE_TEXT_VARIATION_URI -> return FieldKind.URL
        InputType.TYPE_TEXT_VARIATION_PHONETIC -> return FieldKind.NAME
        InputType.TYPE_TEXT_VARIATION_FILTER -> return FieldKind.SEARCH
      }
    }
    if (cls == InputType.TYPE_CLASS_NUMBER) {
      if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return FieldKind.PASSWORD
      return FieldKind.NUMBER
    }
    if (cls == InputType.TYPE_CLASS_PHONE) return FieldKind.PHONE
    val h = hint.lowercase()
    if (h.contains("subject")) return FieldKind.SUBJECT
    if (h.contains("search") || h.contains("find") || h.contains("query")) return FieldKind.SEARCH
    if (h.contains("name") && !h.contains("user")) return FieldKind.NAME
    if (h.contains("email")) return FieldKind.EMAIL
    if (h.contains("phone") || h.contains("mobile")) return FieldKind.PHONE
    if (h.contains("password") || h.contains("passcode") || h.contains("pin")) return FieldKind.PASSWORD
    val multi = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
      (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
    if (multi) return FieldKind.MULTILINE
    // messaging compose is often single-line-ish; treat generic short as message when not special
    return FieldKind.MESSAGE
  }

  private fun styleNotes(style: AppStyle, kind: FieldKind, hint: String): String {
    val parts = mutableListOf<String>()
    when (style) {
      AppStyle.CASUAL_MESSAGING -> parts += "Chat message: casual and short, keep emoji and informal tone, no bullets or headings unless the user asks for a list."
      AppStyle.EMAIL -> {
        if (kind == FieldKind.SUBJECT) parts += "Email subject: return a short clear subject line only, no greeting or sign-off."
        else parts += "Email body: use a natural greeting when starting a new message, short paragraphs, and a brief sign-off when the user seems to be closing."
      }
      AppStyle.NOTES -> parts += "Notes or document: use a short bullet list when the user lists items; otherwise clean paragraphs."
      AppStyle.PROFESSIONAL -> parts += "Professional workplace tone: clear, concise; avoid slang."
      AppStyle.SEARCH_TERM -> parts += "Search or name field: insert only the core search terms or name. No full sentences, no punctuation fluff."
      AppStyle.CORE_VALUE -> parts += "Structured field: insert only the core value (digits, email, or URL). No surrounding sentence."
      AppStyle.CODE -> parts += "Code or terminal: keep the literal text, no autocorrect, no added period."
      AppStyle.NATURAL -> parts += "Natural polished dictation."
    }
    if (hint.isNotBlank()) parts += "Field hint: $hint"
    return parts.joinToString(" ")
  }
}
