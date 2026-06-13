package app.lawnchair.yekjo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
    private var isRtl = false
    private var currentProgress = 0f
    private var snapAnimator: ValueAnimator? = null

    companion object {
        private const val YEKJO_URL = "https://yekjoo.ir/"
    }

    private inner class PanelLayout(context: Context) : FrameLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var startX = 0f
        private var startY = 0f
        private var isTouchTracking = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.x
                    startY = ev.y
                    isTouchTracking = false
                    snapAnimator?.cancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isTouchTracking) {
                        val dx = ev.x - startX
                        val dy = ev.y - startY
                        val isSwipingToClose = if (isRtl) dx < -touchSlop else dx > touchSlop
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
                    val progress = if (isRtl) {
                        (1f + dx / w).coerceIn(0f, 1f)
                    } else {
                        (1f - dx / w).coerceIn(0f, 1f)
                    }
                    currentProgress = progress
                    callbacks?.onOverlayScrollChanged(progress)
                    applyPanelTranslation(progress)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isTouchTracking = false
                    snapTo(if (currentProgress >= 0.5f) 1f else 0f)
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

        val wv = WebView(launcher).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            webViewClient = WebViewClient()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            loadUrl(YEKJO_URL)
        }
        panelLayout.addView(wv)
        webView = wv
        panel = panelLayout

        rootView.addView(panelLayout, 0)

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

    private fun applyPanelTranslation(progress: Float) {
        val p = panel ?: return
        val width = (p.parent as? View)?.width?.takeIf { it > 0 } ?: p.width
        p.translationX = if (isRtl) {
            width * (1f - progress)
        } else {
            -width * (1f - progress)
        }
    }

    override fun onAttachedToWindow() {
        ensurePanelAttached()
    }

    override fun onDetachedFromWindow() {
        panel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        panel = null
        webView = null
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
        webView?.destroy()
        webView = null
        panel = null
    }

    override fun onScrollInteractionBegin() {
        snapAnimator?.cancel()
    }

    override fun onScrollInteractionEnd() {
        snapTo(if (currentProgress >= 0.5f) 1f else 0f)
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
        isRtl = rtl
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
            MotionEvent.ACTION_MOVE -> onScrollChange(scrollProgress, isRtl)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onScrollInteractionEnd()
        }
    }

    override fun openOverlay() {
        snapTo(1f)
    }

    override fun hideOverlay(animate: Boolean) {
        snapTo(0f)
    }

    override fun hideOverlay(duration: Int) {
        snapTo(0f)
    }
}
