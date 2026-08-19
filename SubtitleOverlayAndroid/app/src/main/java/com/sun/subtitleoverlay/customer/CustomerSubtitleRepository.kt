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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal object CustomerBackendConfig {
    const val SUPABASE_URL = "https://qtpxlrnazsonqdljafkd.supabase.co"
    const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_ZIlaAn2SOwncEW11LyzUHg_hI9Wzmtg"
    const val PORTAL_URL = "https://subtitle-project-978670366914.europe-west2.run.app/customer"
    const val GOOGLE_OAUTH_REDIRECT_URL = PORTAL_URL
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
    val provider: String,
    val languageCode: String,
    val languageName: String,
    val label: String,
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

    fun createGoogleSignInUrl(): String {
        val settingsResponse = request(
            method = "GET",
            url = "${CustomerBackendConfig.SUPABASE_URL}/auth/v1/settings",
        )
        ensureSuccess(settingsResponse, "Unable to check Google sign-in")
        val googleEnabled = JSONObject(settingsResponse.body)
            .optJSONObject("external")
            ?.optBoolean("google", false)
            ?: false
        if (!googleEnabled) {
            error("Google sign-in is not enabled in Supabase yet.")
        }

        val verifier = randomBase64Url(32)
        val challenge = sha256Base64Url(verifier)
        preferences.edit {
            putString(KEY_OAUTH_CODE_VERIFIER, verifier)
            putLong(KEY_OAUTH_STARTED_AT, System.currentTimeMillis())
        }

        return buildString {
            append("${CustomerBackendConfig.SUPABASE_URL}/auth/v1/authorize")
            append("?provider=google")
            append("&redirect_to=")
            append(encodeQueryValue(CustomerBackendConfig.GOOGLE_OAUTH_REDIRECT_URL))
            append("&code_challenge=")
            append(encodeQueryValue(challenge))
            append("&code_challenge_method=s256")
        }
    }

    fun completeGoogleSignIn(authCode: String): CustomerSession {
        require(authCode.isNotBlank()) { "Google sign-in did not return an authorization code." }
        val verifier = preferences.getString(KEY_OAUTH_CODE_VERIFIER, null)
            ?.takeIf(String::isNotBlank)
            ?: error("Google sign-in has expired. Please try again.")
        val startedAt = preferences.getLong(KEY_OAUTH_STARTED_AT, 0L)
        if (startedAt <= 0L || System.currentTimeMillis() - startedAt > OAUTH_MAX_AGE_MS) {
            clearPendingGoogleSignIn()
            error("Google sign-in has expired. Please try again.")
        }

        return try {
            val response = request(
                method = "POST",
                url = "${CustomerBackendConfig.SUPABASE_URL}/auth/v1/token?grant_type=pkce",
                body = JSONObject()
                    .put("auth_code", authCode)
                    .put("code_verifier", verifier)
                    .toString(),
            )
            ensureSuccess(response, "Unable to complete Google sign-in")
            val session = parseAndSaveSession(JSONObject(response.body), fallbackEmail = "")

            // Match the customer portal: verify the signed-in Supabase user after OAuth
            // before treating the callback as a completed customer session.
            val userResponse = request(
                method = "GET",
                url = "${CustomerBackendConfig.SUPABASE_URL}/auth/v1/user",
                accessToken = session.accessToken,
            )
            ensureSuccess(userResponse, "Unable to verify your Google account")
            val verifiedEmail = JSONObject(userResponse.body)
                .optString("email")
                .ifBlank { session.email }
            saveSession(session.copy(email = verifiedEmail))
        } finally {
            clearPendingGoogleSignIn()
        }
    }

    fun clearPendingGoogleSignIn() {
        preferences.edit {
            remove(KEY_OAUTH_CODE_VERIFIER)
            remove(KEY_OAUTH_STARTED_AT)
        }
    }

    fun restoreSession(): CustomerSession? {
        val stored = loadStoredSession() ?: return null
        val now = System.currentTimeMillis() / 1000L
        if (stored.expiresAtEpochSeconds > now + SESSION_REFRESH_MARGIN_SECONDS) {
            return stored
        }

        return try {
            refreshSession(stored)
        } catch (_: SessionRejectedException) {
            clearSession()
            null
        } catch (_: Exception) {
            // Keep the refresh token for transient network/server failures so the next
            // app launch can retry automatic sign-in without asking for a password.
            null
        }
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
        val select = "id,storage_path"
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
        val storagePath = row.optString("storage_path")
            .takeIf { it.isNotBlank() && it != "null" }
            ?: error("This subtitle has not been migrated to private Storage yet. Please contact support.")
        val srt = downloadStorageText(storagePath, session)
        val cues = SrtParser.parse(srt)
        require(cues.isNotEmpty()) { "The selected subtitle contains no valid cues." }
        return LoadedAuthorizedSubtitle(trackId = trackId, cues = cues)
    }

    fun hasStoredSession(): Boolean = loadStoredSession() != null

    private fun requireSession(): CustomerSession {
        return restoreSession() ?: error("Your session has expired or could not be refreshed. Please check your connection or sign in again.")
    }

    private fun refreshSession(stored: CustomerSession): CustomerSession {
        if (stored.refreshToken.isBlank()) {
            throw SessionRejectedException("Your saved session cannot be refreshed.")
        }

        val response = request(
            method = "POST",
            url = "${CustomerBackendConfig.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token",
            body = JSONObject().put("refresh_token", stored.refreshToken).toString(),
        )

        if (response.status == HttpURLConnection.HTTP_BAD_REQUEST ||
            response.status == HttpURLConnection.HTTP_UNAUTHORIZED ||
            response.status == HttpURLConnection.HTTP_FORBIDDEN
        ) {
            throw SessionRejectedException(responseErrorMessage(response, "Your saved session is no longer valid."))
        }

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
                        provider = video?.optString("provider").orEmpty(),
                        languageCode = row.optString("language_code"),
                        languageName = row.optString("language_name").ifBlank { "Subtitle" },
                        label = row.optString("label"),
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
        return saveSession(session)
    }

    private fun saveSession(session: CustomerSession): CustomerSession {
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
        error(responseErrorMessage(response, "$action (HTTP ${response.status})."))
    }

    private fun responseErrorMessage(response: HttpResponse, fallback: String): String {
        val detail = runCatching {
            val payload = JSONObject(response.body)
            payload.optString("message")
                .ifBlank { payload.optString("error_description") }
                .ifBlank { payload.optString("msg") }
                .ifBlank { payload.optString("error") }
        }.getOrNull().orEmpty()
        return if (detail.isBlank()) fallback else detail
    }

    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")

    private fun encodePathSegment(value: String): String = encodeQueryValue(value)

    private fun randomBase64Url(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256Base64Url(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private data class HttpResponse(val status: Int, val body: String)

    private class SessionRejectedException(message: String) : IllegalStateException(message)

    companion object {
        private const val PREFS_NAME = "customer_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EMAIL = "email"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_OAUTH_CODE_VERIFIER = "google_oauth_code_verifier"
        private const val KEY_OAUTH_STARTED_AT = "google_oauth_started_at"
        private const val OAUTH_MAX_AGE_MS = 10 * 60 * 1000L
        private const val DEFAULT_EXPIRES_IN_SECONDS = 3600L
        private const val SESSION_REFRESH_MARGIN_SECONDS = 90L
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
    }
}
