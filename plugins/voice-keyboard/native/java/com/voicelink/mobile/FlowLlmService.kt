package com.voicelink.mobile

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger

/**
 * Hosts the offline Flow AI model in its own process (android:process=":llm"),
 * so a native fault can never take the keyboard down with it.
 */
class FlowLlmService : Service() {
  private lateinit var worker: HandlerThread
  private lateinit var messenger: Messenger

  override fun onCreate() {
    super.onCreate()
    worker = HandlerThread("flow-llm").apply { start() }
    messenger = Messenger(object : Handler(worker.looper) {
      override fun handleMessage(msg: Message) {
        if (msg.what != FlowLlm.MSG_CLEAN) return
        val text = msg.data.getString("text").orEmpty()
        val budget = msg.data.getInt("budgetMs", 4000)
        val result = runCatching { clean(text, budget) }.getOrNull()
        val reply = Message.obtain(null, FlowLlm.MSG_RESULT).apply {
          data = Bundle().apply { putString("text", result); putLong("id", msg.data.getLong("id")) }
        }
        runCatching { msg.replyTo?.send(reply) }
      }
    })
  }

  private fun clean(text: String, budgetMs: Int): String? {
    val model = FlowLlm.modelFile(this)
    if (!model.exists() || !FlowLlmNative.ensureLibrary()) return null
    val threads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2).coerceAtMost(4)
    if (!FlowLlmNative.load(model.absolutePath, threads)) return null
    val prompt = "<|im_start|>system\n${FlowLlm.SYSTEM_PROMPT}<|im_end|>\n<|im_start|>user\n${FlowLlm.wrapDictated(text)}<|im_end|>\n<|im_start|>assistant\n"
    val maxTokens = (text.length / 2 + 24).coerceAtMost(220)
    return FlowLlmNative.complete(prompt, maxTokens, budgetMs)?.toString(Charsets.UTF_8)
  }

  override fun onBind(intent: Intent?): IBinder = messenger.binder

  override fun onDestroy() {
    worker.quitSafely()
    super.onDestroy()
  }
}
