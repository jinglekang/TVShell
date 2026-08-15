package com.example.tvshell.remote

data class RemoteStatus(
    val connected: Boolean = true,
    val currentUrl: String? = null,
    val title: String? = null,
    val loading: Boolean = false,
    val pageOpen: Boolean = false,
    val language: String = "zh"
)

interface RemoteCommandListener {
    fun onOpenUrl(url: String)
    fun onPasteText(text: String)
    fun onScroll(direction: String) // "up" or "down"
    fun onRemoteKey(key: String, down: Boolean)
    fun onHistoryBack()
    fun onHistoryForward()
    fun onReload()
    fun onShowMenu()
    fun onShowHome()
    fun onShowSettings()
    fun getStatus(): RemoteStatus
}
