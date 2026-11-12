package com.voicelink.mobile

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.UUID

object LocalCleanupHandoff {
  const val ACTION_REQUEST = "com.voicelink.mobile.LOCAL_CLEANUP_REQUEST"
  private const val PREFS = "cursorflow_voice_keyboard"
  private const val REQUEST_ID = "local_cleanup_request_id"
  private const val REQUEST_TEXT = "local_cleanup_request_text"
  private const val REQUEST_LANGUAGE = "local_cleanup_request_language"
  private const val RESULT_ID = "local_cleanup_result_id"
  private const val RESULT_TEXT = "local_cleanup_result_text"

  fun request(context: Context, text: String, language: String): String {
    val id = UUID.randomUUID().toString()
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putString(REQUEST_ID, id)
      .putString(REQUEST_TEXT, text)
      .putString(REQUEST_LANGUAGE, language)
      .remove(RESULT_ID)
      .remove(RESULT_TEXT)
      .apply()
    context.sendBroadcast(Intent(ACTION_REQUEST).setPackage(context.packageName).apply {
      putExtra(REQUEST_ID, id)
      putExtra(REQUEST_TEXT, text)
      putExtra(REQUEST_LANGUAGE, language)
    })
    return id
  }

  fun awaitResult(context: Context, requestId: String, timeoutMs: Long = 2_500L): String? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
      if (prefs.getString(RESULT_ID, null) == requestId) {
        val result = prefs.getString(RESULT_TEXT, null)?.trim()
        prefs.edit().remove(REQUEST_ID).remove(REQUEST_TEXT).remove(REQUEST_LANGUAGE).remove(RESULT_ID).remove(RESULT_TEXT).apply()
        return result?.takeIf { it.isNotBlank() }
      }
      SystemClock.sleep(100L)
    }
    prefs.edit().remove(REQUEST_ID).remove(REQUEST_TEXT).remove(REQUEST_LANGUAGE).remove(RESULT_ID).remove(RESULT_TEXT).apply()
    return null
  }

  fun resolve(context: Context, requestId: String, text: String) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (prefs.getString(REQUEST_ID, null) != requestId) return
    prefs.edit().putString(RESULT_ID, requestId).putString(RESULT_TEXT, text).apply()
  }
}
