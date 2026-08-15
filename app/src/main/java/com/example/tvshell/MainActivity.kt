package com.example.tvshell

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.tvshell.browser.BrowserController
import com.example.tvshell.browser.BrowserState
import com.example.tvshell.browser.UserAgentPreset
import com.example.tvshell.browser.ViewState
import com.example.tvshell.remote.LocalAddress
import com.example.tvshell.remote.NetworkAddressProvider
import com.example.tvshell.remote.QrCodeGenerator
import com.example.tvshell.remote.RemoteCommandListener
import com.example.tvshell.remote.RemoteServer
import com.example.tvshell.remote.RemoteStatus
import com.example.tvshell.storage.BrowserPreferences
import org.mozilla.geckoview.GeckoView

private const val STATE_VIEW = "state_view"
private const val POINTER_TAP_DP = 14f
private const val POINTER_HOLD_DELAY_MS = 160L
private const val POINTER_CRUISE_DP_PER_SEC = 360f
private const val POINTER_MAX_DP_PER_SEC = 780f
private const val POINTER_RAMP_SEC = 0.55f
private const val POINTER_HIDE_DELAY_MS = 3000L

class MainActivity : AppCompatActivity(), RemoteCommandListener {

    private lateinit var preferences: BrowserPreferences
    private lateinit var browserController: BrowserController
    private lateinit var networkProvider: NetworkAddressProvider
    private var remoteServer: RemoteServer? = null

    // UI Views
    private lateinit var geckoView: GeckoView
    private lateinit var pagePointerLayer: FrameLayout
    private lateinit var pagePointerCursor: View
    private lateinit var pagePointerHint: TextView
    private lateinit var homeView: LinearLayout
    private lateinit var menuOverlay: FrameLayout
    private lateinit var backMenuOverlay: FrameLayout
    private lateinit var settingsView: LinearLayout
    private lateinit var errorView: LinearLayout
    private lateinit var exitDialog: FrameLayout

    // Home elements
    private lateinit var etUrl: EditText
    private lateinit var btnVisit: ImageButton
    private lateinit var btnHomePhoneRemote: Button
    private lateinit var btnHomeSettings: Button

    // Phone remote
    private lateinit var ivMenuQr: ImageView
    private lateinit var tvMenuRemoteUrl: TextView
    private lateinit var btnMenuClose: Button

    // Back menu
    private lateinit var tvBackPageTitle: TextView
    private lateinit var tvBackPageUrl: TextView
    private lateinit var btnBackHistoryBack: Button
    private lateinit var btnBackHistoryForward: Button
    private lateinit var btnBackChangeUrl: Button
    private lateinit var btnBackReload: Button
    private lateinit var btnBackSettings: Button
    private lateinit var btnBackExit: Button

    // Settings elements
    private lateinit var etSettingsStartUrl: EditText
    private lateinit var tvSettingsIpRemote: TextView
    private lateinit var tvSettingsSysInfo: TextView
    private lateinit var btnSettingsSaveUrl: Button
    private lateinit var btnSettingsClearUrl: ImageButton
    private lateinit var btnSettingsRegenToken: Button
    private lateinit var btnSettingsBack: Button
    private lateinit var spinnerSettingsUa: Spinner
    private lateinit var spinnerSettingsNic: Spinner
    private lateinit var spinnerSettingsLanguage: Spinner

    // Error elements
    private lateinit var tvErrorUrl: TextView
    private lateinit var tvErrorMsg: TextView
    private lateinit var btnErrorRetry: Button
    private lateinit var btnErrorChangeUrl: Button
    private lateinit var btnErrorPhoneRemote: Button

    // Exit Dialog elements
    private lateinit var btnExitCancel: Button
    private lateinit var btnExitConfirm: Button

    private var currentIp: String? = null
    private var lastActiveViewState: ViewState = ViewState.HOME
    private var nicAddresses: List<LocalAddress> = emptyList()
    private var pointerX = 0f
    private var pointerY = 0f
    private var pointerInitialized = false
    private var pointerDirX = 0
    private var pointerDirY = 0
    private var pointerVelX = 0f
    private var pointerVelY = 0f
    private var lastPointerTick = 0L
    private var pointerChromeVisible = true
    private val pointerHideRunnable = Runnable { hidePointerChrome() }
    private val pointerHoldRunnable = Runnable { beginPointerHold() }
    private val pointerMoveRunnable = object : Runnable {
        override fun run() {
            if (currentState() != ViewState.BROWSER || (pointerDirX == 0 && pointerDirY == 0)) {
                pointerVelX = 0f
                pointerVelY = 0f
                lastPointerTick = 0L
                return
            }
            advancePointer()
            pagePointerLayer.postOnAnimation(this)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val language = BrowserPreferences.getInstance(newBase).appLanguage
        super.attachBaseContext(LocaleHelper.wrap(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep TV screen awake
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackPressed()
                }
            }
        )

        preferences = BrowserPreferences.getInstance(this)
        initViews()
        initBrowser()
        initNetworkAndServer()
        setupListeners()

        val restored = savedInstanceState?.getString(STATE_VIEW)?.let {
            runCatching { ViewState.valueOf(it) }.getOrNull()
        }
        val savedUrl = preferences.lastSuccessfulUrl
        if (restored != null) {
            if (BrowserController.isBrowsableUrl(savedUrl) && restored != ViewState.HOME) {
                browserController.loadUrl(savedUrl!!)
            }
            showViewState(restored)
        } else if (BrowserController.isBrowsableUrl(savedUrl)) {
            browserController.loadUrl(savedUrl!!)
        } else {
            showViewState(ViewState.HOME)
            etUrl.requestFocus()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_VIEW, currentState().name)
    }

    private fun initViews() {
        geckoView = findViewById(R.id.gecko_view)
        pagePointerLayer = findViewById(R.id.page_pointer_layer)
        pagePointerCursor = findViewById(R.id.page_pointer_cursor)
        pagePointerHint = findViewById(R.id.page_pointer_hint)
        homeView = findViewById(R.id.home_view)
        menuOverlay = findViewById(R.id.menu_overlay)
        backMenuOverlay = findViewById(R.id.back_menu_overlay)
        settingsView = findViewById(R.id.settings_view)
        errorView = findViewById(R.id.error_view)
        exitDialog = findViewById(R.id.exit_dialog)

        // Home
        etUrl = findViewById(R.id.et_url)
        etUrl.showSoftInputOnFocus = false
        btnVisit = findViewById(R.id.btn_visit)
        btnHomePhoneRemote = findViewById(R.id.btn_home_phone_remote)
        btnHomeSettings = findViewById(R.id.btn_home_settings)

        // Phone remote
        ivMenuQr = findViewById(R.id.iv_menu_qr)
        tvMenuRemoteUrl = findViewById(R.id.tv_menu_remote_url)
        btnMenuClose = findViewById(R.id.btn_menu_close)

        // Back menu
        tvBackPageTitle = findViewById(R.id.tv_back_page_title)
        tvBackPageUrl = findViewById(R.id.tv_back_page_url)
        btnBackHistoryBack = findViewById(R.id.btn_back_history_back)
        btnBackHistoryForward = findViewById(R.id.btn_back_history_forward)
        btnBackChangeUrl = findViewById(R.id.btn_back_change_url)
        btnBackReload = findViewById(R.id.btn_back_reload)
        btnBackSettings = findViewById(R.id.btn_back_settings)
        btnBackExit = findViewById(R.id.btn_back_exit)

        // Settings
        etSettingsStartUrl = findViewById(R.id.et_settings_start_url)
        etSettingsStartUrl.showSoftInputOnFocus = false
        tvSettingsIpRemote = findViewById(R.id.tv_settings_ip_remote)
        tvSettingsSysInfo = findViewById(R.id.tv_settings_sys_info)
        btnSettingsSaveUrl = findViewById(R.id.btn_settings_save_url)
        btnSettingsClearUrl = findViewById(R.id.btn_settings_clear_url)
        btnSettingsRegenToken = findViewById(R.id.btn_settings_regen_token)
        btnSettingsBack = findViewById(R.id.btn_settings_back)
        spinnerSettingsUa = findViewById(R.id.spinner_settings_ua)
        spinnerSettingsNic = findViewById(R.id.spinner_settings_nic)
        spinnerSettingsLanguage = findViewById(R.id.spinner_settings_language)

        // Error
        tvErrorUrl = findViewById(R.id.tv_error_url)
        tvErrorMsg = findViewById(R.id.tv_error_msg)
        btnErrorRetry = findViewById(R.id.btn_error_retry)
        btnErrorChangeUrl = findViewById(R.id.btn_error_change_url)
        btnErrorPhoneRemote = findViewById(R.id.btn_error_phone_remote)

        // Exit
        btnExitCancel = findViewById(R.id.btn_exit_cancel)
        btnExitConfirm = findViewById(R.id.btn_exit_confirm)
    }

    private fun initBrowser() {
        browserController = BrowserController(
            preferences = preferences,
            onStateChanged = { state ->
                runOnUiThread {
                    onBrowserStateUpdated(state)
                }
            }
        )
        browserController.attachGeckoView(geckoView)
    }

    private fun initNetworkAndServer() {
        networkProvider = NetworkAddressProvider(this) {
            runOnUiThread { refreshRemoteAddress() }
        }
        networkProvider.startListening()

        remoteServer = RemoteServer(
            context = this,
            port = 8765,
            tokenProvider = { preferences.remoteToken },
            listener = this
        )
        remoteServer?.start()

        refreshRemoteAddress()
    }

    private fun updateRemoteEndpoints() {
        val ip = currentIp ?: "127.0.0.1"
        val token = preferences.remoteToken
        val fullRemoteUrl = "http://$ip:8765/?token=$token"
        val displayUrl = "http://$ip:8765"

        tvMenuRemoteUrl.text = displayUrl
        tvSettingsIpRemote.text = getString(R.string.remote_address, displayUrl)

        // Generate QR code for mobile scanning
        val qrBitmap = QrCodeGenerator.generateQrBitmap(
            content = fullRemoteUrl,
            width = 512,
            height = 512
        )
        if (qrBitmap != null) {
            ivMenuQr.setImageBitmap(qrBitmap)
        }

        browserController.updateRemoteInfo(displayUrl, token)
    }

    private fun setupListeners() {
        // Home
        etUrl.setOnClickListener { showKeyboard(etUrl) }
        etSettingsStartUrl.setOnClickListener { showKeyboard(etSettingsStartUrl) }

        btnVisit.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                hideKeyboard()
                browserController.loadUrl(url)
            }
        }

        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val url = etUrl.text.toString().trim()
                if (url.isNotEmpty()) {
                    hideKeyboard()
                    browserController.loadUrl(url)
                    true
                } else false
            } else false
        }

        btnHomePhoneRemote.setOnClickListener {
            showViewState(ViewState.CONTROL_MENU)
        }

        btnHomeSettings.setOnClickListener {
            showViewState(ViewState.SETTINGS)
        }

        btnMenuClose.setOnClickListener {
            if (lastActiveViewState == ViewState.BROWSER) {
                resumePageOrHome()
            } else {
                showViewState(lastActiveViewState)
            }
        }

        btnBackHistoryBack.setOnClickListener {
            browserController.goBack()
            showViewState(ViewState.BROWSER)
        }

        btnBackHistoryForward.setOnClickListener {
            browserController.goForward()
            showViewState(ViewState.BROWSER)
        }

        btnBackChangeUrl.setOnClickListener {
            showViewState(ViewState.HOME)
            etUrl.requestFocus()
        }

        btnBackReload.setOnClickListener {
            reloadPageOrHome()
        }

        btnBackSettings.setOnClickListener {
            showViewState(ViewState.SETTINGS)
        }

        btnBackExit.setOnClickListener {
            showExitDialog(true)
        }

        // Settings
        btnSettingsBack.setOnClickListener {
            showViewState(lastActiveViewState)
        }

        btnSettingsSaveUrl.setOnClickListener {
            val input = BrowserController.normalizeUrl(etSettingsStartUrl.text.toString())
            if (!BrowserController.isBrowsableUrl(input)) {
                preferences.clearLastUrl()
                showSettingsNotice(getString(R.string.msg_start_url_cleared))
            } else {
                preferences.lastSuccessfulUrl = input
                showSettingsNotice(getString(R.string.msg_start_url_saved) + "\n" + input)
            }
            updateSettingsDisplay()
        }

        btnSettingsClearUrl.setOnClickListener {
            preferences.clearLastUrl()
            updateSettingsDisplay()
            showSettingsNotice(getString(R.string.msg_start_url_cleared))
        }

        btnSettingsRegenToken.setOnClickListener {
            preferences.regenerateRemoteToken()
            updateRemoteEndpoints()
            showSettingsNotice(getString(R.string.msg_remote_token_regenerated))
        }

        spinnerSettingsUa.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val presets = UserAgentPreset.values()
                if (position !in presets.indices) return
                val preset = presets[position]
                if (preset.id == preferences.userAgentPreset) return
                preferences.userAgentPreset = preset.id
                browserController.applyUserAgent()
                refreshSettingsSysInfo()
                showSettingsNotice(getString(R.string.msg_ua_updated, getString(preset.labelRes)))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        spinnerSettingsNic.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (position !in nicAddresses.indices) return
                val chosen = nicAddresses[position]
                val alreadyUsing = chosen.ip == currentIp &&
                    (preferences.preferredNetworkInterface == null ||
                        preferences.preferredNetworkInterface == chosen.interfaceName)
                if (alreadyUsing) {
                    preferences.preferredNetworkInterface = chosen.interfaceName
                    return
                }
                preferences.preferredNetworkInterface = chosen.interfaceName
                currentIp = chosen.ip
                updateRemoteEndpoints()
                showSettingsNotice(getString(R.string.msg_nic_updated, chosen.label(this@MainActivity)))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        spinnerSettingsLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (position !in LocaleHelper.OPTIONS.indices) return
                val chosen = LocaleHelper.OPTIONS[position]
                if (chosen == preferences.appLanguage) return
                preferences.appLanguage = chosen
                recreate()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // Error
        btnErrorRetry.setOnClickListener {
            reloadPageOrHome()
        }

        btnErrorChangeUrl.setOnClickListener {
            showViewState(ViewState.HOME)
            etUrl.requestFocus()
        }

        btnErrorPhoneRemote.setOnClickListener {
            showViewState(ViewState.CONTROL_MENU)
        }

        // Exit Dialog
        btnExitCancel.setOnClickListener {
            showExitDialog(false)
        }

        btnExitConfirm.setOnClickListener {
            finishAndRemoveTask()
        }
    }

    private fun onBrowserStateUpdated(state: BrowserState) {
        val pageUrl = state.currentUrl.takeIf { BrowserController.isBrowsableUrl(it) }
        tvBackPageTitle.text = if (pageUrl != null) state.title?.takeIf { it.isNotBlank() }.orEmpty() else ""
        tvBackPageUrl.text = pageUrl.orEmpty()
        updateHistoryButtons(state)

        if (state.pageError != null) {
            tvErrorUrl.text = pageUrl.orEmpty()
            tvErrorMsg.text = getString(R.string.msg_error_hint)
            showViewState(ViewState.ERROR)
        } else if (state.viewState == ViewState.BROWSER && pageUrl != null) {
            if (currentState() != ViewState.CONTROL_MENU &&
                currentState() != ViewState.BACK_MENU &&
                currentState() != ViewState.SETTINGS &&
                currentState() != ViewState.ERROR
            ) {
                showViewState(ViewState.BROWSER)
            }
        }
    }

    private fun showViewState(viewState: ViewState) {
        val resolved =
            if (viewState == ViewState.BROWSER && !hasBrowsablePage()) ViewState.HOME else viewState

        if (resolved != ViewState.CONTROL_MENU &&
            resolved != ViewState.BACK_MENU &&
            resolved != ViewState.SETTINGS
        ) {
            lastActiveViewState = resolved
        }

        if (resolved == ViewState.HOME &&
            BrowserController.isBrowsableUrl(browserController.getCurrentState().currentUrl)
        ) {
            browserController.closePage()
        }

        val showPage = hasBrowsablePage()
        homeView.visibility = if (resolved == ViewState.HOME) View.VISIBLE else View.GONE
        geckoView.visibility =
            if ((resolved == ViewState.BROWSER ||
                    resolved == ViewState.BACK_MENU ||
                    resolved == ViewState.CONTROL_MENU) && showPage
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
        menuOverlay.visibility =
            if (resolved == ViewState.CONTROL_MENU) View.VISIBLE else View.GONE
        backMenuOverlay.visibility =
            if (resolved == ViewState.BACK_MENU) View.VISIBLE else View.GONE
        settingsView.visibility = if (resolved == ViewState.SETTINGS) View.VISIBLE else View.GONE
        errorView.visibility = if (resolved == ViewState.ERROR) View.VISIBLE else View.GONE

        pagePointerLayer.visibility =
            if (resolved == ViewState.BROWSER) View.VISIBLE else View.GONE
        if (resolved == ViewState.BROWSER) {
            pagePointerLayer.post { preparePointer() }
        } else {
            stopPointerMovement()
        }

        val browsing = resolved == ViewState.BROWSER
        geckoView.isFocusable = browsing
        geckoView.isFocusableInTouchMode = browsing
        if (!browsing && geckoView.hasFocus()) {
            geckoView.clearFocus()
        }

        menuOverlay.isClickable = resolved == ViewState.CONTROL_MENU
        backMenuOverlay.isClickable = resolved == ViewState.BACK_MENU
        exitDialog.isClickable = exitDialog.isVisible
        setLayerFocusEnabled(homeView, resolved == ViewState.HOME)
        setLayerFocusEnabled(menuOverlay, resolved == ViewState.CONTROL_MENU)
        setLayerFocusEnabled(backMenuOverlay, resolved == ViewState.BACK_MENU)
        setLayerFocusEnabled(settingsView, resolved == ViewState.SETTINGS)
        setLayerFocusEnabled(errorView, resolved == ViewState.ERROR)
        if (resolved == ViewState.BACK_MENU) {
            updateHistoryButtons(browserController.getCurrentState())
        }

        if (browsing) {
            hideSystemBars()
            return
        }

        if (resolved == ViewState.SETTINGS) {
            updateSettingsDisplay()
        }

        requestFocusFor(resolved)
    }

    private fun updateHistoryButtons(state: BrowserState) {
        btnBackHistoryBack.isEnabled = state.canGoBack
        btnBackHistoryBack.isFocusable = state.canGoBack
        btnBackHistoryForward.isEnabled = state.canGoForward
        btnBackHistoryForward.isFocusable = state.canGoForward
        btnBackHistoryBack.alpha = if (state.canGoBack) 1f else 0.4f
        btnBackHistoryForward.alpha = if (state.canGoForward) 1f else 0.4f
    }

    private fun hasBrowsablePage(): Boolean {
        return BrowserController.isBrowsableUrl(browserController.getCurrentState().currentUrl)
    }

    private fun isPageSessionActive(): Boolean {
        val view = currentState()
        return hasBrowsablePage() && view != ViewState.HOME && view != ViewState.ERROR
    }

    private fun resumePageOrHome() {
        if (hasBrowsablePage()) {
            showViewState(ViewState.BROWSER)
        } else {
            showViewState(ViewState.HOME)
        }
    }

    private fun reloadPageOrHome() {
        if (hasBrowsablePage()) {
            showViewState(ViewState.BROWSER)
            browserController.reload()
        } else {
            showViewState(ViewState.HOME)
        }
    }

    private fun currentState(): ViewState {
        return when {
            settingsView.isVisible -> ViewState.SETTINGS
            backMenuOverlay.isVisible -> ViewState.BACK_MENU
            menuOverlay.isVisible -> ViewState.CONTROL_MENU
            errorView.isVisible -> ViewState.ERROR
            homeView.isVisible -> ViewState.HOME
            geckoView.isVisible -> ViewState.BROWSER
            else -> ViewState.HOME
        }
    }

    private fun showSettingsNotice(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun updateSettingsDisplay() {
        val saved = preferences.lastSuccessfulUrl
        if (etSettingsStartUrl.text.toString() != (saved ?: "")) {
            etSettingsStartUrl.setText(saved ?: "")
        }

        bindLanguageSpinner()
        bindUaSpinner()
        bindNicSpinner()
        refreshSettingsSysInfo()
    }

    private fun refreshSettingsSysInfo() {
        val supportedAbis = Build.SUPPORTED_ABIS.joinToString(", ")
        val ua = UserAgentPreset.fromId(preferences.userAgentPreset)
        tvSettingsSysInfo.text = buildString {
            append("• ${getString(R.string.about_android, "${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")}\n")
            append("• ${getString(R.string.about_device, Build.MANUFACTURER, Build.MODEL)}\n")
            append("• ${getString(R.string.about_cpu, supportedAbis)}\n")
            append("• ${getString(R.string.about_engine)}\n")
            append("• ${getString(R.string.about_display_mode, getString(ua.labelRes))}\n")
            append("• ${getString(R.string.about_remote_port)}\n")
            append("• ${getString(R.string.about_keep_on)}\n")
            append("• ${getString(R.string.about_version, "1.0.0")}")
        }
    }

    private fun refreshRemoteAddress() {
        val selected = networkProvider.resolveAddress(preferences.preferredNetworkInterface)
        currentIp = selected?.ip
        updateRemoteEndpoints()
        if (::spinnerSettingsNic.isInitialized && settingsView.isVisible) {
            bindNicSpinner()
        }
    }

    private fun bindLanguageSpinner() {
        val labels = LocaleHelper.OPTIONS.map { getString(LocaleHelper.labelRes(it)) }
        spinnerSettingsLanguage.adapter = createSpinnerAdapter(labels)
        val index = LocaleHelper.OPTIONS.indexOf(preferences.appLanguage).takeIf { it >= 0 } ?: 0
        spinnerSettingsLanguage.setSelection(index, false)
    }

    private fun bindUaSpinner() {
        val presets = UserAgentPreset.values()
        val labels = presets.map { getString(it.labelRes) }
        spinnerSettingsUa.adapter = createSpinnerAdapter(labels)
        val index = presets.indexOf(UserAgentPreset.fromId(preferences.userAgentPreset))
        if (index >= 0 && spinnerSettingsUa.selectedItemPosition != index) {
            spinnerSettingsUa.setSelection(index, false)
        }
    }

    private fun bindNicSpinner() {
        nicAddresses = networkProvider.listLocalIpv4Addresses()
        val labels = if (nicAddresses.isEmpty()) {
            listOf(getString(R.string.msg_no_lan_address))
        } else {
            nicAddresses.map { it.label(this) }
        }
        spinnerSettingsNic.adapter = createSpinnerAdapter(labels)
        spinnerSettingsNic.isEnabled = nicAddresses.size > 1
        val selected = networkProvider.resolveAddress(preferences.preferredNetworkInterface)
        val index = selected?.let { nic ->
            nicAddresses.indexOfFirst {
                it.interfaceName == nic.interfaceName && it.ip == nic.ip
            }
        } ?: 0
        if (index >= 0) {
            spinnerSettingsNic.setSelection(index, false)
        }
    }

    private fun createSpinnerAdapter(items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, R.layout.item_spinner_tv, items) {
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                val highlighted = parent is AdapterView<*> &&
                    position == (parent as AdapterView<*>).selectedItemPosition
                view.isSelected = highlighted
                view.isActivated = highlighted
                return view
            }
        }.apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
    }

    private fun showExitDialog(show: Boolean) {
        exitDialog.visibility = if (show) View.VISIBLE else View.GONE
        exitDialog.isClickable = show
        setLayerFocusEnabled(exitDialog, show)
        if (show) {
            btnExitCancel.post { btnExitCancel.requestFocus() }
        } else {
            requestFocusFor(currentState())
        }
    }

    private fun requestFocusFor(viewState: ViewState) {
        val target = when (viewState) {
            ViewState.HOME -> etUrl
            ViewState.CONTROL_MENU -> btnMenuClose
            ViewState.BACK_MENU ->
                if (browserController.getCurrentState().canGoBack) {
                    btnBackHistoryBack
                } else {
                    btnBackReload
                }
            ViewState.SETTINGS -> btnSettingsBack
            ViewState.ERROR -> btnErrorRetry
            ViewState.BROWSER -> null
        } ?: return

        val host = when (viewState) {
            ViewState.HOME -> homeView
            ViewState.CONTROL_MENU -> menuOverlay
            ViewState.BACK_MENU -> backMenuOverlay
            ViewState.SETTINGS -> settingsView
            ViewState.ERROR -> errorView
            ViewState.BROWSER -> null
        } ?: return

        host.post {
            if (currentState() != viewState) return@post
            if (!target.requestFocus()) {
                host.requestFocus()
            }
            if (target is EditText) {
                hideKeyboard()
            }
        }
    }

    private fun setLayerFocusEnabled(root: View, enabled: Boolean) {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                setLayerFocusEnabled(root.getChildAt(i), enabled)
            }
        }
        if (root is Button || root is ImageButton || root is EditText || root is Spinner) {
            root.isFocusable = enabled
            if (root is EditText || root is Spinner) {
                root.isFocusableInTouchMode = enabled
            }
            if (!enabled && root.hasFocus()) {
                root.clearFocus()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!exitDialog.isVisible && currentState() == ViewState.BROWSER) {
            if (handleBrowserKey(event)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBrowserKey(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            startPointerMovement(event.keyCode)
                        }
                        true
                    }
                    KeyEvent.ACTION_UP -> {
                        stopPointerDirection(event.keyCode)
                        true
                    }
                    else -> false
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (!pointerChromeVisible) {
                        showPointerChrome()
                    } else {
                        browserController.clickAt(pointerX, pointerY)
                        schedulePointerHide()
                    }
                }
                event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
            }
            else -> false
        }
    }

    private fun preparePointer() {
        val width = geckoView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = geckoView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (!pointerInitialized) {
            pointerX = width / 2f
            pointerY = height / 2f
            pointerInitialized = true
        }
        clampPointer()
        updatePointerVisual()
        showPointerChrome()
    }

    private fun startPointerMovement(keyCode: Int) {
        val step = POINTER_TAP_DP * resources.displayMetrics.density
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                pointerDirX = -1
                applyPointerDelta(-step, 0f)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                pointerDirX = 1
                applyPointerDelta(step, 0f)
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                pointerDirY = -1
                applyPointerDelta(0f, -step)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                pointerDirY = 1
                applyPointerDelta(0f, step)
            }
        }
        pagePointerLayer.removeCallbacks(pointerHoldRunnable)
        pagePointerLayer.removeCallbacks(pointerMoveRunnable)
        lastPointerTick = 0L
        showPointerChrome()
        pagePointerLayer.postDelayed(pointerHoldRunnable, POINTER_HOLD_DELAY_MS)
    }

    private fun beginPointerHold() {
        if (currentState() != ViewState.BROWSER || (pointerDirX == 0 && pointerDirY == 0)) {
            return
        }
        val cruise = POINTER_CRUISE_DP_PER_SEC * resources.displayMetrics.density
        if (pointerDirX != 0) pointerVelX = pointerDirX * cruise
        if (pointerDirY != 0) pointerVelY = pointerDirY * cruise
        lastPointerTick = SystemClock.uptimeMillis()
        pagePointerLayer.postOnAnimation(pointerMoveRunnable)
    }

    private fun stopPointerDirection(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (pointerDirX < 0) {
                pointerDirX = 0
                pointerVelX = 0f
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (pointerDirX > 0) {
                pointerDirX = 0
                pointerVelX = 0f
            }
            KeyEvent.KEYCODE_DPAD_UP -> if (pointerDirY < 0) {
                pointerDirY = 0
                pointerVelY = 0f
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (pointerDirY > 0) {
                pointerDirY = 0
                pointerVelY = 0f
            }
        }
        if (pointerDirX == 0 && pointerDirY == 0) {
            lastPointerTick = 0L
            pagePointerLayer.removeCallbacks(pointerHoldRunnable)
            pagePointerLayer.removeCallbacks(pointerMoveRunnable)
            schedulePointerHide()
        }
    }

    private fun stopPointerMovement() {
        pointerDirX = 0
        pointerDirY = 0
        pointerVelX = 0f
        pointerVelY = 0f
        lastPointerTick = 0L
        if (::pagePointerLayer.isInitialized) {
            pagePointerLayer.removeCallbacks(pointerHoldRunnable)
            pagePointerLayer.removeCallbacks(pointerMoveRunnable)
            pagePointerLayer.removeCallbacks(pointerHideRunnable)
        }
    }

    private fun advancePointer() {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastPointerTick).coerceIn(1L, 24L) / 1000f
        lastPointerTick = now
        val density = resources.displayMetrics.density
        val maxSpeed = POINTER_MAX_DP_PER_SEC * density
        val accel = maxSpeed / POINTER_RAMP_SEC
        pointerVelX = if (pointerDirX == 0) {
            0f
        } else {
            (pointerVelX + pointerDirX * accel * dt).coerceIn(-maxSpeed, maxSpeed)
        }
        pointerVelY = if (pointerDirY == 0) {
            0f
        } else {
            (pointerVelY + pointerDirY * accel * dt).coerceIn(-maxSpeed, maxSpeed)
        }
        applyPointerDelta(pointerVelX * dt, pointerVelY * dt)
    }

    private fun applyPointerDelta(dx: Float, dy: Float) {
        val width = geckoView.width.takeIf { it > 0 } ?: return
        val height = geckoView.height.takeIf { it > 0 } ?: return
        val nextX = pointerX + dx
        val nextY = pointerY + dy
        var scrollX = 0f
        var scrollY = 0f
        when {
            dx < 0f && nextX <= 0f -> {
                pointerX = 0f
                scrollX = nextX
            }
            dx > 0f && nextX >= width -> {
                pointerX = width.toFloat()
                scrollX = nextX - width
            }
            else -> pointerX = nextX.coerceIn(0f, width.toFloat())
        }
        when {
            dy < 0f && nextY <= 0f -> {
                pointerY = 0f
                scrollY = nextY
            }
            dy > 0f && nextY >= height -> {
                pointerY = height.toFloat()
                scrollY = nextY - height
            }
            else -> pointerY = nextY.coerceIn(0f, height.toFloat())
        }
        browserController.scrollByPixels(scrollX, scrollY)
        updatePointerVisual()
    }

    private fun clampPointer() {
        val width = geckoView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = geckoView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        pointerX = pointerX.coerceIn(0f, width.toFloat())
        pointerY = pointerY.coerceIn(0f, height.toFloat())
    }

    private fun updatePointerVisual() {
        val half = if (pagePointerCursor.width > 0) pagePointerCursor.width / 2f else {
            9f * resources.displayMetrics.density
        }
        pagePointerCursor.translationX = pointerX - half
        pagePointerCursor.translationY = pointerY - half
    }

    private fun showPointerChrome() {
        if (!::pagePointerCursor.isInitialized) return
        pointerChromeVisible = true
        pagePointerCursor.animate().cancel()
        pagePointerHint.animate().cancel()
        pagePointerCursor.visibility = View.VISIBLE
        pagePointerHint.visibility = View.VISIBLE
        pagePointerCursor.alpha = 1f
        pagePointerHint.alpha = 1f
        schedulePointerHide()
    }

    private fun hidePointerChrome() {
        if (currentState() != ViewState.BROWSER) return
        if (pointerDirX != 0 || pointerDirY != 0) {
            schedulePointerHide()
            return
        }
        pointerChromeVisible = false
        pagePointerCursor.animate().cancel()
        pagePointerHint.animate().cancel()
        pagePointerCursor.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction {
                if (!pointerChromeVisible) {
                    pagePointerCursor.visibility = View.INVISIBLE
                }
            }
            .start()
        pagePointerHint.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction {
                if (!pointerChromeVisible) {
                    pagePointerHint.visibility = View.INVISIBLE
                }
            }
            .start()
    }

    private fun schedulePointerHide() {
        if (!::pagePointerLayer.isInitialized) return
        pagePointerLayer.removeCallbacks(pointerHideRunnable)
        pagePointerLayer.postDelayed(pointerHideRunnable, POINTER_HIDE_DELAY_MS)
    }

    private fun handleBackPressed() {
        if (exitDialog.isVisible) {
            showExitDialog(false)
            return
        }
        when (currentState()) {
            ViewState.BROWSER -> showViewState(ViewState.BACK_MENU)
            ViewState.CONTROL_MENU, ViewState.BACK_MENU, ViewState.SETTINGS -> {
                if (lastActiveViewState == ViewState.BROWSER) {
                    resumePageOrHome()
                } else {
                    showViewState(lastActiveViewState)
                }
            }
            ViewState.ERROR -> showViewState(ViewState.HOME)
            ViewState.HOME -> showExitDialog(true)
        }
    }

    // RemoteCommandListener implementation
    override fun onOpenUrl(url: String) {
        browserController.loadUrl(url)
        showViewState(ViewState.BROWSER)
    }

    override fun onPasteText(text: String) {
        if (text.isEmpty()) return
        if (!isPageSessionActive()) return
        browserController.pasteText(text)
    }

    override fun onScroll(direction: String) {
        browserController.scroll(direction, geckoView.height.takeIf { it > 0 } ?: 1080)
    }

    override fun onRemoteKey(key: String, down: Boolean) {
        if (key == "back") {
            if (down) handleBackPressed()
            return
        }
        val keyCode = when (key) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
            else -> return
        }
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        dispatchKeyEvent(KeyEvent(action, keyCode))
    }

    override fun onHistoryBack() {
        if (!isPageSessionActive()) return
        browserController.goBack()
    }

    override fun onHistoryForward() {
        if (!isPageSessionActive()) return
        browserController.goForward()
    }

    override fun onReload() {
        if (!isPageSessionActive()) return
        browserController.reload()
    }

    override fun onShowMenu() {
        showViewState(ViewState.BACK_MENU)
    }

    override fun onShowHome() {
        showViewState(ViewState.HOME)
    }

    override fun onShowSettings() {
        showViewState(ViewState.SETTINGS)
    }

    override fun getStatus(): RemoteStatus {
        val state = browserController.getCurrentState()
        val pageUrl = state.currentUrl.takeIf { BrowserController.isBrowsableUrl(it) }
            ?: preferences.lastSuccessfulUrl.takeIf { BrowserController.isBrowsableUrl(it) }
        return RemoteStatus(
            connected = true,
            currentUrl = pageUrl,
            title = if (pageUrl != null) state.title else null,
            loading = state.isLoading && pageUrl != null,
            pageOpen = isPageSessionActive(),
            language = LocaleHelper.resolved(this, preferences.appLanguage)
        )
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        val token = currentFocus?.windowToken ?: window.decorView.windowToken
        imm?.hideSoftInputFromWindow(token, 0)
    }

    private fun showKeyboard(target: View) {
        target.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (::browserController.isInitialized) {
            browserController.ensureSession()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkProvider.stopListening()
        remoteServer?.stop()
        browserController.destroy()
    }
}
