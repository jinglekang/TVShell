package com.example.tvshell.browser

import org.mozilla.geckoview.GeckoSessionSettings

enum class UserAgentPreset(
    val id: String,
    val labelRes: Int,
    val userAgentMode: Int,
    val override: String?
) {
    ANDROID_TV(
        id = "android_tv",
        labelRes = com.example.tvshell.R.string.ua_android_tv,
        userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_MOBILE,
        override = "Mozilla/5.0 (Linux; Android 9; SHIELD Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    ),
    CHROME_WINDOWS(
        id = "chrome_windows",
        labelRes = com.example.tvshell.R.string.ua_chrome_desktop,
        userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_DESKTOP,
        override = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    ),
    FIREFOX_WINDOWS(
        id = "firefox_windows",
        labelRes = com.example.tvshell.R.string.ua_firefox_desktop,
        userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_DESKTOP,
        override = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0"
    ),
    SAFARI_MAC(
        id = "safari_mac",
        labelRes = com.example.tvshell.R.string.ua_safari_desktop,
        userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_DESKTOP,
        override = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15"
    ),
    CHROME_ANDROID(
        id = "chrome_android",
        labelRes = com.example.tvshell.R.string.ua_chrome_phone,
        userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_MOBILE,
        override = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    ),
    SAFARI_IOS(
        id = "safari_ios",
        labelRes = com.example.tvshell.R.string.ua_safari_phone,
        userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_MOBILE,
        override = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"
    ),
    ;

    companion object {
        val DEFAULT = ANDROID_TV

        fun fromId(id: String?): UserAgentPreset {
            return values().firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}
