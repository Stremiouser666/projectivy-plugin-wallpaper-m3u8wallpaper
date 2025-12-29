package tv.projectivy.plugin.wallpaperprovider.bingwallpaper

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "M3U8Settings"
        private const val TEST_TIMEOUT_MS = 20_000L
        private const val URL_EXPIRY_HOURS = 5L
        private const val PREFS_NAME = "stream_prefs"
        private const val KEY_ORIGINAL_URL = "original_url"
        private const val KEY_M3U8_URL = "m3u8_url"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_SELECTED_QUALITY = "selected_quality"
        private const val KEY_URL_TYPE = "url_type" // NEW: Track URL type
    }

    private var testPlayer: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var testCompleted = false
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val streamPrefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            PreferencesManager.init(this)
        } catch (e: Exception) {
            Log.e(TAG, "PreferencesManager init failed", e)
        }

        setContentView(R.layout.activity_settings)

        val urlInput = findViewById<EditText>(R.id.m3u8_url_input)
        val testButton = findViewById<Button>(R.id.test_button)
        val saveButton = findViewById<Button>(R.id.save_button)

        try {
            urlInput.setText(PreferencesManager.wallpaperSourceUrl ?: "")

            // Check if URL is expired and show warning
            if (isUrlExpired()) {
                val urlType = streamPrefs.getString(KEY_URL_TYPE, "unknown")
                toast("⚠️ Saved URL may be expired (${getHoursSinceExtraction()}h old, type: $urlType)")
            }
        } catch (e: Exception) {
            toast("Error loading saved URL: ${e.message}")
        }

        testButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isBlank()) {
                toast("Enter a URL")
                return@setOnClickListener
            }

            when {
                isYouTubeUrl(url) -> {
                    showYouTubeQualityDialog(url) { _ ->
                        toast("Extracting YouTube stream...")
                        extractYouTubeM3U8(url) { result ->
                            when {
                                result.success && result.m3u8Url != null -> {
                                    toast("✅ Extracted! Testing stream...")
                                    urlInput.setText(result.m3u8Url)
                                    saveOriginalUrl(url, result.m3u8Url, "youtube")
                                    testStream(result.m3u8Url)
                                }
                                else -> {
                                    showDebugDialog("❌ Extraction Failed\n\n${result.errorMessage}")
                                }
                            }
                        }
                    }
                }
                isRutubeUrl(url) -> {
                    showRutubeQualityDialog { _ ->
                        toast("Extracting Rutube stream...")
                        extractRutubeM3U8(url) { result ->
                            when {
                                result.success && result.m3u8Url != null -> {
                                    toast("✅ Extracted! Testing stream...")
                                    urlInput.setText(result.m3u8Url)
                                    saveOriginalUrl(url, result.m3u8Url, "rutube")
                                    testStream(result.m3u8Url)
                                }
                                else -> {
                                    showDebugDialog("❌ Extraction Failed\n\n${result.errorMessage}")
                                }
                            }
                        }
                    }
                }
                isDirectVideoUrl(url) -> {
                    // NEW: Handle direct video URLs (MP4, etc.)
                    toast("Testing direct video URL...")
                    saveOriginalUrl(url, url, "direct")
                    testStream(url)
                }
                isValidM3U8Url(url) -> {
                    saveOriginalUrl(url, url, "m3u8")
                    testStream(url)
                }
                else -> {
                    toast("Enter a valid YouTube, Rutube, M3U8, or direct video URL")
                }
            }
        }

        saveButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isBlank()) {
                toast("Enter a URL")
                return@setOnClickListener
            }

            when {
                isYouTubeUrl(url) -> {
                    showYouTubeQualityDialog(url) { _ ->
                        toast("Extracting and saving YouTube stream...")
                        extractYouTubeM3U8(url) { result ->
                            when {
                                result.success && result.m3u8Url != null -> {
                                    saveOriginalUrl(url, result.m3u8Url, "youtube")
                                    saveUrl(result.m3u8Url)
                                }
                                else -> {
                                    showDebugDialog("❌ Failed to save\n\n${result.errorMessage}")
                                }
                            }
                        }
                    }
                }
                isRutubeUrl(url) -> {
                    showRutubeQualityDialog { _ ->
                        toast("Extracting and saving Rutube stream...")
                        extractRutubeM3U8(url) { result ->
                            when {
                                result.success && result.m3u8Url != null -> {
                                    saveOriginalUrl(url, result.m3u8Url, "rutube")
                                    saveUrl(result.m3u8Url)
                                }
                                else -> {
                                    showDebugDialog("❌ Failed to save\n\n${result.errorMessage}")
                                }
                            }
                        }
                    }
                }
                isDirectVideoUrl(url) -> {
                    // NEW: Save direct video URLs
                    toast("Saving direct video URL...")
                    saveOriginalUrl(url, url, "direct")
                    saveUrl(url)
                    showDirectUrlWarning()
                }
                isValidM3U8Url(url) -> {
                    saveOriginalUrl(url, url, "m3u8")
                    saveUrl(url)
                }
                else -> {
                    toast("Enter a valid YouTube, Rutube, M3U8, or direct video URL")
                }
            }
        }

        // Add automatic refresh check on activity start
        checkAndRefreshExpiredUrl()
    }

    // NEW: Check if URL is a direct video file
    private fun isDirectVideoUrl(url: String): Boolean {
        return url.contains("googlevideo.com") || // YouTube direct URLs
               url.contains(".mp4", ignoreCase = true) ||
               url.contains(".webm", ignoreCase = true) ||
               url.contains(".mkv", ignoreCase = true) ||
               url.matches(Regex(".*videoplayback\\?.*", RegexOption.IGNORE_CASE))
    }

    // NEW: Show warning about direct URL expiration
    private fun showDirectUrlWarning() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Direct URL Warning")
            .setMessage("Direct video URLs (like from YouTube) typically expire after 6 hours.\n\n" +
                    "For permanent wallpapers:\n" +
                    "• Use the YouTube URL instead\n" +
                    "• The plugin will auto-extract and refresh\n\n" +
                    "Current URL will work until it expires.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showDebugDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Debug Info")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // ==================== QUALITY SELECTION ====================

    private fun showYouTubeQualityDialog(youtubeUrl: String, callback: (String) -> Unit) {
        val qualities = arrayOf(
            "Best Quality (1080p)" to "96",
            "High Quality (720p)" to "95",
            "Medium Quality (480p)" to "94",
            "Low Quality (360p)" to "93",
            "Mobile Quality (240p)" to "92"
        )

        val qualityNames = qualities.map { it.first }.toTypedArray()
        val savedQualityIndex = streamPrefs.getInt(KEY_SELECTED_QUALITY, 0)

        AlertDialog.Builder(this)
            .setTitle("Select Video Quality")
            .setSingleChoiceItems(qualityNames, savedQualityIndex) { dialog, which ->
                streamPrefs.edit().putInt(KEY_SELECTED_QUALITY, which).apply()
                callback(youtubeUrl)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRutubeQualityDialog(callback: (String) -> Unit) {
        val qualities = arrayOf(
            "Automatic (Best Available)" to "auto",
            "1080p (Full HD)" to "1080",
            "720p (HD)" to "720",
            "480p (SD)" to "480",
            "360p (Mobile)" to "360"
        )

        val qualityNames = qualities.map { it.first }.toTypedArray()
        val savedQualityIndex = streamPrefs.getInt("rutube_quality", 0)

        AlertDialog.Builder(this)
            .setTitle("Select Video Quality")
            .setMessage("Note: Rutube automatically selects the best available quality from the m3u8 playlist. This is for information only.")
            .setSingleChoiceItems(qualityNames, savedQualityIndex) { dialog, which ->
                streamPrefs.edit().putInt("rutube_quality", which).apply()
                callback(qualities[which].second)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ==================== EXPIRY MANAGEMENT ====================

    private fun saveOriginalUrl(originalUrl: String, extractedUrl: String, urlType: String) {
        streamPrefs.edit()
            .putString(KEY_ORIGINAL_URL, originalUrl)
            .putString(KEY_M3U8_URL, extractedUrl)
            .putString(KEY_URL_TYPE, urlType)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
        
        Log.d(TAG, "Saved URL - Type: $urlType, Original: $originalUrl")
    }

    private fun isUrlExpired(): Boolean {
        val timestamp = streamPrefs.getLong(KEY_TIMESTAMP, 0)
        if (timestamp == 0L) return false

        val urlType = streamPrefs.getString(KEY_URL_TYPE, "unknown")
        val hoursSince = getHoursSinceExtraction()
        
        // Direct URLs expire faster (6 hours), YouTube/Rutube extracted URLs last longer
        return when (urlType) {
            "direct" -> hoursSince >= 6 // Direct video URLs expire in ~6 hours
            "youtube", "rutube" -> hoursSince >= URL_EXPIRY_HOURS
            else -> hoursSince >= URL_EXPIRY_HOURS
        }
    }

    private fun getHoursSinceExtraction(): Long {
        val timestamp = streamPrefs.getLong(KEY_TIMESTAMP, 0)
        if (timestamp == 0L) return 0

        val currentTime = System.currentTimeMillis()
        return (currentTime - timestamp) / (1000 * 60 * 60)
    }

    private fun checkAndRefreshExpiredUrl() {
        if (!isUrlExpired()) return

        val originalUrl = streamPrefs.getString(KEY_ORIGINAL_URL, null) ?: return
        val urlType = streamPrefs.getString(KEY_URL_TYPE, "unknown")

        // Don't try to refresh direct URLs - they can't be refreshed
        if (urlType == "direct") {
            AlertDialog.Builder(this)
                .setTitle("⚠️ Direct URL Expired")
                .setMessage("Your direct video URL has expired (${getHoursSinceExtraction()}h old).\n\n" +
                        "Direct URLs cannot be automatically refreshed.\n\n" +
                        "Options:\n" +
                        "• Get a new direct URL using yt-dlp\n" +
                        "• Use the original YouTube URL instead for auto-refresh")
                .setPositiveButton("OK") { _, _ ->
                    findViewById<EditText>(R.id.m3u8_url_input)?.setText("")
                }
                .show()
            return
        }

        // For YouTube/Rutube, offer auto-refresh
        AlertDialog.Builder(this)
            .setTitle("URL Expired")
            .setMessage("Your saved stream URL has expired (${getHoursSinceExtraction()}h old, type: $urlType).\n\n" +
                    "Would you like to refresh it automatically?")
            .setPositiveButton("Refresh Now") { _, _ ->
                refreshExpiredUrl(originalUrl, urlType)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun refreshExpiredUrl(originalUrl: String, urlType: String) {
        toast("Refreshing expired URL...")

        when (urlType) {
            "youtube" -> {
                extractYouTubeM3U8(originalUrl) { result ->
                    handleRefreshResult(originalUrl, result, "youtube")
                }
            }
            "rutube" -> {
                extractRutubeM3U8(originalUrl) { result ->
                    handleRefreshResult(originalUrl, result, "rutube")
                }
            }
            else -> {
                toast("❌ Cannot refresh URL type: $urlType")
            }
        }
    }

    private fun handleRefreshResult(originalUrl: String, result: ExtractionResult, urlType: String) {
        when {
            result.success && result.m3u8Url != null -> {
                saveOriginalUrl(originalUrl, result.m3u8Url, urlType)
                PreferencesManager.wallpaperSourceUrl = result.m3u8Url
                toast("✅ URL refreshed successfully!")

                // Update the UI
                findViewById<EditText>(R.id.m3u8_url_input)?.setText(result.m3u8Url)
            }
            else -> {
                toast("❌ Failed to refresh: ${result.errorMessage}")
                showDebugDialog("Refresh Failed\n\nOriginal URL: $originalUrl\n\nError: ${result.errorMessage}")
            }
        }
    }

    data class ExtractionResult(
        val success: Boolean,
        val m3u8Url: String? = null,
        val errorMessage: String? = null,
        val availableFormats: List<VideoFormat>? = null
    )

    data class VideoFormat(
        val quality: String,
        val url: String,
        val formatId: String
    )

    // ==================== YOUTUBE EXTRACTION ====================

    private fun isYouTubeUrl(url: String): Boolean {
        return url.contains("youtube.com", ignoreCase = true) ||
               url.contains("youtu.be", ignoreCase = true)
    }

    private fun extractYouTubeVideoId(url: String): String? {
        val patterns = listOf(
            "(?<=watch\\?v=)[^&]+",
            "(?<=youtu.be/)[^?&]+",
            "(?<=embed/)[^?&]+",
            "(?<=v=)[^&]+"
        )

        for (pattern in patterns) {
            val matcher = Pattern.compile(pattern).matcher(url)
            if (matcher.find()) {
                return matcher.group(0)
            }
        }
        return null
    }

    private fun extractYouTubeM3U8(youtubeUrl: String, callback: (ExtractionResult) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val videoId = extractYouTubeVideoId(youtubeUrl)

                if (videoId == null) {
                    withContext(Dispatchers.Main) {
                        callback(ExtractionResult(
                            success = false,
                            errorMessage = "Could not extract video ID from YouTube URL\n\nURL: $youtubeUrl"
                        ))
                    }
                    return@launch
                }

                // Fetch YouTube page
                val request = Request.Builder()
                    .url("https://www.youtube.com/watch?v=$videoId")
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36")
                    .addHeader("Accept-Language", "en-US,en;q=0.9")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        callback(ExtractionResult(
                            success = false,
                            errorMessage = "YouTube page request failed\n\nStatus: ${response.code}\nMessage: ${response.message}"
                        ))
                    }
                    return@launch
                }

                val html = response.body?.string()

                if (html.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        callback(ExtractionResult(
                            success = false,
                            errorMessage = "Empty response from YouTube"
                        ))
                    }
                    return@launch
                }

                // Extract player response
                val playerResponse = extractYouTubePlayerResponse(html)

                if (playerResponse == null) {
                    withContext(Dispatchers.Main) {
                        callback(ExtractionResult(
                            success = false,
                            errorMessage = "Could not find player response in YouTube page"
                        ))
                    }
                    return@launch
                }

                // Parse JSON and find streaming URL
                val jsonObject = JSONObject(playerResponse)
                val streamingData = jsonObject.optJSONObject("streamingData")

                if (streamingData == null) {
                    withContext(Dispatchers.Main) {
                        callback(ExtractionResult(
                            success = false,
                            errorMessage = "No streaming data found in player response"
                        ))
                    }
                    return@launch
                }

                // Try to get HLS manifest URL (for livestreams)
                var videoUrl = streamingData.optString("hlsManifestUrl")

                // Fallback: check formats for m3u8
                if (videoUrl.isEmpty()) {
                    val formats = streamingData.optJSONArray("formats")
                    if (formats != null) {
                        for (i in 0 until formats.length()) {
                            val format = formats.getJSONObject(i)
                            val url = format.optString("url")
                            if (url.contains("m3u8") || url.contains("manifest")) {
                                videoUrl = url
                                break
                            }
                        }
                    }
                }

                // Fallback: check adaptive formats
                if (videoUrl.isEmpty()) {
                    val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                    if (adaptiveFormats != null) {
                        for (i in 0 until adaptiveFormats.length()) {
                            val format = adaptiveFormats.getJSONObject(i)
                            val url = format.optString("url")
                            if (url.contains("m3u8") || url.contains("manifest")) {
                                videoUrl = url
                                break
                            }
                        }
                    }
                }

                // IMPROVED: Get regular video URL (mp4) with better quality selection
                if (videoUrl.isEmpty()) {
                    val formats = streamingData.optJSONArray("formats")
                    if (formats != null && formats.length() > 0) {
                        var bestFormat: JSONObject? = null
                        var bestHeight = 0

                        for (i in 0 until formats.length()) {
                            val format = formats.getJSONObject(i)
                            val url = format.optString("url")
                            val height = format.optInt("height", 0)
                            val mimeType = format.optString("mimeType", "")

                            // Prefer formats with both video and audio
                            if (url.isNotEmpty() && mimeType.contains("video") && height > bestHeight) {
                                bestFormat = format
                                bestHeight = height
                            }
                        }

                        if (bestFormat != null) {
                            videoUrl = bestFormat.optString("url")
                            Log.d(TAG, "Using direct MP4 format - Height: $bestHeight")
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (videoUrl.isEmpty()) {
                        callback(ExtractionResult(
                            success = false,
                            errorMessage = "❌ No stream found for this video.\n\n" +
                                    "This video may be:\n" +
                                    "• Age-restricted\n" +
                                    "• Private/unlisted\n" +
                                    "• Geo-blocked in your region\n\n" +
                                    "💡 TIP: For best results, use:\n" +
                                    "• Live streams (🔴 LIVE)\n" +
                                    "• 24/7 streams (lofi, nature cams)\n" +
                                    "• Public videos"
                        ))
                    } else {
                        callback(ExtractionResult(
                            success = true,
                            m3u8Url = videoUrl
                        ))
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "YouTube extraction error", e)
                withContext(Dispatchers.Main) {
                    callback(ExtractionResult(
                        success = false,
                        errorMessage = "Exception: ${e.message}\n\n${e.stackTraceToString()}"
                    ))
                }
            }
        }
    }

    private fun extractYouTubePlayerResponse(html: String): String? {
        val patterns = listOf(
            "var ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});",
            "ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});",
            "\"player\":\\s*(\\{.+?\\}),\"response\""
        )

        for (pattern in patterns) {
            val matcher = Pattern.compile(pattern, Pattern.DOTALL).matcher(html)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    // ==================== RUTUBE EXTRACTION ====================

    private fun isRutubeUrl(url: String): Boolean {
        return url.contains("rutube.ru", ignoreCase = true)
    }

    private fun extractRutubeM3U8(rutubeUrl: String, callback: (ExtractionResult) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val videoId = extractRutubeVideoId(rutubeUrl)

                if (videoId == null) {
                    withContext(Dispatchers.Main) {
                        callback(ExtractionResult(
                            success = false,
                            errorMessage = "Could not extract video ID from Rutube URL"
                        ))
                    }
                    return@launch
                }

                val apiUrl = "https://rutube.ru/api/play/options/$videoId/?no_404=true&referer=https%3A%2F%2Frutube.ru"

                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        callback(ExtractionResult(
                            success = false,
                            errorMessage = "Rutube API request failed: ${response.code}"
                        ))
                    }
                    return@launch
                }

                val json = response.body?.string()
                if (json.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        callback(ExtractionResult(
                            success = false,
                            errorMessage = "Empty response from Rutube API"
                        ))
                    }
                    return@launch
                }

                val jsonObject = JSONObject(json)
                val m3u8Url = jsonObject.optString("video_balancer")
                    .ifEmpty { jsonObject.optString("m3u8_url") }

                withContext(Dispatchers.Main) {
                    if (m3u8Url.isEmpty()) {
                        callback(ExtractionResult(
                            success = false,
                            errorMessage = "No m3u8 URL found in Rutube response"
                        ))
                    } else {
                        callback(ExtractionResult(
                            success = true,
                            m3u8Url = m3u8Url
                        ))
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Rutube extraction error", e)
                withContext(Dispatchers.Main) {
                    callback(ExtractionResult(
                        success = false,
                        errorMessage = "Exception: ${e.message}"
                    ))
                }
            }
        }
    }

    private fun extractRutubeVideoId(url: String): String? {
        val pattern = "rutube\\.ru/video/([a-zA-Z0-9]+)"
        val matcher = Pattern.compile(pattern).matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    // ==================== URL VALIDATION ====================

    private fun isValidM3U8Url(url: String): Boolean {
        return Patterns.WEB_URL.matcher(url).matches() &&
               (url.contains(".m3u8") || url.contains("manifest"))
    }

    // ==================== STREAM TESTING ====================

    private fun testStream(url: String) {
        testCompleted = false

        try {
            testPlayer?.release()
            testPlayer = ExoPlayer.Builder(this).build()

            val mediaItem = MediaItem.fromUri(url)
            testPlayer?.setMediaItem(mediaItem)
            testPlayer?.prepare()

            testPlayer?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (testCompleted) return

                    when (playbackState) {
                        Player.STATE_READY -> {
                            testCompleted = true
                            timeoutRunnable?.let { handler.removeCallbacks(it) }

                            val format = testPlayer?.videoFormat
                            val info = buildString {
                                append("✅ Stream is playable!\n\n")
                                append("Resolution: ${format?.width ?: "?"} x ${format?.height ?: "?"}\n")
                                append("FPS: ${format?.frameRate ?: "?"}\n")
                                append("Codec: ${format?.sampleMimeType ?: "?"}")
                            }

                            toast(info)
                            testPlayer?.release()
                            testPlayer = null
                        }
                        Player.STATE_ENDED -> {
                            if (!testCompleted) {
                                testCompleted = true
                                timeoutRunnable?.let { handler.removeCallbacks(it) }
                                toast("⚠️ Stream ended immediately")
                                testPlayer?.release()
                                testPlayer = null
                            }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (testCompleted) return

                    testCompleted = true
                    timeoutRunnable?.let { handler.removeCallbacks(it) }

                    val errorMsg = "❌ Playback Error\n\n${error.errorCodeName}\n${error.message}"
                    toast(errorMsg)
                    Log.e(TAG, "Playback error", error)

                    testPlayer?.release()
                    testPlayer = null
                }
            })

            // Timeout handler
            timeoutRunnable = Runnable {
                if (!testCompleted) {
                    testCompleted = true
                    toast("⏱️ Test timeout - stream might be slow")
                    testPlayer?.release()
                    testPlayer = null
                }
            }
            handler.postDelayed(timeoutRunnable!!, TEST_TIMEOUT_MS)

        } catch (e: Exception) {
            toast("❌ Error testing stream: ${e.message}")
            Log.e(TAG, "Test stream error", e)
        }
    }

    // ==================== SAVE & UTILITY ====================

    private fun saveUrl(url: String) {
        try {
            PreferencesManager.wallpaperSourceUrl = url
            toast("✅ Saved successfully!")
            Log.d(TAG, "Saved URL: $url")
        } catch (e: Exception) {
            toast("❌ Failed to save: ${e.message}")
            Log.e(TAG, "Save error", e)
        }
    }

    private fun toast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        testPlayer?.release()
        testPlayer = null
        timeoutRunnable?.let { handler.removeCallbacks(it) }
    }