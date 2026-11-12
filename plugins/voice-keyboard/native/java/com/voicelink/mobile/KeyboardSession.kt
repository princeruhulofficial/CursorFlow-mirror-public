package com.voicelink.mobile

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the keyboard signed in without the app running. The app hands over a long-lived
 * device token once; the keyboard trades it for a fresh access token when needed.
 */
object KeyboardSession {
  private const val REFRESH_MARGIN_S = 120L
  private val lock = Any()

  private fun expiry(token: String): Long? = runCatching {
    val part = token.split(".")[1]
    val json = String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
    JSONObject(json).getLong("exp")
  }.getOrNull()

  private fun post(context: Context, body: JSONObject, bearer: String? = null): JSONObject? {
    val base = VoiceKeyboardSecureStore.getApiBaseUrl(context)?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
      val conn = (URL(base.trimEnd('/') + "/keyboard-session").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 8000; readTimeout = 12000; doOutput = true
        setRequestProperty("content-type", "application/json")
        if (bearer != null) setRequestProperty("authorization", "Bearer $bearer")
      }
      try {
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode !in 200..299) null
        else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
      } finally { conn.disconnect() }
    }.getOrNull()
  }

  private fun isFresh(token: String?): Boolean {
    if (token.isNullOrBlank()) return false
    val exp = expiry(token) ?: return true
    return exp - System.currentTimeMillis() / 1000 > REFRESH_MARGIN_S
  }

  /** Gets a device token while the stored access token is still valid. Network call: never on the main thread. */
  fun ensureDeviceToken(context: Context) {
    synchronized(lock) {
      if (VoiceKeyboardSecureStore.getDeviceToken(context) != null) return
      val access = VoiceKeyboardSecureStore.get(context)
      if (!isFresh(access)) return
      val res = post(context, JSONObject().put("action", "issue").put("deviceId", "android-voice-keyboard"), access) ?: return
      val token = res.optString("deviceToken")
      if (token.isNotBlank()) VoiceKeyboardSecureStore.putDeviceToken(context, token)
    }
  }

  /** Returns a usable access token, refreshing it silently when close to expiry or when forced. */
  fun freshAccessToken(context: Context, force: Boolean = False): String? {
    synchronized(lock) {
      val current = VoiceKeyboardSecureStore.get(context)
      if (!force && isFresh(current)) {
        if (VoiceKeyboardSecureStore.getDeviceToken(context) == null) runCatching { ensureDeviceTokenUnlocked(context, current!!) }
        return current
      }
      val device = VoiceKeyboardSecureStore.getDeviceToken(context) ?: return current
      val res = post(context, JSONObject().put("action", "refresh").put("deviceToken", device)) ?: return current
      val next = res.optString("accessToken")
      if (next.isBlank()) return current
      VoiceKeyboardSecureStore.put(context, next)
      return next
    }
  }

  private fun ensureDeviceTokenUnlocked(context: Context, access: String) {
    val res = post(context, JSONObject().put("action", "issue").put("deviceId", "android-voice-keyboard"), access) ?: return
    val token = res.optString("deviceToken")
    if (token.isNotBlank()) VoiceKeyboardSecureStore.putDeviceToken(context, token)
  }

  /** Best-effort server revoke on sign-out. Network call: never on the main thread. */
  fun revoke(context: Context, deviceToken: String, baseUrl: String?) {
    if (baseUrl.isNullOrBlank()) return
    runCatching {
      val conn = (URL(baseUrl.trimEnd('/') + "/keyboard-session").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 8000; readTimeout = 8000; doOutput = true
        setRequestProperty("content-type", "application/json")
      }
      try {
        conn.outputStream.use { it.write(JSONObject().put("action", "revoke").put("deviceToken", deviceToken).toString().toByteArray()) }
        conn.responseCode
      } finally { conn.disconnect() }
    }
  }
}
