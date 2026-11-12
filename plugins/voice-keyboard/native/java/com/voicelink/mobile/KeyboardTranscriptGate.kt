package com.voicelink.mobile

/** A locked chunk may be revised/replayed at the utterance boundary. Only that boundary inserts. */
class KeyboardTranscriptGate {
  data class Update(val partial: String, val committed: String? = null)
  private val finalizedAudio = HashSet<Pair<Double, Double>>()

  fun accept(text: String, isFinal: Boolean, speechFinal: Boolean, start: Double?, duration: Double?): Update {
    if (!isFinal || !speechFinal) return Update(text)
    // Identify audio, not words or wall-clock time: genuinely repeated phrases must survive.
    if (start != null && duration != null && !finalizedAudio.add(start to duration)) return Update("")
    return Update("", text.trim().takeIf { it.isNotEmpty() })
  }
}
