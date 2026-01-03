package tv.projectivy.plugin.wallpaperprovider.bingwallpaper

import android.content.Context
import android.util.Log
import android.view.SurfaceHolder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperProvider

class WallpaperProviderService : WallpaperProvider() {

    companion object {
        private const val TAG = "M3U8WallpaperProvider"
    }

    override fun onCreateEngine(): WallpaperProvider.VideoEngine {
        return M3U8VideoEngine()
    }

    private inner class M3U8VideoEngine : VideoEngine() {

        private var player: ExoPlayer? = null
        private var currentUrl: String? = null
        
        // ⭐ NEW: Track visibility and surface state
        private var isWallpaperVisible = false
        private var hasSurface = false
        private var savedPosition: Long = 0
        
        // ⭐ NEW: Determine if we should play
        private val shouldPlay: Boolean
            get() = isWallpaperVisible && hasSurface

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            hasSurface = true
            Log.d(TAG, "Surface created")
            
            // Initialize ExoPlayer if not already created
            if (player == null) {
                player = ExoPlayer.Builder(this@WallpaperProviderService).build().apply {
                    repeatMode = Player.REPEAT_MODE_ALL
                    playWhenReady = false  // Don't auto-play yet, wait for visibility
                    
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_READY -> Log.d(TAG, "Player ready")
                                Player.STATE_ENDED -> Log.d(TAG, "Player ended")
                                Player.STATE_BUFFERING -> Log.d(TAG, "Player buffering")
                                Player.STATE_IDLE -> Log.d(TAG, "Player idle")
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Log.e(TAG, "Player error: ${error.message}", error)
                        }
                    })
                    
                    setVideoSurfaceHolder(holder)
                }
                
                // Load wallpaper URL
                loadWallpaper()
            } else {
                // Re-attach surface to existing player
                player?.setVideoSurfaceHolder(holder)
            }
            
            // Restore position when surface is recreated
            if (savedPosition > 0) {
                player?.seekTo(savedPosition)
                Log.d(TAG, "Restored position after surface created: ${savedPosition / 1000}s")
            }
            
            updatePlaybackState()
        }

        private fun loadWallpaper() {
            try {
                PreferencesManager.init(this@WallpaperProviderService)
                val url = PreferencesManager.wallpaperSourceUrl
                
                if (url.isNullOrBlank()) {
                    Log.e(TAG, "No wallpaper URL configured")
                    return
                }
                
                if (url == currentUrl) {
                    Log.d(TAG, "URL unchanged, skipping reload")
                    return
                }
                
                currentUrl = url
                Log.d(TAG, "Loading wallpaper from: $url")
                
                player?.apply {
                    setMediaItem(MediaItem.fromUri(url))
                    prepare()
                }
                
                // Restore saved position if available
                restoreSavedPosition()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading wallpaper", e)
            }
        }

        // ⭐ NEW: Handle visibility changes
        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            isWallpaperVisible = visible
            Log.d(TAG, "Visibility changed: $visible")
            updatePlaybackState()
        }

        // ⭐ MODIFIED: Handle surface changes
        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            Log.d(TAG, "Surface changed: ${width}x${height}")
        }

        // ⭐ MODIFIED: Handle surface destruction
        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            hasSurface = false
            
            // Save current position before surface is destroyed
            savedPosition = player?.currentPosition ?: 0
            Log.d(TAG, "Surface destroyed, saved position: ${savedPosition / 1000}s")
            
            updatePlaybackState()
        }

        // ⭐ NEW: Central playback control based on visibility and surface
        private fun updatePlaybackState() {
            val player = player ?: return
            
            if (shouldPlay) {
                player.play()
                Log.d(TAG, "▶️ Playing (visible: $isWallpaperVisible, surface: $hasSurface, position: ${player.currentPosition / 1000}s)")
            } else {
                player.pause()
                Log.d(TAG, "⏸️ Paused (visible: $isWallpaperVisible, surface: $hasSurface, position: ${player.currentPosition / 1000}s)")
            }
        }

        // ⭐ NEW: Save position to SharedPreferences on destroy
        private fun savePositionToDisk() {
            val position = player?.currentPosition ?: 0
            if (position > 0) {
                try {
                    this@WallpaperProviderService.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putLong("saved_position", position)
                        .putLong("saved_timestamp", System.currentTimeMillis())
                        .putString("saved_url", currentUrl)
                        .apply()
                    Log.d(TAG, "💾 Saved position to disk: ${position / 1000}s")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save position", e)
                }
            }
        }

        // ⭐ NEW: Restore saved position from SharedPreferences
        private fun restoreSavedPosition() {
            try {
                val prefs = this@WallpaperProviderService.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
                val savedPos = prefs.getLong("saved_position", 0)
                val timestamp = prefs.getLong("saved_timestamp", 0)
                val savedUrl = prefs.getString("saved_url", null)
                
                // Only restore if:
                // 1. Position was saved
                // 2. It's for the same URL
                // 3. It was saved within the last hour
                val hoursSince = (System.currentTimeMillis() - timestamp) / (1000L * 60L * 60L)
                
                if (savedPos > 0 && savedUrl == currentUrl && hoursSince < 1) {
                    savedPosition = savedPos
                    player?.seekTo(savedPos)
                    Log.d(TAG, "📂 Restored saved position: ${savedPos / 1000}s (${hoursSince}h old)")
                } else {
                    if (hoursSince >= 1) {
                        Log.d(TAG, "Position too old (${hoursSince}h), starting from beginning")
                    } else if (savedUrl != currentUrl) {
                        Log.d(TAG, "Different URL, starting from beginning")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore position", e)
            }
        }

        override fun onDestroy() {
            Log.d(TAG, "VideoWallpaperEngine destroyed")
            
            // Save position before destroying
            savePositionToDisk()
            
            // Clean up player
            player?.release()
            player = null
            currentUrl = null
            savedPosition = 0
            
            super.onDestroy()
        }
    }
}