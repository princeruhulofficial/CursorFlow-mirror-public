package com.voicelink.mobile

/** Replacement is never an insertion fallback. Works only on the exact owned cursor suffix. */
object DictationSpan {
  fun replace(
    before: String, owned: String, replacement: String, ageMs: Long, sameSession: Boolean,
    delete: (Int) -> Boolean, commit: (String) -> Boolean,
  ): Boolean {
    if (!sameSession || ageMs !in 0..1_200 || owned.isEmpty() || !before.endsWith(owned)) return false
    if (!delete(owned.length)) return false
    return commit(replacement)
  }
}
