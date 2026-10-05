package com.plainnotes.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.plainnotes.android.ui.PlainNotesApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep content beneath the transparent gesture-navigation bar. Window
        // transparency alone is insufficient: NoteEditorScreen must also avoid
        // Scaffold's default bottom inset (see its edge-to-edge contract).
        enableEdgeToEdge()
        setContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                PlainNotesApp()
            }
        }
    }
}
