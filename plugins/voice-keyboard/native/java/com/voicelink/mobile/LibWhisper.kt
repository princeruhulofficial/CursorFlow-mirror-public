package com.voicelink.mobile

/**
 * Stub only — whisper.cpp / ggml native libraries are not shipped in the Android
 * product APK. Keeping this symbol satisfies historical IME call sites while
 * ensuring no native whisper shared object is loaded at process start.
 */
object LibWhisper {
  fun isLoaded(): Boolean = false

  fun initContext(@Suppress("UNUSED_PARAMETER") modelPath: String): Long = 0L

  fun freeContext(@Suppress("UNUSED_PARAMETER") contextPtr: Long) = Unit

  fun fullTranscribe(
    @Suppress("UNUSED_PARAMETER") contextPtr: Long,
    @Suppress("UNUSED_PARAMETER") numThreads: Int,
    @Suppress("UNUSED_PARAMETER") audioData: FloatArray,
    @Suppress("UNUSED_PARAMETER") language: String,
  ): Int = -1

  fun getTextSegmentCount(@Suppress("UNUSED_PARAMETER") contextPtr: Long): Int = 0

  fun getTextSegment(@Suppress("UNUSED_PARAMETER") contextPtr: Long, @Suppress("UNUSED_PARAMETER") index: Int): String = ""

  fun preferredThreadCount(): Int = 1
}
