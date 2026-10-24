package com.morningdigest.kindle.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.morningdigest.kindle.model.XPost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import kotlin.coroutines.resume

class XFeedScraper(private val context: Context) {

    fun isLoggedInToX(): Boolean {
        val cookies = CookieManager.getInstance().getCookie("https://x.com") ?: ""
        return cookies.contains("auth_token=") || cookies.contains("twid=")
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun scrapeFollowingFeed(
        scrollIterations: Int = 6,
        onProgress: (String) -> Unit = {}
    ): List<XPost> = withContext(Dispatchers.Main) {
        val webView = WebView(context).apply {
            visibility = View.INVISIBLE
            layout(0, 0, 1080, 2400)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        try {
            onProgress("Loading x.com/home in hidden WebView...")
            val pageLoaded = withTimeoutOrNull(25_000L) {
                loadUrlAndWait(webView, "https://x.com/home")
            } ?: false

            if (!pageLoaded) {
                onProgress("Initial X page load timed out, attempting DOM read anyway...")
            }

            // Wait for X React timeline & tabs to mount
            var timelineReady = false
            for (attempt in 1..12) {
                delay(1500L)
                val countStr = evalJs(
                    webView,
                    "(function(){ return document.querySelectorAll('[role=\"tab\"], article[data-testid=\"tweet\"]').length; })()"
                )
                val count = countStr.trim().removeSurrounding("\"").toIntOrNull() ?: 0
                if (count > 0) {
                    timelineReady = true
                    break
                }
            }

            if (!timelineReady) {
                return@withContext emptyList()
            }

            // Switch to the "Following" tab
            onProgress("Switching to 'Following' tab on X...")
            evalJs(
                webView,
                """
                (function() {
                    window.__tweetMap = window.__tweetMap || {};
                    const tabs = Array.from(document.querySelectorAll('[role="tab"]'));
                    for (const tab of tabs) {
                        const text = (tab.innerText || '').trim().toLowerCase();
                        if (text === 'following') {
                            tab.click();
                            return 'CLICKED_FOLLOWING';
                        }
                    }
                    return 'FOLLOWING_TAB_NOT_FOUND';
                })();
                """.trimIndent()
            )

            // Wait for Following feed tweets to render
            delay(3000L)

            val collectorJs = """
                (function() {
                    window.__tweetMap = window.__tweetMap || {};
                    const articles = Array.from(document.querySelectorAll('article[data-testid="tweet"]'));
                    for (const art of articles) {
                        try {
                            const fullText = art.innerText || '';
                            // Skip promoted ads
                            if (fullText.includes('\nAd\n') || fullText.includes('Promoted')) continue;

                            const userNameEl = art.querySelector('[data-testid="User-Name"]');
                            const userRaw = userNameEl ? (userNameEl.innerText || '') : '';
                            const userLines = userRaw.split('\n').map(s => s.trim()).filter(Boolean);
                            const authorName = userLines[0] || 'Unknown';
                            const handleLine = userLines.find(l => l.startsWith('@')) || '';

                            const timeEl = art.querySelector('time');
                            const timestamp = timeEl ? (timeEl.getAttribute('datetime') || timeEl.innerText || '') : '';

                            let statusUrl = '';
                            const links = Array.from(art.querySelectorAll('a[href*="/status/"]'));
                            if (links.length > 0) {
                                statusUrl = links[0].getAttribute('href') || '';
                            }

                            const textEls = Array.from(art.querySelectorAll('[data-testid="tweetText"]'));
                            const tweetText = textEls.map(el => (el.innerText || '').trim()).filter(Boolean).join('\n\n[Quoted]: ');
                            if (!tweetText || tweetText.length < 5) continue;

                            const groupEl = art.querySelector('[role="group"]');
                            const metrics = groupEl ? (groupEl.getAttribute('aria-label') || '') : '';

                            const id = statusUrl || (handleLine + '_' + tweetText.slice(0, 40));
                            window.__tweetMap[id] = {
                                id: id,
                                authorName: authorName,
                                authorHandle: handleLine,
                                timestamp: timestamp,
                                text: tweetText,
                                metricsSummary: metrics,
                                url: statusUrl ? ('https://x.com' + statusUrl) : ''
                            };
                        } catch (e) {}
                    }
                    return Object.keys(window.__tweetMap).length;
                })();
            """.trimIndent()

            for (step in 1..scrollIterations) {
                val collectedStr = evalJs(webView, collectorJs)
                val collectedCount = collectedStr.trim().removeSurrounding("\"").toIntOrNull() ?: 0
                onProgress("Scanning X Following tab (scroll $step/$scrollIterations — $collectedCount posts found)...")
                evalJs(webView, "window.scrollBy(0, 1500);")
                delay(2200L)
            }

            // Final collection pass
            evalJs(webView, collectorJs)
            val jsonResult = evalJs(
                webView,
                "(function(){ return JSON.stringify(Object.values(window.__tweetMap || {})); })()"
            )

            parseTweetsJson(jsonResult)
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    private suspend fun loadUrlAndWait(webView: WebView, url: String): Boolean =
        suspendCancellableCoroutine { cont ->
            var resumed = false
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    if (!resumed && cont.isActive) {
                        resumed = true
                        cont.resume(true)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = false
            }
            webView.loadUrl(url)
        }

    private suspend fun evalJs(webView: WebView, script: String): String =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { result ->
                if (cont.isActive) {
                    cont.resume(result ?: "")
                }
            }
        }

    private fun parseTweetsJson(rawEvalOutput: String): List<XPost> {
        if (rawEvalOutput.isBlank() || rawEvalOutput == "null") return emptyList()
        return try {
            // evaluateJavascript returns a JSON-encoded string literal if the script returned a string
            val unescaped = if (rawEvalOutput.startsWith("\"") && rawEvalOutput.endsWith("\"")) {
                org.json.JSONTokener(rawEvalOutput).nextValue().toString()
            } else {
                rawEvalOutput
            }
            val arr = JSONArray(unescaped)
            val list = mutableListOf<XPost>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    XPost(
                        id = obj.optString("id"),
                        authorName = obj.optString("authorName", "Unknown"),
                        authorHandle = obj.optString("authorHandle", ""),
                        timestamp = obj.optString("timestamp", ""),
                        text = obj.optString("text", ""),
                        metricsSummary = obj.optString("metricsSummary", ""),
                        url = obj.optString("url", "")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
