package com.voicelink.mobile

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Binary file chunks over the paired Wi-Fi relay (protocol "bin-v2", matches desktop/fast-transfer.cjs).
 *
 * Each chunk is read once from storage, sealed with AES-256-GCM and posted as the raw request
 * body, several at a time. Keys come from the pairing secret (HKDF-SHA256, one per direction) and
 * every chunk's AAD binds the session, file, offset and length. The whole-file SHA-256 is computed
 * while reading and checked by the receiver before the file is saved.
 *
 * Plain JVM code (no Android imports) so it can be checked against the desktop on a JVM.
 */
object FastTransferCore {
  const val VERSION: Byte = 2
  const val NONCE_BYTES = 12
  const val TAG_BYTES = 16
  const val OVERHEAD = 1 + NONCE_BYTES + TAG_BYTES
  const val MAX_CHUNK_BYTES = 1024 * 1024
  const val MAX_WINDOW = 8
  private const val RETRIES = 3
  private val random = SecureRandom()
  private val octets = "application/octet-stream".toMediaType()

  class TransferError(val code: String, val retryable: Boolean) : IOException(code)

  fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(salt, "HmacSHA256"))
    val prk = mac.doFinal(ikm)
    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    val out = ByteArrayOutputStream()
    var block = ByteArray(0)
    var counter = 1
    while (out.size() < length) {
      mac.update(block); mac.update(info); mac.update(counter.toByte())
      block = mac.doFinal()
      out.write(block)
      counter += 1
    }
    return out.toByteArray().copyOf(length)
  }

  fun key(secret: String, sessionId: String, direction: String): ByteArray {
    require(secret.isNotEmpty()) { "A paired session secret is required." }
    require(direction == "up" || direction == "down") { "Unknown direction." }
    return hkdf(secret.toByteArray(Charsets.UTF_8), "cursorflow-bin-v2".toByteArray(Charsets.UTF_8), "$direction|$sessionId".toByteArray(Charsets.UTF_8))
  }

  fun aad(kind: String, sessionId: String, id: String, offset: Long, length: Int): ByteArray =
    "cf-bin-v2|$kind|$sessionId|$id|$offset|$length".toByteArray(Charsets.UTF_8)

  fun seal(key: ByteArray, aad: ByteArray, plain: ByteArray, start: Int = 0, length: Int = plain.size): ByteArray {
    val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
    cipher.updateAAD(aad)
    val body = cipher.doFinal(plain, start, length) // ciphertext followed by the tag
    val out = ByteArray(1 + NONCE_BYTES + body.size)
    out[0] = VERSION
    System.arraycopy(nonce, 0, out, 1, NONCE_BYTES)
    System.arraycopy(body, 0, out, 1 + NONCE_BYTES, body.size)
    return out
  }

  fun open(key: ByteArray, aadFor: (Int) -> ByteArray, sealed: ByteArray): ByteArray {
    if (sealed.size < OVERHEAD || sealed[0] != VERSION) throw TransferError("bad_chunk", false)
    val length = sealed.size - OVERHEAD
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, sealed, 1, NONCE_BYTES))
    cipher.updateAAD(aadFor(length))
    try {
      return cipher.doFinal(sealed, 1 + NONCE_BYTES, sealed.size - 1 - NONCE_BYTES)
    } catch (_: AEADBadTagException) {
      throw TransferError("bad_chunk", false)
    }
  }

  fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

  /** Trusts exactly the PC certificate saved at pairing and nothing else. Used only for the PC's LAN address. */
  fun pinnedClient(pinnedDer: ByteArray, window: Int): OkHttpClient {
    val trust = object : X509TrustManager {
      override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw CertificateException("client certificates are not used") }
      override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        if (!MessageDigest.isEqual(leaf.encoded, pinnedDer)) throw CertificateException("not the paired PC")
        leaf.checkValidity()
      }
      override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf<TrustManager>(trust), null)
    return OkHttpClient.Builder()
      .sslSocketFactory(context.socketFactory, trust)
      // The certificate itself is pinned above, so the name in it does not matter (the PC's Wi-Fi address can change).
      .hostnameVerifier { _, session -> runCatching { MessageDigest.isEqual(session.peerCertificates.first().encoded, pinnedDer) }.getOrDefault(false) }
      .connectTimeout(5, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .writeTimeout(20, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .connectionPool(okhttp3.ConnectionPool(window.coerceIn(1, MAX_WINDOW) + 1, 30, TimeUnit.SECONDS))
      .build()
  }

  private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

  private fun jsonError(text: String): String? = Regex("\"error\"\\s*:\\s*\"([^\"]{1,80})\"").find(text)?.groupValues?.get(1)

  private fun <T> withRetries(block: () -> T): T {
    var last: Throwable? = null
    for (attempt in 0..RETRIES) {
      try { return block() } catch (error: TransferError) {
        if (!error.retryable) throw error
        last = error
      } catch (error: IOException) { last = error }
      if (attempt < RETRIES) Thread.sleep(200L * (attempt + 1))
    }
    throw last ?: TransferError("network", true)
  }

  private fun readFully(input: InputStream, buffer: ByteArray): Int {
    var read = 0
    while (read < buffer.size) {
      val n = input.read(buffer, read, buffer.size - read)
      if (n < 0) break
      read += n
    }
    return read
  }

  /**
   * Phone to PC. Streams [size] bytes from [input] in [chunkBytes] pieces with up to [window] in flight.
   * Memory stays at about (window + 1) chunks. Returns the SHA-256 (hex) of exactly what was sent.
   */
  fun upload(
    client: OkHttpClient, input: InputStream, size: Long, relayUrl: String, sessionId: String, secret: String,
    jobId: String, chunkBytes: Int, window: Int, onProgress: (Long, Long) -> Unit,
  ): String {
    val chunk = chunkBytes.coerceIn(16 * 1024, MAX_CHUNK_BYTES)
    val inFlight = window.coerceIn(1, MAX_WINDOW)
    val key = key(secret, sessionId, "up")
    val base = "${relayUrl.trimEnd('/')}/v1/sessions/${enc(sessionId)}/payloads/${enc(jobId)}/bin"
    val digest = MessageDigest.getInstance("SHA-256")
    val pool = Executors.newFixedThreadPool(inFlight)
    val permits = Semaphore(inFlight)
    val failure = AtomicReference<Throwable?>(null)
    val acked = AtomicLong(0)
    val tasks = ArrayList<Future<*>>()
    try {
      var offset = 0L
      while (offset < size && failure.get() == null) {
        val length = minOf(chunk.toLong(), size - offset).toInt()
        val buffer = ByteArray(length)
        if (readFully(input, buffer) != length) throw TransferError("file_changed", false)
        digest.update(buffer)
        permits.acquire()
        val at = offset
        tasks += pool.submit {
          try {
            if (failure.get() == null) {
              val sealed = seal(key, aad("up", sessionId, jobId, at, length), buffer)
              withRetries { postChunk(client, "$base?offset=$at", sealed) }
              onProgress(acked.addAndGet(length.toLong()), size)
            }
          } catch (error: Throwable) {
            failure.compareAndSet(null, error)
          } finally { permits.release() }
        }
        offset += length
      }
      for (task in tasks) try { task.get() } catch (_: ExecutionException) { /* recorded in failure */ }
      failure.get()?.let { throw it }
      return hex(digest.digest())
    } finally {
      pool.shutdownNow()
    }
  }

  private fun postChunk(client: OkHttpClient, url: String, sealed: ByteArray) {
    val request = Request.Builder().url(url).post(sealed.toRequestBody(octets)).build()
    client.newCall(request).execute().use { response ->
      val text = response.body?.string() ?: ""
      if (response.isSuccessful && Regex("\"ok\"\\s*:\\s*true").containsMatchIn(text)) return
      val code = response.code
      throw TransferError(jsonError(text) ?: "http_$code", code >= 500 || code == 408 || code == 429)
    }
  }

  /**
   * PC to phone. Pulls [size] bytes of [fileId] with up to [window] requests in flight and writes each
   * piece at its offset in [out]. The caller verifies the SHA-256 before saving the file.
   */
  fun download(
    client: OkHttpClient, out: FileChannel, size: Long, relayUrl: String, sessionId: String, secret: String,
    fileId: String, chunkBytes: Int, window: Int, onProgress: (Long, Long) -> Unit,
  ): Long {
    val chunk = chunkBytes.coerceIn(16 * 1024, MAX_CHUNK_BYTES)
    val inFlight = window.coerceIn(1, MAX_WINDOW)
    val up = key(secret, sessionId, "up")
    val down = key(secret, sessionId, "down")
    val base = "${relayUrl.trimEnd('/')}/v1/sessions/${enc(sessionId)}/outbox/${enc(fileId)}/bin"
    val pool = Executors.newFixedThreadPool(inFlight)
    val failure = AtomicReference<Throwable?>(null)
    val received = AtomicLong(0)
    val tasks = ArrayList<Future<*>>()
    try {
      var offset = 0L
      while (offset < size) {
        val length = minOf(chunk.toLong(), size - offset).toInt()
        val at = offset
        tasks += pool.submit {
          try {
            if (failure.get() == null) {
              val bytes = withRetries {
                val sealedRequest = seal(up, aad("pull", sessionId, fileId, at, length), ByteArray(0))
                val request = Request.Builder().url("$base?offset=$at&length=$length").post(sealedRequest.toRequestBody(octets)).build()
                client.newCall(request).execute().use { response ->
                  val body = response.body?.bytes() ?: ByteArray(0)
                  if (!response.isSuccessful) {
                    val code = response.code
                    throw TransferError(jsonError(String(body, Charsets.UTF_8)) ?: "http_$code", code >= 500 || code == 408 || code == 429)
                  }
                  open(down, { n -> aad("down", sessionId, fileId, at, n) }, body)
                }
              }
              if (bytes.size != length) throw TransferError("short_chunk", false)
              var written = 0
              val buffer = ByteBuffer.wrap(bytes)
              while (buffer.hasRemaining()) written += out.write(buffer, at + written)
              onProgress(received.addAndGet(length.toLong()), size)
            }
          } catch (error: Throwable) {
            failure.compareAndSet(null, error)
          }
        }
        offset += length
      }
      for (task in tasks) try { task.get() } catch (_: ExecutionException) { /* recorded in failure */ }
      failure.get()?.let { throw it }
      return size
    } finally {
      pool.shutdownNow()
    }
  }
}
