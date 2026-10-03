package net.jamesjennison.filamajignfc

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/*
 * Sign in with ChatGPT: OAuth 2.0 authorization code flow with PKCE and OpenID Connect,
 * using dynamic client registration and a loopback redirect, as documented at
 * https://developers.openai.com/siwc/token-sharing-open-source/sign-in.
 *
 * This file holds the protocol pieces that need no Android classes, so they run in plain unit tests.
 * It is an independent implementation of the documented protocol; no devkit code is included.
 */

internal const val CHATGPT_ISSUER = "https://auth.openai.com"
internal const val CHATGPT_RESOURCE = "https://api.openai.com/v1"
internal const val CHATGPT_SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
internal const val CHATGPT_PLAN_SCOPE = "chatgpt.tokens.use.direct"
internal const val CHATGPT_CALLBACK_PATH = "/auth/callback"
internal const val CHATGPT_REGISTRATION_CLIENT_ID = "dynamic_agent_client"
internal const val CHATGPT_APP_NAME = "SpoolForge"
internal const val CHATGPT_RETURN_URI = "spoolforge://chatgpt-signin"
const val CHATGPT_USAGE_URL = "https://chatgpt.com/settings/usage"

/** A failure with a stable machine code. Messages never contain tokens, codes, or request bodies. */
class ChatGptException(
    val code: String,
    message: String,
    val retryable: Boolean = false,
    val status: Int? = null,
) : Exception(message)

internal data class ChatGptEndpoints(
    val authorization: String,
    val token: String,
    val jwks: String,
    val revocation: String?,
)

internal data class AuthorizationAttempt(val state: String, val nonce: String, val verifier: String)

internal data class CallbackGrant(val code: String, val clientId: String)

internal data class ChatGptIdentity(val subject: String, val email: String?, val name: String?)

internal data class ChatGptCredentials(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
)

internal data class ChatGptTokens(val scopes: List<String>, val credentials: ChatGptCredentials, val idToken: String?)

private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
private val urlDecoder = Base64.getUrlDecoder()
private val secureRandom = SecureRandom()
private val CLIENT_ID_PATTERN = Regex("^[a-zA-Z0-9_-]{1,200}$")

internal fun randomUrlSafe(byteCount: Int = 32): String =
    urlEncoder.encodeToString(ByteArray(byteCount).also(secureRandom::nextBytes))

internal fun newAuthorizationAttempt() = AuthorizationAttempt(randomUrlSafe(), randomUrlSafe(), randomUrlSafe())

internal fun pkceChallenge(verifier: String): String =
    urlEncoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

/** Validates the OpenID discovery document and refuses endpoints outside the issuer's origin. */
internal fun parseDiscovery(json: String): ChatGptEndpoints {
    val failure = ChatGptException("discovery_failed", "ChatGPT sign-in configuration could not be verified.", retryable = true)
    val root = runCatching { JSONObject(json) }.getOrElse { throw failure }
    if (root.optString("issuer") != CHATGPT_ISSUER) throw failure
    fun endpoint(name: String, required: Boolean): String? {
        val value = root.optString(name).takeIf(String::isNotBlank)
        if (value == null) { if (required) throw failure else return null }
        val uri = runCatching { URI(value) }.getOrElse { throw failure }
        if ("${uri.scheme}://${uri.authority}" != CHATGPT_ISSUER) throw failure
        return value
    }
    return ChatGptEndpoints(
        authorization = endpoint("authorization_endpoint", true)!!,
        token = endpoint("token_endpoint", true)!!,
        jwks = endpoint("jwks_uri", true)!!,
        revocation = endpoint("revocation_endpoint", false),
    )
}

internal fun formEncode(values: Map<String, String>): String =
    values.entries.joinToString("&") { (key, value) -> "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" }

/**
 * Builds the browser URL. A first sign-in registers the app with the fixed registration client ID;
 * later sign-ins reuse the client ID that ChatGPT issued, so the app is not registered twice.
 */
internal fun buildAuthorizationUrl(
    authorizationEndpoint: String,
    redirectUri: String,
    attempt: AuthorizationAttempt,
    hostId: String,
    savedClientId: String?,
    loginHint: String?,
): String {
    val parameters = linkedMapOf(
        "client_id" to (savedClientId ?: CHATGPT_REGISTRATION_CLIENT_ID),
        "response_type" to "code",
        "redirect_uri" to redirectUri,
        "scope" to CHATGPT_SCOPES,
        "resource" to CHATGPT_RESOURCE,
        "state" to attempt.state,
        "nonce" to attempt.nonce,
        "code_challenge_method" to "S256",
        "code_challenge" to pkceChallenge(attempt.verifier),
        "ext_agent_host_id" to hostId,
    )
    if (savedClientId == null) parameters["agent_name_hint"] = CHATGPT_APP_NAME
    if (!loginHint.isNullOrBlank()) parameters["login_hint"] = loginHint
    return "$authorizationEndpoint?${formEncode(parameters)}"
}

internal sealed interface CallbackOutcome {
    /** Not the sign-in callback, or a stale one. The listener keeps waiting. */
    data class Ignored(val status: Int, val text: String) : CallbackOutcome
    data class Granted(val grant: CallbackGrant) : CallbackOutcome
    data class Failed(val error: ChatGptException) : CallbackOutcome
}

private fun parseQuery(rawQuery: String?): Map<String, List<String>> {
    if (rawQuery.isNullOrEmpty()) return emptyMap()
    val result = linkedMapOf<String, MutableList<String>>()
    rawQuery.split('&').filter(String::isNotEmpty).forEach { part ->
        val separator = part.indexOf('=')
        val key = URLDecoder.decode(if (separator < 0) part else part.substring(0, separator), "UTF-8")
        val value = if (separator < 0) "" else URLDecoder.decode(part.substring(separator + 1), "UTF-8")
        result.getOrPut(key) { mutableListOf() } += value
    }
    return result
}

/** Decides what one HTTP request to the loopback listener means. Pure, so every branch is unit tested. */
internal fun evaluateCallbackRequest(
    requestLine: String,
    hostHeader: String?,
    port: Int,
    expectedState: String,
    savedClientId: String?,
): CallbackOutcome {
    val notFound = CallbackOutcome.Ignored(404, "Not found")
    val parts = requestLine.trim().split(' ')
    if (parts.size < 2 || parts[0] != "GET" || hostHeader?.trim() != "127.0.0.1:$port") return notFound
    val target = runCatching { URI(parts[1]) }.getOrNull() ?: return CallbackOutcome.Ignored(400, "Invalid request")
    if (target.isAbsolute || target.rawPath != CHATGPT_CALLBACK_PATH) return notFound
    val query = runCatching { parseQuery(target.rawQuery) }.getOrNull() ?: return CallbackOutcome.Ignored(400, "Invalid request")

    val states = query["state"].orEmpty()
    val stateMatches = states.size == 1 &&
        MessageDigest.isEqual(states.single().toByteArray(), expectedState.toByteArray())
    if (!stateMatches) {
        // An unrelated loopback request must not consume the pending sign-in.
        return CallbackOutcome.Ignored(400, "Invalid sign-in state. Return to the browser tab that started sign-in.")
    }

    query["error"]?.firstOrNull()?.let { error ->
        return CallbackOutcome.Failed(chatGptApiError(JSONObject().put("error", error).toString(), null))
    }

    val codes = query["code"].orEmpty()
    val returnedClientIds = query["client_id"].orEmpty()
    val returnedClientId = returnedClientIds.singleOrNull()
    val clientId = returnedClientId ?: savedClientId
    val incomplete = codes.size != 1 || codes.single().isEmpty() ||
        returnedClientIds.size > 1 ||
        clientId == null || !CLIENT_ID_PATTERN.matches(clientId) || clientId == CHATGPT_REGISTRATION_CLIENT_ID ||
        (savedClientId != null && returnedClientId != null && savedClientId != returnedClientId)
    if (incomplete) {
        return CallbackOutcome.Failed(
            ChatGptException("registration_incomplete", "ChatGPT did not complete app registration. Please try signing in again."),
        )
    }
    return CallbackOutcome.Granted(CallbackGrant(codes.single(), clientId!!))
}

private const val RETURN_SCRIPT = "history.replaceState(null,\"\",\"/auth/complete\");location.href=\"$CHATGPT_RETURN_URI\";"

private fun returnPage(): String =
    "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<title>Return to $CHATGPT_APP_NAME</title>" +
        "<style>body{font:17px system-ui;max-width:32rem;margin:18vh auto;padding:24px;color:#202123}h1{font-size:26px}" +
        "a{display:inline-block;margin-top:16px;padding:12px 20px;border-radius:24px;background:#286354;color:#fff;text-decoration:none}</style>" +
        "<h1>Return to $CHATGPT_APP_NAME</h1><p>$CHATGPT_APP_NAME will finish checking your ChatGPT connection. You can close this tab.</p>" +
        "<a href=\"$CHATGPT_RETURN_URI\">Open $CHATGPT_APP_NAME</a><script>$RETURN_SCRIPT</script></html>"

/**
 * Receives the browser redirect on http://127.0.0.1:{port}/auth/callback.
 * The socket is bound to loopback only, so nothing off the device can reach it.
 */
internal class LoopbackCallbackListener(
    private val expectedState: String,
    private val savedClientId: String?,
    private val timeoutMs: Long = 10 * 60_000L,
) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort
    val redirectUri: String = "http://127.0.0.1:$port$CHATGPT_CALLBACK_PATH"

    /** Blocks until the sign-in callback arrives, the listener is closed, or the timeout passes. */
    fun await(): CallbackGrant {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) throw ChatGptException("sign_in_timeout", "Sign-in was not completed in time. Please try again.")
            server.soTimeout = remainingMs.coerceAtMost(30_000).toInt()
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                throw ChatGptException("cancelled", "Sign-in was cancelled.")
            }
            val outcome = socket.use(::handle)
            when (outcome) {
                is CallbackOutcome.Granted -> return outcome.grant
                is CallbackOutcome.Failed -> throw outcome.error
                is CallbackOutcome.Ignored, null -> Unit
            }
        }
    }

    private fun handle(socket: Socket): CallbackOutcome? = try {
        socket.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine()?.take(8_192)
        var host: String? = null
        var headerCount = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty() || ++headerCount > 100) break
            if (line.startsWith("host:", ignoreCase = true)) host = line.substring(5)
        }
        val outcome = if (requestLine == null) CallbackOutcome.Ignored(400, "Invalid request")
        else evaluateCallbackRequest(requestLine, host, port, expectedState, savedClientId)
        when (outcome) {
            is CallbackOutcome.Ignored -> respond(socket, outcome.status, "text/plain; charset=utf-8", outcome.text)
            else -> respond(socket, 200, "text/html; charset=utf-8", returnPage())
        }
        outcome
    } catch (_: IOException) {
        null
    }

    private fun respond(socket: Socket, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; else -> "Not Found" }
        val scriptHash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(RETURN_SCRIPT.toByteArray()))
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'sha256-$scriptHash'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(bytes); flush() }
    }

    override fun close() {
        runCatching { server.close() }
    }
}

private fun decodeJwtPart(part: String): JSONObject = JSONObject(String(urlDecoder.decode(part), Charsets.UTF_8))

/**
 * Verifies the ID token's RS256 signature against the issuer's published keys and checks its claims.
 * [receivedAtEpochSeconds] is the time the token response arrived.
 */
internal fun verifyIdToken(
    idToken: String,
    jwksJson: String,
    clientId: String,
    expectedNonce: String?,
    receivedAtEpochSeconds: Long,
): ChatGptIdentity {
    val invalid = ChatGptException("invalid_id_token", "The ChatGPT identity could not be verified. Please sign in again.")
    val unavailable = ChatGptException(
        "identity_verification_unavailable",
        "ChatGPT identity verification is temporarily unavailable. Try again shortly.",
        retryable = true,
    )
    val keys: JSONArray = runCatching { JSONObject(jwksJson).getJSONArray("keys") }.getOrElse { throw unavailable }
    val parts = idToken.split('.')
    if (parts.size != 3) throw invalid
    val header = runCatching { decodeJwtPart(parts[0]) }.getOrElse { throw invalid }
    val claims = runCatching { decodeJwtPart(parts[1]) }.getOrElse { throw invalid }
    if (header.optString("alg") != "RS256") throw invalid
    val keyId = header.optString("kid")

    val key = (0 until keys.length()).mapNotNull(keys::optJSONObject).firstOrNull { candidate ->
        candidate.optString("kty") == "RSA" && (keyId.isEmpty() && keys.length() == 1 || candidate.optString("kid") == keyId)
    } ?: throw unavailable
    val signatureValid = runCatching {
        val spec = RSAPublicKeySpec(
            BigInteger(1, urlDecoder.decode(key.getString("n"))),
            BigInteger(1, urlDecoder.decode(key.getString("e"))),
        )
        Signature.getInstance("SHA256withRSA").run {
            initVerify(KeyFactory.getInstance("RSA").generatePublic(spec))
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            verify(urlDecoder.decode(parts[2]))
        }
    }.getOrDefault(false)
    if (!signatureValid) throw invalid

    val audiences: List<String> = when (val audience = claims.opt("aud")) {
        is String -> listOf(audience)
        is JSONArray -> (0 until audience.length()).map(audience::optString)
        else -> emptyList()
    }
    val authorizedParty = claims.optString("azp").takeIf(String::isNotEmpty)
    val subject = claims.optString("sub")
    val clockToleranceSeconds = 5
    val claimsValid = claims.optString("iss") == CHATGPT_ISSUER &&
        clientId in audiences &&
        (authorizedParty == null || authorizedParty == clientId) &&
        (audiences.size == 1 || authorizedParty == clientId) &&
        claims.has("iat") &&
        claims.optLong("exp", 0) + clockToleranceSeconds > receivedAtEpochSeconds &&
        subject.isNotEmpty() &&
        (expectedNonce == null || claims.optString("nonce") == expectedNonce)
    if (!claimsValid) throw invalid

    return ChatGptIdentity(
        subject = subject,
        email = claims.optString("email").takeIf(String::isNotEmpty),
        name = claims.optString("name").takeIf(String::isNotEmpty),
    )
}

/** Parses a token response. A refresh may omit scope, in which case the previously granted scopes still apply. */
internal fun parseTokenResponse(json: String, previousScopes: List<String>?, nowEpochMs: Long): ChatGptTokens {
    val incomplete = ChatGptException("invalid_token_response", "ChatGPT returned incomplete credentials. Please sign in again.")
    val root = runCatching { JSONObject(json) }.getOrElse { throw incomplete }
    val scopes = root.optString("scope").split(Regex("\\s+")).filter(String::isNotEmpty).ifEmpty { previousScopes.orEmpty() }
    if (scopes.isEmpty()) {
        throw ChatGptException("invalid_token_response", "ChatGPT did not confirm the granted permissions. Please sign in again.")
    }
    if (CHATGPT_PLAN_SCOPE !in scopes) {
        throw ChatGptException(
            "plan_usage_not_granted",
            "ChatGPT plan usage was not granted for this account or workspace. It needs an eligible ChatGPT plan with plan usage enabled.",
        )
    }
    val accessToken = root.optString("access_token")
    val refreshToken = root.optString("refresh_token")
    val expiresIn = root.optLong("expires_in", 0)
    val bearer = root.optString("token_type").equals("bearer", ignoreCase = true)
    if (accessToken.isEmpty() || refreshToken.isEmpty() || expiresIn <= 0 || !bearer) throw incomplete
    return ChatGptTokens(
        scopes = scopes,
        credentials = ChatGptCredentials(accessToken, refreshToken, nowEpochMs + expiresIn * 1000),
        idToken = root.optString("id_token").takeIf(String::isNotEmpty),
    )
}

private val KNOWN_ERRORS: Map<String, Pair<String, Boolean>> = mapOf(
    "subscription_sharing_user_not_eligible" to ("ChatGPT plan usage is not available for this account or workspace." to false),
    "subscription_sharing_usage_limit_exceeded" to ("Your ChatGPT plan usage limit has been reached. Manage usage in your ChatGPT settings." to false),
    "subscription_sharing_usage_unavailable" to ("ChatGPT usage could not be checked. Try again shortly." to true),
    "subscription_sharing_unsupported_capability" to ("This request uses something ChatGPT plan usage does not support. Try a different model." to false),
    "subscription_sharing_route_not_supported" to ("This request uses a route ChatGPT plan usage does not support." to false),
    "subscription_sharing_invalid_user" to ("ChatGPT could not validate this account's plan. Sign in again." to false),
    "subscription_sharing_user_unavailable" to ("Your ChatGPT account or workspace is temporarily unavailable. Try again shortly." to true),
    "invalid_client" to ("ChatGPT rejected this app's registration. Sign in again to register it afresh." to false),
    "invalid_grant" to ("ChatGPT did not accept this sign-in. Please sign in again." to false),
    "invalid_refresh_token" to ("Your ChatGPT connection can no longer be renewed. Sign in again." to false),
    "token_expired" to ("Your ChatGPT connection can no longer be renewed. Sign in again." to false),
    "refresh_token_expired" to ("Your ChatGPT connection can no longer be renewed. Sign in again." to false),
    "refresh_token_invalidated" to ("Your ChatGPT connection can no longer be renewed. Sign in again." to false),
    "refresh_token_reused" to ("Your ChatGPT connection can no longer be renewed. Sign in again." to false),
    "invalid_token" to ("ChatGPT did not accept this connection. Sign in again." to false),
    "access_denied" to ("Sign-in was not completed, or ChatGPT plan usage is not enabled for this account." to false),
    "model_not_found" to ("This model is not available for your ChatGPT connection. Choose another model." to false),
)

internal val CHATGPT_REAUTHENTICATION_CODES = setOf(
    "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused",
)
internal const val CHATGPT_USAGE_LIMIT_CODE = "subscription_sharing_usage_limit_exceeded"
internal const val CHATGPT_UNSUPPORTED_CAPABILITY_CODE = "subscription_sharing_unsupported_capability"

private val SAFE_CODE = Regex("^[a-zA-Z0-9_][a-zA-Z0-9_.:-]{0,99}$")

/**
 * Maps an error body to a [ChatGptException]. Server-supplied message text is never surfaced,
 * because validation errors can echo the submitted request.
 */
internal fun chatGptApiError(body: String?, status: Int?): ChatGptException {
    var detail = runCatching { JSONObject(body.orEmpty()) }.getOrDefault(JSONObject())
    repeat(4) {
        detail = detail.optJSONObject("error") ?: detail.optJSONObject("detail") ?: return@repeat
    }
    // OAuth errors carry a string `error`; Responses errors carry `error.code`.
    val rawCode = (detail.opt("error") as? String) ?: (detail.opt("code") as? String)
    val code = rawCode?.takeIf(SAFE_CODE::matches) ?: "api_error"
    KNOWN_ERRORS[code.replace("subscription_sharing_v2_", "subscription_sharing_")]?.let { (message, retryable) ->
        return ChatGptException(code.replace("subscription_sharing_v2_", "subscription_sharing_"), message, retryable, status)
    }
    val (message, retryable) = when {
        status == 400 || status == 422 || code.startsWith("invalid_request") ->
            "ChatGPT rejected this request. Check the selected model and try again." to false
        status == 401 -> "ChatGPT did not accept this connection. Sign in again." to false
        status == 403 -> "A ChatGPT policy or permission restriction prevented this request." to false
        status == 429 -> "Too many requests. Wait a moment before trying again." to true
        status != null && status >= 500 -> "ChatGPT is temporarily unavailable. Try again shortly." to true
        else -> "ChatGPT could not complete this request." to false
    }
    return ChatGptException(code, message, retryable, status)
}
