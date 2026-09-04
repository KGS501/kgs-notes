package com.kgs.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.kgs.notes.design.KgsNotesTheme

class MainActivity : ComponentActivity() {
    private val notesViewModel: NotesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KgsNotesTheme {
                KgsNotesApp(notesViewModel)
            }
        }
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
