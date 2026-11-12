package com.voicelink.mobile

import android.util.Base64
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object CloudKeyboardTranscriber {
  fun transcribe(baseUrl: String, token: String, pcm: ShortArray, language: String, cleanAndFormat: Boolean, vocabulary: List<String>, formatStyle: String, context: JSONObject? = null): String {
    require(baseUrl.isNotBlank()) { "Cloud transcription is not configured" }
    require(token.isNotBlank()) { "Sign in to Cursor Flow first" }
    require(baseUrl.trim().startsWith("https://")) { "Cloud transcription requires HTTPS" }
    val audio = wavBytes(pcm, 16000)
    val audioDataUrl = "data:audio/wav;base64," + Base64.encodeToString(audio, Base64.NO_WRAP)
    val payload = JSONObject().apply {
      put("audioDataUrl", audioDataUrl)
      put("cleanAndFormat", cleanAndFormat)
      put("vocabulary", org.json.JSONArray(vocabulary.take(32)))
      put("formatStyle", formatStyle)
      if (context != null) put("inputContext", context)
      put("operationId", UUID.randomUUID().toString().replace("-", "").take(32))
      put("deviceId", "android-voice-keyboard")
      put("deviceType", "android")
      put("durationMs", pcm.size / 16)
      put("network", "unknown")
      if (language != "Auto-detect" && languageCode(language) in FlowLanguages.cloudSpeechCodes) put("language", languageCode(language))
    }
    val endpoint = baseUrl.trimEnd('/') + "/transcribe-mobile"
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      doOutput = true
      connectTimeout = 15_000
      readTimeout = 60_000
      setRequestProperty("Authorization", "Bearer $token")
      setRequestProperty("Accept", "application/json")
      setRequestProperty("Content-Type", "application/json")
    }
    try {
      connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
      val status = connection.responseCode
      val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
        ?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (status !in 200..299) {
        // Keep the server's reason (for example no active plan) so the keyboard can explain it.
        val reason = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty().take(40)
        throw IllegalStateException("Cloud transcription failed ($status)" + if (reason.isNotBlank()) " $reason" else "")
      }
      val text = JSONObject(body).optString("text").trim()
      if (text.isBlank()) throw IllegalStateException("Cloud transcription returned no speech")
      return text
    } finally {
      connection.disconnect()
    }
  }


  fun rewrite(baseUrl: String, token: String, sourceText: String, instruction: String): String {
    require(baseUrl.isNotBlank()) { "Cloud rewrite is not configured" }
    require(token.isNotBlank()) { "Sign in to Cursor Flow first" }
    require(baseUrl.trim().startsWith("https://")) { "Cloud rewrite requires HTTPS" }
    require(sourceText.isNotBlank()) { "Nothing to edit" }
    require(instruction.isNotBlank()) { "No edit instruction" }
    val payload = JSONObject().apply {
      put("llmMessages", org.json.JSONArray().apply {
        put(JSONObject().apply {
          put("role", "system")
          put("content", "You rewrite the user's text per their spoken instruction. Return only the rewritten text. Do not explain. Preserve meaning unless asked to change it. Keep the same language unless asked to translate.")
        })
        put(JSONObject().apply {
          put("role", "user")
          put("content", "Instruction: " + instruction.trim() + "\n\nText:\n" + sourceText)
        })
      })
      put("operationId", UUID.randomUUID().toString().replace("-", "").take(32))
      put("deviceId", "android-voice-keyboard-edit")
      put("task", "edit")
      put("deviceType", "android")
    }
    val endpoint = baseUrl.trimEnd('/') + "/transcribe-mobile"
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      doOutput = true
      connectTimeout = 15_000
      readTimeout = 60_000
      setRequestProperty("Authorization", "Bearer $token")
      setRequestProperty("Accept", "application/json")
      setRequestProperty("Content-Type", "application/json")
    }
    try {
      connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
      val status = connection.responseCode
      val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
        ?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (status == 402 || "credit" in body.lowercase() || "quota" in body.lowercase()) {
        throw IllegalStateException("Flow AI needs cloud credit for voice edit. Raw dictation still works.")
      }
      if (status !in 200..299) {
        throw IllegalStateException("Voice edit failed ($status)")
      }
      val json = JSONObject(body)
      val text = json.optString("text").ifBlank { json.optString("content") }.trim()
      if (text.isBlank()) throw IllegalStateException("Voice edit returned empty text")
      return text
    } finally {
      connection.disconnect()
    }
  }





  fun transcribeWav(baseUrl: String, token: String, wavBytes: ByteArray, language: String, cleanAndFormat: Boolean, vocabulary: List<String>, formatStyle: String, context: JSONObject? = null): String {
    require(baseUrl.isNotBlank()) { "Cloud transcription is not configured" }
    require(token.isNotBlank()) { "Sign in to Cursor Flow first" }
    require(baseUrl.trim().startsWith("https://")) { "Cloud transcription requires HTTPS" }
    require(wavBytes.size > 44) { "Empty audio" }
    val audioDataUrl = "data:audio/wav;base64," + Base64.encodeToString(wavBytes, Base64.NO_WRAP)
    val payload = JSONObject().apply {
      put("audioDataUrl", audioDataUrl)
      put("cleanAndFormat", cleanAndFormat)
      put("vocabulary", org.json.JSONArray(vocabulary.take(32)))
      put("formatStyle", formatStyle)
      if (context != null) put("inputContext", context)
      put("operationId", UUID.randomUUID().toString().replace("-", "").take(32))
      put("deviceId", "android-voice-keyboard-offline")
      put("deviceType", "android")
      put("durationMs", ((wavBytes.size - 44) / 32).coerceAtLeast(1))
      put("network", "offline-retry")
      if (language != "Auto-detect" && languageCode(language) in FlowLanguages.cloudSpeechCodes) put("language", languageCode(language))
    }
    val endpoint = baseUrl.trimEnd('/') + "/transcribe-mobile"
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      doOutput = true
      connectTimeout = 15_000
      readTimeout = 60_000
      setRequestProperty("Authorization", "Bearer $token")
      setRequestProperty("Accept", "application/json")
      setRequestProperty("Content-Type", "application/json")
    }
    try {
      connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
      val status = connection.responseCode
      val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
        ?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (status !in 200..299) throw IllegalStateException("Cloud transcription failed ($status)")
      val text = JSONObject(body).optString("text").trim()
      if (text.isBlank()) throw IllegalStateException("Cloud transcription returned no speech")
      return text
    } finally {
      connection.disconnect()
    }
  }

  /**
   * Like [polishText] but returns null whenever the cloud did not really clean
   * (offline, timeout, out of credits, 403, empty), so the caller can fall back
   * to offline Flow AI. Short timeouts: this runs after text is already inserted.
   */
  fun polishTextOrNull(
    baseUrl: String,
    token: String,
    sourceText: String,
    vocabulary: List<String>,
    formatStyle: String,
    context: JSONObject? = null,
    timeoutMs: Int = 3_000,
    /** One finalized piece of a live dictation: clean words only, no layout. */
    liveChunk: Boolean = false,
  ): String? {
    if (baseUrl.isBlank() || token.isBlank() || !baseUrl.trim().startsWith("https://") || sourceText.isBlank()) return null
    val payload = JSONObject().apply {
      put("cleanupText", sourceText)
      if (liveChunk) put("liveChunk", true)
      put("formatStyle", formatStyle)
      put("vocabulary", org.json.JSONArray(vocabulary.take(32)))
      if (context != null) put("inputContext", context)
      put("operationId", UUID.randomUUID().toString().replace("-", "").take(32))
      put("deviceId", "android-voice-keyboard-polish")
      put("deviceType", "android")
    }
    return runCatching {
      val connection = (URL(baseUrl.trimEnd('/') + "/transcribe-mobile").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = (timeoutMs / 2).coerceAtLeast(800)
        readTimeout = timeoutMs
        setRequestProperty("Authorization", "Bearer $token")
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Content-Type", "application/json")
      }
      try {
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        if (status !in 200..299) return@runCatching null
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        if (json.optString("status") == "failed") return@runCatching null
        json.optString("text").trim().ifBlank { null }
      } finally {
        connection.disconnect()
      }
    }.getOrNull()
  }

  /** Real-time edit of the live text. Returns the edited full text, or null when nothing changed or it failed. */
  fun liveEditOrNull(baseUrl: String, token: String, current: String, segment: String, timeoutMs: Int = 1_800): String? {
    if (baseUrl.isBlank() || token.isBlank() || !baseUrl.trim().startsWith("https://") || segment.isBlank()) return null
    val payload = JSONObject().apply {
      put("liveEdit", JSONObject().put("text", current.takeLast(4000)).put("segment", segment.take(1000)))
      put("deviceType", "android")
    }
    return runCatching {
      val connection = (URL(baseUrl.trimEnd('/') + "/transcribe-mobile").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 800
        readTimeout = timeoutMs
        setRequestProperty("Authorization", "Bearer $token")
        setRequestProperty("Content-Type", "application/json")
      }
      try {
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        if (connection.responseCode !in 200..299) return@runCatching null
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        if (!json.optBoolean("edited")) null else json.optString("text")
      } finally {
        connection.disconnect()
      }
    }.getOrNull()
  }

  fun polishText(
    baseUrl: String,
    token: String,
    sourceText: String,
    vocabulary: List<String>,
    formatStyle: String,
    context: JSONObject? = null,
  ): String {
    require(baseUrl.isNotBlank()) { "Cloud polish is not configured" }
    require(token.isNotBlank()) { "Sign in to Cursor Flow first" }
    require(baseUrl.trim().startsWith("https://")) { "Cloud polish requires HTTPS" }
    require(sourceText.isNotBlank()) { "Nothing to polish" }
    val payload = JSONObject().apply {
      put("cleanupText", sourceText)
      put("formatStyle", formatStyle)
      put("vocabulary", org.json.JSONArray(vocabulary.take(32)))
      if (context != null) put("inputContext", context)
      put("operationId", UUID.randomUUID().toString().replace("-", "").take(32))
      put("deviceId", "android-voice-keyboard-polish")
      put("deviceType", "android")
    }
    val endpoint = baseUrl.trimEnd('/') + "/transcribe-mobile"
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      doOutput = true
      connectTimeout = 15_000
      readTimeout = 60_000
      setRequestProperty("Authorization", "Bearer $token")
      setRequestProperty("Accept", "application/json")
      setRequestProperty("Content-Type", "application/json")
    }
    try {
      connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
      val status = connection.responseCode
      val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
        ?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (status == 402 || "credit" in body.lowercase() || "quota" in body.lowercase()) {
        return sourceText
      }
      if (status !in 200..299) return sourceText
      val json = JSONObject(body)
      if (json.optString("status") == "failed") return sourceText
      return json.optString("text").trim().ifBlank { sourceText }
    } finally {
      connection.disconnect()
    }
  }

  fun generate(baseUrl: String, token: String, instruction: String): String {
    require(baseUrl.isNotBlank()) { "Cloud write is not configured" }
    require(token.isNotBlank()) { "Sign in to Cursor Flow first" }
    require(baseUrl.trim().startsWith("https://")) { "Cloud write requires HTTPS" }
    require(instruction.isNotBlank()) { "No writing prompt" }
    val payload = JSONObject().apply {
      put("llmMessages", org.json.JSONArray().apply {
        put(JSONObject().apply {
          put("role", "system")
          put("content", "You are Flow AI. Write the text the user described. Return only the finished text. Do not explain. Match the language of the request unless they ask otherwise. Keep a natural professional tone.")
        })
        put(JSONObject().apply {
          put("role", "user")
          put("content", instruction.trim())
        })
      })
      put("operationId", UUID.randomUUID().toString().replace("-", "").take(32))
      put("deviceId", "android-voice-keyboard-write")
      put("task", "write")
      put("deviceType", "android")
    }
    return postLlm(baseUrl, token, payload, "Help me write")
  }

  fun translate(baseUrl: String, token: String, sourceText: String, targetLanguage: String): String {
    require(baseUrl.isNotBlank()) { "Cloud translate is not configured" }
    require(token.isNotBlank()) { "Sign in to Cursor Flow first" }
    require(baseUrl.trim().startsWith("https://")) { "Cloud translate requires HTTPS" }
    require(sourceText.isNotBlank()) { "Nothing to translate" }
    require(targetLanguage.isNotBlank()) { "Pick a language" }
    val payload = JSONObject().apply {
      put("llmMessages", org.json.JSONArray().apply {
        put(JSONObject().apply {
          put("role", "system")
          put("content", "You are Flow AI. Translate the user's text into " + targetLanguage.trim() + ". Return only the translation. Do not explain. Preserve meaning and tone.")
        })
        put(JSONObject().apply {
          put("role", "user")
          put("content", sourceText)
        })
      })
      put("operationId", UUID.randomUUID().toString().replace("-", "").take(32))
      put("deviceId", "android-voice-keyboard-translate")
      put("task", "translate")
      put("deviceType", "android")
    }
    return postLlm(baseUrl, token, payload, "Translate")
  }

  private fun postLlm(baseUrl: String, token: String, payload: JSONObject, label: String): String {
    val endpoint = baseUrl.trimEnd('/') + "/transcribe-mobile"
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      doOutput = true
      connectTimeout = 15_000
      readTimeout = 60_000
      setRequestProperty("Authorization", "Bearer $token")
      setRequestProperty("Accept", "application/json")
      setRequestProperty("Content-Type", "application/json")
    }
    try {
      connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
      val status = connection.responseCode
      val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
        ?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (status == 402 || "credit" in body.lowercase() || "quota" in body.lowercase()) {
        throw IllegalStateException("Flow AI needs cloud credit for $label. Raw dictation still works.")
      }
      if (status !in 200..299) {
        throw IllegalStateException("$label failed ($status)")
      }
      val json = JSONObject(body)
      val text = json.optString("text").ifBlank { json.optString("content") }.trim()
      if (text.isBlank()) throw IllegalStateException("$label returned empty text")
      return text
    } finally {
      connection.disconnect()
    }
  }

  private fun languageCode(value: String): String = when (value) {
    "বাংলা" -> "bn"
    "English" -> "en"
    "हिन्दी" -> "hi"
    "Español" -> "es"
    "Français" -> "fr"
    else -> value.lowercase().take(12)
  }

  private fun wavBytes(samples: ShortArray, sampleRate: Int): ByteArray {
    val dataSize = samples.size * 2
    val output = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
    output.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataSize)
      .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
      .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
      .put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize)
    samples.forEach { output.putShort(it) }
    return output.array()
  }
}
