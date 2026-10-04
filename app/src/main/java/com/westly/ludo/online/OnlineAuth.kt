package com.westly.ludo.online

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** The signed-in player. [name] is the Google display name; it is only shown in the account dialog. */
data class OnlineUser(val id: String, val name: String)

/** The Supabase session kept on this phone. [expiresAt] is in seconds since 1970. */
private class Session(
    val access: String,
    val refresh: String,
    val expiresAt: Long,
    val userId: String,
    val name: String
)

/**
 * Online sign-in: Google (Credential Manager) -> Supabase session.
 *
 * Owned by MainActivity, not by a screen, so the session survives moving between screens.
 * [user], [busy] and [error] are Compose state, so screens update by themselves.
 *
 * Only the Supabase user id and the Google display name are kept. The Google email is never
 * stored, and no token is ever logged.
 */
class OnlineAuth(context: Context, private val prefs: SharedPreferences) {
    private val appContext = context.applicationContext
    private val refreshLock = Mutex()

    @Volatile
    private var session: Session? = null

    /** The signed-in player, or null. Restored from storage at start without any network call. */
    var user by mutableStateOf<OnlineUser?>(null)
        private set

    /** True while the Google chooser or the Supabase exchange is running. */
    var busy by mutableStateOf(false)
        private set

    /** A friendly message for the player, or null. A closed chooser is not an error. */
    var error by mutableStateOf<String?>(null)
        private set

    /** The technical reason, shown in small text so it can be used for troubleshooting. */
    var errorDetail by mutableStateOf<String?>(null)
        private set

    /** True when the phone has no Google account: the sign-in panel then offers an "Add account" button. */
    var canAddAccount by mutableStateOf(false)
        private set

    /** The REST helper later phases use; it takes its token from this class. */
    val api: SupabaseApi by lazy { SupabaseApi(this@OnlineAuth) }

    init {
        restore()
    }

    fun clearError() {
        error = null
        errorDetail = null
        canAddAccount = false
    }

    /** Opens the phone's "add account" screen, pre-set to Google accounts. */
    fun addAccountIntent(): Intent =
        Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))

    /**
     * Shows the Google account chooser and, if the player picks an account, turns Google's answer
     * into a Supabase session. Returns true when the player ends up signed in.
     * Must be called from the main thread (the chooser needs the [activity]).
     */
    suspend fun signIn(activity: Activity): Boolean {
        if (busy) return false
        busy = true
        clearError()
        try {
            if (!isOnline()) {
                fail(MSG_OFFLINE, "no active network")
                return false
            }
            // Google gets the hashed nonce, Supabase gets the raw one, and Supabase checks they match.
            val rawNonce = UUID.randomUUID().toString()
            val option = GetSignInWithGoogleOption.Builder(OnlineConfig.GOOGLE_WEB_CLIENT_ID)
                .setNonce(sha256Hex(rawNonce))
                .build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val result = CredentialManager.create(activity).getCredential(activity, request)

            val custom = result.credential as? CustomCredential
            if (custom == null || custom.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                fail(MSG_FAILED, "unexpected credential type")
                return false
            }
            val google = GoogleIdTokenCredential.createFrom(custom.data)

            val reply = withContext(Dispatchers.IO) { exchange(google.idToken, rawNonce) }
            if (!reply.ok) {
                fail(MSG_FAILED, "Supabase HTTP ${reply.code}: ${reasonOf(reply.text)}")
                return false
            }
            val name = google.displayName?.trim().orEmpty().ifEmpty { "Player" }
            store(parseSession(reply.text, name))

            // Smoke test: the new token must work against Supabase.
            try {
                api.getUser()
            } catch (e: OnlineException) {
                forget()
                if (e.kind == OnlineException.Kind.NO_NETWORK) {
                    fail(MSG_OFFLINE, "user check: no network")
                } else {
                    fail(MSG_FAILED, "user check failed: ${e.kind} HTTP ${e.httpCode}")
                }
                return false
            }
            return true
        } catch (e: GetCredentialCancellationException) {
            // The player closed the chooser. Not an error, so no message.
            return false
        } catch (e: NoCredentialException) {
            canAddAccount = true
            fail("Add a Google account to your phone, then try again.", describe(e))
            return false
        } catch (e: GetCredentialProviderConfigurationException) {
            fail("This feature needs Google Play Services on your phone.", describe(e))
            return false
        } catch (e: GetCredentialException) {
            fail(MSG_FAILED, describe(e))
            return false
        } catch (e: IOException) {
            fail(MSG_OFFLINE, describe(e))
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(MSG_FAILED, describe(e))
            return false
        } finally {
            busy = false
        }
    }

    /**
     * A token that is valid for at least a minute, refreshed first if needed, or null when nobody
     * is signed in (or the refresh was rejected, which also signs out locally).
     * [forceRefresh] is for a 401 answer: the server says this token is no good, so get a new one.
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String? {
        val current = session ?: return null
        if (!forceRefresh && current.expiresAt - nowSeconds() > REFRESH_MARGIN_SECONDS) return current.access
        return refreshLock.withLock {
            val latest = session ?: return@withLock null
            // Another caller may have refreshed while this one waited for the lock.
            if (latest.access != current.access && latest.expiresAt - nowSeconds() > REFRESH_MARGIN_SECONDS) {
                return@withLock latest.access
            }
            refresh(latest)
        }
    }

    /**
     * Signs out on this phone straight away (so screens update at once), then tells Supabase and
     * Google as a best effort, so the account chooser appears again next time.
     */
    suspend fun signOut() {
        val old = session
        forget()
        clearError()
        if (old != null) {
            withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url("${OnlineConfig.SUPABASE_URL}/auth/v1/logout?scope=local")
                        .header("apikey", OnlineConfig.SUPABASE_PUBLISHABLE_KEY)
                        .header("Authorization", "Bearer ${old.access}")
                        .post("{}".toRequestBody(OnlineHttp.JSON))
                        .build()
                    OnlineHttp.call(request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "logout call failed: ${e.javaClass.simpleName}")
                }
            }
        }
        try {
            CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "clearCredentialState failed: ${e.javaClass.simpleName}")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Session storage
    // ---------------------------------------------------------------------------------------

    private fun restore() {
        val text = prefs.getString(KEY_SESSION, null) ?: return
        try {
            val j = JSONObject(text)
            val s = Session(
                access = j.getString("access"),
                refresh = j.getString("refresh"),
                expiresAt = j.getLong("expiresAt"),
                userId = j.getString("userId"),
                name = j.optString("name", "")
            )
            session = s
            user = OnlineUser(s.userId, s.name)
        } catch (e: JSONException) {
            prefs.edit().remove(KEY_SESSION).apply()
        }
    }

    private fun store(s: Session) {
        session = s
        user = OnlineUser(s.userId, s.name)
        val j = JSONObject()
            .put("access", s.access)
            .put("refresh", s.refresh)
            .put("expiresAt", s.expiresAt)
            .put("userId", s.userId)
            .put("name", s.name)
        prefs.edit().putString(KEY_SESSION, j.toString()).apply()
    }

    private fun forget() {
        session = null
        user = null
        prefs.edit().remove(KEY_SESSION).apply()
    }

    // ---------------------------------------------------------------------------------------
    // Talking to Supabase Auth
    // ---------------------------------------------------------------------------------------

    private fun exchange(idToken: String, rawNonce: String): HttpReply {
        val body = JSONObject()
            .put("provider", "google")
            .put("id_token", idToken)
            .put("nonce", rawNonce)
            .toString()
            .toRequestBody(OnlineHttp.JSON)
        val request = Request.Builder()
            .url("${OnlineConfig.SUPABASE_URL}/auth/v1/token?grant_type=id_token")
            .header("apikey", OnlineConfig.SUPABASE_PUBLISHABLE_KEY)
            .post(body)
            .build()
        return OnlineHttp.call(request)
    }

    /** Swaps the refresh token for a new session. Supabase rotates refresh tokens, so the new one is stored. */
    private suspend fun refresh(s: Session): String? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("refresh_token", s.refresh).toString().toRequestBody(OnlineHttp.JSON)
            val request = Request.Builder()
                .url("${OnlineConfig.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token")
                .header("apikey", OnlineConfig.SUPABASE_PUBLISHABLE_KEY)
                .post(body)
                .build()
            val reply = OnlineHttp.call(request)
            when {
                reply.ok -> {
                    val fresh = parseSession(reply.text, s.name)
                    store(fresh)
                    fresh.access
                }
                reply.code == 400 || reply.code == 401 -> {
                    // The server will not accept this session any more: sign out on this phone.
                    Log.w(TAG, "refresh rejected with HTTP ${reply.code}; signing out locally")
                    forget()
                    null
                }
                else -> stillValid(s)
            }
        } catch (e: IOException) {
            stillValid(s)
        } catch (e: JSONException) {
            stillValid(s)
        }
    }

    /** When a refresh could not be tried (no network), the old token is still fine until it really expires. */
    private fun stillValid(s: Session): String? = if (s.expiresAt - nowSeconds() > 0) s.access else null

    private fun parseSession(text: String, name: String): Session {
        val j = JSONObject(text)
        val u = j.getJSONObject("user")
        return Session(
            access = j.getString("access_token"),
            refresh = j.getString("refresh_token"),
            expiresAt = nowSeconds() + j.optLong("expires_in", 3600L),
            userId = u.getString("id"),
            name = name
        )
    }

    // ---------------------------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------------------------

    private fun fail(message: String, detail: String) {
        error = message
        errorDetail = detail
        Log.w(TAG, "sign-in problem: $detail")
    }

    private fun describe(e: Exception): String = "${e.javaClass.simpleName}: ${e.message.orEmpty().take(160)}"

    /** The short reason out of a Supabase error answer (never the whole body). */
    private fun reasonOf(text: String): String = try {
        val j = JSONObject(text)
        listOf("msg", "error_description", "message", "error")
            .map { j.optString(it, "") }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .take(160)
    } catch (e: JSONException) {
        ""
    }

    private fun isOnline(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "OnlineAuth"
        const val KEY_SESSION = "online_session"
        const val REFRESH_MARGIN_SECONDS = 60L
        const val MSG_OFFLINE = "Online needs an internet connection."
        const val MSG_FAILED = "Sign-in failed. Please try again."
    }
}
