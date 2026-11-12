package com.voicelink.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Split pill [waveform | language]. Waveform side = voice panel, language side = typing panel.
 * In the voice panel the language side shows Auto or the detected code; in typing it shows Aa.
 */
class FlowSplitPill(
  context: Context,
  night: Boolean,
  voiceActive: Boolean,
  private val onVoice: () -> Unit,
  private val onTyping: () -> Unit,
  private val onLongLanguage: (() -> Unit)? = null,
) : LinearLayout(context) {
  private val seg = if (night) 0xFF2E2F33.toInt() else 0xFFE0E2E6.toInt()
  private val on = if (night) 0xFF4A4B50.toInt() else 0xFFFFFFFF.toInt()
  private val ink = if (night) 0xFFF2F2F5.toInt() else 0xFF1C1C1E.toInt()
  private val mut = if (night) 0xFF9A9AA0.toInt() else 0xFF7A7C82.toInt()
  private val wave = WaveGlyph(context)
  private val waveSeg = LinearLayout(context)
  private val langText = TextView(context)

  init {
    orientation = HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    background = GradientDrawable().apply { setColor(seg); cornerRadius = dp(18).toFloat() }
    setPadding(dp(3), dp(3), dp(3), dp(3))
    waveSeg.gravity = Gravity.CENTER
    waveSeg.addView(wave, LayoutParams(dp(18), dp(16)))
    waveSeg.contentDescription = "Voice"
    waveSeg.setOnClickListener { onVoice() }
    addView(waveSeg, LayoutParams(dp(48), dp(30)))
    langText.apply {
      textSize = 12.5f
      gravity = Gravity.CENTER
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      setPadding(dp(10), 0, dp(10), 0)
      minWidth = dp(48)
      contentDescription = "Typing keyboard"
      setOnClickListener { onTyping() }
      onLongLanguage?.let { cb -> setOnLongClickListener { cb(); true } }
    }
    addView(langText, LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)))
    setVoiceActive(voiceActive)
  }

  fun setVoiceActive(active: Boolean) {
    waveSeg.background = if (active) chip() else null
    langText.background = if (active) null else chip()
    wave.color = if (active) ink else mut
    langText.setTextColor(if (active) mut else ink)
    if (!active) langText.text = "Aa"
    wave.invalidate()
  }

  fun setLanguageLabel(label: String) { langText.text = label }

  private fun chip() = GradientDrawable().apply { setColor(on); cornerRadius = dp(15).toFloat() }
  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

  private class WaveGlyph(context: Context) : View(context) {
    var color: Int = 0xFF000000.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    override fun onDraw(canvas: Canvas) {
      p.color = color
      p.strokeWidth = 2.2f * resources.displayMetrics.density
      val hs = floatArrayOf(0.4f, 0.75f, 1f, 0.75f, 0.4f)
      val w = width.toFloat(); val h = height.toFloat()
      hs.forEachIndexed { i, k ->
        val x = w * (0.12f + i * 0.19f); val bh = h * k * 0.9f
        canvas.drawLine(x, (h - bh) / 2f, x, (h + bh) / 2f, p)
      }
    }
  }
}
