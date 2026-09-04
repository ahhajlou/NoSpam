package com.example.nospam

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.nospam.ui.NoSpamAppShell

// AppCompatActivity (not ComponentActivity): AppCompatDelegate applies
// per-app locales and recreates activities on pre-33 devices only for
// activities running through its delegate. Required for fa/RTL switching.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NoSpamAppShell()
        }
    }
}
