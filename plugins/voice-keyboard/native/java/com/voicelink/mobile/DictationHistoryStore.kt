package com.voicelink.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Local IME history for offline / failed-insert dictations.
 * Status: pending | inserted | saved
 */
object DictationHistoryStore {
  private const val DIR = "ime_dictation_history"
  private const val INDEX = "history.json"
  private const val MAX = 300

  data class Entry(
    val id: String,
    val text: String,
    val packageName: String,
    val language: String,
    val createdAt: Long,
    val source: String,
    val status: String = "saved",
  )

  private fun dir(context: Context) = File(context.filesDir, DIR).also { it.mkdirs() }
  private fun indexFile(context: Context) = File(dir(context), INDEX)

  fun append(
    context: Context,
    text: String,
    packageName: String,
    language: String,
    source: String = "offline",
    status: String = "saved",
  ): String {
    val id = UUID.randomUUID().toString().replace("-", "").take(16)
    val items = load(context).toMutableList()
    items.add(0, Entry(id, text, packageName, language, System.currentTimeMillis(), source, status))
    while (items.size > MAX) items.removeAt(items.lastIndex)
    save(context, items)
    return id
  }

  fun updateStatus(context: Context, id: String, status: String) {
    val items = load(context).toMutableList()
    val idx = items.indexOfFirst { it.id == id }
    if (idx < 0) return
    items[idx] = items[idx].copy(status = status)
    save(context, items)
  }

  fun remove(context: Context, id: String) { save(context, load(context).filter { it.id != id }) }

  fun get(context: Context, id: String): Entry? = load(context).firstOrNull { it.id == id }

  fun load(context: Context): List<Entry> {
    val f = indexFile(context)
    if (!f.exists()) return emptyList()
    return runCatching {
      val arr = JSONArray(f.readText())
      buildList {
        for (i in 0 until arr.length()) {
          val o = arr.getJSONObject(i)
          add(
            Entry(
              o.getString("id"),
              o.getString("text"),
              o.optString("packageName", ""),
              o.optString("language", ""),
              o.optLong("createdAt", 0L),
              o.optString("source", "offline"),
              o.optString("status", "saved"),
            ),
          )
        }
      }
    }.getOrDefault(emptyList())
  }

  fun toJson(context: Context): String {
    // Merge active offline queue (pending) with saved history.
    val arr = JSONArray()
    val seen = HashSet<String>()
    runCatching {
      OfflineDictationQueue.load(context).forEach { item ->
        val text = runCatching {
          if (item.kind == "text") {
            JSONObject(File(item.wavPath).readText()).optString("text", "")
          } else ""
        }.getOrDefault("")
        val preview = text.ifBlank { "(audio pending transcription)" }
        arr.put(JSONObject().apply {
          put("id", item.id)
          put("text", preview)
          put("packageName", item.packageName)
          put("language", item.language)
          put("createdAt", item.createdAt)
          put("source", "offline_queue")
          put("status", "pending")
          put("attempts", item.attempts)
          put("lastError", item.lastError)
        })
        seen.add(item.id)
      }
    }
    load(context).forEach { e ->
      if (e.id in seen) return@forEach
      arr.put(JSONObject().apply {
        put("id", e.id)
        put("text", e.text)
        put("packageName", e.packageName)
        put("language", e.language)
        put("createdAt", e.createdAt)
        put("source", e.source)
        put("status", e.status)
      })
    }
    return arr.toString()
  }

  private fun save(context: Context, items: List<Entry>) {
    val arr = JSONArray()
    items.forEach { e ->
      arr.put(JSONObject().apply {
        put("id", e.id)
        put("text", e.text)
        put("packageName", e.packageName)
        put("language", e.language)
        put("createdAt", e.createdAt)
        put("source", e.source)
        put("status", e.status)
      })
    }
    indexFile(context).writeText(arr.toString())
  }
}
