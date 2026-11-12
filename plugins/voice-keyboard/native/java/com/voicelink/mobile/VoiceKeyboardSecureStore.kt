package com.voicelink.mobile

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object VoiceKeyboardSecureStore {
  private const val KEY_ALIAS = "cursorflow.keyboard.access"
  private const val PREFS = "cursorflow_keyboard_secure"
  private const val TOKEN = "access_token"
  private const val IV = "access_token_iv"
  private const val MODEL_PATH = "local_model_path"
  private const val QWEN_MODEL_PATH = "local_qwen_model_path"
  private const val API_BASE_URL = "api_base_url"
  private const val LANGUAGE = "language"
  private const val CLEAN_AND_FORMAT = "clean_and_format"
  private const val VOCABULARY = "custom_vocabulary"
  private const val FORMAT_STYLE = "format_style"
  private const val TRANSLATE_TARGET = "translate_target"
  private const val APP_STYLE_OVERRIDES = "app_style_overrides"
  private const val SMART_CONTEXT = "smart_context_enabled"
  private const val LEARNED_VOCAB = "learned_vocabulary"
  private const val TYPING_LANGUAGE = "typing_language"
  private const val TYPING_INPUT_MODE = "typing_input_mode"
  private const val IME_PANEL = "ime_panel"
  private const val CORRECTION_PAIRS = "correction_pairs"
  private const val KEY_SOUND = "key_sound"
  private const val KEY_HAPTICS = "key_haptics"
  private const val THEME_MODE = "theme_mode"
  private const val LIVE_TYPING = "live_typing"
  private const val SWIPE_TYPING = "swipe_typing"
  private const val AUTOCORRECT = "autocorrect"

  fun setLiveTyping(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(LIVE_TYPING, enabled).apply()
  }
  fun getLiveTyping(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(LIVE_TYPING, true)

  /** [haptics] null keeps the keyboard's own choice (it can be changed in keyboard Settings). */
  fun setKeyboardPrefs(context: Context, keySound: Boolean, haptics: Boolean?, theme: String) {
    val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putBoolean(KEY_SOUND, keySound).putString(THEME_MODE, theme)
    if (haptics != null) edit.putBoolean(KEY_HAPTICS, haptics)
    edit.apply()
  }
  fun putKeyHaptics(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HAPTICS, enabled).apply()
  }
  fun getSwipeTyping(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SWIPE_TYPING, true)
  fun putSwipeTyping(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SWIPE_TYPING, enabled).apply()
  }
  fun getAutocorrect(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTOCORRECT, true)
  fun putAutocorrect(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTOCORRECT, enabled).apply()
  }
  fun getKeySound(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SOUND, false)
  fun getKeyHaptics(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HAPTICS, true)
  fun getThemeMode(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(THEME_MODE, "system") ?: "system"
  /** True when the keyboard should draw dark, honoring the app's System/Light/Dark choice. */
  fun isNight(context: Context): Boolean = when (getThemeMode(context)) {
    "dark" -> true
    "light" -> false
    else -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
  }

  private fun key(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    if (existing != null) return existing
    return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
      init(android.security.keystore.KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
      ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
        .setUserAuthenticationRequired(false)
        .build())
    }.generateKey()
  }

  private fun putProtected(context: Context, name: String, value: String) {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key())
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putString(name, Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP))
      .putString("${name}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
      .apply()
  }

  private fun getProtected(context: Context, name: String): String? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val payload = prefs.getString(name, null) ?: return null
    val iv = prefs.getString("${name}_iv", null) ?: return null
    return runCatching {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
      String(cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }.getOrNull()
  }

  fun put(context: Context, accessToken: String) {
    require(accessToken.isNotBlank())
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.edit()
      .putString(TOKEN, Base64.encodeToString(cipher.doFinal(accessToken.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP))
      .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
      .apply()
  }

  fun get(context: Context): String? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val token = prefs.getString(TOKEN, null) ?: return null
    val iv = prefs.getString(IV, null) ?: return null
    return runCatching {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
      String(cipher.doFinal(Base64.decode(token, Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }.getOrNull()
  }

  fun putDeviceToken(context: Context, token: String) = putProtected(context, "keyboard_device_token", token)
  fun getDeviceToken(context: Context): String? = getProtected(context, "keyboard_device_token")?.takeIf { it.isNotBlank() }

  /** Non-personal keyboard settings that survive sign-out, so the next sign-in works at once. */
  private val KEEP_ON_CLEAR = listOf(API_BASE_URL, "app_language", LANGUAGE, CLEAN_AND_FORMAT, FORMAT_STYLE, KEY_SOUND, KEY_HAPTICS, SWIPE_TYPING, AUTOCORRECT, THEME_MODE, LIVE_TYPING, TYPING_LANGUAGE, TYPING_INPUT_MODE, "offline_flow_ai")

  fun clear(context: Context) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val kept = KEEP_ON_CLEAR.mapNotNull { key -> prefs.all[key]?.let { key to it } }
    val editor = prefs.edit().clear()
    kept.forEach { (key, value) ->
      when (value) {
        is String -> editor.putString(key, value)
        is Boolean -> editor.putBoolean(key, value)
        is Int -> editor.putInt(key, value)
        is Long -> editor.putLong(key, value)
        is Float -> editor.putFloat(key, value)
      }
    }
    // commit, not apply: a sign-in right after must not race a pending wipe.
    editor.commit()
  }

  fun putModelPath(context: Context, path: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(MODEL_PATH, path).apply()
  }

  fun getModelPath(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODEL_PATH, null)

  fun clearModelPath(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(MODEL_PATH).apply()
  }

  fun putQwenModelPath(context: Context, path: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(QWEN_MODEL_PATH, path).apply()
  }

  fun getQwenModelPath(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(QWEN_MODEL_PATH, null)

  fun clearQwenModelPath(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(QWEN_MODEL_PATH).apply()
  }

  fun putCloudConfig(context: Context, apiBaseUrl: String, language: String, cleanAndFormat: Boolean, vocabulary: List<String>, formatStyle: String) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    // The keyboard language picker wins until the app setting itself changes.
    val appChanged = prefs.getString("app_language", null) != language
    prefs.edit()
      .putString(API_BASE_URL, apiBaseUrl.trim().trimEnd('/'))
      .putString("app_language", language)
      .apply { if (appChanged) putString(LANGUAGE, language) }
      .putBoolean(CLEAN_AND_FORMAT, cleanAndFormat)
      .putString(FORMAT_STYLE, formatStyle)
      .apply()
    putProtected(context, VOCABULARY, vocabulary.map { it.trim() }.filter { it.isNotBlank() }.take(32).joinToString("\u001f"))
  }

  fun setPersonalStyleNote(context: Context, note: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("personal_style_note", note.take(400)).apply()
  }
  fun getPersonalStyleNote(context: Context): String =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("personal_style_note", "") ?: ""

  fun getApiBaseUrl(context: Context): String? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (prefs.contains(API_BASE_URL)) return prefs.getString(API_BASE_URL, null)
    // Never synced (or wiped by an old build): recover it from the signed-in session itself.
    val derived = runCatching { get(context)?.let { functionsUrlFromToken(it) } }.getOrNull()
    if (!derived.isNullOrBlank()) prefs.edit().putString(API_BASE_URL, derived).apply()
    return derived
  }

  /** The account token's issuer is "https://<project>/auth/v1"; voice lives at "/functions/v1". */
  internal fun functionsUrlFromToken(token: String): String? {
    val part = token.split(".").getOrNull(1) ?: return null
    val json = String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), StandardCharsets.UTF_8)
    val iss = org.json.JSONObject(json).optString("iss").trim().trimEnd('/')
    if (!iss.startsWith("https://") || !iss.endsWith("/auth/v1")) return null
    return iss.removeSuffix("/auth/v1") + "/functions/v1"
  }
  fun putLanguage(context: Context, language: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LANGUAGE, language).apply()
  }

  fun getLanguage(context: Context): String = FlowLanguages.supportedSpeech(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LANGUAGE, "Auto-detect"))
  fun putLastCleanup(context: Context, path: String, reason: String) {
    val line = if (reason.isBlank()) path else "$path ($reason)"
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_cleanup", line).apply()
  }
  fun getLastCleanup(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_cleanup", "").orEmpty()
  fun getOfflineFlowAi(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("offline_flow_ai", true)
  fun setOfflineFlowAi(context: Context, enabled: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("offline_flow_ai", enabled).apply() }
  // Formatted dictation is the default. Controls can still switch back to verbatim.
  fun getCleanAndFormat(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLEAN_AND_FORMAT, true)
  fun getVocabulary(context: Context): List<String> {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val protected = getProtected(context, VOCABULARY)
    val raw = protected ?: prefs.getString(VOCABULARY, "").orEmpty()
    if (protected == null && raw.isNotBlank()) {
      putProtected(context, VOCABULARY, raw)
      prefs.edit().remove(VOCABULARY).remove("${VOCABULARY}_iv").apply()
    }
    return raw.split("\u001f").filter { it.isNotBlank() }.take(32)
  }
  fun getFormatStyle(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FORMAT_STYLE, "natural") ?: "natural"

  fun putTranslateTarget(context: Context, language: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TRANSLATE_TARGET, language).apply()
  }

  fun getTranslateRecents(context: Context): List<String> =
    (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("translate_recents", "English,Bangla,Hindi") ?: "")
      .split(",").map { it.trim() }.filter { it.isNotEmpty() }

  fun pushTranslateRecent(context: Context, language: String) {
    val list = (listOf(language) + getTranslateRecents(context)).distinct().take(3)
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("translate_recents", list.joinToString(",")).apply()
  }

  fun getLastDetected(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_detected_lang", null)

  fun putLastDetected(context: Context, code: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_detected_lang", code).apply()
  }

  fun getTranslateTarget(context: Context): String =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TRANSLATE_TARGET, "Japanese") ?: "Japanese"

  fun putAppStyleOverride(context: Context, packageName: String, style: String) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val map = getAppStyleOverrides(context).toMutableMap()
    val pkg = packageName.trim()
    if (pkg.isEmpty()) return
    if (style.isBlank() || style.equals("auto", true)) map.remove(pkg) else map[pkg] = style
    prefs.edit().putString(APP_STYLE_OVERRIDES, map.entries.joinToString("\u001e") { "${it.key}\u001f${it.value}" }).apply()
  }

  fun getAppStyleOverrides(context: Context): Map<String, String> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(APP_STYLE_OVERRIDES, "").orEmpty()
    if (raw.isBlank()) return emptyMap()
    return raw.split("\u001e").mapNotNull { row ->
      val parts = row.split("\u001f")
      if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
    }.toMap()
  }

  fun getAppStyleOverride(context: Context, packageName: String): String? =
    getAppStyleOverrides(context)[packageName]

  fun setSmartContextEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SMART_CONTEXT, enabled).apply()
  }

  /** OFF by default. When on, IME may read an optional Accessibility snippet. */
  fun isSmartContextEnabled(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SMART_CONTEXT, false)

  fun addLearnedVocabulary(context: Context, terms: List<String>) {
    val current = (getVocabulary(context) + getLearnedVocabulary(context) + terms)
      .map { it.trim() }.filter { it.isNotBlank() }.distinct().take(64)
    putProtected(context, LEARNED_VOCAB, current.joinToString("\u001f"))
    // Also merge into primary vocabulary list used by polish.
    val base = getVocabulary(context).toMutableList()
    for (term in terms) {
      val t = term.trim()
      if (t.isNotBlank() && t !in base) base.add(t)
    }
    putProtected(context, VOCABULARY, base.take(32).joinToString("\u001f"))
  }

  fun getLearnedVocabulary(context: Context): List<String> {
    val raw = getProtected(context, LEARNED_VOCAB).orEmpty()
    return raw.split("\u001f").filter { it.isNotBlank() }.take(64)
  }

  fun putTypingLanguage(context: Context, language: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TYPING_LANGUAGE, language).apply()
  }

  fun getTypingLanguage(context: Context): String =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TYPING_LANGUAGE, "en") ?: "en"

  fun putTypingInputMode(context: Context, mode: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TYPING_INPUT_MODE, mode).apply()
  }

  fun getTypingInputMode(context: Context): String =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TYPING_INPUT_MODE, "fixed") ?: "fixed"

  fun putImePanel(context: Context, panel: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(IME_PANEL, panel).apply()
  }

  fun getImePanel(context: Context): String =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(IME_PANEL, "typing") ?: "typing"

  /** Persist raw→corrected pairs to improve future learning. */
  fun addCorrectionPair(context: Context, original: String, corrected: String) {
    val o = original.trim(); val c = corrected.trim()
    if (o.isBlank() || c.isBlank() || o.equals(c, true)) return
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString(CORRECTION_PAIRS, "").orEmpty()
    val rows = raw.split("\u001e").filter { it.isNotBlank() }.toMutableList()
    rows.add(0, "$o\u001f$c")
    prefs.edit().putString(CORRECTION_PAIRS, rows.distinct().take(40).joinToString("\u001e")).apply()
    // Prefer corrected tokens in learned vocab
    addLearnedVocabulary(context, c.split(Regex("\\s+")).filter { it.length >= 2 })
  }

  fun getCorrectionPairs(context: Context): List<Pair<String, String>> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CORRECTION_PAIRS, "").orEmpty()
    if (raw.isBlank()) return emptyList()
    return raw.split("\u001e").mapNotNull { row ->
      val parts = row.split("\u001f")
      if (parts.size == 2) parts[0] to parts[1] else null
    }
  }
}
