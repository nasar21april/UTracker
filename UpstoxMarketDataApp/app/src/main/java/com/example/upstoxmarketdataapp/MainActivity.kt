package com.example.upstoxmarketdataapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.upstoxmarketdataapp.data.UpstoxService
import com.example.upstoxmarketdataapp.theme.UpstoxMarketDataAppTheme

class MainActivity : ComponentActivity() {
  private lateinit var upstoxService: UpstoxService

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    upstoxService = UpstoxService(applicationContext)

    // Schedule Daily GitHub Sync
    val syncRequest = androidx.work.PeriodicWorkRequestBuilder<com.example.upstoxmarketdataapp.data.CloudSyncWorker>(24, java.util.concurrent.TimeUnit.HOURS).build()
    androidx.work.WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork("DailyGitHubSync", androidx.work.ExistingPeriodicWorkPolicy.KEEP, syncRequest)

    
    handleIntent(intent)
    pinShortcutOnFirstInstall()

    enableEdgeToEdge()
    setContent {
      val themeMode by upstoxService.themeMode.collectAsState()
      val isDark = when (themeMode) {
          "light" -> false
          "dark" -> true
          else -> isSystemInDarkTheme()
      }

      UpstoxMarketDataAppTheme(darkTheme = isDark, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          MainNavigation(upstoxService)
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
      super.onNewIntent(intent)
      setIntent(intent)
      handleIntent(intent)
  }

  private fun handleIntent(intent: Intent) {
      if (Intent.ACTION_VIEW == intent.action) {
          val uri = intent.data
          if (uri != null && uri.scheme == "http" && uri.host == "localhost" && uri.path?.startsWith("/callback") == true) {
              val code = uri.getQueryParameter("code")
              if (!code.isNullOrEmpty()) {
                  upstoxService.setDeepLinkedAuthCode(code)
              }
          }
      }
  }

  /** Pins a home screen shortcut the very first time the app launches after install. */
  private fun pinShortcutOnFirstInstall() {
      val prefs = getSharedPreferences("artemis_prefs", Context.MODE_PRIVATE)
      if (prefs.getBoolean("shortcut_pinned", false)) return   // already done

      if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) return

      val shortcutIntent = Intent(this, MainActivity::class.java).apply {
          action = Intent.ACTION_MAIN
      }
      val shortcut = ShortcutInfoCompat.Builder(this, "upstox_home_shortcut")
          .setShortLabel("UTracker")
          .setLongLabel("UTracker")
          .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
          .setIntent(shortcutIntent)
          .build()

      ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
      prefs.edit().putBoolean("shortcut_pinned", true).apply()
  }
}
