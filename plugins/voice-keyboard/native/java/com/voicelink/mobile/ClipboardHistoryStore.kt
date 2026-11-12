package com.voicelink.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-only IME clipboard snippets. Keystore encryption, excluded from device backups. */
object ClipboardHistoryStore {
  private const val ALIAS = "cursorflow.ime.clipboard.v1"
  private fun file(context: Context) = File(context.noBackupFilesDir, "ime-clipboard.enc")
  private fun key(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
      init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
  }
  @Synchronized fun read(context: Context): List<String> = runCatching {
    val bytes = file(context).readBytes()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
    val rows = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    (0 until rows.length()).map { rows.getString(it) }.take(20)
  }.getOrDefault(emptyList())
  @Synchronized fun remember(context: Context, text: String) {
    if (text.isBlank() || text.length > 4_000) return
    runCatching {
      val rows = JSONArray((listOf(text) + read(context).filter { it != text }).take(20))
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key())
      val atomic = android.util.AtomicFile(file(context))
      val stream = atomic.startWrite()
      try { stream.write(cipher.iv + cipher.doFinal(rows.toString().toByteArray(Charsets.UTF_8))); atomic.finishWrite(stream) }
      catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
  }
  @Synchronized fun clear(context: Context) { file(context).delete() }
}
