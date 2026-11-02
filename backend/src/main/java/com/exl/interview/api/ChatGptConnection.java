package com.exl.interview.api;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Local-only, per-author OAuth connection. Credentials never enter application JSON responses. */
@Component
public class ChatGptConnection {

    static final String ISSUER = "https://auth.openai.com",
        RESOURCE = "https://api.openai.com/v1";
    static final String SCOPES =
        "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private final ObjectMapper json;
    private final Path directory;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Attempt> pending = new HashMap<>();
    private final Map<String, Launch> launches = new HashMap<>();
    private final Map<String, String> results = new HashMap<>();
    private final JwtDecoder decoder;

    @Value("${interview.chatgpt-enabled:true}")
    private boolean enabled = true;

    record Attempt(
        String owner,
        String session,
        String verifier,
        String nonce,
        String redirect,
        String clientId,
        String subject,
        long expires
    ) {}

    record Launch(String owner, String session, String url, long expires) {}

    public record ConnectionStatus(
        boolean enabled,
        boolean connected,
        String email,
        String message
    ) {}

    public record Model(String slug, String displayName) {}

    // Deliberately not a record: generated toString must never reveal credentials.
    static class Credentials {

        public String clientId = "",
            subject = "",
            email = "",
            idToken = "",
            accessToken = "",
            refreshToken = "",
            scope = "";
        public long expires;
    }

    public ChatGptConnection(
        ObjectMapper json,
        @Value("${interview.chatgpt-storage:./data/chatgpt}") String storage
    ) {
        this.json = json;
        this.directory = Path.of(storage).toAbsolutePath().normalize();
        var jwt = NimbusJwtDecoder.withJwkSetUri(ISSUER + "/.well-known/jwks.json").build();
        jwt.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        this.decoder = jwt;
    }

    static ResponseStatusException fail(int status, String message) {
        return new ResponseStatusException(HttpStatus.valueOf(status), message);
    }

    public void requireLocal(String remote) {
        if (!enabled) throw fail(503, "ChatGPT connection is disabled");
        if (!Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(remote)) throw fail(
            403,
            "ChatGPT subscription connections are available only on this computer. Hosted use requires a separately approved integration."
        );
    }

    private String secret() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String form(Map<String, String> values) {
        return values
            .entrySet()
            .stream()
            .map(
                e ->
                    URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) +
                    "=" +
                    URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)
            )
            .collect(java.util.stream.Collectors.joining("&"));
    }

    private void protect(Path p) throws Exception {
        var acl = Files.getFileAttributeView(
            p,
            AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS
        );
        if (acl != null) {
            var owner = Files.getOwner(p, LinkOption.NOFOLLOW_LINKS);
            acl.setAcl(
                List.of(
                    AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .build()
                )
            );
        } else Files.setPosixFilePermissions(
            p,
            Files.isDirectory(p)
                ? PosixFilePermissions.fromString("rwx------")
                : PosixFilePermissions.fromString("rw-------")
        );
    }

    private void prepare() throws Exception {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IllegalStateException();
        protect(directory);
    }

    private void atomic(Path path, byte[] bytes) throws Exception {
        prepare();
        Path tmp = Files.createTempFile(directory, "connection-", ".tmp");
        try {
            protect(tmp);
            Files.write(tmp, bytes);
            Files.move(
                tmp,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private byte[] key() throws Exception {
        prepare();
        Path p = directory.resolve("credentials.key");
        if (!Files.exists(p)) {
            byte[] k = new byte[32];
            random.nextBytes(k);
            atomic(p, k);
        }
        byte[] k = Files.readAllBytes(p);
        if (k.length != 32) throw new IllegalStateException();
        return k;
    }

    private Path file(String owner) {
        return directory.resolve(StaffAccess.hash(owner) + ".enc");
    }

    private Credentials load(String owner) {
        try {
            Path p = file(owner);
            if (!Files.exists(p)) return new Credentials();
            if (
                !Files.exists(directory.resolve("credentials.key"))
            ) throw new IllegalStateException();
            byte[] b = Files.readAllBytes(p);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(key(), "AES"),
                new GCMParameterSpec(128, b, 0, 12)
            );
            cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
            return json.readValue(cipher.doFinal(b, 12, b.length - 12), Credentials.class);
        } catch (Exception e) {
            throw fail(
                503,
                "The local ChatGPT credential store could not be read. Restore its original encryption key before using this connection."
            );
        }
    }

    private void save(String owner, Credentials c) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key(), "AES"),
                new GCMParameterSpec(128, iv)
            );
            cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
            byte[] body = cipher.doFinal(json.writeValueAsBytes(c)),
                all = new byte[12 + body.length];
            System.arraycopy(iv, 0, all, 0, 12);
            System.arraycopy(body, 0, all, 12, body.length);
            atomic(file(owner), all);
        } catch (Exception e) {
            throw fail(503, "ChatGPT credentials could not be stored securely");
        }
    }

    private String host() {
        try {
            prepare();
            Path p = directory.resolve("host-id");
            if (!Files.exists(p)) atomic(
                p,
                ("urn:uuid:" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)
            );
            String id = Files.readString(p);
            if (!id.matches("urn:uuid:[0-9a-f-]{36}")) throw new IllegalStateException();
            return id;
        } catch (Exception e) {
            throw fail(503, "Local ChatGPT host identity could not be loaded");
        }
    }

    public synchronized ConnectionStatus status(String owner) {
        var c = load(owner);
        return new ConnectionStatus(
            enabled,
            !c.accessToken.isBlank() && hasPlan(c.scope),
            c.email,
            results.getOrDefault(owner, "")
        );
    }

    static boolean hasPlan(String scope) {
        return new HashSet<>(Arrays.asList(scope.split(" +"))).containsAll(
            List.of("resource.invoke", "chatgpt.tokens.use.direct")
        );
    }

    public synchronized String begin(String owner, String session, int port) {
        if (!enabled) throw fail(503, "ChatGPT connection is disabled");
        pending
            .entrySet()
            .removeIf(
                e ->
                    e.getValue().expires() < System.currentTimeMillis() ||
                    e.getValue().owner().equals(owner)
            );
        if (pending.size() > 100) throw fail(429, "Too many pending connection attempts");
        var c = load(owner);
        String state = secret(),
            verifier = secret(),
            nonce = secret(),
            redirect = "http://127.0.0.1:" + port + "/api/author/chatgpt/callback";
        var params = new LinkedHashMap<String, String>();
        params.put("client_id", c.clientId.isBlank() ? "dynamic_agent_client" : c.clientId);
        params.put("ext_agent_host_id", host());
        if (c.clientId.isBlank()) params.put("agent_name_hint", "EXL Interview Platform");
        if (!c.idToken.isBlank()) params.put("id_token_hint", c.idToken);
        params.put("response_type", "code");
        params.put("redirect_uri", redirect);
        params.put("scope", SCOPES);
        params.put("resource", RESOURCE);
        params.put("state", state);
        params.put("nonce", nonce);
        params.put("code_challenge_method", "S256");
        try {
            params.put(
                "code_challenge",
                Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                        java.security.MessageDigest.getInstance("SHA-256").digest(
                            verifier.getBytes(StandardCharsets.US_ASCII)
                        )
                    )
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        pending.put(
            state,
            new Attempt(
                owner,
                session,
                verifier,
                nonce,
                redirect,
                c.clientId,
                c.subject,
                System.currentTimeMillis() + 600000
            )
        );
        results.remove(owner);
        return ISSUER + "/api/accounts/authorize?" + form(params);
    }

    public synchronized String prepareLogin(String owner, String session, int port) {
        launches
            .entrySet()
            .removeIf(
                e ->
                    e.getValue().expires() < System.currentTimeMillis() ||
                    e.getValue().owner().equals(owner)
            );
        String url = begin(owner, session, port),
            ticket = secret();
        launches.put(ticket, new Launch(owner, session, url, System.currentTimeMillis() + 600000));
        return "http://127.0.0.1:" + port + "/api/author/chatgpt/authorize?ticket=" + ticket;
    }

    synchronized Launch launch(String ticket) {
        var launch = launches.remove(ticket);
        if (launch == null || launch.expires() < System.currentTimeMillis()) throw fail(
            400,
            "This connection link expired. Start again in Assessment authoring."
        );
        return launch;
    }

    // One-time consumption occurs before network I/O, including failed callbacks.
    synchronized Attempt consume(String state) {
        var a = pending.remove(state);
        if (a == null || a.expires() < System.currentTimeMillis()) throw fail(
            400,
            "Connection attempt expired or was already used. Start again in the application."
        );
        return a;
    }

    JsonNode token(Map<String, String> values) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(ISSUER + "/api/accounts/oauth/token"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(values)))
            .build();
        var r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw fail(
            502,
            "OpenAI could not authorize this connection. Start again or check your ChatGPT plan permissions."
        );
        return json.readTree(r.body());
    }

    Jwt identity(String token, String clientId, String nonce, String subject) {
        try {
            var jwt = decoder.decode(token);
            validateClaims(jwt, clientId, nonce, subject);
            return jwt;
        } catch (Exception e) {
            throw fail(
                502,
                "OpenAI account identity could not be verified. The previous connection was preserved."
            );
        }
    }

    static void validateClaims(Jwt jwt, String clientId, String nonce, String subject) {
        if (
            !jwt.getAudience().contains(clientId) ||
            jwt.getSubject() == null ||
            jwt.getSubject().isBlank() ||
            jwt.getIssuedAt() == null ||
            jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(5)) ||
            !Objects.equals(jwt.getClaimAsString("nonce"), nonce) ||
            (!subject.isBlank() && !subject.equals(jwt.getSubject())) ||
            jwt.getExpiresAt() == null ||
            jwt.getExpiresAt().isBefore(Instant.now())
        ) throw new IllegalArgumentException("Invalid ID token claims");
        String azp = jwt.getClaimAsString("azp");
        if (
            (jwt.getAudience().size() > 1 || azp != null) && !clientId.equals(azp)
        ) throw new IllegalArgumentException("Invalid authorized party");
    }

    private void update(Credentials c, JsonNode t) {
        if (
            !"Bearer".equalsIgnoreCase(t.path("token_type").asText()) ||
            t.path("access_token").asText().isBlank() ||
            t.path("expires_in").asLong() <= 0 ||
            !hasPlan(t.path("scope").asText(c.scope))
        ) throw fail(
            403,
            "ChatGPT plan usage was not granted. Reconnect and allow ChatGPT plan access."
        );
        c.accessToken = t.path("access_token").asText();
        c.refreshToken = t.path("refresh_token").asText(c.refreshToken);
        c.idToken = t.path("id_token").asText(c.idToken);
        c.scope = t.path("scope").asText(c.scope);
        c.expires =
            System.currentTimeMillis() + Math.min(t.path("expires_in").asLong(), 86400) * 1000;
    }

    public synchronized void complete(Attempt a, String code, String issued, String error) {
        if (error != null && !error.isBlank()) {
            results.put(
                a.owner(),
                "ChatGPT connection was declined. The previous connection was preserved."
            );
            return;
        }
        String clientId = a.clientId().isBlank() ? issued : a.clientId();
        if (
            clientId == null ||
            !clientId.matches("oaiapp_[A-Za-z0-9_-]+") ||
            (!a.clientId().isBlank() && issued != null && !issued.equals(a.clientId())) ||
            code == null ||
            code.isBlank()
        ) throw fail(400, "Incomplete ChatGPT registration. Start the connection again.");
        try {
            var t = token(
                Map.of(
                    "grant_type",
                    "authorization_code",
                    "client_id",
                    clientId,
                    "code",
                    code,
                    "code_verifier",
                    a.verifier(),
                    "redirect_uri",
                    a.redirect(),
                    "resource",
                    RESOURCE
                )
            );
            var jwt = identity(t.path("id_token").asText(), clientId, a.nonce(), a.subject());
            var c = new Credentials();
            c.clientId = clientId;
            c.subject = jwt.getSubject();
            c.email = Optional.ofNullable(jwt.getClaimAsString("email")).orElse("");
            update(c, t);
            if (c.refreshToken.isBlank()) throw fail(
                403,
                "Offline access was not granted. Reconnect with the requested permissions."
            );
            save(a.owner(), c);
            results.put(
                a.owner(),
                "ChatGPT connected. Refresh connection status and choose a model."
            );
        } catch (ResponseStatusException e) {
            results.put(a.owner(), e.getReason());
            throw e;
        } catch (Exception e) {
            results.put(
                a.owner(),
                "ChatGPT connection failed. Start again; the previous connection was preserved."
            );
            throw fail(502, "ChatGPT connection could not be completed");
        }
    }

    public synchronized String access(String owner) {
        var c = load(owner);
        if (c.accessToken.isBlank() || !hasPlan(c.scope)) throw fail(
            409,
            "Connect your ChatGPT subscription first"
        );
        if (c.expires < System.currentTimeMillis() + 60000) {
            try {
                var t = token(
                    Map.of(
                        "grant_type",
                        "refresh_token",
                        "client_id",
                        c.clientId,
                        "refresh_token",
                        c.refreshToken,
                        "resource",
                        RESOURCE
                    )
                );
                update(c, t);
                save(owner, c);
            } catch (Exception e) {
                throw fail(
                    409,
                    "ChatGPT connection needs renewal. Reconnect your subscription; no paid API fallback was used."
                );
            }
        }
        return c.accessToken;
    }

    public synchronized List<Model> models(String owner) {
        try {
            var req = HttpRequest.newBuilder(URI.create(RESOURCE + "/models"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + access(owner))
                .GET()
                .build();
            var r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw fail(
                502,
                "ChatGPT models could not be loaded. Refresh or reconnect."
            );
            var models = new ArrayList<Model>();
            for (var m : json.readTree(r.body()).path("models"))
                if (
                    m.path("visibility").asText().equals("list") &&
                    !m.path("slug").asText().isBlank()
                ) models.add(
                    new Model(
                        m.path("slug").asText(),
                        m.path("display_name").asText(m.path("slug").asText())
                    )
                );
            return models;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw fail(502, "ChatGPT models could not be loaded");
        }
    }

    public synchronized boolean disconnect(String owner) {
        pending.entrySet().removeIf(e -> e.getValue().owner().equals(owner));
        launches.entrySet().removeIf(e -> e.getValue().owner().equals(owner));
        var c = load(owner);
        boolean revoked = c.refreshToken.isBlank();
        if (!revoked) {
            try {
                var discovery = http.send(
                    HttpRequest.newBuilder(URI.create(ISSUER + "/.well-known/openid-configuration"))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                URI endpoint = URI.create(
                    json.readTree(discovery.body()).path("revocation_endpoint").asText()
                );
                if (
                    !"https".equals(endpoint.getScheme()) ||
                    !"auth.openai.com".equals(endpoint.getHost())
                ) throw new IllegalStateException();
                var r = http.send(
                    HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                form(
                                    Map.of(
                                        "token",
                                        c.refreshToken,
                                        "token_type_hint",
                                        "refresh_token",
                                        "client_id",
                                        c.clientId
                                    )
                                )
                            )
                        )
                        .build(),
                    HttpResponse.BodyHandlers.discarding()
                );
                revoked = r.statusCode() == 200;
            } catch (Exception ignored) {}
        }
        c.accessToken = "";
        c.refreshToken = "";
        c.idToken = "";
        c.scope = "";
        c.expires = 0;
        save(owner, c);
        results.put(
            owner,
            revoked
                ? "ChatGPT disconnected."
                : "Disconnected locally. Remote revocation was not confirmed; remove the connection in ChatGPT Settings."
        );
        return revoked;
    }
}
