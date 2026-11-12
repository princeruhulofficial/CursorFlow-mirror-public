package com.voicelink.mobile

import java.io.File

/** JNI surface for libflowllm.so. Only touched inside the ":llm" process. */
object FlowLlmNative {
  @Volatile private var loaded = false

  fun cpuSupported(): Boolean {
    if (!android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")) return false
    return runCatching { File("/proc/cpuinfo").readText().contains("asimddp") }.getOrDefault(false)
  }

  fun ensureLibrary(): Boolean {
    if (loaded) return true
    if (!cpuSupported()) return false
    return runCatching { System.loadLibrary("flowllm"); loaded = true; true }.getOrDefault(false)
  }

  external fun load(path: String, threads: Int): Boolean
  external fun complete(prompt: String, maxTokens: Int, budgetMs: Int): ByteArray?
}
