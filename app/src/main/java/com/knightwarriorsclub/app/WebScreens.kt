package com.knightwarriorsclub.app

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hybrid pages (Pranto's decision, 2026-11-02): News, Events / official
 * tournaments and the Leaderboard are website content — the app shows
 * knightwarriorsclub.com inside a WebView, so whatever is published or
 * updated on the website (fixtures, results, joins) appears in the app
 * instantly, with no new APK. The website's tournament Join works here.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPageView(url: String, modifier: Modifier = Modifier) {
    var progress by remember(url) { mutableStateOf(0) }
    Box(modifier.background(Color(0xFF0A101C))) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webViewClient = WebViewClient()
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    loadUrl(url)
                }
            },
            update = { view ->
                if (view.url != url) view.loadUrl(url)
            },
            modifier = Modifier.fillMaxSize()
        )
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                color = Gold,
                trackColor = Color.White.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
            )
        }
    }
}

/** A hybrid page with its own header + back (opened from the More panel). */
@Composable
fun WebPageScreen(url: String, title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(26.dp).clickable { onBack() }
            )
            Spacer(Modifier.width(10.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        WebPageView(url, Modifier.weight(1f).fillMaxWidth())
    }
}
