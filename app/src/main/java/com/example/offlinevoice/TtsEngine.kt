package com.example.offlinevoice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class TtsEngine(context: Context) : TextToSpeech.OnInitListener {
  private val ready = AtomicBoolean(false)
  private val tts = TextToSpeech(context, this)

  override fun onInit(status: Int) {
    if (status == TextToSpeech.SUCCESS) {
      tts.language = Locale.GERMAN
      ready.set(true)
    }
  }

  fun speak(text: String) {
    if (!ready.get()) return
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt1")
  }

  fun shutdown() {
    try {
      tts.stop()
    } finally {
      tts.shutdown()
    }
  }
}
