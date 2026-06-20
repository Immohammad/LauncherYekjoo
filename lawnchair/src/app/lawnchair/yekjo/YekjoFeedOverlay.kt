package app.lawnchair.yekjo

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.Insettable
import com.android.systemui.plugins.shared.LauncherOverlayManager
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlay
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayCallbacks
import kotlin.math.abs

class YekjoFeedOverlay(private val launcher: LawnchairLauncher) :
    LauncherOverlayManager,
    LauncherOverlay {

    private var callbacks: LauncherOverlayCallbacks? = null
    private var panel: View? = null
    private var webView: WebView? = null
    private var currentProgress = 0f
    private var snapAnimator: ValueAnimator? = null
    private var wasOpenAtInteractionStart = false
    @Volatile private var isScrollableUnderFinger = false

    private var hasLoaded = false
    private var lastRefreshTime = 0L
    private var refreshLabel: TextView? = null
    private var refreshIcon: TextView? = null
    private var iconAnimator: ObjectAnimator? = null
    private var errorView: View? = null
    private var currentLoadHadError = false
    private var currentTabUrl = YEKJO_URL
    private var tabBar: LinearLayout? = null
    private var tabNewsButton: TextView? = null
    private var tabMarketsButton: TextView? = null
    private var backButton: TextView? = null
    private var navBar: View? = null
    private var currentWebUrl: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    // Self-canceling: stops re-posting when the panel is closed or the feed has never
    // successfully loaded. Prevents the launcher from waking every 60s in the background.
    private val labelUpdateRunnable = object : Runnable {
        override fun run() {
            if (currentProgress <= 0f || lastRefreshTime == 0L) return
            updateRefreshLabel()
            mainHandler.postDelayed(this, LABEL_UPDATE_INTERVAL_MS)
        }
    }

    private fun startLabelUpdatesIfNeeded() {
        mainHandler.removeCallbacks(labelUpdateRunnable)
        if (currentProgress > 0f && lastRefreshTime != 0L) {
            mainHandler.postDelayed(labelUpdateRunnable, LABEL_UPDATE_INTERVAL_MS)
        }
    }

    private fun stopLabelUpdates() {
        mainHandler.removeCallbacks(labelUpdateRunnable)
    }

    companion object {
        private const val YEKJO_URL = "https://yekjoo.ir/?utm_source=yekjoo&utm_medium=launcher"
        private const val YEKJO_MARKETS_URL = "https://yekjoo.ir/markets?utm_source=yekjoo&utm_medium=launcher"
        private const val LABEL_UPDATE_INTERVAL_MS = 60L * 1000
        // Swipe 25% of screen width to open; swipe 25% to close (close snaps below 0.75f)
        private const val OPEN_SNAP_THRESHOLD = 0.25f
        private const val CLOSE_SNAP_THRESHOLD = 0.75f
        // Injected on every page load. Walks up the DOM from the touch target and reports
        // whether the finger is on a horizontally-scrollable element (native overflow or
        // common carousel libraries like Swiper.js). PanelLayout uses this to decide whether
        // a left swipe should scroll the carousel or close the panel.
        private const val SCROLL_DETECT_JS = """(function(){
  if (window._yekjoScrollListenerAdded) return;
  window._yekjoScrollListenerAdded = true;
  document.addEventListener('touchstart', function(e) {
    var el = e.target, h = false;
    while (el && el !== document.body) {
      var s = window.getComputedStyle(el);
      if ((s.overflowX === 'scroll' || s.overflowX === 'auto') && el.scrollWidth > el.clientWidth + 2) { h = true; break; }
      var cls = typeof el.className === 'string' ? el.className : '';
      if (/swiper|carousel|slider/.test(cls)) { h = true; break; }
      el = el.parentElement;
    }
    try { yekjoNative.onScrollableUnderFinger(h); } catch(_) {}
  }, {capture: true, passive: true});
})();"""
    }

    // Lawnchair forces the launcher Configuration to LTR. Workspace attaches the overlay
    // edge effect to mEdgeGlowLeft regardless of system locale, so the feed always lives
    // on the LEFT of home and is opened by pulling the left edge (swipe finger right).
    // We mirror that here: panel always slides in from the left and the close gesture
    // is always swipe-left, so behavior matches in both Persian and English builds.
    private inner class PanelLayout(context: Context) : FrameLayout(context), Insettable {
        // scaledPagingTouchSlop (~2× regular slop) gives the WebView's async renderer thread
        // enough time to call requestDisallowInterceptTouchEvent(true) before we intercept.
        private val touchSlop = ViewConfiguration.get(context).scaledPagingTouchSlop.toFloat()

        // Implementing Insettable opts out of InsettableFrameLayout's default behavior, which
        // would add insets.top to our topMargin. We want the panel to extend all the way to
        // y=0 so the nav bar's dark background fills the status-bar area continuously; the
        // bar applies its own top padding from setOnApplyWindowInsetsListener instead.
        override fun setInsets(insets: Rect) {}
        private var startX = 0f
        private var startY = 0f
        private var isTouchTracking = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.x
                    startY = ev.y
                    isTouchTracking = false
                    wasOpenAtInteractionStart = currentProgress >= 0.99f
                    isScrollableUnderFinger = false  // JS touchstart will override if needed
                    snapAnimator?.cancel()
                    if (wasOpenAtInteractionStart) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isTouchTracking) {
                        val dx = ev.x - startX
                        val dy = ev.y - startY
                        // Don't steal the gesture if the WebView reported a scrollable element
                        // under the finger (JS bridge fires on touchstart, before any move).
                        val isSwipingToClose = dx < -touchSlop && !isScrollableUnderFinger
                        if (isSwipingToClose && abs(dx) > abs(dy)) {
                            isTouchTracking = true
                            return true
                        }
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!isTouchTracking) return false
            when (ev.action) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - startX
                    val w = width.toFloat().takeIf { it > 0 } ?: return true
                    // dx is negative while closing (finger moves left); progress goes 1 -> 0.
                    val progress = (1f + dx / w).coerceIn(0f, 1f)
                    currentProgress = progress
                    callbacks?.onOverlayScrollChanged(progress)
                    applyPanelTranslation(progress)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isTouchTracking = false
                    snapTo(resolveSnapTarget())
                }
            }
            return true
        }
    }

    private inner class ScrollBridge {
        @JavascriptInterface
        fun onScrollableUnderFinger(scrollable: Boolean) {
            isScrollableUnderFinger = scrollable
        }
    }

    private fun ensurePanelAttached() {
        if (panel != null) return

        val rootView = launcher.rootView as ViewGroup

        val panelLayout = PanelLayout(launcher).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
        }

        val contentLayout = LinearLayout(launcher).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        panelLayout.addView(contentLayout)

        addTopNavBar(contentLayout)

        val wv = WebView(launcher).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    currentWebUrl = url
                    currentLoadHadError = false
                    hideErrorView()
                    startIconRotation()
                    updateActiveTabFromUrl(url)
                    updateNavBarState()
                }
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: android.net.http.SslError?,
                ) {
                    handler?.proceed()
                }
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame != true) return
                    currentLoadHadError = true
                    stopIconRotation()
                    showErrorView()
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    currentWebUrl = url ?: currentWebUrl
                    stopIconRotation()
                    view?.evaluateJavascript(SCROLL_DETECT_JS, null)
                    if (currentLoadHadError) return
                    if (isRootUrl(url)) {
                        lastRefreshTime = System.currentTimeMillis()
                        refreshLabel?.visibility = View.VISIBLE
                        updateRefreshLabel()
                        startLabelUpdatesIfNeeded()
                    } else {
                        refreshLabel?.visibility = View.GONE
                    }
                    updateNavBarState()
                }
                // Catches SPA pushState navigation (Next.js client-side routing).
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (!isReload) {
                        currentWebUrl = url
                        updateActiveTabFromUrl(url)
                        updateNavBarState()
                    }
                }
            }
            // Redirect target="_blank" / window.open() links into the same WebView so
            // back navigation stays intact and the user never leaves the overlay.
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?,
                ): Boolean {
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    val temp = WebView(view!!.context)
                    temp.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            v: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url?.toString() ?: return true
                            view.loadUrl(url)
                            temp.destroy()
                            return true
                        }
                    }
                    transport.webView = temp
                    resultMsg.sendToTarget()
                    return true
                }
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                setSupportMultipleWindows(true)
            }
            addJavascriptInterface(ScrollBridge(), "yekjoNative")
        }
        contentLayout.addView(wv)
        webView = wv
        panel = panelLayout

        addErrorView(contentLayout)
        addTabBar(contentLayout)

        rootView.addView(panelLayout)

        if (rootView.width > 0) {
            applyPanelTranslation(0f)
        } else {
            panelLayout.visibility = View.INVISIBLE
            rootView.post {
                panelLayout.visibility = View.VISIBLE
                applyPanelTranslation(0f)
            }
        }

        launcher.setLauncherOverlay(this)
    }

    private fun addTopNavBar(contentLayout: LinearLayout) {
        val context = contentLayout.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Float): Int = (density * value).toInt()

        val fontResId = context.resources.getIdentifier("vazirmatn_regular", "font", context.packageName)
        val tf: Typeface = runCatching {
            if (fontResId != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.resources.getFont(fontResId)
            } else null
        }.getOrNull() ?: Typeface.DEFAULT

        val accent = Color.parseColor("#0EA5E9")
        val aR = Color.red(accent); val aG = Color.green(accent); val aB = Color.blue(accent)
        val buttonBg     = Color.argb(31,  aR, aG, aB)
        val buttonBorder = Color.argb(46,  aR, aG, aB)
        val labelColor   = Color.argb(217, minOf(255, aR + 40), minOf(255, aG + 30), minOf(255, aB + 30))
        val strokePx = Math.round(density)

        // Fallback top padding used until the first WindowInsets dispatch arrives.
        val fallbackStatusBarHeight = context.resources.let { res ->
            val id = res.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) res.getDimensionPixelSize(id) else dp(24f)
        }

        // Opaque bar sits above the WebView (no overlap with site content). Top padding
        // is set dynamically from the real status-bar inset (cutout-aware), so the buttons
        // land immediately below the system status-bar items with no empty band. Bottom
        // padding is 0 so the buttons' bottom edges line up with the WebView's top edge.
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), fallbackStatusBarHeight, dp(16f), 0)
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setOnApplyWindowInsetsListener { v, insets ->
                val topInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.statusBars()).top
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop
                }
                v.setPadding(dp(16f), topInset, dp(16f), 0)
                insets
            }
        }

        val back = TextView(context).apply {
            text = "←"
            textSize = 16f
            typeface = tf
            setTextColor(accent)
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(buttonBg)
                setStroke(strokePx, buttonBorder)
            }
            layoutParams = LinearLayout.LayoutParams(dp(36f), dp(36f))
            setOnClickListener { navigateBack() }
        }

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        val refreshArea = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999f).toFloat()
                setColor(buttonBg)
                setStroke(strokePx, buttonBorder)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            setOnClickListener { userTriggeredRefresh() }
        }

        val icon = TextView(context).apply {
            text = "↻"
            setTextColor(accent)
            textSize = 15f
            typeface = tf
            includeFontPadding = false
        }

        val label = TextView(context).apply {
            text = ""
            setTextColor(labelColor)
            textSize = 12f
            typeface = tf
            includeFontPadding = false
            setPadding(dp(8f), 0, 0, 0)
            visibility = View.GONE
        }

        refreshArea.addView(icon)
        refreshArea.addView(label)
        bar.addView(back)
        bar.addView(spacer)
        bar.addView(refreshArea)
        contentLayout.addView(bar)

        navBar = bar
        backButton = back
        refreshIcon = icon
        refreshLabel = label
    }

    private fun isRootUrl(url: String?): Boolean {
        if (url == null) return false
        return try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host ?: return false
            if (!host.contains("yekjoo")) return false
            val path = uri.path?.trimEnd('/') ?: ""
            path.isEmpty() || path == "/markets"
        } catch (e: Exception) {
            false
        }
    }

    private fun updateNavBarState() {
        val atRoot = isRootUrl(currentWebUrl)
        backButton?.visibility = if (atRoot) View.GONE else View.VISIBLE
        if (!atRoot) refreshLabel?.visibility = View.GONE
    }

    private fun navigateBack() {
        val wv = webView ?: return
        if (wv.canGoBack()) wv.goBack()
    }

    private fun triggerInitialLoadIfNeeded() {
        if (hasLoaded) return
        val wv = webView ?: return
        hasLoaded = true
        wv.loadUrl(YEKJO_URL)
    }

    private fun userTriggeredRefresh() {
        val wv = webView ?: return
        if (!hasLoaded) {
            triggerInitialLoadIfNeeded()
        } else {
            wv.reload()
        }
    }

    private fun applyPanelTranslation(progress: Float) {
        val p = panel ?: return
        val width = (p.parent as? View)?.width?.takeIf { it > 0 } ?: p.width
        p.translationX = -width * (1f - progress)
    }

    private fun addErrorView(contentLayout: LinearLayout) {
        val context = contentLayout.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Float): Int = (density * value).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
            setPadding(dp(32f), dp(32f), dp(32f), dp(32f))
        }

        val icon = TextView(context).apply {
            text = "⚠"
            setTextColor(Color.parseColor("#FFB74D"))
            textSize = 64f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        val title = TextView(context).apply {
            text = "اتصال اینترنت برقرار نیست"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        val subtitle = TextView(context).apply {
            text = "برای مشاهده آخرین اتفاقات،\nاینترنت خود را روشن کنید"
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 14f
            gravity = Gravity.CENTER
            setLineSpacing(dp(4f).toFloat(), 1f)
        }

        val retry = TextView(context).apply {
            text = "تلاش مجدد"
            setTextColor(Color.BLACK)
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = density * 22f
                setColor(Color.WHITE)
            }
            setPadding(dp(32f), dp(12f), dp(32f), dp(12f))
            isClickable = true
            isFocusable = true
            setOnClickListener { userTriggeredRefresh() }
        }

        container.addView(
            icon,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        container.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20f) },
        )
        container.addView(
            subtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8f) },
        )
        container.addView(
            retry,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(28f) },
        )

        contentLayout.addView(
            container,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        errorView = container
    }

    private fun showErrorView() {
        errorView?.visibility = View.VISIBLE
        webView?.visibility = View.GONE
    }

    private fun hideErrorView() {
        errorView?.visibility = View.GONE
        webView?.visibility = View.VISIBLE
    }

    private fun updateRefreshLabel() {
        val label = refreshLabel ?: return
        if (lastRefreshTime == 0L) return
        if (!isRootUrl(currentWebUrl)) return
        label.text = formatRelativeTime(System.currentTimeMillis() - lastRefreshTime)
    }

    private fun formatRelativeTime(elapsedMs: Long): String {
        val seconds = elapsedMs / 1000
        return when {
            seconds < 60 -> "همین الان"
            seconds < 3600 -> "${toPersianDigits(seconds / 60)} دقیقه پیش"
            seconds < 86400 -> "${toPersianDigits(seconds / 3600)} ساعت پیش"
            else -> "${toPersianDigits(seconds / 86400)} روز پیش"
        }
    }

    private fun toPersianDigits(num: Long): String {
        val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        return num.toString().map { c ->
            if (c.isDigit()) persianDigits[c.digitToInt()] else c
        }.joinToString("")
    }

    private fun startIconRotation() {
        val icon = refreshIcon ?: return
        iconAnimator?.cancel()
        iconAnimator = ObjectAnimator.ofFloat(icon, "rotation", 0f, 360f).apply {
            duration = 800
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopIconRotation() {
        iconAnimator?.cancel()
        iconAnimator = null
        refreshIcon?.rotation = 0f
    }

    private fun addTabBar(contentLayout: LinearLayout) {
        val context = contentLayout.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Float): Int = (density * value).toInt()

        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1,
            )
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }
        contentLayout.addView(divider)

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        fun makeTabButton(label: String, url: String): TextView = TextView(context).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, dp(52f), 1f)
            setOnClickListener { selectTab(url) }
        }

        val newsTab = makeTabButton("اخبار", YEKJO_URL)
        val marketsTab = makeTabButton("بازار", YEKJO_MARKETS_URL)
        bar.addView(marketsTab)
        bar.addView(newsTab)

        bar.setOnApplyWindowInsetsListener { v, insets ->
            val bottomInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            (v as LinearLayout).setPadding(0, 0, 0, bottomInset)
            insets
        }

        contentLayout.addView(bar)
        tabBar = bar
        tabNewsButton = newsTab
        tabMarketsButton = marketsTab
        updateTabBarSelection()
    }

    private fun selectTab(url: String) {
        if (currentTabUrl == url) return
        currentTabUrl = url
        updateTabBarSelection()
        webView?.clearHistory()
        webView?.loadUrl(url)
    }

    private fun updateTabBarSelection() {
        tabNewsButton?.setTextColor(if (currentTabUrl == YEKJO_URL) Color.WHITE else Color.parseColor("#777777"))
        tabMarketsButton?.setTextColor(if (currentTabUrl == YEKJO_MARKETS_URL) Color.WHITE else Color.parseColor("#777777"))
    }

    private fun updateActiveTabFromUrl(url: String?) {
        if (url == null) return
        val host = try { android.net.Uri.parse(url).host } catch (e: Exception) { null } ?: return
        if (!host.contains("yekjoo")) return
        val path = try { android.net.Uri.parse(url).path } catch (e: Exception) { null } ?: "/"
        val newTabUrl = if (path.startsWith("/markets")) YEKJO_MARKETS_URL else YEKJO_URL
        if (newTabUrl != currentTabUrl) {
            currentTabUrl = newTabUrl
            updateTabBarSelection()
        }
    }

    override fun onAttachedToWindow() {
        ensurePanelAttached()
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(labelUpdateRunnable)
        iconAnimator?.cancel()
        iconAnimator = null
        panel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        panel = null
        webView = null
        refreshIcon = null
        refreshLabel = null
        errorView = null
        tabBar = null
        tabNewsButton = null
        tabMarketsButton = null
        backButton = null
        navBar = null
    }

    override fun onActivityStarted() {
        ensurePanelAttached()
        webView?.onResume()
    }

    override fun onActivityResumed() {
        webView?.onResume()
        startLabelUpdatesIfNeeded()
    }

    override fun onActivityPaused() {
        webView?.onPause()
        stopLabelUpdates()
    }

    override fun onActivityStopped() {
        webView?.onPause()
        stopLabelUpdates()
    }

    override fun onActivityDestroyed() {
        mainHandler.removeCallbacks(labelUpdateRunnable)
        iconAnimator?.cancel()
        iconAnimator = null
        webView?.destroy()
        webView = null
        panel = null
        refreshIcon = null
        refreshLabel = null
        errorView = null
        tabBar = null
        tabNewsButton = null
        tabMarketsButton = null
        backButton = null
        navBar = null
        hasLoaded = false
        lastRefreshTime = 0L
        currentLoadHadError = false
        currentTabUrl = YEKJO_URL
        currentWebUrl = null
    }

    override fun onScrollInteractionBegin() {
        // When the panel is fully open, ignore workspace-side gesture starts so that
        // horizontal swipes inside the WebView (e.g. carousel) don't close the feed.
        if (currentProgress >= 0.99f) return
        snapAnimator?.cancel()
        wasOpenAtInteractionStart = currentProgress >= 0.99f
        triggerInitialLoadIfNeeded()
    }

    override fun onScrollInteractionEnd() {
        if (currentProgress >= 0.99f) return
        snapTo(resolveSnapTarget())
    }

    private fun resolveSnapTarget(): Float {
        val threshold = if (wasOpenAtInteractionStart) CLOSE_SNAP_THRESHOLD else OPEN_SNAP_THRESHOLD
        return if (currentProgress >= threshold) 1f else 0f
    }

    private fun snapTo(target: Float) {
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(currentProgress, target).apply {
            duration = 200
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                currentProgress = p
                callbacks?.onOverlayScrollChanged(p)
                applyPanelTranslation(p)
            }
            start()
        }
    }

    override fun onScrollChange(progress: Float, rtl: Boolean) {
        // If the panel is fully open and the workspace is trying to decrease progress
        // (e.g. triggered by a carousel swipe), discard it — the WebView owns that touch.
        if (currentProgress >= 0.99f && progress < currentProgress) return
        if (progress > 0f) triggerInitialLoadIfNeeded()
        currentProgress = progress
        callbacks?.onOverlayScrollChanged(progress)
        applyPanelTranslation(progress)
    }

    override fun setOverlayCallbacks(callbacks: LauncherOverlayCallbacks?) {
        this.callbacks = callbacks
    }

    override fun onFlingVelocity(velocity: Float) {}

    override fun onOverlayMotionEvent(ev: MotionEvent, scrollProgress: Float) {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> onScrollInteractionBegin()
            MotionEvent.ACTION_MOVE -> onScrollChange(scrollProgress, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onScrollInteractionEnd()
        }
    }

    override fun openOverlay() {
        triggerInitialLoadIfNeeded()
        snapTo(1f)
    }

    override fun hideOverlay(animate: Boolean) {
        snapTo(0f)
    }

    override fun hideOverlay(duration: Int) {
        snapTo(0f)
    }
}
