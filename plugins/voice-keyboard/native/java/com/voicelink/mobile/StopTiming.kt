package com.voicelink.mobile

import android.os.SystemClock
import android.util.Log

/**
 * Stop-to-text timing for the voice keyboard: `adb logcat -s CursorFlowLatency`.
 * Only step names, the path taken, milliseconds and character counts are logged,
 * never words, audio or field content.
 */
internal object StopTiming {
  private const val TAG = "CursorFlowLatency"
  @Volatile private var startedAt = 0L
  @Volatile var path = ""

  fun start(newPath: String) {
    startedAt = SystemClock.elapsedRealtime()
    path = newPath
    runCatching { Log.i(TAG, "stop_tapped path=$newPath") }
  }

  fun step(name: String, chars: Int? = null) {
    val at = startedAt
    if (at <= 0L) return
    val extra = if (chars != null) " chars=$chars" else ""
    runCatching { Log.i(TAG, "$name +${SystemClock.elapsedRealtime() - at}ms path=$path$extra") }
  }

  fun end(name: String) {
    step(name)
    startedAt = 0L
  }
}
