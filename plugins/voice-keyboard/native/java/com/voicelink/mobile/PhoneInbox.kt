package com.voicelink.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Files sent from the paired PC. Kept private until verified, then saved via MediaStore. */
object PhoneInbox {
  private const val CHANNEL = "cursorflow_received"
  private val EXECUTABLE = setOf("apk", "exe", "msi", "bat", "cmd", "ps1", "sh", "jar", "scr", "js", "vbs", "com", "dll", "xapk", "apks")

  private fun dir(context: Context) = File(context.filesDir, "inbox").apply { mkdirs() }
  private fun part(context: Context, id: String): File {
    require(Regex("^[A-Za-z0-9_-]{4,80}$").matches(id)) { "bad id" }
    return File(dir(context), "$id.part")
  }

  fun partFile(context: Context, id: String): File = part(context, id)

  fun cleanName(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f<>:\"/\\\\|?*]"), "_").trim()
    return (if (base.isBlank() || base == "." || base == "..") "file" else base).takeLast(150)
  }

  fun isExecutable(name: String) = name.substringAfterLast('.', "").lowercase() in EXECUTABLE

  fun partialSize(context: Context, id: String): Long = part(context, id).let { if (it.exists()) it.length() else 0L }

  fun append(context: Context, id: String, offset: Long, base64: String): Long {
    val file = part(context, id)
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    RandomAccessFile(file, "rw").use { raf ->
      if (raf.length() != offset) raf.setLength(offset.coerceAtMost(raf.length()))
      if (raf.length() != offset) throw IllegalStateException("gap")
      raf.seek(offset); raf.write(bytes)
      return raf.length()
    }
  }

  fun discard(context: Context, id: String) { part(context, id).delete() }

  fun finish(context: Context, id: String, rawName: String, mime: String, sha256: String): Uri {
    val file = part(context, id)
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val buf = ByteArray(256 * 1024); while (true) { val n = input.read(buf); if (n <= 0) break; digest.update(buf, 0, n) } }
    val hex = digest.digest().joinToString("") { "%02x".format(it) }
    if (!hex.equals(sha256, ignoreCase = true)) { file.delete(); throw SecurityException("hash_mismatch") }
    val name = cleanName(rawName)
    val (collection, folder) = when {
      mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_PICTURES}/Cursor Flow"
      mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_MOVIES}/Cursor Flow"
      else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_DOWNLOADS}/Cursor Flow"
    }
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, name)
      put(MediaStore.MediaColumns.MIME_TYPE, mime)
      put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
      put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(collection, values) ?: throw IllegalStateException("save_failed")
    try {
      resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } } ?: throw IllegalStateException("save_failed")
      resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } catch (error: Exception) { resolver.delete(uri, null, null); throw error }
    file.delete()
    return uri
  }

  fun viewIntent(uri: Uri, mime: String) = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }

  fun shareIntent(uri: Uri, mime: String) = Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, null).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

  fun delete(context: Context, uri: Uri): Boolean = try { context.contentResolver.delete(uri, null, null) > 0 } catch (_: Exception) { false }

  fun notifyReceived(context: Context, uri: Uri, mime: String, name: String) {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL) == null) {
      manager.createNotificationChannel(NotificationChannel(CHANNEL, "Received from your PC", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }
    val tap = if (isExecutable(name)) context.packageManager.getLaunchIntentForPackage(context.packageName) else viewIntent(uri, mime)
    val pending = tap?.let { PendingIntent.getActivity(context, uri.hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
    val notification = NotificationCompat.Builder(context, CHANNEL)
      .setSmallIcon(context.applicationInfo.icon)
      .setContentTitle("Received from your PC")
      .setContentText(name)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setSilent(true)
      .setAutoCancel(true)
      .apply { if (pending != null) setContentIntent(pending) }
      .build()
    manager.notify(uri.hashCode(), notification)
  }
}
