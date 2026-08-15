package com.example.tvshell.browser

enum class ViewState {
    HOME,
    BROWSER,
    CONTROL_MENU,
    BACK_MENU,
    SETTINGS,
    ERROR
}

data class BrowserState(
    val viewState: ViewState = ViewState.HOME,
    val currentUrl: String? = null,
    val lastSuccessfulUrl: String? = null,
    val title: String? = null,
    val isLoading: Boolean = false,
    val pageError: String? = null,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val remoteAddress: String? = null,
    val remoteToken: String = ""
)
