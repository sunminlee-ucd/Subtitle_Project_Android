from pathlib import Path

ROOT = Path('.')
main_path = ROOT / 'SubtitleOverlayAndroid/app/src/main/java/com/sun/subtitleoverlay/MainActivity.kt'
repo_path = ROOT / 'SubtitleOverlayAndroid/app/src/main/java/com/sun/subtitleoverlay/customer/CustomerSubtitleRepository.kt'
drawable_path = ROOT / 'SubtitleOverlayAndroid/app/src/main/res/drawable/google_g_logo.xml'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected exactly one match, found {count}')
    return text.replace(old, new, 1)


repo = repo_path.read_text(encoding='utf-8')
if 'Google sign-in is not enabled in Supabase yet.' not in repo:
    repo = replace_once(
        repo,
        '''    fun createGoogleSignInUrl(): String {
        val verifier = randomBase64Url(32)
''',
        '''    fun createGoogleSignInUrl(): String {
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
''',
        'Supabase Google provider check',
    )

    repo = replace_once(
        repo,
        '''            ensureSuccess(response, "Unable to complete Google sign-in")
            parseAndSaveSession(JSONObject(response.body), fallbackEmail = "")
        } finally {
''',
        '''            ensureSuccess(response, "Unable to complete Google sign-in")
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
''',
        'Google OAuth user verification',
    )

    repo = replace_once(
        repo,
        '''        preferences.edit {
            putString(KEY_ACCESS_TOKEN, session.accessToken)
            putString(KEY_REFRESH_TOKEN, session.refreshToken)
            putString(KEY_EMAIL, session.email)
            putLong(KEY_EXPIRES_AT, session.expiresAtEpochSeconds)
        }
        return session
    }

    private fun loadStoredSession(): CustomerSession? {
''',
        '''        return saveSession(session)
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
''',
        'shared session persistence',
    )
    repo_path.write_text(repo, encoding='utf-8')


main = main_path.read_text(encoding='utf-8')
if 'R.drawable.google_g_logo' not in main:
    main = replace_once(
        main,
        'import android.widget.EditText\nimport android.widget.LinearLayout\n',
        'import android.widget.EditText\nimport android.widget.ImageView\nimport android.widget.LinearLayout\n',
        'ImageView import',
    )
    main = replace_once(
        main,
        '    private lateinit var googleSignInButton: TextView\n',
        '    private lateinit var googleSignInButton: LinearLayout\n',
        'Google button type',
    )

    main = replace_once(
        main,
        '''    private fun startGoogleSignIn() {
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
''',
        '''    private fun startGoogleSignIn() {
        if (::googleSignInButton.isInitialized && !googleSignInButton.isEnabled) return
        setGoogleSignInBusy(true)
        setStatus("Checking Google sign-in…")

        executor.execute {
            val result = runCatching { repository.createGoogleSignInUrl() }
            runOnUiThread {
                result.onSuccess { authorizationUrl ->
                    setStatus("Opening Google sign-in…")
                    val opened = runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, authorizationUrl.toUri()))
                    }
                    setGoogleSignInBusy(false)
                    if (opened.isFailure) {
                        repository.clearPendingGoogleSignIn()
                        setStatus(opened.exceptionOrNull()?.message ?: "Unable to open Google sign-in.")
                    }
                }.onFailure { error ->
                    setGoogleSignInBusy(false)
                    setStatus(error.message ?: "Unable to start Google sign-in.")
                }
            }
        }
    }
''',
        'background Google provider check',
    )

    main = replace_once(
        main,
        '''    private fun googleAuthButton(action: () -> Unit) = TextView(this).apply {
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
''',
        '''    private fun googleAuthButton(action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        background = roundedBackground(Color.WHITE, dp(12), 0xFFDADCE0.toInt())
        isClickable = true
        isFocusable = true
        contentDescription = "Continue with Google"

        addView(ImageView(context).apply {
            setImageResource(R.drawable.google_g_logo)
            contentDescription = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply {
            marginEnd = dp(10)
        })

        addView(TextView(context).apply {
            text = "Continue with Google"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF202124.toInt())
            gravity = Gravity.CENTER_VERTICAL
        })

        setOnClickListener { action() }
    }

    private fun setGoogleSignInBusy(busy: Boolean) {
        if (!::googleSignInButton.isInitialized) return
        googleSignInButton.isEnabled = !busy
        googleSignInButton.isClickable = !busy
        googleSignInButton.alpha = if (busy) 0.65f else 1f
    }

    private fun compactButton(label: String, action: () -> Unit) = TextView(this).apply {
''',
        'Google branded button',
    )
    main_path.write_text(main, encoding='utf-8')


drawable_path.parent.mkdir(parents=True, exist_ok=True)
drawable_path.write_text('''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="18dp"
    android:height="18dp"
    android:viewportWidth="18"
    android:viewportHeight="18">
    <path
        android:fillColor="#4285F4"
        android:pathData="M17.64,9.205c0,-0.638 -0.057,-1.252 -0.164,-1.841H9v3.481h4.844c-0.209,1.125 -0.843,2.078 -1.796,2.716v2.258h2.909c1.703,-1.568 2.683,-3.878 2.683,-6.614z" />
    <path
        android:fillColor="#34A853"
        android:pathData="M9,18c2.43,0 4.467,-0.806 5.956,-2.18l-2.909,-2.259c-0.806,0.54 -1.835,0.859 -3.047,0.859 -2.344,0 -4.328,-1.585 -5.037,-3.714H0.956v2.332C2.437,15.983 5.482,18 9,18z" />
    <path
        android:fillColor="#FBBC05"
        android:pathData="M3.963,10.706A5.41,5.41 0,0 1,3.682 9c0,-0.592 0.102,-1.167 0.281,-1.706V4.962H0.956A9.003,9.003 0,0 0,0 9c0,1.45 0.347,2.824 0.956,4.038l3.007,-2.332z" />
    <path
        android:fillColor="#EA4335"
        android:pathData="M9,3.58c1.321,0 2.508,0.454 3.441,1.346l2.581,-2.581C13.463,0.892 11.426,0 9,0 5.482,0 2.437,2.017 0.956,4.962l3.007,2.332C4.672,5.165 6.656,3.58 9,3.58z" />
</vector>
''', encoding='utf-8')

print('Android Google auth synced with customer portal semantics and branded logo.')
