package com.office.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.office.app.data.Settings
import com.office.app.ui.AppNavHost
import com.office.app.ui.theme.OfficeTheme

class MainActivity : AppCompatActivity() {

    /** A file handed to us by another app ("Open with" / share), consumed by the nav host. */
    private val pendingUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingUri.value = extractUri(intent)
        val container = (application as OfficeApp).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
            OfficeTheme(themeMode = settings.theme, dynamicColor = settings.dynamicColor) {
                AppNavHost(
                    container = container,
                    incomingUri = pendingUri.value,
                    onIncomingConsumed = { pendingUri.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractUri(intent)?.let { pendingUri.value = it }
    }

    private fun extractUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        else -> null
    }
}
