package com.example.tvshell.browser

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import com.example.tvshell.TvBrowserApplication
import com.example.tvshell.storage.BrowserPreferences
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController
import org.mozilla.geckoview.ScreenLength

class BrowserController(
    private val preferences: BrowserPreferences,
    private val onStateChanged: (BrowserState) -> Unit
) {
    private val runtime: GeckoRuntime = TvBrowserApplication.instance.geckoRuntime
    var session: GeckoSession? = null
        private set

    private var currentGeckoView: GeckoView? = null

    private var currentState = BrowserState(
        remoteToken = preferences.remoteToken,
        lastSuccessfulUrl = preferences.lastSuccessfulUrl
    )

    init {
        initSession()
    }

    private fun initSession() {
        val preset = UserAgentPreset.fromId(preferences.userAgentPreset)
        val sessionBuilder = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .useTrackingProtection(false)
            .userAgentMode(preset.userAgentMode)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .suspendMediaWhenInactive(false)
        if (preset.override != null) {
            sessionBuilder.userAgentOverride(preset.override)
        }
        val sessionSettings = sessionBuilder.build()

        session = GeckoSession(sessionSettings).apply {
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLocationChange(
                    session: GeckoSession,
                    url: String?,
                    perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>
                ) {
                    Log.d(TAG, "onLocationChange: $url")
                    if (!isBrowsableUrl(url) && !isBrowsableUrl(currentState.currentUrl)) {
                        return
                    }
                    updateState { it.copy(currentUrl = url) }
                }

                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    updateState { it.copy(canGoBack = canGoBack) }
                }

                override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                    updateState { it.copy(canGoForward = canGoForward) }
                }
            }

            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    Log.d(TAG, "onPageStart: $url")
                    if (!isBrowsableUrl(url) && !isBrowsableUrl(currentState.currentUrl)) {
                        updateState { it.copy(isLoading = false, pageError = null) }
                        return
                    }
                    updateState {
                        it.copy(
                            isLoading = true,
                            currentUrl = url,
                            pageError = null
                        )
                    }
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    Log.d(TAG, "onPageStop: success=$success, url=${session.navigationDelegate}")
                    val finalUrl = currentState.currentUrl
                    if (success && isBrowsableUrl(finalUrl)) {
                        preferences.lastSuccessfulUrl = finalUrl
                        updateState {
                            it.copy(
                                isLoading = false,
                                lastSuccessfulUrl = finalUrl,
                                pageError = null
                            )
                        }
                    } else if (!success) {
                        updateState {
                            it.copy(
                                isLoading = false,
                                pageError = finalUrl ?: ""
                            )
                        }
                    } else {
                        updateState { it.copy(isLoading = false) }
                    }
                }
            }

            contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onTitleChange(session: GeckoSession, title: String?) {
                    updateState { it.copy(title = title) }
                }

                override fun onCrash(session: GeckoSession) {
                    Log.e(TAG, "GeckoSession content process crashed. Recovering...")
                    recoverSession()
                }
            }

            open(runtime)
        }

        currentGeckoView?.let { gv ->
            session?.let { s -> gv.setSession(s) }
        }
    }

    fun attachGeckoView(geckoView: GeckoView) {
        currentGeckoView = geckoView
        session?.let { geckoView.setSession(it) }
    }

    fun loadUrl(inputUrl: String) {
        val normalizedUrl = normalizeUrl(inputUrl)
        if (!isBrowsableUrl(normalizedUrl)) {
            return
        }
        val openSession = session
        if (openSession == null || !openSession.isOpen) {
            initSession()
        }
        updateState {
            it.copy(
                viewState = ViewState.BROWSER,
                currentUrl = normalizedUrl,
                isLoading = true,
                pageError = null
            )
        }
        session?.loadUri(normalizedUrl)
    }

    fun closePage() {
        val openSession = session
        try {
            openSession?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            openSession?.setActive(false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            currentGeckoView?.releaseSession()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            openSession?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        session = null
        updateState {
            it.copy(
                viewState = ViewState.HOME,
                currentUrl = null,
                title = null,
                isLoading = false,
                pageError = null,
                canGoBack = false,
                canGoForward = false
            )
        }
    }

    fun reload() {
        if (!isBrowsableUrl(currentState.currentUrl)) {
            return
        }
        session?.reload()
    }

    fun goBack() {
        if (currentState.canGoBack) {
            session?.goBack()
        }
    }

    fun goForward() {
        if (currentState.canGoForward) {
            session?.goForward()
        }
    }

    fun ensureSession() {
        val openSession = session
        if (openSession == null || !openSession.isOpen) {
            return
        }
        currentGeckoView?.let { view ->
            if (view.session != openSession) {
                view.setSession(openSession)
            }
        }
    }

    fun pasteText(text: String) {
        if (text.isEmpty()) return
        val openSession = session ?: return
        val attrs = EditorInfo()
        val input = try {
            openSession.textInput.onCreateInputConnection(attrs)
                ?: currentGeckoView?.onCreateInputConnection(attrs)
        } catch (e: Exception) {
            Log.w(TAG, "pasteText: no input connection", e)
            null
        }
        if (input != null) {
            val task = Runnable {
                try {
                    input.beginBatchEdit()
                    input.finishComposingText()
                    input.commitText(text, 1)
                    input.endBatchEdit()
                } catch (e: Exception) {
                    Log.w(TAG, "pasteText: commitText failed", e)
                    insertTextInPage(text)
                }
            }
            val handler = input.handler
            if (handler != null) {
                handler.post(task)
            } else {
                Handler(Looper.getMainLooper()).post(task)
            }
            return
        }
        insertTextInPage(text)
    }

    private fun insertTextInPage(text: String) {
        val quoted = JSONObject.quote(text)
        session?.loadUri(
            "javascript:void((function(){var t=$quoted;var e=document.activeElement;if(!e)return;" +
                "if(e.isContentEditable){document.execCommand('insertText',false,t);return;}" +
                "if('value' in e){var s=e.selectionStart,n=e.selectionEnd,v=String(e.value||'');" +
                "if(s==null)s=v.length;if(n==null)n=s;" +
                "e.value=v.slice(0,s)+t+v.slice(n);" +
                "try{e.selectionStart=e.selectionEnd=s+t.length;}catch(x){}" +
                "e.dispatchEvent(new Event('input',{bubbles:true}));}})())"
        )
    }

    fun clickAt(x: Float, y: Float) {
        val view = currentGeckoView ?: return
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    fun scroll(direction: String, viewportHeightPx: Int = 1080) {
        val scrollAmount = viewportHeightPx * 0.70f
        val deltaY = if (direction.equals("up", ignoreCase = true)) -scrollAmount else scrollAmount
        scrollByPixels(0f, deltaY, smooth = true)
    }

    fun scrollByPixels(deltaX: Float, deltaY: Float, smooth: Boolean = false) {
        if (deltaX == 0f && deltaY == 0f) return
        session?.panZoomController?.scrollBy(
            ScreenLength.fromPixels(deltaX.toDouble()),
            ScreenLength.fromPixels(deltaY.toDouble()),
            if (smooth) {
                PanZoomController.SCROLL_BEHAVIOR_SMOOTH
            } else {
                PanZoomController.SCROLL_BEHAVIOR_AUTO
            }
        )
    }

    fun updateRemoteInfo(address: String?, token: String) {
        updateState {
            it.copy(
                remoteAddress = address,
                remoteToken = token
            )
        }
    }

    private fun recoverSession() {
        try {
            currentGeckoView?.releaseSession()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            session?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        initSession()
        val targetUrl = currentState.currentUrl?.takeIf { isBrowsableUrl(it) }
        if (isBrowsableUrl(targetUrl)) {
            loadUrl(targetUrl!!)
        }
    }

    fun applyUserAgent() {
        recoverSession()
    }

    fun destroy() {
        try {
            session?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        session = null
        currentGeckoView = null
    }

    fun getCurrentState(): BrowserState = currentState

    private fun updateState(reducer: (BrowserState) -> BrowserState) {
        currentState = reducer(currentState)
        onStateChanged(currentState)
    }

    companion object {
        private const val TAG = "BrowserController"

        fun isBrowsableUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val trimmed = url.trim()
            return !trimmed.equals("about:blank", ignoreCase = true) &&
                !trimmed.startsWith("about:blank?", ignoreCase = true) &&
                !trimmed.startsWith("about:blank#", ignoreCase = true) &&
                !trimmed.startsWith("javascript:", ignoreCase = true)
        }

        fun normalizeUrl(input: String): String {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return ""
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("about:", ignoreCase = true)
            ) {
                return trimmed
            }
            return "http://$trimmed"
        }
    }
}
