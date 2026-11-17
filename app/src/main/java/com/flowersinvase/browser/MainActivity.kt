package com.flowersinvase.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.flowersinvase.browser.ui.theme.BrowserTheme

class MainActivity : ComponentActivity() {
    private val session: BrowserSession by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BrowserTheme {
                BrowserScreen(session, window)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        session.active.view?.onResume()
    }

    override fun onPause() {
        session.active.view?.onPause()
        super.onPause()
    }

    override fun onStop() {
        session.save()
        super.onStop()
    }
}
