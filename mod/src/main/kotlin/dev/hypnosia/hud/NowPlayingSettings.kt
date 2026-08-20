package dev.hypnosia.hud

import dev.hypnosia.config.HypnosiaClientSettings

object NowPlayingSettings {
    private const val ONLY_WHEN_PLAYING_KEY = "hud.nowplaying.only_when_playing"
    private const val SHOW_COVER_KEY = "hud.nowplaying.show_cover"
    private const val SHOW_CONTROLS_KEY = "hud.nowplaying.show_controls"
    private const val SHOW_PROGRESS_KEY = "hud.nowplaying.show_progress"
    private const val ALPHA_KEY = "hud.nowplaying.alpha"

    private var loaded = false
    private var cachedOnlyWhenPlaying = true
    private var cachedShowCover = true
    private var cachedShowControls = true
    private var cachedShowProgress = true
    private var cachedAlpha = 220

    fun isEnabled(): Boolean = HudModuleSettings.isEnabled(HudModuleSettings.Module.NOW_PLAYING)
    fun setEnabled(v: Boolean) = HudModuleSettings.setEnabled(HudModuleSettings.Module.NOW_PLAYING, v)

    fun onlyWhenPlaying(): Boolean { ensureLoaded(); return cachedOnlyWhenPlaying }
    fun setOnlyWhenPlaying(v: Boolean) { cachedOnlyWhenPlaying = v; loaded = true; HypnosiaClientSettings.set(ONLY_WHEN_PLAYING_KEY, v.toString()) }

    fun showCover(): Boolean { ensureLoaded(); return cachedShowCover }
    fun setShowCover(v: Boolean) { cachedShowCover = v; loaded = true; HypnosiaClientSettings.set(SHOW_COVER_KEY, v.toString()) }

    fun showControls(): Boolean { ensureLoaded(); return cachedShowControls }
    fun setShowControls(v: Boolean) { cachedShowControls = v; loaded = true; HypnosiaClientSettings.set(SHOW_CONTROLS_KEY, v.toString()) }

    fun showProgress(): Boolean { ensureLoaded(); return cachedShowProgress }
    fun setShowProgress(v: Boolean) { cachedShowProgress = v; loaded = true; HypnosiaClientSettings.set(SHOW_PROGRESS_KEY, v.toString()) }

    fun alpha(): Int { ensureLoaded(); return cachedAlpha }
    fun setAlpha(v: Int) { cachedAlpha = v.coerceIn(50, 255); loaded = true; HypnosiaClientSettings.set(ALPHA_KEY, cachedAlpha.toString()) }

    private fun ensureLoaded() {
        if (loaded) return
        cachedOnlyWhenPlaying = HypnosiaClientSettings.string(ONLY_WHEN_PLAYING_KEY, "true").toBooleanStrictOrNull() ?: true
        cachedShowCover = HypnosiaClientSettings.string(SHOW_COVER_KEY, "true").toBooleanStrictOrNull() ?: true
        cachedShowControls = HypnosiaClientSettings.string(SHOW_CONTROLS_KEY, "true").toBooleanStrictOrNull() ?: true
        cachedShowProgress = HypnosiaClientSettings.string(SHOW_PROGRESS_KEY, "true").toBooleanStrictOrNull() ?: true
        cachedAlpha = HypnosiaClientSettings.string(ALPHA_KEY, "220").toIntOrNull() ?: 220
        loaded = true
    }
}
