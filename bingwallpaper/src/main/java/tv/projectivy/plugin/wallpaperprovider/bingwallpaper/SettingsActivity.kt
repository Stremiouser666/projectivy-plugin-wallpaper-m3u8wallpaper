package tv.projectivy.plugin.wallpaperprovider.bingwallpaper

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
        private const val TEST_TIMEOUT_MS = 10_000L
    }

    private var testPlayer: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var testCompleted = false
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

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
                    showDebugDialog("Starting YouTube extraction...")
                    extractYouTubeM3U8(url) { result ->
                        when {
                            result.success && result.m3u8Url != null -> {
                                showDebugDialog("✅ Extracted M3U8!\n\nURL: ${result.m3u8Url.take(100)}...")
                                urlInput.setText(result.m3u8Url)
                                testStream(result.m3u8Url)
                            }
                            else -> {
                                showDebugDialog("❌ Extraction Failed\n\n${result.errorMessage}")
                            }
                        }
                    }
                }
                isRutubeUrl(url) -> {
                    showDebugDialog("Starting Rutube extraction...")
                    extractRutubeM3U8(url) { result ->
                        when {
                            result.success && result.m3u8Url != null -> {
                                showDebugDialog("✅ Extracted M3U8!\n\nURL: ${result.m3u8Url.take(100)}...")
                                urlInput.setText(result.m3u8Url)
                                testStream(result.m3u8Url)
                            }
                            else -> {
                                showDebugDialog("❌ Extraction Failed\n\n${result.errorMessage}")
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
                    showDebugDialog("Extracting and saving YouTube stream...")
                    extractYouTubeM3U8(url) { result ->
                        when {
                            result.success && result.m3u8Url != null -> {
                                saveUrl(result.m3u8Url)
                            }
                            else -> {
                                showDebugDialog("❌ Failed to save\n\n${result.errorMessage}")
                            }
                        }
                    }
                }
                isRutubeUrl(url) -> {
                    showDebugDialog("Extracting and saving Rutube stream...")
                    extractRutubeM3U8(url) { result ->
                        when {
                            result.success && result.m3u8Url != null -> {
                                saveUrl(result.m3u8Url)
                            }
                            else -> {
                                showDebugDialog("❌ Failed to save\n\n${result.errorMessage}")
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
    }

    private fun showDebugDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Debug Info")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    data class ExtractionResult(
        val success: Boolean,
        val m3u8Url: String? = null,
        val errorMessage: String? = null
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

                // Try to get HLS manifest URL
                var m3u8Url = streamingData.optString("hlsManifestUrl")

                // Fallback: check formats
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

                // Fallback: check adaptive formats
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

                withContext(Dispatchers.Main) {
                    if (m3u8Url.isEmpty()) {
                        callback(ExtractionResult(
                            success = false,
                            errorMessage = "No HLS/M3U8 stream found for this video.\n\nThis might be a regular video (not a livestream)."
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

        toast("Testing stream…")

        try {
            testPlayer = ExoPlayer.Builder(this).build().apply {
                volume = 0f

                addListener(object : Player.Listener {

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && !testCompleted) {
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

                    override fun onPlayerError(error: PlaybackException) {
                        if (!testCompleted) {
                            testCompleted = true
                            toast("❌ Stream failed: ${error.errorCodeName}")
                            releaseTestPlayer()
                        }
                    }
                })

                setMediaItem(MediaItem.fromUri(url))
                prepare()
                play()
            }

            timeoutRunnable = Runnable {
                if (!testCom