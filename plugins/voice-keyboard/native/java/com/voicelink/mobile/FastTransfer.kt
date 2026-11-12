package com.voicelink.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Android side of [FastTransferCore]: content URIs, the private inbox file and image previews. */
object FastTransfer {
  private const val PROGRESS_EVERY_MS = 60L
  @Volatile private var cached: Pair<String, OkHttpClient>? = null

  private fun client(certificateDerBase64: String, window: Int): OkHttpClient {
    val key = "$window|$certificateDerBase64"
    cached?.let { if (it.first == key) return it.second }
    val der = Base64.decode(certificateDerBase64, Base64.DEFAULT)
    if (der.size < 100) throw IOException("no_trust")
    return FastTransferCore.pinnedClient(der, window).also { cached = key to it }
  }

  private fun throttled(emit: (Long, Long) -> Unit): (Long, Long) -> Unit {
    var last = 0L
    var best = 0L
    val lock = Any()
    return { done, total ->
      synchronized(lock) {
        if (done > best) {
          best = done
          val now = System.currentTimeMillis()
          if (done >= total || now - last >= PROGRESS_EVERY_MS) { last = now; emit(done, total) }
        }
      }
    }
  }

  fun upload(
    context: Context, uri: String, size: Long, relayUrl: String, sessionId: String, secret: String, jobId: String,
    certificateDerBase64: String, chunkBytes: Int, window: Int, emit: (Long, Long) -> Unit,
  ): String {
    val input = context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("open_failed")
    return input.use {
      FastTransferCore.upload(client(certificateDerBase64, window), it, size, relayUrl, sessionId, secret, jobId, chunkBytes, window, throttled(emit))
    }
  }

  fun download(
    context: Context, fileId: String, size: Long, relayUrl: String, sessionId: String, secret: String,
    certificateDerBase64: String, chunkBytes: Int, window: Int, emit: (Long, Long) -> Unit,
  ): Long {
    val file = PhoneInbox.partFile(context, fileId)
    RandomAccessFile(file, "rw").use { raf ->
      raf.setLength(0)
      try {
        return FastTransferCore.download(client(certificateDerBase64, window), raf.channel, size, relayUrl, sessionId, secret, fileId, chunkBytes, window, throttled(emit))
      } catch (error: Throwable) {
        raf.setLength(0)
        throw error
      }
    }
  }

  fun sha256(context: Context, uri: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("open_failed")
    input.use { stream ->
      val buffer = ByteArray(256 * 1024)
      while (true) { val n = stream.read(buffer); if (n <= 0) break; digest.update(buffer, 0, n) }
    }
    return FastTransferCore.hex(digest.digest())
  }

  fun thumbnail(context: Context, uri: String, maxEdge: Int): String? {
    val parsed = Uri.parse(uri)
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    val decoded = resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
    val rotation = runCatching {
      resolver.openInputStream(parsed)?.use { stream ->
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
          ExifInterface.ORIENTATION_ROTATE_90 -> 90f
          ExifInterface.ORIENTATION_ROTATE_180 -> 180f
          ExifInterface.ORIENTATION_ROTATE_270 -> 270f
          else -> 0f
        }
      } ?: 0f
    }.getOrDefault(0f)
    val scale = minOf(1f, maxEdge.toFloat() / maxOf(decoded.width, decoded.height))
    val matrix = Matrix().apply { postScale(scale, scale); if (rotation != 0f) postRotate(rotation) }
    val shaped = if (scale < 1f || rotation != 0f) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true) else decoded
    try {
      for (quality in intArrayOf(60, 45, 30)) {
        val out = ByteArrayOutputStream()
        shaped.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        if (encoded.length <= 60 * 1024) return "data:image/jpeg;base64,$encoded"
      }
      return null
    } finally {
      if (shaped !== decoded) shaped.recycle()
      decoded.recycle()
    }
  }
}
