package com.flowersinvase.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BrowserWebView(
    tab: BrowserTab,
    window: Window,
    background: Int,
    visible: Boolean,
    accessible: Boolean,
    onVerticalDrag: (downPage: Boolean) -> Unit,
    onPageEdgeColor: (String, Int) -> Unit,
    onVisited: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    key(tab.id) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                BrowserPageView(context, window).apply {
                    tab.view = this
                    // WebView uses its layout params to resolve percentage heights in HTML.
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(background)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        setAlgorithmicDarkeningAllowed(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    webViewClient = TabWebViewClient(tab, onVisited)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, progress: Int) {
                            if (view === tab.view && !tab.failed) tab.progress = progress
                        }

                        override fun onReceivedTitle(view: WebView, title: String?) {
                            if (view === tab.view && !tab.failed) tab.title = title.orEmpty().take(300)
                        }
                    }

                    val history = tab.history
                    tab.history = null
                    if (history == null || restoreState(history) == null) {
                        if (tab.url != null) tab.progress = 0
                        tab.url?.let(::loadUrl)
                    }
                    tab.canGoForward = canGoForward()
                }
            },
            update = { view ->
                view.onVerticalDrag = onVerticalDrag
                view.onPageEdgeColor = onPageEdgeColor.takeIf { visible && accessible }
                view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                view.importantForAccessibility = if (accessible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            },
            onRelease = { view ->
                view.onPageEdgeColor = null
                if (view === tab.view) {
                    if (tab.url != null) {
                        val history = Bundle()
                        val saved = view.saveState(history)
                        // A newly requested page may not yet be part of WebView's history.
                        if (saved?.currentItem?.url == tab.url) tab.history = history
                    }
                    tab.view = null
                    view.stopLoading()
                }
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
        )
    }
}

private class BrowserPageView(context: Context, private val window: Window) : WebView(context) {
    var onPageEdgeColor: ((String, Int) -> Unit)? = null
        set(value) {
            val resumeSampling = field == null && value != null && pageToSample != null
            field = value
            if (resumeSampling) invalidate()
        }
    private val edgeSample by lazy { Bitmap.createBitmap(24, 1, Bitmap.Config.ARGB_8888) }
    private val windowPosition = IntArray(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var copyPending = false
    private var pageToSample: String? = null
    private var pageColorCaptured = false

    fun resetPageColorSample() {
        pageToSample = null
        pageColorCaptured = false
    }

    fun sampleAfterLoad(page: String) {
        if (pageColorCaptured) return
        postVisualStateCallback(0L, object : VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (url != page || pageColorCaptured) return
                pageToSample = page
                invalidate()
            }
        })
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val page = pageToSample ?: return
        if (onPageEdgeColor == null || copyPending || width == 0) return
        copyPending = true
        pageToSample = null
        postDelayed({ samplePageEdge(page) }, 48)
    }

    private fun samplePageEdge(page: String) {
        if (!isShown || url != page || pageColorCaptured) {
            copyPending = false
            return
        }
        if (onPageEdgeColor == null) {
            pageToSample = page
            copyPending = false
            return
        }
        getLocationInWindow(windowPosition)
        val (left, top) = windowPosition
        val band = Rect(left, top, left + width, top + minOf(height, 6))
        if (band.isEmpty) {
            copyPending = false
            return
        }
        // Copy the first visible rows without drawing the web page beneath the system icons.
        try {
            PixelCopy.request(window, band, edgeSample, { status ->
                copyPending = false
                if (status == PixelCopy.SUCCESS && url == page) {
                    val reportColor = onPageEdgeColor
                    if (reportColor != null) {
                        pageColorCaptured = true
                        reportColor(page, averageEdgeColor())
                    } else {
                        pageToSample = page
                    }
                }
            }, mainHandler)
        } catch (_: IllegalArgumentException) {
            copyPending = false
        }
    }

    private fun averageEdgeColor(): Int {
        var red = 0
        var green = 0
        var blue = 0
        for (x in 0 until edgeSample.width) {
            val pixel = edgeSample.getPixel(x, 0)
            red += Color.red(pixel)
            green += Color.green(pixel)
            blue += Color.blue(pixel)
        }
        return Color.rgb(red / edgeSample.width, green / edgeSample.width, blue / edgeSample.width)
    }

    var onVerticalDrag: ((downPage: Boolean) -> Unit)? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val toggleDistance = maxOf(touchSlop * 2f, 24f * resources.displayMetrics.density)
    private var tracking = false
    private var vertical = false
    private var startX = 0f
    private var anchorY = 0f
    private var lastDirection: Boolean? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                vertical = false
                startX = event.x
                anchorY = event.y
                lastDirection = null
            }
            MotionEvent.ACTION_MOVE -> if (tracking) trackDrag(event.x, event.y)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
            }
        }
        // Let the page handle the gesture, including scrolling in nested HTML containers.
        return super.dispatchTouchEvent(event)
    }

    private fun trackDrag(x: Float, y: Float) {
        if (!vertical) {
            val horizontal = abs(x - startX)
            val verticalDistance = abs(y - anchorY)
            if (maxOf(horizontal, verticalDistance) < touchSlop) return
            if (horizontal >= verticalDistance) {
                tracking = false
                return
            }
            vertical = true
        }

        anchorY = when (lastDirection) {
            true -> minOf(anchorY, y)
            false -> maxOf(anchorY, y)
            null -> anchorY
        }
        val travel = y - anchorY
        if (abs(travel) >= toggleDistance) {
            val downPage = travel < 0
            lastDirection = downPage
            anchorY = y
            onVerticalDrag?.invoke(downPage)
        }
    }
}

private class TabWebViewClient(
    private val tab: BrowserTab,
    private val onVisited: (String) -> Unit
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return !isWebUrl(request.url.toString())
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (view !== tab.view) return
        (view as? BrowserPageView)?.resetPageColorSample()
        tab.failed = false
        tab.errorMessage = null
        tab.progress = 0
        updateLocation(view, url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        updateLocation(view, url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (view !== tab.view || view.url != url) return
        updateLocation(view, url)
        tab.progress = 100
        if (!tab.failed && url != null) {
            onVisited(url)
            (view as? BrowserPageView)?.sampleAfterLoad(url)
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (view !== tab.view || !request.isForMainFrame) return
        fail(view, request.url.toString(), error.description.toString())
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        // This callback also covers subresources. Only replace the page for its own SSL error.
        if (error.url == tab.url) fail(view, error.url, view.context.getString(R.string.ssl_error))
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        if (view === tab.view) {
            tab.view = null
            tab.history = null
            tab.rendererGone = true
            tab.canGoForward = false
            tab.errorMessage = null
            tab.failed = true
            tab.progress = 100
        }
        return true
    }

    private fun fail(view: WebView, url: String?, message: String) {
        if (view !== tab.view) return
        updateLocation(view, url)
        if (!tab.failed) tab.errorMessage = message
        tab.failed = true
        tab.progress = 100
    }

    private fun updateLocation(view: WebView, url: String?) {
        if (view !== tab.view) return
        tab.canGoForward = view.canGoForward()
        if (url != null && isWebUrl(url) && tab.url != url) {
            tab.url = url
            tab.title = ""
        }
    }
}
