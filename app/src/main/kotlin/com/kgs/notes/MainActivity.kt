package com.kgs.notes

import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.kgs.notes.editor.RichEditorStartup

// The platform's default scrims for three-button navigation.
private val LightNavigationScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DarkNavigationScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

class MainActivity : ComponentActivity() {
    private val notesViewModel: NotesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openExternalIntent(intent)
        RichEditorStartup.prepare(applicationContext)
        setContent {
            val state by notesViewModel.state.collectAsState()
            val dark = state.darkMode.isDark()
            DisposableEffect(dark) {
                // Bar icons follow the app's dark mode, which may differ from the system's.
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LightNavigationScrim, DarkNavigationScrim) { dark },
                )
                onDispose {}
            }
            KgsNotesApp(notesViewModel, onExternalPreviewClosed = ::finish)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openExternalIntent(intent)
    }

    private fun openExternalIntent(intent: Intent) {
        ExternalFileRequest.from(intent)?.let(notesViewModel::openExternalFile)
    }

    override fun onStart() {
        super.onStart()
        notesViewModel.startForegroundSourceRefresh()
    }

    override fun onStop() {
        notesViewModel.stopForegroundSourceRefresh()
        notesViewModel.flushPendingContent()
        super.onStop()
    }
}
