package com.kgs.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.kgs.notes.design.KgsNotesTheme
import com.kgs.notes.editor.warmUpKgsMarkdownEditor

class MainActivity : ComponentActivity() {
    private val notesViewModel: NotesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KgsNotesTheme {
                KgsNotesApp(notesViewModel)
            }
        }
        warmUpKgsMarkdownEditor(applicationContext)
    }

    override fun onStop() {
        notesViewModel.flushPendingContent()
        super.onStop()
    }
}
