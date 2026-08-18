package com.sun.subtitleoverlay.customer

import android.content.Context
import androidx.core.content.edit
import com.sun.subtitleoverlay.subtitle.SrtParser
import com.sun.subtitleoverlay.subtitle.SubtitleCue
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.roundToLong

internal object CustomerBackendConfig {
    const val SUPABASE_URL = "https://qtpxlrnazsonqdljafkd.supabase.co"
    const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_ZIlaAn2SOwncEW11LyzUHg_hI9Wzmtg"
    const val PORTAL_URL = "https://subtitle-project-978670366914.europe-west2.run.app/customer"
}

data class CustomerSession(
    val accessToken: String,
    val refreshToken: String,
    val email: String,
    val expiresAtEpochSeconds: Long,
)

data class AuthorizedSubtitleTrack(
    val id: String,
    val title: String,
    val episodeLabel: String,
    val languageCode: String,
    val languageName: String,
    val cueCount: Int,
) {
    val displayTitle: String
        get() = if (episodeLabel.isBlank()) title else "$title · $episodeLabel"

    val displayLabel: String
        get() = "$displayTitle · $languageName"
}

data class LoadedAuthorizedSubtitle(
    val trackId: String,
    val cues: List<SubtitleCue>,
)

class CustomerSubtitleRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun signIn(email: String, password: String): CustomerSession {
        val normalizedEmail = email.trim()
        require(normalizedEmail.isNotBlank()) { "Enter your email address." }
        require(password.isNotEmpty()) { "Enter your password." }

        val response = request(
            method = "POST",
            url = "${CustomerBackendConfig.SUPABASE_URL}/auth/v1/token?grant_type=password",
            body = JSONObject().put("email", normalizedEmail).put("password", password).toString(),
        )
        ensureSuccess(response, "Unable to sign in")
        return parseAndSaveSession(JSONObject(response.body), fallbackEmail = normalizedEmail)
    }

    fun restoreSession(): CustomerSession? {
        val stored = loadStoredSession() ?: return null
        val now = System.currentTimeMillis() / 1000L
        if (stored.expiresAtEpochSeconds > now + SESSION_REFRESH_MARGIN_SECONDS) {
            return stored
        }
        return runCatching { refreshSession(stored) }
            .onFailure { clearSession() }
            .getOrNull()
    }

    fun signOut() {
        val session = loadStoredSession()
        if (session != null) {
            runCatching {
                request(
                    method = "POST",
                    url = "${CustomerBackendConfig.SUPABASE_URL}/auth/v1/logout",
                    accessToken = session.accessToken,
                )
            }
        }
        clearSession()
    }

    fun authorizedTracks(): List<AuthorizedSubtitleTrack> {
        val session = requireSession()
        val select = "id,language_code,language_name,label,cue_count,video:videos(title,episode_label,provider)"
        val url = buildString {
            append("${CustomerBackendConfig.SUPABASE_URL}/rest/v1/subtitle_tracks")
            append("?select=")
            append(encodeQueryValue(select))
            append("&is_active=eq.true&order=updated_at.desc")
        }
        val response = request("GET", url, accessToken = session.accessToken)
        ensureSuccess(response, "Unable to load your subtitles")
        return parseTracks(JSONArray(response.body))
    }

    fun loadAuthorizedSubtitle(trackId: String): LoadedAuthorizedSubtitle {
        require(trackId.isNotBlank()) { "Choose a subtitle first." }
        val session = requireSession()
        val select = "id,storage_path,cues"
        val url = buildString {
            append("${CustomerBackendConfig.SUPABASE_URL}/rest/v1/subtitle_tracks")
            append("?select=")
            append(encodeQueryValue(select))
            append("&id=eq.")
            append(encodeQueryValue(trackId))
            append("&limit=1")
        }
        val response = request("GET", url, accessToken = session.accessToken)
        ensureSuccess(response, "Unable to load the selected subtitle")
        val rows = JSONArray(response.body)
        if (rows.length() == 0) {
            error("This subtitle is no longer shared with your account.")
        }

        val row = rows.getJSONObject(0)
        val storagePath = row.optString("storage_path").takeIf { it.isNotBlank() && it != "null" }
        val cues = if (storagePath != null) {
            val srt = downloadStorageText(storagePath, session)
            SrtParser.parse(srt)
        } else {
            parseCueArray(row.optJSONArray("cues") ?: JSONArray())
        }
        require(cues.isNotEmpty()) { "The selected subtitle contains no valid cues." }
        return LoadedAuthorizedSubtitle(trackId = trackId, cues = cues)
    }

    fun hasStoredSession(): Boolean = loadStoredSession() != null

    private fun requireSession(): CustomerSession {
        return restoreSession() ?: error("Your session has expired. Please sign in again.")
    }

    private fun refreshSession(stored: CustomerSession): CustomerSession {
        if (stored.refreshToken.isBlank()) error("Your session has expired. Please sign in again.")
        val response = request(
            method = "POST",
            url = "${CustomerBackendConfig.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token",
            body = JSONObject().put("refresh_token", stored.refreshToken).toString(),
        )
        ensureSuccess(response, "Unable to refresh your session")
        return parseAndSaveSession(JSONObject(response.body), fallbackEmail = stored.email)
    }

    private fun downloadStorageText(storagePath: String, session: CustomerSession): String {
        val encodedPath = storagePath.split('/').joinToString("/") { encodePathSegment(it) }
        val url = "${CustomerBackendConfig.SUPABASE_URL}/storage/v1/object/authenticated/subtitle-files/$encodedPath"
        val response = request("GET", url, accessToken = session.accessToken)
        ensureSuccess(response, "Unable to read this private subtitle")
        return response.body
    }

    private fun parseTracks(rows: JSONArray): List<AuthorizedSubtitleTrack> {
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val video = embeddedVideo(row.opt("video"))
                val id = row.optString("id").trim()
                if (id.isBlank()) continue
                add(
                    AuthorizedSubtitleTrack(
                        id = id,
                        title = video?.optString("title")?.takeIf(String::isNotBlank) ?: "Untitled",
                        episodeLabel = video?.optString("episode_label").orEmpty(),
                        languageCode = row.optString("language_code"),
                        languageName = row.optString("language_name").ifBlank { "Subtitle" },
                        cueCount = row.optInt("cue_count", 0),
                    )
                )
            }
        }
    }

    private fun embeddedVideo(value: Any?): JSONObject? {
        return when (value) {
            is JSONObject -> value
            is JSONArray -> value.optJSONObject(0)
            else -> null
        }
    }

    private fun parseCueArray(rows: JSONArray): List<SubtitleCue> {
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val startSeconds = row.optDouble("start", Double.NaN)
                val endSeconds = row.optDouble("end", Double.NaN)
                val text = row.optString("text").trim()
                if (!startSeconds.isFinite() || !endSeconds.isFinite() || endSeconds <= startSeconds || text.isBlank()) {
                    continue
                }
                add(
                    SubtitleCue(
                        startMs = (startSeconds * 1000.0).roundToLong(),
                        endMs = (endSeconds * 1000.0).roundToLong(),
                        text = text,
                    )
                )
            }
        }.sortedBy(SubtitleCue::startMs)
    }

    private fun parseAndSaveSession(payload: JSONObject, fallbackEmail: String): CustomerSession {
        val accessToken = payload.optString("access_token")
        val refreshToken = payload.optString("refresh_token")
        if (accessToken.isBlank()) error("Supabase did not return a valid session.")
        val email = payload.optJSONObject("user")?.optString("email")?.ifBlank { fallbackEmail } ?: fallbackEmail
        val expiresIn = payload.optLong("expires_in", DEFAULT_EXPIRES_IN_SECONDS)
        val session = CustomerSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            email = email,
            expiresAtEpochSeconds = System.currentTimeMillis() / 1000L + expiresIn,
        )
        preferences.edit {
            putString(KEY_ACCESS_TOKEN, session.accessToken)
            putString(KEY_REFRESH_TOKEN, session.refreshToken)
            putString(KEY_EMAIL, session.email)
            putLong(KEY_EXPIRES_AT, session.expiresAtEpochSeconds)
        }
        return session
    }

    private fun loadStoredSession(): CustomerSession? {
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null)?.takeIf(String::isNotBlank) ?: return null
        return CustomerSession(
            accessToken = accessToken,
            refreshToken = preferences.getString(KEY_REFRESH_TOKEN, "").orEmpty(),
            email = preferences.getString(KEY_EMAIL, "").orEmpty(),
            expiresAtEpochSeconds = preferences.getLong(KEY_EXPIRES_AT, 0L),
        )
    }

    private fun clearSession() {
        preferences.edit { clear() }
    }

    private fun request(
        method: String,
        url: String,
        accessToken: String? = null,
        body: String? = null,
    ): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("apikey", CustomerBackendConfig.SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            if (!accessToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $accessToken")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureSuccess(response: HttpResponse, action: String) {
        if (response.status in 200..299) return
        val detail = runCatching {
            val payload = JSONObject(response.body)
            payload.optString("message")
                .ifBlank { payload.optString("error_description") }
                .ifBlank { payload.optString("msg") }
                .ifBlank { payload.optString("error") }
        }.getOrNull().orEmpty()
        error(if (detail.isBlank()) "$action (HTTP ${response.status})." else "$action: $detail")
    }

    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")

    private fun encodePathSegment(value: String): String = encodeQueryValue(value)

    private data class HttpResponse(val status: Int, val body: String)

    companion object {
        private const val PREFS_NAME = "customer_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EMAIL = "email"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val DEFAULT_EXPIRES_IN_SECONDS = 3600L
        private const val SESSION_REFRESH_MARGIN_SECONDS = 90L
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
    }
}
