package com.divarsmartsearch.app.data.webview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.divarsmartsearch.app.presentation.screens.webview.JsExtractionScripts
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Runs the exact same extraction logic as [com.divarsmartsearch.app.presentation.screens.webview.DivarWebViewScreen],
 * but against a WebView that is never attached to any visible UI and
 * scrolls itself — this is what lets a saved search be checked
 * automatically from [com.divarsmartsearch.app.service.BackgroundScanService]
 * without the person ever having to open the app and scroll a page by hand.
 *
 * Must be driven from the main thread (WebView requirement); callers on a
 * background dispatcher are automatically switched to Dispatchers.Main.
 */
@Singleton
class HeadlessDivarScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Loads [url], then repeatedly auto-scrolls and re-runs extraction
     * [scrollCycles] times (waiting [scrollDelayMs] between each cycle so
     * Divar's own lazy-loading has time to render), then returns every
     * de-duplicated listing seen across all cycles.
     *
     * Best-effort and time-boxed: if the page never loads, or extraction
     * never fires, this returns an empty list after [overallTimeoutMs]
     * instead of hanging the background scan forever.
     */
    /**
     * Real bug found, and the most likely explanation for "پس‌زمینه اسکن
     * می‌کند ولی انگار کاری نمی‌کند" (background scan runs but doesn't
     * seem to actually do anything): this WebView is created purely
     * headless — it is NEVER added to any View hierarchy or window.
     * Chromium treats a WebView in that state as a hidden/backgrounded
     * tab, and per the standard Page Visibility API, `document.hidden`
     * reports true inside it. Divar's own front-end (like most modern
     * single-page apps) checks that flag and deliberately throttles or
     * pauses its own periodic data-loading/infinite-scroll behavior while
     * the tab is "not visible" to save the (real) user's battery — which
     * is exactly the right thing for a real hidden browser tab, and
     * exactly the wrong thing for a bot that is the ONLY thing looking at
     * this page. The effect matches the report precisely: the very first
     * load looks fine (nothing has been marked hidden yet), and within
     * roughly the timeframe Chromium/most SPAs use for this kind of
     * throttling, further auto-scroll cycles silently stop pulling in new
     * content. Overriding the Page Visibility getters to always report
     * "visible", and firing a `visibilitychange` event so any listener
     * that already ran once re-checks, tells Divar's own code there is
     * nothing to throttle. Injected in onPageStarted (before Divar's own
     * scripts run) rather than onPageFinished, so this is in place from
     * the very first script Divar executes on the page.
     */
    private val VISIBILITY_OVERRIDE_SCRIPT = """
        (function () {
          try {
            Object.defineProperty(document, 'hidden', { get: function () { return false; }, configurable: true });
            Object.defineProperty(document, 'visibilityState', { get: function () { return 'visible'; }, configurable: true });
            document.dispatchEvent(new Event('visibilitychange'));
          } catch (e) {
            // Best-effort only.
          }
        })();
    """.trimIndent()

    suspend fun scan(
        url: String,
        scrollCycles: Int = 10,
        scrollDelayMs: Long = 1500L,
        overallTimeoutMs: Long = 90_000L,
    ): List<ExtractedListing> = withContext(Dispatchers.Main) {
        withTimeoutOrNull(overallTimeoutMs) { runScan(url, scrollCycles, scrollDelayMs) } ?: emptyList()
    }

    private suspend fun runScan(
        url: String,
        scrollCycles: Int,
        scrollDelayMs: Long,
    ): List<ExtractedListing> = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        val collected = LinkedHashMap<String, ExtractedListing>()
        var webView: WebView? = null
        var finished = false

        fun finish() {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            val toDestroy = webView
            webView = null
            // Destroying immediately from inside a WebView callback can
            // crash on some OEM WebView builds, so post it instead.
            handler.post {
                try {
                    toDestroy?.stopLoading()
                    toDestroy?.destroy()
                } catch (e: Exception) {
                    // Nothing more to do; the WebView is being torn down anyway.
                }
            }
            if (continuation.isActive) continuation.resume(collected.values.toList())
        }

        try {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // See VISIBILITY_OVERRIDE_SCRIPT above: a never-attached
                // WebView is otherwise treated as a background tab, and
                // Chromium/most SPAs throttle a background tab's own
                // timers on top of whatever we override in JS — resuming
                // explicitly here removes that second layer of throttling.
                resumeTimers()

                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onListingsExtracted(rawJson: String) {
                            try {
                                val items = json.decodeFromString<List<ExtractedListing>>(rawJson)
                                for (item in items) collected[item.divarToken] = item
                            } catch (e: Exception) {
                                // Malformed batch from the page; keep whatever we already have.
                            }
                        }
                    },
                    "AndroidBridge",
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, startedUrl, favicon)
                        view?.evaluateJavascript(VISIBILITY_OVERRIDE_SCRIPT, null)
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        super.onPageFinished(view, loadedUrl)
                        view?.evaluateJavascript(VISIBILITY_OVERRIDE_SCRIPT, null)
                        view?.evaluateJavascript(JsExtractionScripts.EXTRACTION_SCRIPT, null)
                        runScrollCycle(view, remainingCycles = scrollCycles, delayMs = scrollDelayMs, handler = handler) {
                            finish()
                        }
                    }
                }

                loadUrl(url)
            }
        } catch (e: Exception) {
            finish()
        }

        continuation.invokeOnCancellation {
            try {
                webView?.stopLoading()
                webView?.destroy()
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * Loads a single listing's detail page in its own headless WebView and
     * returns its real title/description/phone straight from the rendered
     * DOM (via [JsExtractionScripts.EXTRACTION_SCRIPT]'s detail-page branch).
     *
     * Bug fix: this replaces an earlier plain-HTTP (OkHttp + regex over raw
     * HTML) detail fetch. Divar's detail page fills in the actual
     * description and contact number with its own JavaScript after the
     * initial page load, so a request without a real JS engine only ever
     * saw the empty page shell and got back nulls. Ingestion then had
     * nothing to filter against except the short (and sometimes
     * over-broad) list-card preview text, which is what let the
     * "دفتر"/"مشاور"/"املاک" hard-exclude filters wrongly catch genuine,
     * non-agency listings and send them to "رد شد" instead of "نتیجه". A
     * real WebView renders JavaScript exactly like the in-app browser tab
     * does, so this sees the same content a person actually would.
     *
     * One extraction right after the page reports finished, plus one more
     * after [extraRenderDelayMs] — whichever arrives first resolves this
     * call — covers content that hydrates a moment after `onPageFinished`.
     */
    suspend fun fetchDetail(
        url: String,
        extraRenderDelayMs: Long = 3000L,
        overallTimeoutMs: Long = 25_000L,
    ): ExtractedListing? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(overallTimeoutMs) { runDetailFetch(url, extraRenderDelayMs) }
    }

    private suspend fun runDetailFetch(
        url: String,
        extraRenderDelayMs: Long,
    ): ExtractedListing? = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var finished = false

        fun finish(result: ExtractedListing?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            val toDestroy = webView
            webView = null
            handler.post {
                try {
                    toDestroy?.stopLoading()
                    toDestroy?.destroy()
                } catch (e: Exception) {
                    // Nothing more to do; the WebView is being torn down anyway.
                }
            }
            if (continuation.isActive) continuation.resume(result)
        }

        try {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                resumeTimers()

                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onListingsExtracted(rawJson: String) {
                            try {
                                val items = json.decodeFromString<List<ExtractedListing>>(rawJson)
                                items.firstOrNull()?.let { finish(it) }
                            } catch (e: Exception) {
                                // Malformed payload from this pass; the second, delayed
                                // extraction (or the timeout) will still resolve the call.
                            }
                        }
                    },
                    "AndroidBridge",
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, startedUrl, favicon)
                        view?.evaluateJavascript(VISIBILITY_OVERRIDE_SCRIPT, null)
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        super.onPageFinished(view, loadedUrl)
                        view?.evaluateJavascript(VISIBILITY_OVERRIDE_SCRIPT, null)
                        view?.evaluateJavascript(JsExtractionScripts.EXTRACTION_SCRIPT, null)
                        handler.postDelayed(
                            { view?.evaluateJavascript(JsExtractionScripts.EXTRACTION_SCRIPT, null) },
                            extraRenderDelayMs,
                        )
                    }
                }

                loadUrl(url)
            }
        } catch (e: Exception) {
            finish(null)
        }

        continuation.invokeOnCancellation {
            try {
                webView?.stopLoading()
                webView?.destroy()
            } catch (ignored: Exception) {
            }
        }
    }

    /** Recursively scrolls, waits, and re-extracts, counting down until [remainingCycles] hits zero. */
    private fun runScrollCycle(
        view: WebView?,
        remainingCycles: Int,
        delayMs: Long,
        handler: Handler,
        onDone: () -> Unit,
    ) {
        if (view == null || remainingCycles <= 0) {
            onDone()
            return
        }
        handler.postDelayed(
            {
                view.evaluateJavascript(JsExtractionScripts.AUTO_SCROLL_SCRIPT, null)
                handler.postDelayed(
                    {
                        view.evaluateJavascript(JsExtractionScripts.EXTRACTION_SCRIPT, null)
                        runScrollCycle(view, remainingCycles - 1, delayMs, handler, onDone)
                    },
                    delayMs / 2,
                )
            },
            delayMs / 2,
        )
    }
}
