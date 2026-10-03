package net.jamesjennison.filamajignfc

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * Runtime side of Sign in with ChatGPT: HTTP calls, the streamed Responses API reader,
 * the signed-in session with token refresh, and encrypted credential storage.
 */

private const val RESPONSES_URL = "https://api.openai.com/v1/responses"
private const val MODELS_URL = "https://api.openai.com/v1/models"
private const val MAX_RESPONSE_TEXT = 1 * 1024 * 1024
private const val REFRESH_MARGIN_MS = 60_000L

data class ChatGptModel(val slug: String, val displayName: String)

internal class ChatGptHttpResponse(val status: Int, val body: InputStream) : Closeable {
    fun text(limit: Int = 512 * 1024): String = body.use { stream ->
        val bytes = stream.readNBytesCompat(limit + 1)
        if (bytes.size > limit) throw ChatGptException("invalid_response", "ChatGPT returned an unexpectedly large response.", retryable = true, status = status)
        String(bytes, Charsets.UTF_8)
    }
    override fun close() { runCatching { body.close() } }
}

private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8_192)
    while (buffer.size() < limit) {
        val read = read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
        if (read < 0) break
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}

/** One HTTP exchange. Injectable so the whole flow can be tested without a network. */
internal fun interface ChatGptHttp {
    @Throws(IOException::class)
    fun execute(method: String, url: String, headers: Map<String, String>, body: ByteArray?, contentType: String?): ChatGptHttpResponse
}

private val chatGptHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // A redirect could carry the bearer token to another host, so none is followed.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

internal val okHttpChatGptHttp = ChatGptHttp { method, url, headers, body, contentType ->
    require(url.startsWith("https://")) { "ChatGPT requests must use HTTPS" }
    val request = Request.Builder().url(url).apply {
        headers.forEach { (name, value) -> header(name, value) }
        method(method, body?.toRequestBody(contentType?.toMediaType()))
    }.build()
    val response = chatGptHttpClient.newCall(request).execute()
    ChatGptHttpResponse(response.code, response.body?.byteStream() ?: ByteArray(0).inputStream())
}

/**
 * Reads a Responses API event stream and returns the accumulated output text.
 * Success requires a `response.completed` event; a stream that merely ends is an error.
 */
internal fun readResponseStream(stream: InputStream, status: Int = 200): String {
    val text = StringBuilder()
    val data = StringBuilder()
    var completed = false

    fun dispatch() {
        val payload = data.toString()
        data.setLength(0)
        if (payload.isEmpty() || payload == "[DONE]") return
        val event = runCatching { JSONObject(payload) }.getOrElse {
            throw ChatGptException("invalid_stream", "The response stream contained an invalid event. Try again.", retryable = true)
        }
        when (event.optString("type")) {
            "response.output_text.delta" -> {
                text.append(event.optString("delta"))
                if (text.length > MAX_RESPONSE_TEXT) throw ChatGptException("response_too_large", "The response was too large.")
            }
            "response.failed", "error" -> throw chatGptApiError((event.optJSONObject("response") ?: event).toString(), status)
            "response.incomplete" -> throw ChatGptException("response_incomplete", "ChatGPT stopped before completing the response. Try again.", retryable = true)
            "response.completed" -> completed = true
        }
    }

    try {
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (!completed) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) { dispatch(); continue }
                if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).removePrefix(" "))
                    if (data.length > 4 * 1024 * 1024) throw ChatGptException("invalid_stream", "ChatGPT returned an oversized stream event.")
                }
            }
            if (!completed) dispatch()
        }
    } catch (failure: IOException) {
        throw ChatGptException("stream_interrupted", "The connection to ChatGPT was interrupted. Try again.", retryable = true)
    }
    if (!completed) throw ChatGptException("stream_interrupted", "The response ended before completion. Try again.", retryable = true)
    return text.toString()
}

/** Parses the model catalog and keeps only models ChatGPT marks for listing. */
internal fun parseModelCatalog(json: String, status: Int): List<ChatGptModel> {
    val invalid = ChatGptException("invalid_model_catalog", "ChatGPT returned an unexpected model list. Try again.", retryable = true, status = status)
    val models = runCatching { JSONObject(json).getJSONArray("models") }.getOrElse { throw invalid }
    return (0 until models.length()).mapNotNull(models::optJSONObject)
        .filter { it.optString("visibility") == "list" }
        .map { model ->
            val slug = model.optString("slug").trim()
            val name = model.optString("display_name").trim()
            if (slug.isEmpty() || slug.length > 200 || name.isEmpty() || name.length > 200) throw invalid
            ChatGptModel(slug, name)
        }
}

/** Stateless calls to the sign-in service and the public API. */
internal class ChatGptApi(private val http: ChatGptHttp = okHttpChatGptHttp) {
    @Volatile private var endpoints: ChatGptEndpoints? = null

    private fun call(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null, contentType: String? = null): ChatGptHttpResponse =
        try {
            http.execute(method, url, headers, body, contentType)
        } catch (failure: IOException) {
            throw ChatGptException("network_error", "Could not reach ChatGPT. Check your connection and try again.", retryable = true)
        }

    fun discovery(): ChatGptEndpoints = endpoints ?: run {
        val response = call("GET", "$CHATGPT_ISSUER/.well-known/openid-configuration", mapOf("accept" to "application/json"))
        val text = response.text()
        if (response.status != 200) throw ChatGptException("discovery_failed", "ChatGPT sign-in configuration could not be verified.", retryable = true)
        parseDiscovery(text).also { endpoints = it }
    }

    private fun tokenRequest(form: Map<String, String>): String {
        val response = call(
            "POST", discovery().token,
            mapOf("accept" to "application/json"),
            formEncode(form).toByteArray(), "application/x-www-form-urlencoded",
        )
        val text = response.text()
        if (response.status !in 200..299) throw chatGptApiError(text, response.status)
        return text
    }

    fun exchangeCode(grant: CallbackGrant, verifier: String, redirectUri: String): String = tokenRequest(
        mapOf(
            "grant_type" to "authorization_code",
            "client_id" to grant.clientId,
            "code" to grant.code,
            "code_verifier" to verifier,
            "redirect_uri" to redirectUri,
            "resource" to CHATGPT_RESOURCE,
        ),
    )

    fun refresh(clientId: String, refreshToken: String): String = tokenRequest(
        mapOf(
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to refreshToken,
            "resource" to CHATGPT_RESOURCE,
        ),
    )

    fun jwks(): String {
        val unavailable = ChatGptException("identity_verification_unavailable", "ChatGPT identity verification is temporarily unavailable. Try again shortly.", retryable = true)
        val response = runCatching { call("GET", discovery().jwks, mapOf("accept" to "application/json")) }.getOrElse { throw unavailable }
        val text = response.text()
        if (response.status != 200) throw unavailable
        return text
    }

    /** Returns true when ChatGPT confirmed the refresh token was revoked. */
    fun revoke(clientId: String, refreshToken: String): Boolean {
        val endpoint = runCatching { discovery().revocation }.getOrNull() ?: return false
        return runCatching {
            call(
                "POST", endpoint, emptyMap(),
                formEncode(mapOf("token" to refreshToken, "token_type_hint" to "refresh_token", "client_id" to clientId)).toByteArray(),
                "application/x-www-form-urlencoded",
            ).use { it.status == 200 }
        }.getOrDefault(false)
    }

    fun listModels(accessToken: String): List<ChatGptModel> {
        val response = call("GET", MODELS_URL, mapOf("authorization" to "Bearer $accessToken", "accept" to "application/json"))
        val text = response.text()
        if (response.status !in 200..299) throw chatGptApiError(text, response.status)
        return parseModelCatalog(text, response.status)
    }

    /** Sends one Responses API request charged to the user's ChatGPT plan and returns the output text. */
    fun respond(accessToken: String, body: JSONObject): String {
        val response = call(
            "POST", RESPONSES_URL,
            mapOf("authorization" to "Bearer $accessToken", "accept" to "text/event-stream"),
            body.toString().toByteArray(), "application/json",
        )
        if (response.status !in 200..299) throw chatGptApiError(response.text(), response.status)
        return response.use { readResponseStream(it.body, it.status) }
    }
}

/** What is known about the signed-in account. Credentials are null after disconnect; the registration is kept. */
internal data class ChatGptConnection(
    val clientId: String,
    val subject: String,
    val email: String?,
    val name: String?,
    val scopes: List<String>,
    val credentials: ChatGptCredentials?,
)

internal fun ChatGptConnection.toJson(): String = JSONObject()
    .put("version", 1)
    .put("client_id", clientId)
    .put("subject", subject)
    .put("email", email ?: JSONObject.NULL)
    .put("name", name ?: JSONObject.NULL)
    .put("scopes", JSONArray(scopes))
    .put("credentials", credentials?.let {
        JSONObject().put("access_token", it.accessToken).put("refresh_token", it.refreshToken).put("expires_at", it.expiresAtEpochMs)
    } ?: JSONObject.NULL)
    .toString()

internal fun chatGptConnectionFromJson(json: String): ChatGptConnection? = runCatching {
    val root = JSONObject(json)
    val scopes = root.getJSONArray("scopes")
    val credentials = root.optJSONObject("credentials")
    ChatGptConnection(
        clientId = root.getString("client_id"),
        subject = root.getString("subject"),
        email = root.optString("email").takeIf { it.isNotEmpty() && !root.isNull("email") },
        name = root.optString("name").takeIf { it.isNotEmpty() && !root.isNull("name") },
        scopes = (0 until scopes.length()).map(scopes::getString),
        credentials = credentials?.let { ChatGptCredentials(it.getString("access_token"), it.getString("refresh_token"), it.getLong("expires_at")) },
    )
}.getOrNull()

/** Persistence for the connection and the small non-secret settings around it. */
internal interface ChatGptStore {
    fun load(): ChatGptConnection?
    fun save(connection: ChatGptConnection)
    fun clear()
    /** A stable identifier for this installation, sent as `ext_agent_host_id`. */
    fun hostId(): String
    var welcomed: Boolean
}

/**
 * Stores the connection encrypted with an AES-GCM key held in the Android Keystore.
 * The key never leaves the keystore, and the app disables backup, so tokens stay on this device.
 */
internal class KeystoreChatGptStore(context: Context) : ChatGptStore {
    private val prefs = context.applicationContext.getSharedPreferences("chatgpt-connection", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    override fun load(): ChatGptConnection? = runCatching {
        val blob = Base64.getDecoder().decode(prefs.getString("connection", null) ?: return null)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES))
        chatGptConnectionFromJson(String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8))
    }.getOrNull()

    override fun save(connection: ChatGptConnection) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = cipher.iv + cipher.doFinal(connection.toJson().toByteArray(Charsets.UTF_8))
        // commit() so a rotated refresh token is on disk before it is used.
        prefs.edit(commit = true) { putString("connection", Base64.getEncoder().encodeToString(blob)) }
    }

    override fun clear() { prefs.edit(commit = true) { remove("connection") } }

    override fun hostId(): String = prefs.getString("host_id", null) ?: "urn:uuid:${UUID.randomUUID()}".also {
        prefs.edit(commit = true) { putString("host_id", it) }
    }

    override var welcomed: Boolean
        get() = prefs.getBoolean("welcomed", false)
        set(value) { prefs.edit { putBoolean("welcomed", value) } }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "spoolforge-chatgpt-connection-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/** The signed-in ChatGPT session: sign-in, token refresh, model listing, and plan-charged requests. */
internal class ChatGptSession(
    private val store: ChatGptStore,
    private val api: ChatGptApi = ChatGptApi(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    @Volatile private var pendingListener: LoopbackCallbackListener? = null

    fun connection(): ChatGptConnection? = store.load()
    val isConnected: Boolean get() = store.load()?.credentials != null

    /**
     * Runs the whole browser sign-in. Blocks until the user finishes in the browser, so call it off the main thread.
     * [openBrowser] receives the authorization URL and must open it in the device browser.
     */
    fun signIn(openBrowser: (String) -> Unit): ChatGptConnection {
        val previous = store.load()
        val endpoints = api.discovery()
        val attempt = newAuthorizationAttempt()
        val listener = LoopbackCallbackListener(attempt.state, previous?.clientId)
        pendingListener = listener
        try {
            openBrowser(
                buildAuthorizationUrl(endpoints.authorization, listener.redirectUri, attempt, store.hostId(), previous?.clientId, previous?.email),
            )
            val grant = listener.await()
            // The one-time code can still fail after registration succeeded. Keep the issued
            // client ID so the next attempt does not register the app a second time.
            if (previous == null) store.save(ChatGptConnection(grant.clientId, "", null, null, emptyList(), null))

            val receivedAtMs = nowMs()
            val tokens = parseTokenResponse(api.exchangeCode(grant, attempt.verifier, listener.redirectUri), null, receivedAtMs)
            val idToken = tokens.idToken
                ?: throw ChatGptException("invalid_id_token", "ChatGPT did not return a verifiable identity. Please sign in again.")
            val identity = verifyIdToken(idToken, api.jwks(), grant.clientId, attempt.nonce, receivedAtMs / 1000)
            if (!previous?.subject.isNullOrEmpty() && previous?.subject != identity.subject) {
                throw ChatGptException("account_mismatch", "This sign-in returned a different ChatGPT account. Try again to connect the new account.")
            }
            return ChatGptConnection(grant.clientId, identity.subject, identity.email, identity.name, tokens.scopes, tokens.credentials)
                .also(store::save)
        } catch (failure: ChatGptException) {
            // A registration that ChatGPT no longer accepts, or one bound to another account,
            // would fail the same way forever. Forget it so the next attempt registers afresh.
            if (failure.code in setOf("invalid_client", "registration_incomplete", "account_mismatch")) store.clear()
            throw failure
        } finally {
            pendingListener = null
            listener.close()
        }
    }

    fun cancelSignIn() { pendingListener?.close() }

    /** Returns a valid access token, refreshing it first when it is about to expire. */
    fun accessToken(): String = synchronized(lock) {
        val connection = store.load()
        val credentials = connection?.credentials
            ?: throw ChatGptException("not_connected", "Sign in with ChatGPT first.")
        if (credentials.expiresAtEpochMs - nowMs() > REFRESH_MARGIN_MS) return credentials.accessToken
        try {
            val tokens = parseTokenResponse(api.refresh(connection.clientId, credentials.refreshToken), connection.scopes, nowMs())
            // Refresh tokens rotate. Persist the new one before anything else can fail.
            store.save(connection.copy(scopes = tokens.scopes, credentials = tokens.credentials))
            tokens.credentials.accessToken
        } catch (failure: ChatGptException) {
            if (failure.code in CHATGPT_REAUTHENTICATION_CODES) store.save(connection.copy(credentials = null))
            throw failure
        }
    }

    fun listModels(): List<ChatGptModel> = api.listModels(accessToken())

    fun respond(body: JSONObject): String = api.respond(accessToken(), body)

    /** Revokes the session at ChatGPT and removes local tokens. Returns false when revocation could not be confirmed. */
    fun disconnect(): Boolean {
        val connection = store.load() ?: return true
        val revoked = connection.credentials?.let { api.revoke(connection.clientId, it.refreshToken) } ?: true
        store.save(connection.copy(credentials = null))
        return revoked
    }
}
