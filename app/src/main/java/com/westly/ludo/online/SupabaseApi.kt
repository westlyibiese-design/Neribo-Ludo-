package com.westly.ludo.online

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What a Supabase call returns: the HTTP status and the raw body text. The text is never logged, because it can hold tokens. */
internal class HttpReply(val code: Int, val text: String) {
    val ok: Boolean get() = code in 200..299
}

/** One shared OkHttp client for every Online call, with a 15-second limit per call. */
internal object OnlineHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    val JSON = "application/json; charset=utf-8".toMediaType()

    /** Sends the request and reads the whole answer. A network problem throws IOException. Call it off the main thread. */
    fun call(request: Request): HttpReply =
        client.newCall(request).execute().use { r -> HttpReply(r.code, r.body?.string().orEmpty()) }
}

/**
 * A failed Online call. [message] is already friendly and safe to show to the player.
 * [kind] lets the caller decide what to do (for example offer a retry when there is no network).
 */
class OnlineException(val kind: Kind, message: String, val httpCode: Int = 0) : Exception(message) {
    enum class Kind { NO_NETWORK, NOT_SIGNED_IN, HTTP, BAD_ANSWER }
}

/**
 * Talks to Supabase over plain HTTPS with the signed-in player's token.
 *
 * All privileged logic lives in database functions that are called as RPC, so this stays small:
 * [rpc] for those functions, [getUser] to check that a session works, and [request] for anything else.
 * If the server answers 401 the token is refreshed once and the call is repeated.
 */
class SupabaseApi(private val auth: OnlineAuth) {

    /** Calls the database function [name] with [args] and returns its JSON answer. */
    suspend fun rpc(name: String, args: JSONObject = JSONObject()): JSONObject =
        request("POST", "/rest/v1/rpc/$name", args)

    /** Asks Supabase who the token belongs to. Used as the smoke test after signing in. */
    suspend fun getUser(): JSONObject = request("GET", "/auth/v1/user")

    /** An authenticated call. [path] starts with "/", for example "/rest/v1/rpc/some_function". */
    suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val first = auth.accessToken()
                ?: throw OnlineException(OnlineException.Kind.NOT_SIGNED_IN, "Please sign in with Google first.")
            var reply = send(method, path, body, first)
            if (reply.code == 401) {
                val fresh = auth.accessToken(forceRefresh = true)
                    ?: throw OnlineException(OnlineException.Kind.NOT_SIGNED_IN, "Please sign in with Google again.")
                reply = send(method, path, body, fresh)
            }
            if (!reply.ok) {
                // Only the status and the path are logged, never the body.
                Log.w(TAG, "$method $path failed with HTTP ${reply.code}")
                throw OnlineException(OnlineException.Kind.HTTP, "Something went wrong. Please try again.", reply.code)
            }
            if (reply.text.isBlank()) {
                JSONObject()
            } else {
                try {
                    // A function that returns a list is wrapped as {"data": [...]}.
                    if (reply.text.trimStart().startsWith("[")) {
                        JSONObject().put("data", JSONArray(reply.text))
                    } else {
                        JSONObject(reply.text)
                    }
                } catch (e: JSONException) {
                    throw OnlineException(OnlineException.Kind.BAD_ANSWER, "Unexpected answer from the server.")
                }
            }
        }

    private fun send(method: String, path: String, body: JSONObject?, token: String): HttpReply {
        val requestBody =
            if (method == "GET") null else (body ?: JSONObject()).toString().toRequestBody(OnlineHttp.JSON)
        val request = Request.Builder()
            .url(OnlineConfig.SUPABASE_URL + path)
            .header("apikey", OnlineConfig.SUPABASE_PUBLISHABLE_KEY)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .method(method, requestBody)
            .build()
        return try {
            OnlineHttp.call(request)
        } catch (e: IOException) {
            throw OnlineException(OnlineException.Kind.NO_NETWORK, "Couldn't reach the server. Your internet may be off or slow - please try again.")
        }
    }

    private companion object {
        const val TAG = "OnlineApi"
    }
}
