package com.example.tvshell

import android.app.Application
import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class TvBrowserApplication : Application() {

    override fun attachBaseContext(base: Context) {
        val language = base.getSharedPreferences("tv_browser_prefs", MODE_PRIVATE)
            .getString("key_app_language", LocaleHelper.SYSTEM)
        super.attachBaseContext(LocaleHelper.wrap(base, language))
    }

    private var _geckoRuntime: GeckoRuntime? = null

    val geckoRuntime: GeckoRuntime
        get() {
            val existing = _geckoRuntime
            if (existing != null) return existing
            synchronized(this) {
                val again = _geckoRuntime
                if (again != null) return again
                val settings = GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .consoleOutput(false)
                    .remoteDebuggingEnabled(false)
                    .forceUserScalableEnabled(true)
                    .build()
                val created = GeckoRuntime.create(this, settings)
                _geckoRuntime = created
                return created
            }
        }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: TvBrowserApplication
            private set
    }
}
