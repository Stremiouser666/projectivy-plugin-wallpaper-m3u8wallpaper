package tv.projectivy.plugin.wallpaperprovider.bingwallpaper

data class BingWallpaper(
    val title: String,
    val url: String,          // IMAGE, MP4, or M3U8
    val copyright: String,
    val copyrightlink: String
) {
    val isHls: Boolean
        get() = url.endsWith(".m3u8", ignoreCase = true)
}
