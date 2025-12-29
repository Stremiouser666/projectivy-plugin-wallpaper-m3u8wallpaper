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
        private const val TEST_TIMEOUT_MS = 20_000L // Increased to 20 seconds for slower connections
        private const val URL_EXPIRY_HOURS = 5L
        private const val PREFS_NAME = "stream_prefs"
        private const val KEY_ORIGINAL_URL = "original_url"
        private const val KEY_M3U8_URL = "m3u8_url"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_SELECTED_QUALITY = "selected_quality"
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
                toast("⚠️ Saved URL may be expired (${getHoursSinceExtraction()}h old)")
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
                    // Show quality selection for YouTube
                    showYouTubeQualityDialog(url) { _ ->
                        toast("Extracting YouTube stream...")
                        extractYouTubeM3U8(url) { result ->
                            when {
                                result.success && result.m3u8Url != null -> {
                                    toast("✅ Extracted! Testing stream...")
                                    urlInput.setText(result.m3u8Url)
                                    saveOriginalUrl(url, result.m3u8Url)
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
                    // Show quality selection for Rutube
                    showRutubeQualityDialog { _ ->
                        toast("Extracting Rutube stream...")
                        extractRutubeM3U8(url) { result ->
                            when {
                                result.success && result.m3u8Url != null -> {
                                    toast("✅ Extracted! Testing stream...")
                                    urlInput.setText(result.m3u8Url)
                                    saveOriginalUrl(url, result.m3u8Url)
                                    testStream(result.m3u8Url)
                                }
                                else -> {
                                    showDebugDialog("❌ Extraction Failed\n\n${result.errorMessage}")
                                }
                            }
                        }
                    }
                }
                isValidM3U8Url(url) -> {
                    testStream(url)
                }
                else -> {
                    toast("Enter a valid YouTube, Rutube, or M3U8 URL")
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
                                    saveOriginalUrl(url, result.m3u8Url)
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
                                    saveOriginalUrl(url, result.m3u8Url)
                                    saveUrl(result.m3u8Url)
                                }
                                else -> {
                                    showDebugDialog("❌ Failed to save\n\n${result.errorMessage}")
                                }
                            }
                        }
                    }
                }
                isValidM3U8Url(url) -> {
                    saveUrl(url)
                }
                else -> {
                    toast("Enter a valid YouTube, Rutube, or M3U8 URL")
                }
            }
        }
        
        // Add automatic refresh check on activity start
        checkAndRefreshExpiredUrl()
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
    
    private fun saveOriginalUrl(originalUrl: String, m3u8Url: String) {
        streamPrefs.edit()
            .putString(KEY_ORIGINAL_URL, originalUrl)
            .putString(KEY_M3U8_URL, m3u8Url)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }
    
    private fun isUrlExpired(): Boolean {
        val timestamp = streamPrefs.getLong(KEY_TIMESTAMP, 0)
        if (timestamp == 0L) return false
        
        val hoursSince = getHoursSinceExtraction()
        return hoursSince >= URL_EXPIRY_HOURS
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
        
        AlertDialog.Builder(this)
            .setTitle("URL Expired")
            .setMessage("Your saved stream URL has expired (${getHoursSinceExtraction()}h old).\n\nWould you like to refresh it automatically?")
            .setPositiveButton("Refresh Now") { _, _ ->
                refreshExpiredUrl(originalUrl)
            }
            .setNegativeButton("Later", null)
            .show()
    }
    
    private fun refreshExpiredUrl(originalUrl: String) {
        toast("Refreshing expired URL...")
        
        when {
            isYouTubeUrl(originalUrl) -> {
                extractYouTubeM3U8(originalUrl) { result ->
                    when {
                        result.success && result.m3u8Url != null -> {
                            saveOriginalUrl(originalUrl, result.m3u8Url)
                            PreferencesManager.wallpaperSourceUrl = result.m3u8Url
                            toast("✅ URL refreshed successfully!")
                            
                            // Update the UI
                            findViewById<EditText>(R.id.m3u8_url_input)?.setText(result.m3u8Url)
                        }
                        else -> {
                            toast("❌ Failed to refresh: ${result.errorMessage}")
                        }
                    }
                }
            }
            isRutubeUrl(originalUrl) -> {
                extractRutubeM3U8(originalUrl) { result ->
                    when {
                        result.success && result.m3u8Url != null -> {
                            saveOriginalUrl(originalUrl, result.m3u8Url)
                            PreferencesManager.wallpaperSourceUrl = result.m3u8Url
                            toast("✅ URL refreshed successfully!")
                            
                            // Update the UI
                            findViewById<EditText>(R.id.m3u8_url_input)?.setText(result.m3u8Url)
                        }
                        else -> {
                            toast("❌ Failed to refresh: ${result.errorMessage}")
                        }
                    }
                }
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

                // Parse JSON and find HLS manifest
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
                var m3u8Url = streamingData.optString("hlsManifestUrl")

                // Fallback: check formats for m3u8
                if (m3u8Url.isEmpty()) {
                    val formats = streamingData.optJSONArray("formats")
                    if (formats != null) {
                        for (i in 0 until formats.length()) {
                            val format = formats.getJSONObject(i)
                            val url = format.optString("url")
                            if (url.contains("m3u8") || url.contains("manifest")) {
                                m3u8Url = url
                                break
                            }
                        }
                    }
                }

                // Fallback: check adaptive formats for m3u8
                if (m3u8Url.isEmpty()) {
                    val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                    if (adaptiveFormats != null) {
                        for (i in 0 until adaptiveFormats.length()) {
                            val format = adaptiveFormats.getJSONObject(i)
                            val url = format.optString("url")
                            if (url.contains("m3u8") || url.contains("manifest")) {
                                m3u8Url = url
                                break
                            }
                        }
                    }
                }
                
                // NEW: If no m3u8, try to get regular video URL (mp4)
                if (m3u8Url.isEmpty()) {
                    val formats = streamingData.optJSONArray("formats")
                    if (formats != null && formats.length() > 0) {
                        // Get highest quality format with both video and audio
                        var bestFormat: JSONObject? = null
                        var bestHeight = 0
                        
                        for (i in 0 until formats.length()) {
                            val format = formats.getJSONObject(i)
                            val url = format.optString("url")
                            val height = format.optInt("height", 0)
                            val mimeType = format.optString("mimeType", "")
                            
                            // Look for formats with both video and audio (contains "video" and "audio")
                            if (url.isNotEmpty() && mimeType.contains("video") && height > bestHeight) {
                                bestFormat = format
                                bestHeight = height
                            }
                        }
                        
                        if (bestFormat != null) {
                            m3u8Url = bestFormat.optString("url")
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (m3u8Url.isEmpty()) {
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
                            m3u8Url = m3u8Url
                        ))
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(ExtractionResult(
                        success = false,
                        errorMessage = "YouTube extraction exception:\n\n${e::class.simpleName}\n${e.message}"
                    ))
                }
            }
        }
    }

    private fun extractYouTubeVideoId(url: String): String? {
        val patterns = listOf(
            "(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/|m\\.youtube\\.com/watch\\?v=)([a-zA-Z0-9_-]{11})",
            "v=([a-zA-Z0-9_-]{11})"
        )

        for (patternStr in patterns) {
            val pattern = Pattern.compile(patternStr)
            val matcher = pattern.matcher(url)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    private fun extractYouTubePlayerResponse(html: String): String? {
        // Pattern 1: var ytInitialPlayerResponse = {...};
        var pattern = Pattern.compile("ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});", Pattern.DOTALL)
        var matcher = pattern.matcher(html)
        if (matcher.find()) {
            return matcher.group(1)
        }

        // Pattern 2: Look for streamingData in any script
        pattern = Pattern.compile("\"streamingData\":\\{[^}]*\"hlsManifestUrl\":\"([^\"]+)\"")
        matcher = pattern.matcher(html)
        if (matcher.find()) {
            val url = matcher.group(1)?.replace("\\/", "/")
            if (url != null) {
                // Construct minimal player response
                return """{"streamingData":{"hlsManifestUrl":"$url"}}"""
            }
        }

        // Pattern 3: Alternative format
        pattern = Pattern.compile("\"player_response\"\\s*:\\s*\"(\\{.+?\\})\"", Pattern.DOTALL)
        matcher = pattern.matcher(html)
        if (matcher.find()) {
            return matcher.group(1)?.replace("\\\"", "\"")
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
                            errorMessage = "Could not extract video ID from Rutube URL\n\nURL: $rutubeUrl"
                        ))
                    }
                    return@launch
                }

                val apiUrl = "https://rutube.ru/api/play/options/$videoId/?no_404=true&referer=https%3A%2F%2Frutube.ru"

                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Referer", "https://rutube.ru")
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        callback(ExtractionResult(
                            success = false,
                            errorMessage = "Rutube API request failed\n\nStatus: ${response.code}\nMessage: ${response.message}"
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
                var m3u8Url: String? = null

                val videoBalancer = jsonObject.optString("video_balancer")

                if (videoBalancer.isNotEmpty() && videoBalancer.startsWith("{")) {
                    try {
                        val balancerObj = JSONObject(videoBalancer)
                        m3u8Url = balancerObj.optString("m3u8")
                            .ifEmpty { balancerObj.optString("default") }
                    } catch (e: Exception) {
                        // Continue to fallbacks
                    }
                } else if (videoBalancer.isNotEmpty()) {
                    m3u8Url = videoBalancer
                }

                if (m3u8Url.isNullOrEmpty()) {
                    val balancerObj = jsonObject.optJSONObject("video_balancer")
                    m3u8Url = balancerObj?.optString("m3u8")
                        ?: balancerObj?.optString("default")
                }

                if (m3u8Url.isNullOrEmpty()) {
                    m3u8Url = jsonObject.optString("m3u8")
                }

                if (m3u8Url.isNullOrEmpty()) {
                    m3u8Url = jsonObject.optString("hls")
                }

                withContext(Dispatchers.Main) {
                    if (m3u8Url.isNullOrEmpty()) {
                        callback(ExtractionResult(
                            success = false,
                            errorMessage = "No M3U8 URL found in Rutube response\n\nResponse preview:\n${json.take(300)}..."
                        ))
                    } else {
                        callback(ExtractionResult(
                            success = true,
                            m3u8Url = m3u8Url
                        ))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(ExtractionResult(
                        success = false,
                        errorMessage = "Rutube extraction exception:\n\n${e::class.simpleName}\n${e.message}"
                    ))
                }
            }
        }
    }

    private fun extractRutubeVideoId(url: String): String? {
        val regex = "rutube\\.ru/video/([a-f0-9]+)".toRegex(RegexOption.IGNORE_CASE)
        return regex.find(url)?.groupValues?.get(1)
    }

    // ==================== COMMON FUNCTIONS ====================

    private fun saveUrl(url: String) {
        try {
            PreferencesManager.wallpaperSourceUrl = url
            toast("✅ M3U8 wallpaper source saved")
            finish()
        } catch (e: Exception) {
            toast("Error saving: ${e.message}")
        }
    }

    private fun testStream(url: String) {
        releaseTestPlayer()
        testCompleted = false

        toast("Testing stream… (up to 20s)")

        try {
            testPlayer = ExoPlayer.Builder(this).build().apply {
                volume = 0f

                addListener(object : Player.Listener {

                    override fun onPlaybackStateChanged(state: Int) {
                        Log.d(TAG, "Playback state changed: $state")
                        
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                Log.d(TAG, "Stream buffering...")
                            }
                            Player.STATE_READY -> {
                                if (!testCompleted) {
                                    testCompleted = true

                                    val format: Format? = videoFormat
                                    val resolution = if (format != null && format.width > 0 && format.height > 0) {
                                        "${format.width}×${format.height}"
                                    } else {
                                        "Unknown resolution"
                                    }

                                    val codec = format?.sampleMimeType ?: "Unknown codec"

                                    toast("✅ Stream OK\n$resolution\n$codec")

                                    releaseTestPlayer()
                                }
                            }
                            Player.STATE_ENDED -> {
                                Log.d(TAG, "Stream ended")
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (!testCompleted) {
                            testCompleted = true
                            val errorMsg = "❌ Stream failed: ${error.errorCodeName}\n${error.message}"
                            Log.e(TAG, errorMsg, error)
                            toast(errorMsg)
                            releaseTestPlayer()
                        }
                    }
                })

                setMediaItem(MediaItem.fromUri(url))
                prepare()
                play()
            }

            timeoutRunnable = Runnable {
                if (!testCompleted) {
                    testCompleted = true
                    toast("❌ Stream timeout (20s)\nStream may still be valid - try saving it")
                    releaseTestPlayer()
                }
            }
            handler.postDelayed(timeoutRunnable!!, TEST_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Test stream exception", e)
            toast("❌ Error: ${e.message}")
            testCompleted = true
            releaseTestPlayer()
        }
    }

    private fun releaseTestPlayer() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null

        testPlayer?.release()
        testPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseTestPlayer()
    }

    private fun isValidM3U8Url(url: String): Boolean {
        if (url.isBlank()) return false
        if (!Patterns.WEB_URL.matcher(url).matches()) return false
        // Accept both m3u8 and common video formats
        return url.contains(".m3u8", ignoreCase = true) ||
               url.contains("manifest", ignoreCase = true) ||
               url.contains("googlevideo.com", ignoreCase = true)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}