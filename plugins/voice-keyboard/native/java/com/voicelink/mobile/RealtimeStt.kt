package com.voicelink.mobile

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Live word-by-word speech over one websocket. The server hands out a short-lived
 * token (stt-token), so no provider key is ever on the phone. Two wire formats:
 * "xai" (binary PCM frames, transcript.partial / transcript.done) and
 * "elevenlabs" (base64 JSON chunks, partial_transcript / committed_transcript), and
 * "soniox" (a JSON start message, then binary PCM frames; token lists with is_final,
 * "<end>" marks a pause). The server picks one; the backup is used when the main one is out of credit.
 * The caller feeds 16 kHz mono PCM16; [onText] gets the full running text
 * (committed + current partial) every time it changes.
 */
class RealtimeStt(
  private val baseUrl: String,
  private val accessToken: String,
  private val language: String,
  private val vocabulary: List<String>,
  private val onText: (String) -> Unit,
  private val onFailed: (String) -> Unit,
  private val onReady: () -> Unit = {},
  /** Each finalized segment (a pause or chunk end), once, in order. */
  private val onCommitted: (String) -> Unit = {},
  /** Optional: fetch the sign-in token inside [connect] (off the main thread) instead of before construction. */
  private val tokenProvider: (() -> String)? = null,
  private val voiceKeyboard: Boolean = false,
) {
  @Volatile private var authToken = accessToken
  @Volatile var failed = false; private set
  @Volatile private var ready = false
  @Volatile private var readyAt = 0L
  fun readyForMs(): Long = if (readyAt == 0L) 0L else android.os.SystemClock.elapsedRealtime() - readyAt
  @Volatile private var socket: WebSocket? = null
  private var protocol = "elevenlabs"
  private var startMessage = ""
  private val sxFinal = StringBuilder()
  private val pending = ArrayList<ByteArray>()
  private val committed = StringBuilder()
  @Volatile private var sawPlainCommit = false
  @Volatile private var partial = ""
  private val keyboardTranscriptGate = KeyboardTranscriptGate()
  private var lastCommit = ""
  private var lastCommitAt = 0L
  private val done = CountDownLatch(1)
  @Volatile private var finishing = false
  @Volatile private var samplesSent = 0L
  @Volatile private var reported = false

  /** The words heard since the last finalized segment. */
  fun partialText(): String = toBengaliScript(partial.trim())

  /** True once any word was heard (live or finalized). */
  fun heardAnything(): Boolean = committed.isNotEmpty() || partial.isNotBlank()

  fun fullText(): String = toBengaliScript(listOf(committed.toString().trim(), partial.trim()).filter { it.isNotEmpty() }.joinToString(" ").trim())

  /** Auto-detect sometimes hears Bangla as Hindi. Devanagari and Bengali share one layout, so map it over and never show Devanagari. */
  private val banglaChosen = language.trim().lowercase().let { it == "ben" || it == "bn" || it.startsWith("bn-") || it.startsWith("bn_") || it == "bangla" || it == "bengali" || it == "বাংলা" }

  private fun toBengaliScript(text: String): String = romanToBangla(devanagariToBengali(text))

  /** Bangla was chosen but the engine wrote Latin letters ("ami tomake"): write real Bangla script, never Banglish. */
  private fun romanToBangla(text: String): String {
    if (!banglaChosen) return text
    val letters = text.filter { it.isLetter() }
    if (letters.isEmpty()) return text
    val latin = letters.count { it in 'a'..'z' || it in 'A'..'Z' }
    if (latin * 10 < letters.length * 7) return text
    return Regex("[A-Za-z]+").replace(text) { m -> AvroPhonetic.transliterate(m.value.lowercase()) }
      .replace(Regex("\\.(?=\\s|$)"), "।")
  }

  private fun devanagariToBengali(text: String): String {
    if (!banglaChosen) return text
    if (text.none { it.code in 0x0900..0x097F }) return text
    val sb = StringBuilder(text.length)
    for (ch in text) {
      val c = ch.code
      sb.append(when {
        c == 0x0964 || c == 0x0965 -> ch // dandas are shared
        c in 0x0900..0x097F -> { val m = c + 0x80; if (Character.isDefined(m)) m.toChar() else ch }
        else -> ch
      })
    }
    return sb.toString()
  }

  /** Blocking: mint token and open the socket. Call off the main thread. */
  fun connect() {
    try {
      tokenProvider?.let { p -> runCatching { p() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { authToken = it } }
      if (authToken.isBlank()) throw IllegalStateException("not_signed_in")
      val body = JSONObject().apply {
        put("language", language)
        put("deviceType", "android")
        if (voiceKeyboard) put("surface", "voice-keyboard")
        put("vocabulary", JSONArray(vocabulary.take(32)))
        put("protocols", JSONArray(listOf("xai", "elevenlabs", "soniox")))
      }
      val conn = (URL(baseUrl.trimEnd('/') + "/stt-token").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; doOutput = true; connectTimeout = 4000; readTimeout = 5000
        setRequestProperty("Authorization", "Bearer $authToken")
        setRequestProperty("Content-Type", "application/json")
      }
      val json = try {
        DataOutputStream(conn.outputStream).use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode !in 200..299) throw IllegalStateException("token_http_${conn.responseCode}")
        JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
      } finally { conn.disconnect() }
      protocol = json.optString("protocol", "elevenlabs")
      // An older gateway must not silently route selected Bangla to another engine.
      val expected = if (banglaChosen) "soniox" else "xai"
      if (voiceKeyboard && protocol != expected) throw IllegalStateException("live_route_unavailable")
      startMessage = json.optString("startMessage")
      val req = Request.Builder().url(json.getString("websocketUrl")).apply {
        val auth = json.optString("authorization")
        if (auth.isNotBlank()) header("Authorization", auth)
      }.build()
      socket = client.newWebSocket(req, listener)
      // A socket that never says it is ready used to buffer audio forever with no words.
      Thread {
        runCatching { Thread.sleep(7000) }
        if (!ready && !failed) fail("socket_timeout")
      }.also { it.isDaemon = true }.start()
    } catch (e: Throwable) {
      fail(e.message ?: "connect")
    }
  }

  fun sendPcm(samples: ShortArray, count: Int) {
    if (failed || count <= 0) return
    samplesSent += count
    val buf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until count) buf.putShort(samples[i])
    val bytes = buf.array()
    synchronized(pending) {
      if (!ready) { pending.add(bytes); return }
      send(bytes)
    }
  }

  private fun send(bytes: ByteArray) {
    val ws = socket ?: return
    if (protocol == "xai" || protocol == "soniox") ws.send(bytes.toByteString())
    else ws.send(JSONObject().apply {
      put("message_type", "input_audio_chunk")
      put("audio_base_64", Base64.encodeToString(bytes, Base64.NO_WRAP))
      put("commit", false)
      put("sample_rate", 16000)
    }.toString())
  }

  /**
   * Blocking: flush, ask for the final words, wait up to [timeoutMs]. Returns the full text.
   * [quietTailMs] > 0 means the speaker went quiet well before stop: when every heard word is
   * already final (no open partial), only that short wait is needed for the goodbye.
   */
  fun finish(timeoutMs: Long = 3000, quietTailMs: Long = 0L): String {
    if (!failed && ready) {
      val ws = socket
      finishing = true
      // Wait for the last words unless the tail was quiet and nothing is still open.
      val wait = if (quietTailMs > 0L && partial.isBlank() && committed.isNotEmpty()) minOf(timeoutMs, quietTailMs) else timeoutMs
      if (protocol == "xai") ws?.send("{\"type\":\"audio.done\"}")
      else if (protocol == "soniox") ws?.send("")
      else ws?.send("{\"message_type\":\"input_audio_chunk\",\"audio_base_64\":\"\",\"commit\":true,\"sample_rate\":16000}")
      runCatching { done.await(wait, TimeUnit.MILLISECONDS) }
    }
    runCatching { socket?.close(1000, null) }
    reportUsage()
    return fullText()
  }

  fun cancel() { failed = true; runCatching { socket?.cancel() }; reportUsage() }

  /** Tells the server how many audio seconds this live session streamed, so cost can be measured. */
  private fun reportUsage() {
    if (reported || samplesSent <= 0) return
    reported = true
    val seconds = samplesSent / 16000.0
    val body = JSONObject().apply {
      put("reportSeconds", Math.round(seconds * 10) / 10.0)
      put("protocol", protocol)
      put("language", language)
      put("ok", !failed && fullText().isNotBlank())
      put("deviceType", "android")
    }.toString()
    Thread {
      runCatching {
        val conn = (URL(baseUrl.trimEnd('/') + "/stt-token").openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"; doOutput = true; connectTimeout = 4000; readTimeout = 5000
          setRequestProperty("Authorization", "Bearer $authToken")
          setRequestProperty("Content-Type", "application/json")
        }
        try { DataOutputStream(conn.outputStream).use { it.write(body.toByteArray()) }; conn.responseCode } finally { conn.disconnect() }
      }
    }.also { it.isDaemon = true }.start()
  }

  private fun fail(reason: String) {
    if (failed) return
    failed = true
    done.countDown()
    runCatching { socket?.cancel() }
    onFailed(reason)
  }

  private fun markReady() {
    // Flush the words spoken while the socket was opening. Holding the lock while
    // sending keeps new live audio strictly behind the backlog, so nothing is
    // reordered. The backlog goes out in ~0.4 s chunks: one huge burst (or many
    // tiny ones) is what some engines drop, which lost the first words.
    synchronized(pending) {
      if (ready) return
      val chunk = java.io.ByteArrayOutputStream()
      for (b in pending) {
        chunk.write(b)
        if (chunk.size() >= FLUSH_CHUNK_BYTES) { send(chunk.toByteArray()); chunk.reset() }
      }
      if (chunk.size() > 0) send(chunk.toByteArray())
      pending.clear()
      readyAt = android.os.SystemClock.elapsedRealtime()
      ready = true
    }
    runCatching { onReady() }
  }

  private fun commit(text: String) {
    val t = text.trim()
    fun norm(v: String) = v.lowercase().replace(Regex("[\\p{P}\\s]+"), "")
    // Only an immediate repeat of the same segment is a duplicate event. A phrase
    // the person really says twice (or that ends like an earlier one) is kept.
    val now = System.currentTimeMillis()
    if (t.isEmpty() || (!voiceKeyboard && norm(t) == norm(lastCommit) && now - lastCommitAt < 1500)) { partial = ""; return }
    lastCommit = t
    lastCommitAt = now
    if (committed.isNotEmpty()) committed.append(' ')
    committed.append(t)
    partial = ""
    runCatching { onCommitted(toBengaliScript(t)) }
  }

  private val listener = object : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
      // Soniox takes audio right after its start message. ElevenLabs is ready on
      // "session_started" (audio sent before it was silently dropped, losing the
      // first words), with a short fallback in case that event never arrives.
      // xAI says transcript.created first.
      if (protocol == "soniox") {
        if (startMessage.isBlank() || !webSocket.send(startMessage)) { fail("no_start"); return }
        markReady()
      } else if (protocol != "xai") {
        Thread {
          runCatching { Thread.sleep(1800) }
          if (!ready && !failed) markReady()
        }.also { it.isDaemon = true }.start()
      }
    }
    override fun onMessage(webSocket: WebSocket, text: String) {
      val j = runCatching { JSONObject(text) }.getOrNull() ?: return
      if (protocol == "soniox") { onSoniox(j); return }
      if (protocol == "xai") {
        when (j.optString("type")) {
          "transcript.created" -> markReady()
          "transcript.partial" -> {
            if (voiceKeyboard) {
              val update = keyboardTranscriptGate.accept(j.optString("text"), j.optBoolean("is_final"), j.optBoolean("speech_final"),
                j.optDouble("start").takeIf { it.isFinite() }, j.optDouble("duration").takeIf { it.isFinite() })
              partial = update.partial
              update.committed?.let { commit(it) }
            } else if (j.optBoolean("is_final")) commit(j.optString("text")) else partial = j.optString("text")
            onText(fullText())
          }
          "transcript.done" -> { if (partial.isNotBlank()) commit(partial); onText(fullText()); done.countDown() }
          "error" -> if (!ready) fail(j.optString("message", "error"))
        }
      } else {
        when (val type = j.optString("message_type")) {
          "session_started" -> markReady()
          "warning" -> Unit // informational (for example a slow chunk); never fatal
          "partial_transcript" -> { partial = j.optString("text"); onText(fullText()) }
          "committed_transcript", "committed_transcript_with_timestamps" -> {
            // Both events can arrive for one segment; keep the first only.
            if (type == "committed_transcript") sawPlainCommit = true
            if (type == "committed_transcript_with_timestamps" && sawPlainCommit) { if (finishing) done.countDown(); return }
            commit(j.optString("text")); onText(fullText())
            if (finishing) done.countDown()
          }
          else -> if (type.contains("error", ignoreCase = true) && !ready) fail(type + ":" + j.optString("error", j.optString("message")))
        }
      }
    }
    private fun onSoniox(j: JSONObject) {
      if (j.has("error_code")) {
        // Nothing heard yet (for example the account is out of credit): fail so the caller retries in batch.
        if (fullText().isBlank()) fail("soniox_" + j.optString("error_type", j.optString("error_code"))) else done.countDown()
        return
      }
      val tokens = j.optJSONArray("tokens")
      var endpoint = false
      val live = StringBuilder()
      if (tokens != null) for (i in 0 until tokens.length()) {
        val t = tokens.optJSONObject(i) ?: continue
        val text = t.optString("text")
        if (text == "<end>") { endpoint = true; continue }
        if (text == "<fin>") continue
        if (t.optBoolean("is_final")) sxFinal.append(text) else live.append(text)
      }
      if (endpoint || j.optBoolean("finished")) {
        commit(sxFinal.toString()); sxFinal.setLength(0)
        partial = live.toString()
      } else {
        partial = sxFinal.toString() + live.toString()
      }
      onText(fullText())
      if (j.optBoolean("finished")) { if (partial.isNotBlank()) commit(partial); onText(fullText()); done.countDown() }
    }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      if (!ready) fail(t.message ?: "socket") else done.countDown()
    }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.countDown() }
  }

  companion object {
    /** ~0.4 s of 16 kHz mono PCM16. */
    private const val FLUSH_CHUNK_BYTES = 12800
    private val client by lazy { OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build() }
  }
}
