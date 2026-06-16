package app.lawnchair.yekjo

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.lawnchair.LawnchairLauncher
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

    private var hasLoaded = false
    private var lastRefreshTime = 0L
    private var refreshPill: View? = null
    private var refreshLabel: TextView? = null
    private var refreshIcon: TextView? = null
    private var iconAnimator: ObjectAnimator? = null
    private var errorView: View? = null
    private var currentLoadHadError = false
    private var currentTabUrl = YEKJO_URL
    private var tabBar: LinearLayout? = null
    private var tabNewsButton: TextView? = null
    private var tabMarketsButton: TextView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val labelUpdateRunnable = object : Runnable {
        override fun run() {
            updateRefreshLabel()
            mainHandler.postDelayed(this, LABEL_UPDATE_INTERVAL_MS)
        }
    }

    companion object {
        private const val YEKJO_URL = "https://yekjoo.ir/"
        private const val YEKJO_MARKETS_URL = "https://yekjoo.ir/markets"
        // Hides the site's own bottom tab bar and resets body bottom padding.
        // Selectors cover common React/Next.js nav patterns; refine against actual DOM if needed.
        private const val HIDE_NAV_JS = "(function(){var s=document.createElement('style');s.textContent='nav,footer,[class*=BottomNav],[class*=bottomNav],[class*=bottom-nav],[class*=BottomBar],[class*=bottomBar],[class*=TabBar],[class*=tabBar],[class*=tab-bar],[class*=tabbar]{display:none!important}body,#__next,#root{padding-bottom:0!important;margin-bottom:0!important}';document.head.appendChild(s);['nav','footer','[class*=BottomNav]','[class*=bottomNav]','[class*=BottomBar]','[class*=TabBar]','[class*=tabBar]'].forEach(function(q){try{document.querySelectorAll(q).forEach(function(el){el.style.setProperty('display','none','important');var p=el.parentElement;if(p&&p.children.length<=2&&p!==document.body&&p.tagName!='MAIN'){p.style.setProperty('display','none','important');}});}catch(e){}});document.body.style.setProperty('padding-bottom','0','important');var n=document.getElementById('__next');if(n)n.style.setProperty('padding-bottom','0','important');})();"
        private const val LABEL_UPDATE_INTERVAL_MS = 60L * 1000
        // Swipe 25% of screen width to open; swipe 25% to close (close snaps below 0.75f)
        private const val OPEN_SNAP_THRESHOLD = 0.25f
        private const val CLOSE_SNAP_THRESHOLD = 0.75f
    }

    // Lawnchair forces the launcher Configuration to LTR. Workspace attaches the overlay
    // edge effect to mEdgeGlowLeft regardless of system locale, so the feed always lives
    // on the LEFT of home and is opened by pulling the left edge (swipe finger right).
    // We mirror that here: panel always slides in from the left and the close gesture
    // is always swipe-left, so behavior matches in both Persian and English builds.
    private inner class PanelLayout(context: Context) : FrameLayout(context) {
        // scaledPagingTouchSlop (~2× regular slop) gives the WebView's async renderer thread
        // enough time to call requestDisallowInterceptTouchEvent(true) before we intercept.
        private val touchSlop = ViewConfiguration.get(context).scaledPagingTouchSlop.toFloat()
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
                    snapAnimator?.cancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isTouchTracking) {
                        val dx = ev.x - startX
                        val dy = ev.y - startY
                        // If the WebView child is on a scrollable element, it will call
                        // requestDisallowInterceptTouchEvent(true) before touchSlop is exceeded,
                        // which prevents this block from running entirely. So we only reach here
                        // when the touch is on a non-scrollable area.
                        // Close = swipe finger left so the panel follows the finger off-screen
                        // to the left (the direction it came from). Same in fa and en builds.
                        val isSwipingToClose = dx < -touchSlop
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

        val wv = WebView(launcher).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    currentLoadHadError = false
                    hideErrorView()
                    startIconRotation()
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
                    stopIconRotation()
                    if (currentLoadHadError) return
                    lastRefreshTime = System.currentTimeMillis()
                    refreshPill?.visibility = View.VISIBLE
                    updateRefreshLabel()
                    view?.evaluateJavascript(HIDE_NAV_JS, null)
                }
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
            }
        }
        contentLayout.addView(wv)
        webView = wv
        panel = panelLayout

        addErrorView(panelLayout)
        addRefreshPill(panelLayout)
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

        mainHandler.removeCallbacks(labelUpdateRunnable)
        mainHandler.postDelayed(labelUpdateRunnable, LABEL_UPDATE_INTERVAL_MS)

        launcher.setLauncherOverlay(this)
    }

    private fun triggerInitialLoadIfNeeded() {
        if (hasLoaded) return
        val wv = webView ?: return
        hasLoaded = true
        wv.loadUrl(YEKJO_URL)
    }

    // Called when the user opens the panel via swipe. Old content stays visible
    // (WebView does not blank during reload) and the spinning icon + last-update
    // pill remain on top until onPageFinished updates them.
    private fun triggerSwipeRefresh() {
        val wv = webView ?: return
        if (!hasLoaded) {
            hasLoaded = true
            wv.loadUrl(YEKJO_URL)
            return
        }
        if (iconAnimator?.isRunning == true) return
        wv.reload()
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

    private fun addRefreshPill(panelLayout: FrameLayout) {
        val context = panelLayout.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Float): Int = (density * value).toInt()

        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = density * 22f
                setColors(intArrayOf(
                    Color.parseColor("#EE0D1B2E"),
                    Color.parseColor("#EE07111F"),
                ))
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(dp(1f), Color.parseColor("#664D9FFF"))
            }
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            elevation = dp(6f).toFloat()
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            // force LTR within the pill so the icon always sits left of the text
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val icon = TextView(context).apply {
            text = "↻"
            setTextColor(Color.parseColor("#82B1FF"))
            textSize = 17f
            includeFontPadding = false
        }

        val label = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#DCE8FF"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            includeFontPadding = false
            setPadding(dp(8f), 0, 0, 0)
        }

        pill.addView(icon)
        pill.addView(label)
        pill.setOnClickListener { userTriggeredRefresh() }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            rightMargin = dp(16f)
            topMargin = dp(16f)
        }
        panelLayout.addView(pill, params)

        pill.setOnApplyWindowInsetsListener { v, insets ->
            val topInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.statusBars()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = dp(4f) + topInset
            v.layoutParams = lp
            insets
        }

        refreshPill = pill
        refreshIcon = icon
        refreshLabel = label
    }

    private fun addErrorView(panelLayout: FrameLayout) {
        val context = panelLayout.context
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

        panelLayout.addView(
            container,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        errorView = container
    }

    private fun showErrorView() {
        errorView?.visibility = View.VISIBLE
        refreshPill?.visibility = View.GONE
    }

    private fun hideErrorView() {
        errorView?.visibility = View.GONE
        // pill visibility is restored by onPageFinished only when load succeeds
    }

    private fun updateRefreshLabel() {
        val label = refreshLabel ?: return
        if (lastRefreshTime == 0L) return
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
        webView?.loadUrl(url)
    }

    private fun updateTabBarSelection() {
        tabNewsButton?.setTextColor(if (currentTabUrl == YEKJO_URL) Color.WHITE else Color.parseColor("#777777"))
        tabMarketsButton?.setTextColor(if (currentTabUrl == YEKJO_MARKETS_URL) Color.WHITE else Color.parseColor("#777777"))
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
        refreshPill = null
        refreshIcon = null
        refreshLabel = null
        errorView = null
        tabBar = null
        tabNewsButton = null
        tabMarketsButton = null
    }

    override fun onActivityStarted() {
        ensurePanelAttached()
        webView?.onResume()
    }

    override fun onActivityResumed() {
        webView?.onResume()
    }

    override fun onActivityPaused() {
        webView?.onPause()
    }

    override fun onActivityStopped() {
        webView?.onPause()
    }

    override fun onActivityDestroyed() {
        mainHandler.removeCallbacks(labelUpdateRunnable)
        iconAnimator?.cancel()
        iconAnimator = null
        webView?.destroy()
        webView = null
        panel = null
        refreshPill = null
        refreshIcon = null
        refreshLabel = null
        errorView = null
        tabBar = null
        tabNewsButton = null
        tabMarketsButton = null
        hasLoaded = false
        lastRefreshTime = 0L
        currentLoadHadError = false
        currentTabUrl = YEKJO_URL
    }

    override fun onScrollInteractionBegin() {
        snapAnimator?.cancel()
        wasOpenAtInteractionStart = currentProgress >= 0.99f
        triggerInitialLoadIfNeeded()
    }

    override fun onScrollInteractionEnd() {
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
