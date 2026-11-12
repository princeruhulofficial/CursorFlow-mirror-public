package com.voicelink.mobile

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * The IME is an explicit user-selected input method, not an AccessibilityService.
 * It must still refuse credential-like fields and avoid extracting target content.
 */
object VoiceInputTargetPolicy {
  fun allows(editorInfo: EditorInfo?): Boolean {
    if (editorInfo == null) return false
    val inputType = editorInfo.inputType
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    val isTextPassword = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
      variation in setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
      )
    val isNumberPassword = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
      variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    if (isTextPassword || isNumberPassword) return false

    // Every other field gets voice: Gmail, notification replies, webviews, search bars.
    // No-extract and no-learning flags only limit what we store, not whether we type.
    return true
  }

  fun denialReason(editorInfo: EditorInfo?): String {
    if (editorInfo == null) return "No focused text field"
    val inputType = editorInfo.inputType
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
      variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
      variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
      variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    ) return "Voice input is disabled for password fields"
    return "Voice input is off here"
  }
}
