package com.example.offlinevoice

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal exported entry point that ignores non-launcher intents before
 * forwarding into the internal MainActivity.
 */
class LauncherActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    if (!isLauncherIntent(intent)) {
      finish()
      return
    }

    val launchIntent = Intent(this, MainActivity::class.java).apply {
      action = Intent.ACTION_MAIN
      addCategory(Intent.CATEGORY_LAUNCHER)
      flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

    startActivity(launchIntent)
    finish()
  }

  private fun isLauncherIntent(intent: Intent?): Boolean {
    if (intent == null) {
      return false
    }
    if (intent.action != Intent.ACTION_MAIN) {
      return false
    }
    val categories = intent.categories ?: emptySet()
    if (!categories.contains(Intent.CATEGORY_LAUNCHER)) {
      return false
    }
    if (intent.data != null) {
      return false
    }
    return true
  }
}
