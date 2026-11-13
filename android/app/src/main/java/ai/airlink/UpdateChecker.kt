package ai.airlink

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tag: String,
    val name: String,
    val notes: String,
    val apkUrl: String?,
    val pageUrl: String
)

/**
 * Reads the newest GitHub release for this project.
 *
 * This is the only network call the app makes, and it only happens when you
 * press "Check for updates" on the About screen. Nothing is sent: no id, no
 * device details, no usage — just a GET for a public JSON document.
 */
object UpdateChecker {

    private const val API =
        "https://api.github.com/repos/Aditya-FullStack/AuDTLink/releases/latest"

    fun fetchLatest(): ReleaseInfo {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AuDTLink-Android")
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                throw IllegalStateException(
                    if (code == 404) "No releases published yet" else "GitHub returned HTTP $code"
                )
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)

            var apk: String? = null
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apk = a.optString("browser_download_url")
                        break
                    }
                }
            }

            return ReleaseInfo(
                tag = o.optString("tag_name"),
                name = o.optString("name"),
                notes = o.optString("body"),
                apkUrl = apk,
                pageUrl = o.optString("html_url")
            )
        } finally {
            conn.disconnect()
        }
    }

    /** True when [latest] is a higher version than [current] ("3.0" vs "2.0"). */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().trimStart('v', 'V')
            .split('.', '-', '+')
            .mapNotNull { it.toIntOrNull() }
        val a = parts(latest)
        val b = parts(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
