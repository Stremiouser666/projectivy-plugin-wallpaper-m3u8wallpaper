package tv.projectivy.plugin.wallpaperprovider.bingwallpaper

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

class WallpaperProviderService : Service() {

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private val binder = object : IWallpaperProviderService.Stub() {

        override fun getWallpapers(event: Event?): MutableList<Wallpaper> {

            // Get the M3U8 URL from preferences or use default
            val hlsUrl = PreferencesManager.wallpaperSourceUrl 
                ?: "https://example.com/stream/playlist.m3u8"

            Log.d("BingWallpaper", "Sending HLS wallpaper: $hlsUrl")

            return mutableListOf(
                Wallpaper(
                    uri = hlsUrl,
                    type = WallpaperType.VIDEO,
                    displayMode = WallpaperDisplayMode.CROP,
                    title = "M3U8 Video Wallpaper"
                )
            )
        }

        override fun getPreferences(): String {
            return PreferencesManager.export()
        }

        override fun setPreferences(params: String) {
            PreferencesManager.import(params)
        }
    }
}