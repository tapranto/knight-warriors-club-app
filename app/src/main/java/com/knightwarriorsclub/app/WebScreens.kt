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
import com.knightwarriorsclub.app.data.Session
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JavaScript that hands the app's Supabase session to the website:
 * supabase-js keeps its session in localStorage under this key, so
 * writing it there (then reloading) makes knightwarriorsclub.com boot
 * already signed in — no second login inside the app (Pranto's bug
 * report, 2026-11-02: the hybrid Tournament Maker asked for login).
 * The site's client refreshes the token itself from the refresh token.
 */
private fun sessionBridgeJs(session: Session): String {
    val sessionJson = buildJsonObject {
        put("access_token", session.accessToken)
        put("refresh_token", session.refreshToken)
        put("expires_at", System.currentTimeMillis() / 1000 + 3500)
        put("expires_in", 3600)
        put("token_type", "bearer")
        put("user", buildJsonObject {
            put("id", session.authUserId)
            put("email", session.email)
            put("aud", "authenticated")
            put("role", "authenticated")
        })
    }.toString()
    val quoted = org.json.JSONObject.quote(sessionJson)
    return "(function(){try{" +
            "localStorage.setItem('sb-jpurbipmekomqjdjvquo-auth-token'," + quoted + ");" +
            "}catch(e){}})();"
}

/**
 * Hybrid WebView pool (beta38, Pranto 2026-11-03): WebViews used to be
 * destroyed every time he left a tab, so Events/News reloaded from the
 * start on every return. Now each hybrid destination owns ONE WebView
 * that stays alive for the whole app session — page, scroll position
 * and in-web history are exactly where he left them, and Android Back
 * can walk the web history (see [goBack]) before leaving the page.
 */
object WebPool {
    private val views = mutableMapOf<String, WebView>()
    private val bridged = mutableSetOf<String>()

    fun obtain(key: String): WebView? = views[key]
    fun store(key: String, view: WebView) { views[key] = view }
    fun isBridged(key: String): Boolean = key in bridged
    fun markBridged(key: String) { bridged.add(key) }

    /** Go back inside the pooled WebView; false when it has no history. */
    fun goBack(key: String): Boolean {
        val v = views[key] ?: return false
        return if (v.canGoBack()) { v.goBack(); true } else false
    }
}

/**
 * Hybrid pages (Pranto's decision, 2026-11-02): News, Events / official
 * tournaments and the Leaderboard are website content — the app shows
 * knightwarriorsclub.com inside a WebView, so whatever is published or
 * updated on the website (fixtures, results, joins) appears in the app
 * instantly, with no new APK. The website's tournament Join works here.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPageView(
    url: String, modifier: Modifier = Modifier,
    bridgeSession: Session? = null, poolKey: String = url
) {
    var progress by remember(poolKey) { mutableStateOf(100) }
    Box(modifier.background(Color(0xFF0A101C))) {
        AndroidView(
            factory = { ctx ->
                WebPool.obtain(poolKey) ?: WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            val bridge = bridgeSession
                            if (bridge != null && !WebPool.isBridged(poolKey)) {
                                WebPool.markBridged(poolKey)
                                view?.evaluateJavascript(sessionBridgeJs(bridge), null)
                                view?.reload()
                                return
                            }
                            // Inside the app, the website's own top header
                            // (logo + nav) duplicates the app's header and
                            // tab bar — hide it and reclaim its space
                            // (Pranto, 2026-11-02). The style element lives
                            // in the page, so it survives the site's in-page
                            // view swaps. beta38: the tournament section
                            // strip (.jump-bar) sticks at top:112px on the
                            // site (room for the site header); in the app
                            // it must pin flush to the WebView top instead.
                            view?.evaluateJavascript(
                                """
                                (function () {
                                  var s = document.getElementById('kwc-app-style');
                                  if (!s) {
                                    s = document.createElement('style');
                                    s.id = 'kwc-app-style';
                                    document.head.appendChild(s);
                                  }
                                  s.textContent = '.site-head{display:none !important}' +
                                    'main{padding-top:12px !important}' +
                                    'section{padding-top:16px !important}' +
                                    '.jump-bar{top:0 !important}';
                                })();
                                """.trimIndent(),
                                null
                            )
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    WebPool.store(poolKey, this)
                    loadUrl(url)
                }
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
