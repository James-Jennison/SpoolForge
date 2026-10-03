package net.jamesjennison.filamajignfc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val b64 = Base64.getUrlEncoder().withoutPadding()

private fun queryOf(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
    val (key, value) = it.split('=', limit = 2)
    URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
}

private inline fun expectChatGptError(code: String, block: () -> Unit): ChatGptException {
    try { block() } catch (error: ChatGptException) { assertEquals(code, error.code); return error }
    fail("Expected ChatGptException($code)"); throw AssertionError()
}

/** Signs ID tokens with a throwaway RSA key so verification runs against a real signature. */
private class TestIssuer(val keyId: String = "test-key") {
    val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    fun jwks(): String {
        val public = keys.public as RSAPublicKey
        fun unsigned(value: java.math.BigInteger) = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return JSONObject().put("keys", JSONArray().put(JSONObject()
            .put("kty", "RSA").put("kid", keyId).put("alg", "RS256").put("use", "sig")
            .put("n", b64.encodeToString(unsigned(public.modulus)))
            .put("e", b64.encodeToString(unsigned(public.publicExponent))))).toString()
    }

    fun idToken(claims: JSONObject, alg: String = "RS256"): String {
        val header = b64.encodeToString(JSONObject().put("alg", alg).put("kid", keyId).toString().toByteArray())
        val payload = b64.encodeToString(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private); update("$header.$payload".toByteArray()); sign()
        }
        return "$header.$payload.${b64.encodeToString(signature)}"
    }

    fun claims(clientId: String, nonce: String, nowSeconds: Long) = JSONObject()
        .put("iss", CHATGPT_ISSUER).put("aud", clientId).put("sub", "user-123")
        .put("iat", nowSeconds).put("exp", nowSeconds + 600).put("nonce", nonce)
        .put("email", "owner@example.com").put("name", "Owner")
}

private class MemoryStore : ChatGptStore {
    var saved: ChatGptConnection? = null
    val history = mutableListOf<ChatGptConnection>()
    override fun load() = saved
    override fun save(connection: ChatGptConnection) { saved = connection; history += connection }
    override fun clear() { saved = null }
    override fun hostId() = "urn:uuid:00000000-0000-4000-8000-000000000001"
    override var welcomed: Boolean = false
}

private fun response(status: Int, body: String) = ChatGptHttpResponse(status, body.toByteArray().inputStream())

private const val DISCOVERY = """{"issuer":"https://auth.openai.com","authorization_endpoint":"https://auth.openai.com/api/accounts/authorize","token_endpoint":"https://auth.openai.com/api/accounts/oauth/token","jwks_uri":"https://auth.openai.com/.well-known/jwks.json","revocation_endpoint":"https://auth.openai.com/api/accounts/oauth/revoke"}"""

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatGptSignInTest {
    @Test fun pkceChallengeMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun randomValuesAreUrlSafeAndDistinct() {
        val first = randomUrlSafe()
        assertTrue(first.matches(Regex("^[A-Za-z0-9_-]{43}$")))
        assertFalse(first == randomUrlSafe())
    }

    @Test fun discoveryRejectsWrongIssuerAndForeignEndpoints() {
        assertEquals("https://auth.openai.com/api/accounts/oauth/token", parseDiscovery(DISCOVERY).token)
        expectChatGptError("discovery_failed") { parseDiscovery(DISCOVERY.replace("\"issuer\":\"https://auth.openai.com\"", "\"issuer\":\"https://evil.example\"")) }
        expectChatGptError("discovery_failed") { parseDiscovery(DISCOVERY.replace("https://auth.openai.com/api/accounts/oauth/token", "https://evil.example/token")) }
        expectChatGptError("discovery_failed") { parseDiscovery("not json") }
    }

    @Test fun firstAuthorizationRegistersTheAppAndLaterOnesReuseTheIssuedClient() {
        val attempt = AuthorizationAttempt("state-1", "nonce-1", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        val first = queryOf(buildAuthorizationUrl("https://auth.openai.com/api/accounts/authorize", "http://127.0.0.1:5000/auth/callback", attempt, "urn:uuid:host", null, null))
        assertEquals("dynamic_agent_client", first["client_id"])
        assertEquals("SpoolForge", first["agent_name_hint"])
        assertEquals("code", first["response_type"])
        assertEquals("http://127.0.0.1:5000/auth/callback", first["redirect_uri"])
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", first["scope"])
        assertEquals("https://api.openai.com/v1", first["resource"])
        assertEquals("S256", first["code_challenge_method"])
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", first["code_challenge"])
        assertEquals("urn:uuid:host", first["ext_agent_host_id"])
        assertFalse("verifier must never be in the URL", first.values.any { it == attempt.verifier })

        val again = queryOf(buildAuthorizationUrl("https://auth.openai.com/api/accounts/authorize", "http://127.0.0.1:5000/auth/callback", attempt, "urn:uuid:host", "client_abc", "owner@example.com"))
        assertEquals("client_abc", again["client_id"])
        assertNull(again["agent_name_hint"])
        assertEquals("owner@example.com", again["login_hint"])
    }

    @Test fun callbackAcceptsOnlyTheMatchingSignInRedirect() {
        fun evaluate(line: String, host: String? = "127.0.0.1:5000", saved: String? = null) = evaluateCallbackRequest(line, host, 5000, "good-state", saved)

        val granted = evaluate("GET /auth/callback?code=abc&state=good-state&client_id=client_xyz HTTP/1.1")
        assertEquals(CallbackOutcome.Granted(CallbackGrant("abc", "client_xyz")), granted)
        // A returning client may omit client_id; the saved one applies.
        assertEquals(CallbackOutcome.Granted(CallbackGrant("abc", "saved_1")), evaluate("GET /auth/callback?code=abc&state=good-state HTTP/1.1", saved = "saved_1"))

        // Requests that must not consume the pending sign-in.
        assertTrue(evaluate("GET /auth/callback?code=abc&state=wrong HTTP/1.1") is CallbackOutcome.Ignored)
        assertTrue(evaluate("GET /auth/callback?code=abc&state=good-state&state=good-state HTTP/1.1") is CallbackOutcome.Ignored)
        assertTrue(evaluate("GET /favicon.ico HTTP/1.1") is CallbackOutcome.Ignored)
        assertTrue(evaluate("POST /auth/callback?code=abc&state=good-state&client_id=c HTTP/1.1") is CallbackOutcome.Ignored)
        assertTrue(evaluate("GET /auth/callback?code=abc&state=good-state&client_id=c HTTP/1.1", host = "evil.example") is CallbackOutcome.Ignored)
        assertTrue(evaluate("GET /auth/callback?code=abc&state=good-state&client_id=c HTTP/1.1", host = "localhost:5000") is CallbackOutcome.Ignored)
        assertTrue(evaluate("GET http://evil.example/auth/callback?code=abc&state=good-state HTTP/1.1") is CallbackOutcome.Ignored)

        // Correct state but an unusable grant ends the attempt with a clear error.
        fun failure(line: String, saved: String? = null) = (evaluate(line, saved = saved) as CallbackOutcome.Failed).error.code
        assertEquals("registration_incomplete", failure("GET /auth/callback?state=good-state&client_id=c HTTP/1.1"))
        assertEquals("registration_incomplete", failure("GET /auth/callback?code=abc&state=good-state HTTP/1.1"))
        assertEquals("registration_incomplete", failure("GET /auth/callback?code=abc&state=good-state&client_id=dynamic_agent_client HTTP/1.1"))
        assertEquals("registration_incomplete", failure("GET /auth/callback?code=abc&state=good-state&client_id=bad%20id HTTP/1.1"))
        assertEquals("registration_incomplete", failure("GET /auth/callback?code=abc&state=good-state&client_id=other HTTP/1.1", saved = "saved_1"))
        assertEquals("access_denied", failure("GET /auth/callback?error=access_denied&state=good-state HTTP/1.1"))
    }

    @Test fun loopbackListenerIgnoresStrayRequestsThenReturnsTheGrant() {
        val listener = LoopbackCallbackListener("good-state", null, timeoutMs = 20_000)
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(listener.redirectUri.matches(Regex("^http://127\\.0\\.0\\.1:\\d+/auth/callback$")))
            val pending = executor.submit(Callable { listener.await() })
            fun get(path: String): Pair<Int, String> {
                val connection = URL("http://127.0.0.1:${listener.port}$path").openConnection() as HttpURLConnection
                val status = connection.responseCode
                val body = (if (status < 400) connection.inputStream else connection.errorStream).bufferedReader().readText()
                assertEquals("no-store", connection.getHeaderField("Cache-Control"))
                return status to body
            }
            assertEquals(404, get("/").first)
            assertEquals(400, get("/auth/callback?code=stolen&state=guess&client_id=c").first)
            val (status, page) = get("/auth/callback?code=real-code&state=good-state&client_id=client_xyz")
            assertEquals(200, status)
            assertTrue(page.contains("Return to SpoolForge"))
            assertTrue(page.contains("spoolforge://chatgpt-signin"))
            assertFalse("the page must not echo the code", page.contains("real-code"))
            assertEquals(CallbackGrant("real-code", "client_xyz"), pending.get(10, TimeUnit.SECONDS))
        } finally {
            listener.close(); executor.shutdownNow()
        }
    }

    @Test fun closingTheListenerCancelsAPendingSignIn() {
        val listener = LoopbackCallbackListener("s", null, timeoutMs = 20_000)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit(Callable { runCatching { listener.await() }.exceptionOrNull() })
            Thread.sleep(200)
            listener.close()
            assertEquals("cancelled", (pending.get(10, TimeUnit.SECONDS) as ChatGptException).code)
        } finally { executor.shutdownNow() }
    }

    @Test fun idTokenVerificationChecksSignatureAndEveryClaim() {
        val issuer = TestIssuer()
        val now = 1_800_000_000L
        val good = issuer.claims("client_xyz", "nonce-1", now)
        val identity = verifyIdToken(issuer.idToken(good), issuer.jwks(), "client_xyz", "nonce-1", now)
        assertEquals(ChatGptIdentity("user-123", "owner@example.com", "Owner"), identity)
        // A refresh carries no nonce to compare.
        assertEquals("user-123", verifyIdToken(issuer.idToken(good), issuer.jwks(), "client_xyz", null, now).subject)

        fun rejected(claims: JSONObject, clientId: String = "client_xyz", nonce: String? = "nonce-1", at: Long = now, alg: String = "RS256") =
            expectChatGptError("invalid_id_token") { verifyIdToken(issuer.idToken(claims, alg), issuer.jwks(), clientId, nonce, at) }
        rejected(good, nonce = "other-nonce")
        rejected(good, clientId = "another_client")
        rejected(good, at = now + 700)
        rejected(JSONObject(good.toString()).put("iss", "https://evil.example"))
        rejected(JSONObject(good.toString()).put("sub", ""))
        rejected(JSONObject(good.toString()).put("azp", "another_client"))
        rejected(JSONObject(good.toString()).put("aud", JSONArray().put("client_xyz").put("other")))
        rejected(JSONObject(good.toString()).apply { remove("iat") })
        rejected(good, alg = "none")

        // A token signed by a different key with the same key id fails the signature check.
        val impostor = TestIssuer()
        expectChatGptError("invalid_id_token") { verifyIdToken(impostor.idToken(good), issuer.jwks(), "client_xyz", "nonce-1", now) }
        // Tampering with the payload after signing fails too.
        val parts = issuer.idToken(good).split('.')
        val tampered = b64.encodeToString(JSONObject(good.toString()).put("sub", "attacker").toString().toByteArray())
        expectChatGptError("invalid_id_token") { verifyIdToken("${parts[0]}.$tampered.${parts[2]}", issuer.jwks(), "client_xyz", "nonce-1", now) }
        // Missing keys are an availability problem, not proof of a bad identity.
        expectChatGptError("identity_verification_unavailable") { verifyIdToken(issuer.idToken(good), "{}", "client_xyz", "nonce-1", now) }
        expectChatGptError("identity_verification_unavailable") { verifyIdToken(issuer.idToken(good), TestIssuer("other-key").jwks(), "client_xyz", "nonce-1", now) }
    }

    @Test fun tokenResponseRequiresPlanScopeAndCompleteCredentials() {
        val full = JSONObject().put("access_token", "at").put("refresh_token", "rt").put("token_type", "Bearer").put("expires_in", 3600)
            .put("scope", "openid offline_access chatgpt.tokens.use.direct").put("id_token", "x.y.z")
        val tokens = parseTokenResponse(full.toString(), null, 1_000)
        assertEquals(ChatGptCredentials("at", "rt", 1_000 + 3_600_000), tokens.credentials)
        assertEquals("x.y.z", tokens.idToken)

        // A refresh may omit scope; the previously granted scopes apply.
        val refreshed = parseTokenResponse(JSONObject(full.toString()).apply { remove("scope"); remove("id_token") }.toString(), listOf("chatgpt.tokens.use.direct"), 0)
        assertEquals(listOf("chatgpt.tokens.use.direct"), refreshed.scopes)
        assertNull(refreshed.idToken)

        expectChatGptError("plan_usage_not_granted") { parseTokenResponse(JSONObject(full.toString()).put("scope", "openid email").toString(), null, 0) }
        expectChatGptError("invalid_token_response") { parseTokenResponse(JSONObject(full.toString()).apply { remove("scope") }.toString(), null, 0) }
        expectChatGptError("invalid_token_response") { parseTokenResponse(JSONObject(full.toString()).apply { remove("refresh_token") }.toString(), null, 0) }
        expectChatGptError("invalid_token_response") { parseTokenResponse(JSONObject(full.toString()).put("token_type", "mac").toString(), null, 0) }
        expectChatGptError("invalid_token_response") { parseTokenResponse(JSONObject(full.toString()).put("expires_in", 0).toString(), null, 0) }
    }

    @Test fun apiErrorsMapToStableCodesWithoutEchoingServerText() {
        val limit = chatGptApiError("""{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"SECRET ECHO"}}""", 429)
        assertEquals(CHATGPT_USAGE_LIMIT_CODE, limit.code)
        assertFalse(limit.message!!.contains("SECRET"))
        assertEquals("subscription_sharing_user_not_eligible", chatGptApiError("""{"detail":{"code":"subscription_sharing_v2_user_not_eligible"}}""", 403).code)
        assertEquals("invalid_grant", chatGptApiError("""{"error":"invalid_grant","error_description":"x"}""", 400).code)
        assertTrue("invalid_grant" in CHATGPT_REAUTHENTICATION_CODES)
        val unknown = chatGptApiError("""{"error":{"code":"brand new code!","message":"echo of input"}}""", 400)
        assertEquals("api_error", unknown.code)
        assertFalse(unknown.message!!.contains("echo"))
        assertTrue(chatGptApiError(null, 503).retryable)
        assertTrue(chatGptApiError("<html>", 429).retryable)
    }

    @Test fun responseStreamNeedsCompletionAndReportsFailures() {
        fun stream(text: String) = readResponseStream(text.toByteArray().inputStream())
        val ok = "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"{\\\"a\\\":\"}\n\n" +
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"1}\"}\r\n\r\n" +
            "data: {\"type\":\"response.completed\"}\n\n"
        assertEquals("{\"a\":1}", stream(ok))
        expectChatGptError("stream_interrupted") { stream("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n") }
        expectChatGptError("response_incomplete") { stream("data: {\"type\":\"response.incomplete\"}\n\n") }
        expectChatGptError(CHATGPT_USAGE_LIMIT_CODE) {
            stream("data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n")
        }
        expectChatGptError("invalid_stream") { stream("data: not json\n\n") }
    }

    @Test fun modelCatalogKeepsOnlyListedModels() {
        val catalog = """{"models":[{"slug":"m-vision","display_name":"Vision","visibility":"list"},{"slug":"m-hidden","display_name":"Hidden","visibility":"hide"}]}"""
        assertEquals(listOf(ChatGptModel("m-vision", "Vision")), parseModelCatalog(catalog, 200))
        expectChatGptError("invalid_model_catalog") { parseModelCatalog("""{"data":[]}""", 200) }
        expectChatGptError("invalid_model_catalog") { parseModelCatalog("""{"models":[{"slug":"","display_name":"x","visibility":"list"}]}""", 200) }
    }

    @Test fun connectionSurvivesAJsonRoundTripAndDisconnectedStateKeepsRegistration() {
        val connected = ChatGptConnection("client_xyz", "user-123", "owner@example.com", null, listOf("openid", CHATGPT_PLAN_SCOPE), ChatGptCredentials("at", "rt", 42))
        assertEquals(connected, chatGptConnectionFromJson(connected.toJson()))
        val disconnected = connected.copy(credentials = null)
        assertEquals(disconnected, chatGptConnectionFromJson(disconnected.toJson()))
        assertNull(chatGptConnectionFromJson("garbage"))
    }

    @Test fun sessionRefreshesNearExpiryPersistsRotationAndSendsBearerOnlyToTheApi() {
        val store = MemoryStore()
        var now = 1_000_000L
        store.saved = ChatGptConnection("client_xyz", "user-123", "owner@example.com", null, listOf(CHATGPT_PLAN_SCOPE), ChatGptCredentials("old-at", "old-rt", now + 30_000))
        val calls = mutableListOf<Triple<String, String, String>>()
        val http = ChatGptHttp { method, url, headers, body, _ ->
            calls += Triple(method, url, body?.decodeToString().orEmpty() + "|" + headers["authorization"].orEmpty())
            when {
                url.endsWith("/.well-known/openid-configuration") -> response(200, DISCOVERY)
                url.endsWith("/oauth/token") -> response(200, """{"access_token":"new-at","refresh_token":"new-rt","token_type":"Bearer","expires_in":3600}""")
                url == "https://api.openai.com/v1/models" -> response(200, """{"models":[{"slug":"m1","display_name":"Model One","visibility":"list"}]}""")
                url == "https://api.openai.com/v1/responses" -> response(200, "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}\n\ndata: {\"type\":\"response.completed\"}\n\n")
                else -> response(404, "{}")
            }
        }
        val session = ChatGptSession(store, ChatGptApi(http)) { now }

        assertEquals(listOf(ChatGptModel("m1", "Model One")), session.listModels())
        val refresh = calls.single { it.second.endsWith("/oauth/token") }.third
        assertTrue(refresh.contains("grant_type=refresh_token") && refresh.contains("refresh_token=old-rt") && refresh.contains("client_id=client_xyz"))
        assertTrue(refresh.contains("resource=https%3A%2F%2Fapi.openai.com%2Fv1"))
        assertEquals(ChatGptCredentials("new-at", "new-rt", now + 3_600_000), store.saved!!.credentials)
        assertEquals("|Bearer new-at", calls.last().third)

        // A fresh token is reused without another refresh.
        assertEquals("hi", session.respond(JSONObject().put("model", "m1")))
        assertEquals(1, calls.count { it.second.endsWith("/oauth/token") })
        // The bearer token goes only to api.openai.com.
        assertTrue(calls.filter { it.third.contains("Bearer") }.all { it.second.startsWith("https://api.openai.com/v1/") })
    }

    @Test fun expiredRefreshTokenDisconnectsButKeepsTheRegistration() {
        val store = MemoryStore()
        store.saved = ChatGptConnection("client_xyz", "user-123", null, null, listOf(CHATGPT_PLAN_SCOPE), ChatGptCredentials("old-at", "old-rt", 0))
        val http = ChatGptHttp { _, url, _, _, _ ->
            if (url.endsWith("/.well-known/openid-configuration")) response(200, DISCOVERY) else response(400, """{"error":"invalid_grant"}""")
        }
        val session = ChatGptSession(store, ChatGptApi(http)) { 10_000_000L }
        expectChatGptError("invalid_grant") { session.accessToken() }
        assertFalse(session.isConnected)
        assertEquals("client_xyz", store.saved!!.clientId)
        expectChatGptError("not_connected") { session.accessToken() }
    }

    @Test fun transientRefreshFailureKeepsTheConnection() {
        val store = MemoryStore()
        store.saved = ChatGptConnection("client_xyz", "user-123", null, null, listOf(CHATGPT_PLAN_SCOPE), ChatGptCredentials("old-at", "old-rt", 0))
        val session = ChatGptSession(store, ChatGptApi { _, _, _, _, _ -> throw IOException("offline") }) { 10_000_000L }
        assertTrue(expectChatGptError("network_error") { session.accessToken() }.retryable)
        assertTrue(session.isConnected)
    }

    @Test fun fullSignInExchangesTheCodeVerifiesIdentityAndStoresTheConnection() {
        val issuer = TestIssuer()
        val store = MemoryStore()
        val now = 1_800_000_000_000L
        var nonce = ""
        var tokenForm = ""
        val http = ChatGptHttp { _, url, _, body, _ ->
            when {
                url.endsWith("/.well-known/openid-configuration") -> response(200, DISCOVERY)
                url.endsWith("/jwks.json") -> response(200, issuer.jwks())
                url.endsWith("/oauth/token") -> {
                    tokenForm = body!!.decodeToString()
                    response(200, JSONObject().put("access_token", "at").put("refresh_token", "rt").put("token_type", "Bearer").put("expires_in", 3600)
                        .put("scope", CHATGPT_SCOPES).put("id_token", issuer.idToken(issuer.claims("client_issued", nonce, now / 1000))).toString())
                }
                else -> response(404, "{}")
            }
        }
        val session = ChatGptSession(store, ChatGptApi(http)) { now }
        val browser = Executors.newSingleThreadExecutor()
        try {
            val connection = session.signIn { url ->
                val query = queryOf(url)
                nonce = query.getValue("nonce")
                // Stand in for the browser: follow the redirect back to the loopback listener.
                browser.submit {
                    val callback = "${query.getValue("redirect_uri")}?code=one-time&state=${query.getValue("state")}&client_id=client_issued"
                    (URL(callback).openConnection() as HttpURLConnection).responseCode
                }
            }
            assertEquals("client_issued", connection.clientId)
            assertEquals("user-123", connection.subject)
            assertEquals("owner@example.com", connection.email)
            assertEquals(ChatGptCredentials("at", "rt", now + 3_600_000), connection.credentials)
            assertEquals(connection, store.saved)
            assertTrue(tokenForm.contains("grant_type=authorization_code") && tokenForm.contains("code=one-time") && tokenForm.contains("client_id=client_issued"))
            assertTrue(tokenForm.contains("code_verifier=") && tokenForm.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A"))
            // The issued registration was saved before the code exchange.
            assertEquals("client_issued", store.history.first().clientId)
            assertNull(store.history.first().credentials)
        } finally { browser.shutdownNow() }
    }

    @Test fun signInFromADifferentAccountIsRejectedAndRegistrationForgotten() {
        val issuer = TestIssuer()
        val store = MemoryStore()
        store.saved = ChatGptConnection("client_issued", "someone-else", "other@example.com", null, listOf(CHATGPT_PLAN_SCOPE), null)
        val now = 1_800_000_000_000L
        var nonce = ""
        val http = ChatGptHttp { _, url, _, _, _ ->
            when {
                url.endsWith("/.well-known/openid-configuration") -> response(200, DISCOVERY)
                url.endsWith("/jwks.json") -> response(200, issuer.jwks())
                else -> response(200, JSONObject().put("access_token", "at").put("refresh_token", "rt").put("token_type", "Bearer").put("expires_in", 3600)
                    .put("scope", CHATGPT_SCOPES).put("id_token", issuer.idToken(issuer.claims("client_issued", nonce, now / 1000))).toString())
            }
        }
        val browser = Executors.newSingleThreadExecutor()
        try {
            expectChatGptError("account_mismatch") {
                ChatGptSession(store, ChatGptApi(http)) { now }.signIn { url ->
                    val query = queryOf(url)
                    nonce = query.getValue("nonce")
                    assertEquals("client_issued", query["client_id"])
                    assertEquals("other@example.com", query["login_hint"])
                    browser.submit { (URL("${query.getValue("redirect_uri")}?code=c&state=${query.getValue("state")}").openConnection() as HttpURLConnection).responseCode }
                }
            }
            assertNull(store.saved)
        } finally { browser.shutdownNow() }
    }
}
