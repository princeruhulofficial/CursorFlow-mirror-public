package com.voicelink.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.util.concurrent.Executors

/**
 * Phone as PC speaker and PC notifications on the phone. Separate from the
 * keyboard, transfers and dictation: its own module, its own service, and every
 * call is guarded so a failure here never reaches the rest of the app.
 */
object PcSpeaker {
  private var track: AudioTrack? = null
  private var rate = 0
  private var channels = 0
  private val writer = Executors.newSingleThreadExecutor()
  @Volatile private var queued = 0

  @Synchronized fun start(sampleRate: Int, channelCount: Int): Boolean {
    if (track != null && rate == sampleRate && channels == channelCount) return true
    stop()
    val mask = if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
    val min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
    if (min <= 0) return false
    // About 120 ms of room keeps latency low while riding out Wi-Fi jitter.
    val bytes = maxOf(min * 2, sampleRate * channelCount * 2 * 120 / 1000)
    val next = AudioTrack.Builder()
      .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
      .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(mask).build())
      .setBufferSizeInBytes(bytes)
      .setTransferMode(AudioTrack.MODE_STREAM)
      .apply { if (Build.VERSION.SDK_INT >= 26) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
      .build()
    next.play()
    track = next
    rate = sampleRate
    channels = channelCount
    return true
  }

  /** PCM 16-bit little endian. Drops audio when too much is waiting, so delay never builds up. */
  fun write(base64: String): Int {
    val t = track ?: return -1
    if (queued > 3) return 0
    val data = try { Base64.decode(base64, Base64.DEFAULT) } catch (_: Exception) { return -1 }
    queued += 1
    writer.execute {
      try { t.write(data, 0, data.size) } catch (_: Exception) { } finally { queued -= 1 }
    }
    return data.size
  }

  @Synchronized fun stop() {
    val t = track ?: return
    track = null
    try { t.pause(); t.flush(); t.release() } catch (_: Exception) { }
  }

  fun playing() = track != null
}

class PcLinkService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      PcSpeaker.stop()
      stopSelfSafely()
      return START_NOT_STICKY
    }
    val speaker = intent?.getBooleanExtra(EXTRA_SPEAKER, false) == true
    try {
      val notification = buildNotification(this, speaker)
      if (Build.VERSION.SDK_INT >= 29) {
        val type = if (speaker) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(ID, notification, type)
      } else startForeground(ID, notification)
    } catch (_: Exception) {
      stopSelfSafely()
    }
    return START_NOT_STICKY
  }

  // Android 15 limits data sync services; stop cleanly when the system says so.
  override fun onTimeout(startId: Int) { stopSelfSafely() }

  override fun onDestroy() { PcSpeaker.stop(); super.onDestroy() }

  private fun stopSelfSafely() {
    try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
    stopSelf()
  }

  companion object {
    const val ID = 4107
    const val CHANNEL = "cursorflow.pclink"
    const val ACTION_STOP = "com.voicelink.mobile.PCLINK_STOP"
    const val EXTRA_SPEAKER = "speaker"

    fun buildNotification(context: Context, speaker: Boolean): Notification {
      val manager = context.getSystemService(NotificationManager::class.java)
      if (manager?.getNotificationChannel(CHANNEL) == null) {
        manager?.createNotificationChannel(NotificationChannel(CHANNEL, "PC link", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
      }
      val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { PendingIntent.getActivity(context, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
      val stop = PendingIntent.getService(context, 2, Intent(context, PcLinkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
      return NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(context.applicationInfo.icon)
        .setContentTitle(if (speaker) "Playing your PC sound" else "Showing your PC notifications")
        .setContentText("Cursor Flow is connected to your PC.")
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .apply { if (open != null) setContentIntent(open) }
        .addAction(0, "Stop", stop)
        .build()
    }
  }
}

object PcNotices {
  private const val CHANNEL = "cursorflow.pcnotices"

  fun show(context: Context, id: Int, app: String, title: String, text: String): Boolean {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    if (manager.getNotificationChannel(CHANNEL) == null) {
      manager.createNotificationChannel(NotificationChannel(CHANNEL, "PC notifications", NotificationManager.IMPORTANCE_DEFAULT))
    }
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { PendingIntent.getActivity(context, 3, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
    val n = NotificationCompat.Builder(context, CHANNEL)
      .setSmallIcon(context.applicationInfo.icon)
      .setSubText(app.take(60))
      .setContentTitle(title.take(120))
      .setContentText(text.take(400))
      .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1000)))
      .setAutoCancel(true)
      .setGroup("pc:" + app.take(40))
      .apply { if (open != null) setContentIntent(open) }
      .build()
    manager.notify(id, n)
    return true
  }
}

class PcLinkModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
  override fun getName(): String = "PcLink"

  private fun service(speaker: Boolean) {
    val intent = Intent(reactContext, PcLinkService::class.java).putExtra(PcLinkService.EXTRA_SPEAKER, speaker)
    ContextCompat.startForegroundService(reactContext, intent)
  }

  @ReactMethod fun speakerStart(sampleRate: Int, channels: Int, promise: Promise) {
    try {
      if (sampleRate !in 8000..48000 || channels !in 1..2) { promise.resolve(false); return }
      val ok = PcSpeaker.start(sampleRate, channels)
      if (ok) runCatching { service(true) }
      promise.resolve(ok)
    } catch (e: Exception) { promise.resolve(false) }
  }

  @ReactMethod fun speakerWrite(base64: String, promise: Promise) {
    try { promise.resolve(PcSpeaker.write(base64)) } catch (_: Exception) { promise.resolve(-1) }
  }

  @ReactMethod fun speakerStop(promise: Promise) {
    try {
      PcSpeaker.stop()
      runCatching { reactContext.startService(Intent(reactContext, PcLinkService::class.java).setAction(PcLinkService.ACTION_STOP)) }
      promise.resolve(true)
    } catch (_: Exception) { promise.resolve(false) }
  }

  @ReactMethod fun noticesKeepAlive(on: Boolean, promise: Promise) {
    try {
      if (on && !PcSpeaker.playing()) service(false)
      else if (!on && !PcSpeaker.playing()) runCatching { reactContext.startService(Intent(reactContext, PcLinkService::class.java).setAction(PcLinkService.ACTION_STOP)) }
      promise.resolve(true)
    } catch (_: Exception) { promise.resolve(false) }
  }

  @ReactMethod fun notify(id: Int, app: String, title: String, text: String, promise: Promise) {
    try { promise.resolve(PcNotices.show(reactContext, id, app, title, text)) } catch (_: Exception) { promise.resolve(false) }
  }
}
