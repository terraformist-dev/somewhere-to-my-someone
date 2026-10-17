package dev.terraformist.someone

import android.content.Context
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Talks to the owner's Firebase project over plain web requests (no Google libraries needed):
 * an anonymous sign-in for each phone, and the Realtime Database for pairing and the
 * scrambled locations. Every call blocks, so only use it off the main thread.
 *
 * The rules in `firebase-rules.json` (pasted into the Firebase console) are what stop
 * anyone other than the two paired phones reading or writing a pair.
 */
object Cloud {

    class CloudException(val code: Int, message: String) : IOException(message)

    private const val PREFS = "cloud"

    val ready: Boolean get() = FirebaseSetup.API_KEY.isNotBlank() && FirebaseSetup.DATABASE_URL.isNotBlank()

    // ---------- Signing in ----------

    private data class Session(val uid: String, val idToken: String)

    @Volatile private var session: Session? = null
    @Volatile private var expiresAt = 0L

    /** This phone's id in Firebase. Signs in the first time. */
    @Synchronized
    fun uid(context: Context): String = signIn(context).uid

    @Synchronized
    private fun signIn(context: Context): Session {
        if (!ready) throw CloudException(0, "Firebase isn't set up yet")
        val now = System.currentTimeMillis()
        session?.let { if (now < expiresAt - 60_000L) return it }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val refresh = prefs.getString("refresh", null)
        val fresh: Session
        val lifetimeSec: Long
        if (refresh == null) {
            val body = JSONObject().put("returnSecureToken", true).toString()
            val reply = JSONObject(
                send("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${FirebaseSetup.API_KEY}", body, json = true),
            )
            fresh = Session(reply.getString("localId"), reply.getString("idToken"))
            lifetimeSec = reply.optString("expiresIn", "3600").toLongOrNull() ?: 3600L
            prefs.edit().putString("refresh", reply.getString("refreshToken")).putString("uid", fresh.uid).apply()
        } else {
            val body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8")
            val reply = JSONObject(
                send("POST", "https://securetoken.googleapis.com/v1/token?key=${FirebaseSetup.API_KEY}", body, json = false),
            )
            fresh = Session(reply.getString("user_id"), reply.getString("id_token"))
            lifetimeSec = reply.optString("expires_in", "3600").toLongOrNull() ?: 3600L
            prefs.edit().putString("refresh", reply.getString("refresh_token")).putString("uid", fresh.uid).apply()
        }
        session = fresh
        expiresAt = now + lifetimeSec * 1000L
        return fresh
    }

    // ---------- The database ----------

    private fun url(context: Context, path: String): String {
        val base = FirebaseSetup.DATABASE_URL.trimEnd('/')
        return "$base/$path.json?auth=${signIn(context).idToken}"
    }

    /** The JSON at [path]: a JSONObject, a String, a Number, a Boolean, or null if there's nothing there. */
    fun get(context: Context, path: String): Any? {
        val text = send("GET", url(context, path), null, json = true)
        val value = JSONTokener(text).nextValue()
        return if (value == JSONObject.NULL) null else value
    }

    /** Write [value] (any JSON) at [path], replacing what's there. */
    fun put(context: Context, path: String, value: Any) {
        val text = if (value is String) JSONObject.quote(value) else JSONObject.wrap(value).toString()
        send("PUT", url(context, path), text, json = true)
    }

    /** Write several children of [path] at once, leaving the others alone. */
    fun patch(context: Context, path: String, values: JSONObject) {
        send("PATCH", url(context, path), values.toString(), json = true)
    }

    fun delete(context: Context, path: String) {
        send("DELETE", url(context, path), null, json = true)
    }

    /** The database's own clock, for "written at". */
    fun serverTime(): JSONObject = JSONObject().put(".sv", "timestamp")

    // ---------- Plumbing ----------

    private fun send(method: String, address: String, body: String?, json: Boolean): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            // HttpURLConnection can't send PATCH; Firebase accepts this instead.
            if (method == "PATCH") {
                connection.requestMethod = "POST"
                connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                connection.requestMethod = method
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    if (json) "application/json; charset=utf-8" else "application/x-www-form-urlencoded",
                )
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                if (code == 401) session = null
                throw CloudException(code, "Firebase said $code: ${text.take(200)}")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }
}
