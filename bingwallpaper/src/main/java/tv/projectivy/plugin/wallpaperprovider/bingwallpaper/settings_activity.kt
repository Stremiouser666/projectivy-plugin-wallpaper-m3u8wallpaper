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
        private const val DIRECT_URL_EXPIRY_HOURS = 6L
        private const val PREFS_NAME = "stream_prefs"
        private const val KEY_ORIGINAL_URL = "original_url"
        private const val KEY_M3U8_URL = "m3u8_url"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_SELECTED_QUALITY = "selected_quality"
        private const val KEY_URL_TYPE = "url_type"
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
            
            if (isUrlExpired()) {
                val urlType = streamPrefs.getString(KEY_URL_TYPE, "unknown") ?: "unknown"
                val hours = getHoursSinceExtraction()
                toast("⚠️ Saved URL may be expired (${hours}h old, type: $urlType)")
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
        
        checkAndRefreshExpiredUrl()
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        return url.contains("googlevideo.com") ||
               url.contains(".mp4", ignoreCase = true) ||
               url.contains(".webm", ignoreCase = true) ||
               url.contains(".mkv", ignoreCase = true) ||
               url.matches(Regex(".*videoplayback\\?.*", RegexOption.IGNORE_CASE))
    }

    private fun showDirectUrlWarning() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Direct URL Warning")
            .setMessage("Direct video URLs (like from yt-dlp) typically expire after 6 hours.\n\n" +
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
            "Automatic (Best Available)",
            "1080p (Full HD)",
            "720p (HD)",
            "480p (SD)",
            "360p (Mobile)"
        )
        
        val savedQualityIndex = streamPrefs.getInt("rutube_quality", 0)
        
        AlertDialog.Builder(this)
            .setTitle("Select Video Quality")
            .setSingleChoiceItems(qualities, savedQualityIndex) { dialog, which ->
                streamPrefs.edit().putInt("rutube_quality", which).apply()
                callback("auto") // Rutube uses adaptive streaming
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
        
        val urlType = streamPrefs.getString(KEY_URL_TYPE, "unknown") ?: "unknown"
        val hoursSince = getHoursSinceExtraction()
        
        return when (urlType) {
            "direct" -> hoursSince >= DIRECT_URL_EXPIRY_HOURS
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
        val urlType = streamPrefs.getString(KEY_URL_TYPE, "unknown") ?: "unknown"

        if (urlType == "direct") {
            AlertDialog.Builder(this)
                .setTitle("⚠️ Direct URL Expired")
                .setMessage("Your direct video URL has expired (${getHoursSinceExtraction()}h old).\n\n" +
                        "Direct URLs cannot be automatically refreshed.\n\n" +
                        "Options:\n" +
                        "• Get a new direct URL using yt-dlp\n" +
                        "• Use the original YouTube URL instead for auto-refresh")
                .setPositiveButton("Clear URL") { _, _ ->
                    findViewById<EditText>(R.id.m3u8_url_input)?.setText("")
                    streamPrefs.edit().clear().apply()
                }
                .setNegativeButton("Keep It", null)
                .show()
            return
        }
        
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
                
                findViewById<EditText>(R.id.m3u8_url_input)?.setText(result.m3u8Url)
            }
            else -> {
                toast("❌ Failed to refresh: ${result.errorMessage}")
                showDebugDialog("Refresh Failed\n\nOriginal: $originalUrl\n\nError: ${result.errorMessage}")
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

                var videoUrl = streamingData.optString("hlsManifestUrl")

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
                            
                            if (url.isNotEmpty() && mimeType.contains("video") && height > bestHeight) {
                                bestFormat = format
                                bestHeight = height
                            }
                        }
                        
                        if (bestFormat != null) {
                            videoUrl = bestFormat.optString("url")
                            Log.d(TAG, "Using direct MP4 - Height: $bestHeight")
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
        var pattern = Pattern.compile("ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});", Pattern.DOTALL)
        var matcher = pattern.matcher(html)
        if (matcher.find()) {
            return matcher.group(1)
        }

        pattern = Pattern.compile("\"streamingData\":\\{[^}]*\"hlsManifestUrl\":\"([^\"]+)\"")
        matcher = pattern.matcher(html)
        if (matcher.find()) {
            val url = matcher.group(1)?.replace("\\/", "/")
            if (url != null) {
                return """{"streamingData":{"hlsManifestUrl":"$url"}}"""
            }
        }

        pattern = Pattern.compile("\"player_response\"\\s*:\\s*\"(\\{.+?\\})\"", Pattern.DOTALL)
        matcher = pattern.matcher(html)
        if (matcher.find()) {
            return matcher.group(1)?.replace("\\\"", "\"")
        }

        return null
    }

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
                        Log.w(TAG, "Failed to parse video_balancer JSON", e)
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
                Log.e(TAG, "Rutube extraction error", e)
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
        return url.contains(".m3u8", ignoreCase = true) ||
               url.contains("manifest", ignoreCase = true) ||
               url.contains("googlevideo.com", ignoreCase = true)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}