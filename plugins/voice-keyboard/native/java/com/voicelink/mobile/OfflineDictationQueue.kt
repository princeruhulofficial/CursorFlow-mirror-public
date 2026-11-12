package com.voicelink.mobile

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Offline dictation queue with retry / backoff metadata.
 * Items are PCM wav or pre-captured text that still needs polish/insert.
 */
object OfflineDictationQueue {
  private const val DIR = "offline_dictation_queue"
  private const val INDEX = "index.json"
  private const val MAX_ATTEMPTS = 8

  data class Item(
    val id: String,
    val wavPath: String,
    val language: String,
    val packageName: String,
    val createdAt: Long,
    val kind: String = "wav", // wav | text
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0L,
    val lastError: String = "",
  )

  private fun dir(context: Context) = File(context.filesDir, DIR).also { it.mkdirs() }
  private fun indexFile(context: Context) = File(dir(context), INDEX)

  fun enqueueText(
    context: Context,
    text: String,
    language: String,
    packageName: String,
    formatStyle: String,
    contextJson: String,
  ): String {
    val id = UUID.randomUUID().toString().replace("-", "").take(16)
    val meta = File(dir(context), "$id.txt.json")
    meta.writeText(JSONObject().apply {
      put("text", text)
      put("language", language)
      put("packageName", packageName)
      put("formatStyle", formatStyle)
      put("inputContext", contextJson)
      put("kind", "text")
    }.toString())
    val items = load(context).toMutableList()
    items.add(Item(id, meta.absolutePath, language, packageName, System.currentTimeMillis(), kind = "text"))
    trim(context, items)
    save(context, items)
    return id
  }

  fun enqueue(context: Context, pcm: ShortArray, language: String, packageName: String): String {
    val id = UUID.randomUUID().toString().replace("-", "").take(16)
    val wav = File(dir(context), "$id.wav")
    wav.writeBytes(pcmToWav(pcm, 16000))
    val items = load(context).toMutableList()
    items.add(Item(id, wav.absolutePath, language, packageName, System.currentTimeMillis(), kind = "wav"))
    trim(context, items)
    save(context, items)
    return id
  }

  fun load(context: Context): List<Item> {
    val f = indexFile(context)
    if (!f.exists()) return emptyList()
    return runCatching {
      val arr = JSONArray(f.readText())
      buildList {
        for (i in 0 until arr.length()) {
          val o = arr.getJSONObject(i)
          add(
            Item(
              o.getString("id"),
              o.getString("wavPath"),
              o.optString("language", "Auto-detect"),
              o.optString("packageName", ""),
              o.optLong("createdAt", 0L),
              o.optString("kind", if (o.getString("wavPath").endsWith(".json")) "text" else "wav"),
              o.optInt("attempts", 0),
              o.optLong("nextAttemptAt", 0L),
              o.optString("lastError", ""),
            ),
          )
        }
      }
    }.getOrDefault(emptyList())
  }

  fun nextReady(context: Context, now: Long = System.currentTimeMillis()): Item? =
    load(context).firstOrNull { it.attempts < MAX_ATTEMPTS && it.nextAttemptAt <= now }

  fun markAttempt(context: Context, id: String, error: String?) {
    val items = load(context).toMutableList()
    val idx = items.indexOfFirst { it.id == id }
    if (idx < 0) return
    val cur = items[idx]
    val attempts = cur.attempts + 1
    val backoffMs = (1500L * (1L shl attempts.coerceAtMost(6))).coerceAtMost(120_000L)
    items[idx] = cur.copy(
      attempts = attempts,
      nextAttemptAt = System.currentTimeMillis() + backoffMs,
      lastError = error.orEmpty().take(160),
    )
    if (attempts >= MAX_ATTEMPTS) {
      // Keep file for History salvage; drop from active queue after max retries.
      File(cur.wavPath).delete()
      items.removeAt(idx)
    }
    save(context, items)
  }

  fun markReadyNow(context: Context, id: String) {
    val items = load(context).toMutableList()
    val idx = items.indexOfFirst { it.id == id }
    if (idx < 0) return
    items[idx] = items[idx].copy(nextAttemptAt = 0L, lastError = "")
    save(context, items)
  }

  fun remove(context: Context, id: String) {
    val all = load(context)
    all.firstOrNull { it.id == id }?.let { File(it.wavPath).delete() }
    save(context, all.filterNot { it.id == id })
  }

  fun peekBytes(context: Context, id: String): ByteArray? {
    val item = load(context).firstOrNull { it.id == id } ?: return null
    val f = File(item.wavPath)
    return if (f.exists()) f.readBytes() else null
  }

  fun peekWav(context: Context, id: String): ByteArray? = peekBytes(context, id)

  fun readTextMeta(context: Context, id: String): JSONObject? {
    val item = load(context).firstOrNull { it.id == id } ?: return null
    if (item.kind != "text") return null
    val f = File(item.wavPath)
    if (!f.exists()) return null
    return runCatching { JSONObject(f.readText()) }.getOrNull()
  }

  private fun trim(context: Context, items: MutableList<Item>) {
    while (items.size > 8) {
      val old = items.removeAt(0)
      File(old.wavPath).delete()
    }
  }

  private fun save(context: Context, items: List<Item>) {
    val arr = JSONArray()
    items.forEach { item ->
      arr.put(JSONObject().apply {
        put("id", item.id)
        put("wavPath", item.wavPath)
        put("language", item.language)
        put("packageName", item.packageName)
        put("createdAt", item.createdAt)
        put("kind", item.kind)
        put("attempts", item.attempts)
        put("nextAttemptAt", item.nextAttemptAt)
        put("lastError", item.lastError)
      })
    }
    indexFile(context).writeText(arr.toString())
  }

  private fun pcmToWav(samples: ShortArray, sampleRate: Int): ByteArray {
    val dataSize = samples.size * 2
    val out = java.nio.ByteBuffer.allocate(44 + dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    out.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataSize)
      .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
      .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
      .put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize)
    samples.forEach { out.putShort(it) }
    return out.array()
  }

  fun wavToDataUrl(bytes: ByteArray): String =
    "data:audio/wav;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

  fun wavToPcm(bytes: ByteArray): ShortArray? {
    if (bytes.size <= 44) return null
    val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    // skip 44-byte header when present
    val dataOffset = if (bytes.size > 44 && String(bytes, 0, 4) == "RIFF") 44 else 0
    val count = (bytes.size - dataOffset) / 2
    val out = ShortArray(count)
    bb.position(dataOffset)
    for (i in 0 until count) out[i] = bb.short
    return out
  }
}
