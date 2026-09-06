package com.tooniboy

import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TooniboyPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Tooniboy())
        registerExtractorAPI(Zephyrflick())
        registerExtractorAPI(Abyss())
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(Cloudy())
        registerExtractorAPI(GDMirrorbot())
        registerExtractorAPI(EmTurboVid())
        registerExtractorAPI(VidMolyNet())
        registerExtractorAPI(Blakite())

        this.openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = TooniboySettingsFragment(this)
            frag.show(activity.supportFragmentManager, "tooniboy_settings")
        }
    }
}
