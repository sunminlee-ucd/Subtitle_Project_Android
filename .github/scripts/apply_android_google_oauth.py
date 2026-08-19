from pathlib import Path

ROOT = Path('.')
main_path = ROOT / 'SubtitleOverlayAndroid/app/src/main/java/com/sun/subtitleoverlay/MainActivity.kt'
repo_path = ROOT / 'SubtitleOverlayAndroid/app/src/main/java/com/sun/subtitleoverlay/customer/CustomerSubtitleRepository.kt'
manifest_path = ROOT / 'SubtitleOverlayAndroid/app/src/main/AndroidManifest.xml'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected exactly one match, found {count}')
    return text.replace(old, new, 1)


# CustomerSubtitleRepository: add a PKCE Google OAuth flow while keeping the existing
# password login and refresh-token session persistence unchanged.
repo = repo_path.read_text(encoding='utf-8')
if 'fun createGoogleSignInUrl()' not in repo:
    repo = replace_once(
        repo,
        'import java.nio.charset.StandardCharsets\n',
        'import java.nio.charset.StandardCharsets\nimport java.security.MessageDigest\nimport java.security.SecureRandom\nimport java.util.Base64\n',
        'repository imports',
    )

    repo = replace_once(
        repo,
        '    const val PORTAL_URL = "https://subtitle-project-978670366914.europe-west2.run.app/customer"\n',
        '    const val PORTAL_URL = "https://subtitle-project-978670366914.europe-west2.run.app/customer"\n'
        '    const val GOOGLE_OAUTH_REDIRECT_URL = "subtitlecompanion://auth-callback"\n',
        'oauth redirect config',
    )

    sign_in_end = '''        ensureSuccess(response, "Unable to sign in")
        return parseAndSaveSession(JSONObject(response.body), fallbackEmail = normalizedEmail)
    }

    fun restoreSession(): CustomerSession? {'''
    google_methods = '''        ensureSuccess(response, "Unable to sign in")
        return parseAndSaveSession(JSONObject(response.body), fallbackEmail = normalizedEmail)
    }

    fun createGoogleSignInUrl(): String {
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
            parseAndSaveSession(JSONObject(response.body), fallbackEmail = "")
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

    fun restoreSession(): CustomerSession? {'''
    repo = replace_once(repo, sign_in_end, google_methods, 'google oauth repository methods')

    helper_anchor = '''    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")

    private fun encodePathSegment(value: String): String = encodeQueryValue(value)
'''
    helper_replacement = '''    private fun encodeQueryValue(value: String): String =
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
'''
    repo = replace_once(repo, helper_anchor, helper_replacement, 'pkce helpers')

    constants_anchor = '''        private const val KEY_EMAIL = "email"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val DEFAULT_EXPIRES_IN_SECONDS = 3600L
'''
    constants_replacement = '''        private const val KEY_EMAIL = "email"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_OAUTH_CODE_VERIFIER = "google_oauth_code_verifier"
        private const val KEY_OAUTH_STARTED_AT = "google_oauth_started_at"
        private const val OAUTH_MAX_AGE_MS = 10 * 60 * 1000L
        private const val DEFAULT_EXPIRES_IN_SECONDS = 3600L
'''
    repo = replace_once(repo, constants_anchor, constants_replacement, 'oauth constants')
    repo_path.write_text(repo, encoding='utf-8')


# MainActivity: add a Google sign-in/sign-up button and consume the deep-link callback.
main = main_path.read_text(encoding='utf-8')
if 'private fun startGoogleSignIn()' not in main:
    main = replace_once(
        main,
        '    private lateinit var passwordInput: EditText\n    private lateinit var userEmailView: TextView\n',
        '    private lateinit var passwordInput: EditText\n    private lateinit var googleSignInButton: TextView\n    private lateinit var userEmailView: TextView\n',
        'google button field',
    )

    on_create_old = '''        setContentView(buildContent())
        requestNotificationPermissionIfNeeded()
        restoreCustomerSession()
    }

    override fun onResume() {'''
    on_create_new = '''        setContentView(buildContent())
        requestNotificationPermissionIfNeeded()
        if (!handleGoogleOAuthCallback(intent)) {
            restoreCustomerSession()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGoogleOAuthCallback(intent)
    }

    override fun onResume() {'''
    main = replace_once(main, on_create_old, on_create_new, 'deep link lifecycle')

    auth_button_old = '''                addView(actionButton("Sign in") { signIn() }, matchWrap())
            }
            addView(authCard, matchWrap(bottom = dp(16)))'''
    auth_button_new = '''                addView(actionButton("Sign in") { signIn() }, matchWrap(bottom = dp(12)))

                addView(TextView(context).apply {
                    text = "OR"
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(COLOR_MUTED)
                }, matchWrap(bottom = dp(10)))

                googleSignInButton = googleAuthButton {
                    startGoogleSignIn()
                }
                addView(googleSignInButton, matchWrap(bottom = dp(9)))
                addView(sectionDescription(
                    "Use Google to sign in or create a Subtitle Companion customer account."
                ), matchWrap())
            }
            addView(authCard, matchWrap(bottom = dp(16)))'''
    main = replace_once(main, auth_button_old, auth_button_new, 'google auth UI')

    sign_in_anchor = '''    private fun signOut() {
'''
    google_functions = '''    private fun startGoogleSignIn() {
        val authorizationUrl = runCatching { repository.createGoogleSignInUrl() }
            .getOrElse { error ->
                setStatus(error.message ?: "Unable to start Google sign-in.")
                return
            }

        setStatus("Continue with Google in your browser…")
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, authorizationUrl.toUri()))
        }
        if (opened.isFailure) {
            repository.clearPendingGoogleSignIn()
            setStatus(opened.exceptionOrNull()?.message ?: "Unable to open Google sign-in.")
        }
    }

    private fun handleGoogleOAuthCallback(sourceIntent: Intent?): Boolean {
        val data = sourceIntent?.data ?: return false
        if (data.scheme != "subtitlecompanion" || data.host != "auth-callback") return false

        val oauthError = data.getQueryParameter("error_description")
            ?: data.getQueryParameter("error")
        if (!oauthError.isNullOrBlank()) {
            repository.clearPendingGoogleSignIn()
            setStatus("Google sign-in failed: $oauthError")
            return true
        }

        val code = data.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            repository.clearPendingGoogleSignIn()
            setStatus("Google sign-in did not return an authorization code.")
            return true
        }

        setStatus("Finishing Google sign-in…")
        executor.execute {
            val result = runCatching { repository.completeGoogleSignIn(code) }
            runOnUiThread {
                result.onSuccess { session ->
                    passwordInput.text.clear()
                    setSignedIn(session)
                    Toast.makeText(this, "Signed in with Google.", Toast.LENGTH_SHORT).show()
                    loadTracks()
                }.onFailure { error ->
                    setStatus(error.message ?: "Unable to complete Google sign-in.")
                }
            }
        }
        return true
    }

    private fun signOut() {
'''
    main = replace_once(main, sign_in_anchor, google_functions, 'google oauth activity functions')

    helper_anchor = '''    private fun compactButton(label: String, action: () -> Unit) = TextView(this).apply {
'''
    google_button_helper = '''    private fun googleAuthButton(action: () -> Unit) = TextView(this).apply {
        text = "G   Continue with Google"
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(0xFF202124.toInt())
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = roundedBackground(Color.WHITE, dp(12), 0xFFDADCE0.toInt())
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun compactButton(label: String, action: () -> Unit) = TextView(this).apply {
'''
    main = replace_once(main, helper_anchor, google_button_helper, 'google button helper')
    main_path.write_text(main, encoding='utf-8')


# Manifest: route the OAuth callback into the existing MainActivity. singleTask keeps
# the callback in the existing customer session screen instead of creating duplicates.
manifest = manifest_path.read_text(encoding='utf-8')
if 'subtitlecompanion' not in manifest:
    manifest = replace_once(
        manifest,
        '''        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>''',
        '''        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data
                    android:scheme="subtitlecompanion"
                    android:host="auth-callback" />
            </intent-filter>
        </activity>''',
        'manifest oauth deep link',
    )
    manifest_path.write_text(manifest, encoding='utf-8')

print('Android Google OAuth PKCE flow applied.')
