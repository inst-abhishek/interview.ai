package com.exl.interview.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class ChatGptConnectionTest {

    @TempDir
    Path temp;

    final ObjectMapper json = new ObjectMapper();

    class Connection extends ChatGptConnection {

        Map<String, String> exchanged;
        String scope = SCOPES;
        int calls;

        Connection() {
            super(json, temp.toString());
        }

        @Override
        JsonNode token(Map<String, String> params) {
            exchanged = params;
            calls++;
            return json.valueToTree(
                Map.of(
                    "access_token",
                    "private-access-token",
                    "refresh_token",
                    "private-refresh-token",
                    "id_token",
                    "private-id-token",
                    "token_type",
                    "Bearer",
                    "expires_in",
                    3600,
                    "scope",
                    scope
                )
            );
        }
    }

    Map<String, String> query(String url) {
        var result = new HashMap<String, String>();
        for (var pair : URI.create(url).getRawQuery().split("&")) {
            var v = pair.split("=", 2);
            result.put(
                URLDecoder.decode(v[0], java.nio.charset.StandardCharsets.UTF_8),
                URLDecoder.decode(v[1], java.nio.charset.StandardCharsets.UTF_8)
            );
        }
        return result;
    }

    Jwt jwt(String client, String nonce, String sub) {
        return Jwt.withTokenValue("private-id-token")
            .header("alg", "RS256")
            .issuer(ChatGptConnection.ISSUER)
            .subject(sub)
            .audience(List.of(client))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .claim("nonce", nonce)
            .claim("email", "author@example.test")
            .build();
    }

    void verifyIdentity(Connection c, ChatGptConnection.Attempt a) {
        var decoder = mock(JwtDecoder.class);
        when(decoder.decode("private-id-token")).thenReturn(
            jwt("oaiapp_test", a.nonce(), "subject-one")
        );
        ReflectionTestUtils.setField(c, "decoder", decoder);
    }

    void connect(Connection c) {
        var a = c.consume(query(c.begin("author-one", "Bearer app-session", 8080)).get("state"));
        verifyIdentity(c, a);
        c.complete(a, "test-code", "oaiapp_test", null);
    }

    @Test
    void signInUsesStableHostPkceAndOneTimeExpiringState() {
        var c = new Connection();
        var one = query(c.begin("author-one", "Bearer app-session", 8080));
        assertEquals("dynamic_agent_client", one.get("client_id"));
        assertEquals("S256", one.get("code_challenge_method"));
        assertEquals("http://127.0.0.1:8080/api/author/chatgpt/callback", one.get("redirect_uri"));
        assertEquals(ChatGptConnection.SCOPES, one.get("scope"));
        assertFalse(one.containsKey("client_secret"));
        var a = c.consume(one.get("state"));
        assertNotEquals(a.verifier(), one.get("code_challenge"));
        assertThrows(ResponseStatusException.class, () -> c.consume(one.get("state")));
        assertThrows(ResponseStatusException.class, () -> c.consume("forged-state"));
        var two = query(c.begin("author-two", "Bearer other-session", 8080));
        assertEquals(one.get("ext_agent_host_id"), two.get("ext_agent_host_id"));
        assertNotEquals(one.get("state"), two.get("state"));
    }

    @Test
    void successfulConnectionExchangesIssuedClientAndKeepsSecretsEncryptedAndIsolated()
        throws Exception {
        var c = new Connection();
        connect(c);
        assertEquals("oaiapp_test", c.exchanged.get("client_id"));
        assertEquals(ChatGptConnection.RESOURCE, c.exchanged.get("resource"));
        assertTrue(c.exchanged.containsKey("code_verifier"));
        assertEquals("private-access-token", c.access("author-one"));
        assertTrue(c.status("author-one").connected());
        assertFalse(c.status("author-two").connected());
        for (Path p : Files.list(temp).toList()) {
            String encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(p));
            assertFalse(
                encoded.contains(
                    Base64.getEncoder().encodeToString("private-access-token".getBytes())
                )
            );
            if (p.toString().endsWith(".enc")) {
                String text = new String(
                    Files.readAllBytes(p),
                    java.nio.charset.StandardCharsets.ISO_8859_1
                );
                assertFalse(text.contains("private-refresh-token"));
                Files.copy(p, temp.resolve(StaffAccess.hash("author-two") + ".enc"));
            }
        }
        assertThrows(ResponseStatusException.class, () -> c.access("author-two"));
        var returning = query(c.begin("author-one", "Bearer app-session", 8080));
        assertEquals("oaiapp_test", returning.get("client_id"));
        assertEquals("private-id-token", returning.get("id_token_hint"));
        assertFalse(returning.containsKey("agent_name_hint"));
    }

    @Test
    void failedOrDeclinedReconnectionPreservesExistingCredentials() {
        var c = new Connection();
        connect(c);
        var a = c.consume(query(c.begin("author-one", "Bearer app-session", 8080)).get("state"));
        c.complete(a, null, null, "access_denied");
        assertTrue(c.status("author-one").connected());
        assertEquals(1, c.calls);
        var b = c.consume(query(c.begin("author-one", "Bearer app-session", 8080)).get("state"));
        assertThrows(ResponseStatusException.class, () ->
            c.complete(b, "code", "oaiapp_other", null)
        );
        assertEquals(1, c.calls);
        assertEquals("private-access-token", c.access("author-one"));
    }

    @Test
    void planScopeAndVerifiedIdentityAreRequired() {
        var c = new Connection();
        var a = c.consume(query(c.begin("author-one", "Bearer app-session", 8080)).get("state"));
        verifyIdentity(c, a);
        c.scope = "openid profile email";
        assertThrows(ResponseStatusException.class, () ->
            c.complete(a, "code", "oaiapp_test", null)
        );
        assertFalse(c.status("author-one").connected());
        var decoder = mock(JwtDecoder.class);
        when(decoder.decode(anyString())).thenThrow(new JwtException("Bad signature"));
        ReflectionTestUtils.setField(c, "decoder", decoder);
        assertThrows(ResponseStatusException.class, () ->
            c.identity("invalid-token", "oaiapp_test", "nonce", "")
        );
        assertThrows(IllegalArgumentException.class, () ->
            ChatGptConnection.validateClaims(
                jwt("wrong-client", "nonce", "sub"),
                "expected-client",
                "nonce",
                ""
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            ChatGptConnection.validateClaims(
                jwt("client", "wrong-nonce", "sub"),
                "client",
                "nonce",
                ""
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            ChatGptConnection.validateClaims(
                jwt("client", "nonce", "wrong-person"),
                "client",
                "nonce",
                "expected-person"
            )
        );
    }

    @Test
    void remoteConnectionsAreRejectedAndDisconnectCancelsPendingLogin() {
        var c = new Connection();
        c.requireLocal("127.0.0.1");
        assertThrows(ResponseStatusException.class, () -> c.requireLocal("192.168.1.20"));
        var state = query(c.begin("author-one", "Bearer app-session", 8080)).get("state");
        assertTrue(c.disconnect("author-one"));
        assertThrows(ResponseStatusException.class, () -> c.consume(state));
        assertFalse(c.status("author-one").connected());
    }

    @Test
    void browserLaunchUsesOneTimeOpaqueTicketRatherThanExposingIdTokenToFrontend() {
        var c = new Connection();
        connect(c);
        String url = c.prepareLogin("author-one", "Bearer app-session", 8080);
        assertTrue(url.startsWith("http://127.0.0.1:8080/api/author/chatgpt/authorize?ticket="));
        assertFalse(url.contains("private-id-token"));
        String ticket = query(url).get("ticket");
        var launch = c.launch(ticket);
        assertEquals("author-one", launch.owner());
        assertTrue(launch.url().startsWith(ChatGptConnection.ISSUER + "/api/accounts/authorize?"));
        assertThrows(ResponseStatusException.class, () -> c.launch(ticket));
    }

    @Test
    void missingEncryptionKeyDoesNotSilentlyReplaceTheOriginalKey() throws Exception {
        var c = new Connection();
        connect(c);
        Path key = temp.resolve("credentials.key");
        Files.delete(key);
        assertThrows(ResponseStatusException.class, () -> c.status("author-one"));
        assertFalse(Files.exists(key));
    }

    @Test
    void expiredStateIsRejectedAndRefreshUsesIssuedClientWithSerializedRotation() throws Exception {
        var c = new Connection();
        String state = query(c.begin("author-one", "Bearer app-session", 8080)).get("state");
        var a = c.consume(state);
        @SuppressWarnings("unchecked")
        var pending = (Map<String, ChatGptConnection.Attempt>) ReflectionTestUtils.getField(
            c,
            "pending"
        );
        pending.put(
            state,
            new ChatGptConnection.Attempt(
                a.owner(),
                a.session(),
                a.verifier(),
                a.nonce(),
                a.redirect(),
                a.clientId(),
                a.subject(),
                0
            )
        );
        assertThrows(ResponseStatusException.class, () -> c.consume(state));
        connect(c);
        ChatGptConnection.Credentials credentials = ReflectionTestUtils.invokeMethod(
            c,
            "load",
            "author-one"
        );
        credentials.expires = 0;
        ReflectionTestUtils.invokeMethod(c, "save", "author-one", credentials);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 10; i++) tasks.add(executor.submit(() -> c.access("author-one")));
            for (var task : tasks) assertEquals("private-access-token", task.get());
        }
        assertEquals(2, c.calls);
        assertEquals("refresh_token", c.exchanged.get("grant_type"));
        assertEquals("oaiapp_test", c.exchanged.get("client_id"));
        assertFalse(c.exchanged.containsKey("scope"));
    }
}
