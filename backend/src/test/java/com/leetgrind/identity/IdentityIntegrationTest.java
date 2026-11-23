package com.leetgrind.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "leetgrind.engagement.worker-enabled=false",
        "leetgrind.competition.worker-enabled=false",
        "leetgrind.auth.browser-origin=https://leetgrind.test",
        "leetgrind.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "leetgrind.auth.secure-cookie=true",
        "leetgrind.integration.fixtures-enabled=false",
        "spring.datasource.password=${LEETGRIND_DB_PASSWORD:phase1-test-password}"
})
class IdentityIntegrationTest {
    private static final String ORIGIN = "https://leetgrind.test";
    private static final String PASSWORD = "phase-one-only-password";
    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate database;
    @Autowired
    JsonMapper json;
    @Autowired
    JwtEncoder jwtEncoder;

    @Test
    void migrationsAndReadiness() throws Exception {
        HttpResponse<String> readiness = send("GET", "/actuator/health/readiness", null, null, null, false);
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(json.readTree(readiness.body()).path("status").asText()).isEqualTo("UP");
        assertThat(database.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success = true", Integer.class))
                .isEqualTo(1);
        assertThat(database.queryForObject(
                "select count(*) from flyway_schema_history where version = '2' and success = true", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void savingTheFixtureHandleWithDemoDisabledDoesNotQueueDemoSync() throws Exception {
        Fixture fixture = fixture();
        var registration = register(fixture.email(), fixture.leetcodeHandle());
        assertThat(registration.statusCode()).isEqualTo(201);
        String id = json.readTree(registration.body()).path("id").asText();
        String access = token(login(fixture.email()));
        var link = send("PUT", "/api/v1/me/leetcode-link", Map.of("handle", "fixture-" + id), access, null, false);
        assertThat(link.statusCode()).isEqualTo(200);
        assertThat(json.readTree(link.body()).path("state").asText()).isEqualTo("VERIFIED");
        assertThat(json.readTree(link.body()).path("demoAvailable").asBoolean()).isFalse();
        assertThat(database.queryForObject("""
                SELECT COUNT(*) FROM sync_jobs j JOIN leetcode_links l ON l.id = j.link_id WHERE l.account_id = ?
                """, Integer.class, Long.parseLong(id))).isZero();

        var group = send("POST", "/api/v1/groups", Map.of("name", "Local study"), access, null, false);
        assertThat(group.statusCode()).isEqualTo(201);
        String groupId = json.readTree(group.body()).path("id").asText();
        assertError(send("GET", "/api/v1/groups/" + groupId + "/leaderboard?mode=demo", null, access, null, false),
                400, "invalid_standings");
    }

    @Test
    void accountRoundTripRotationAndSignOut() throws Exception {
        Fixture fixture = fixture();
        var registration = register(fixture.email(), fixture.leetcodeHandle());
        assertThat(registration.statusCode()).isEqualTo(201);
        assertThat(json.readTree(registration.body()).path("email").asText()).isEqualTo(fixture.email());
        assertThat(database.queryForObject("select password_hash from accounts where email = ?", String.class, fixture.email()))
                .startsWith("$2").isNotEqualTo(PASSWORD);

        var login = login(fixture.email().toUpperCase(Locale.ROOT));
        assertThat(login.statusCode()).isEqualTo(200);
        String oldCookie = cookie(login);
        assertThat(oldCookie).startsWith("__Host-leetgrind_refresh=");
        assertThat(login.headers().firstValue("Set-Cookie").orElseThrow())
                .contains("HttpOnly", "SameSite=Strict", "Secure", "Path=/");

        var profile = send("GET", "/api/v1/me", null, token(login), null, false);
        assertThat(profile.statusCode()).isEqualTo(200);
        var updated = send("PATCH", "/api/v1/me", Map.of("displayName", "  New name  "), token(login), null, false);
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(json.readTree(updated.body()).path("displayName").asText()).isEqualTo("New name");

        var refreshed = send("POST", "/api/v1/auth/refresh", null, null, oldCookie, true);
        assertThat(refreshed.statusCode()).isEqualTo(200);
        String newCookie = cookie(refreshed);
        assertThat(newCookie).isNotEqualTo(oldCookie);
        assertThat(database.queryForObject("select count(*) from refresh_sessions where digest = ?",
                Integer.class, newCookie.split("=", 2)[1])).isZero();

        var logout = send("POST", "/api/v1/auth/logout", null, null, newCookie, true);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.headers().firstValue("Set-Cookie").orElseThrow()).contains("Max-Age=0");
        assertError(send("GET", "/api/v1/me", null, token(refreshed), null, false), 401, "unauthenticated");
        assertError(send("POST", "/api/v1/auth/refresh", null, null, newCookie, true), 401, "invalid_session");
        assertThat(send("POST", "/api/v1/auth/logout", null, null, null, true).statusCode()).isEqualTo(204);
    }

    @Test
    void concurrentRegistrationKeepsAccountConstraints() throws Exception {
        Fixture fixture = fixture();
        var first = request("POST", "/api/v1/auth/register", registration(fixture.email(), fixture.leetcodeHandle()),
                null, null, true);
        var second = request("POST", "/api/v1/auth/register",
                registration(" " + fixture.email().toUpperCase(Locale.ROOT) + " ", fixture.leetcodeHandle() + "x"),
                null, null, true);
        var a = client.sendAsync(first, HttpResponse.BodyHandlers.ofString());
        var b = client.sendAsync(second, HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(a, b).get(20, TimeUnit.SECONDS);
        assertThat(List.of(a.join().statusCode(), b.join().statusCode())).containsExactlyInAnyOrder(201, 409);
        assertError(a.join().statusCode() == 409 ? a.join() : b.join(), 409, "account_conflict");
        assertThat(database.queryForObject("select count(*) from accounts where email = ?", Integer.class, fixture.email()))
                .isEqualTo(1);
        String winningHandle = database.queryForObject("select handle from accounts where email = ?",
                String.class, fixture.email());
        var another = fixture();
        var sameName = register(another.email(), another.leetcodeHandle());
        assertThat(sameName.statusCode()).isEqualTo(201);
        assertThat(json.readTree(sameName.body()).path("handle").asText()).isNotEqualTo(winningHandle);
        assertThat(database.queryForObject("""
                select count(*) from session_families f join accounts a on a.id = f.account_id where a.email = ?
                """, Integer.class, fixture.email())).isZero();
    }

    @Test
    void simultaneousRefreshDetectsReplayAndRevokesFamily() throws Exception {
        Fixture fixture = fixture();
        assertThat(register(fixture.email(), fixture.leetcodeHandle()).statusCode()).isEqualTo(201);
        var login = login(fixture.email());
        assertThat(login.statusCode()).isEqualTo(200);
        var replay = request("POST", "/api/v1/auth/refresh", null, null, cookie(login), true);
        var a = client.sendAsync(replay, HttpResponse.BodyHandlers.ofString());
        var b = client.sendAsync(replay, HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(a, b).get(20, TimeUnit.SECONDS);
        assertThat(List.of(a.join().statusCode(), b.join().statusCode())).containsExactlyInAnyOrder(200, 401);
        var successful = a.join().statusCode() == 200 ? a.join() : b.join();
        var failed = a.join().statusCode() == 401 ? a.join() : b.join();
        assertError(failed, 401, "invalid_session");
        assertError(send("GET", "/api/v1/me", null, token(successful), null, false), 401, "unauthenticated");
        assertError(send("POST", "/api/v1/auth/refresh", null, null, cookie(successful), true), 401, "invalid_session");
    }

    @Test
    void cookieActionsRequireOriginAndClientHeader() throws Exception {
        Fixture fixture = fixture();
        assertError(send("POST", "/api/v1/auth/register", registration(fixture.email(), fixture.leetcodeHandle()),
                null, null, false), 403, "invalid_origin");

        assertError(send("POST", "/api/v1/auth/login", Map.of("email", fixture.email(), "password", PASSWORD),
                null, null, true), 401, "invalid_credentials");
        assertThat(register(fixture.email(), fixture.leetcodeHandle()).statusCode()).isEqualTo(201);
        var login = login(fixture.email());
        assertThat(login.statusCode()).isEqualTo(200);
        var crossOrigin = HttpRequest.newBuilder(uri("/api/v1/auth/refresh"))
                .header("Origin", "https://other.test").header("X-LeetGrind-Client", "browser")
                .header("Cookie", cookie(login)).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertError(client.send(crossOrigin, HttpResponse.BodyHandlers.ofString()), 403, "invalid_origin");

        var noHeader = HttpRequest.newBuilder(uri("/api/v1/auth/logout")).header("Origin", ORIGIN)
                .header("Cookie", cookie(login)).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertError(client.send(noHeader, HttpResponse.BodyHandlers.ofString()), 403, "invalid_origin");
        assertThat(send("POST", "/api/v1/auth/refresh", null, null, cookie(login), true).statusCode()).isEqualTo(200);
    }

    @Test
    void protectedRequestsRejectInvalidTokensAndDisabledAccount() throws Exception {
        assertError(send("GET", "/api/v1/me", null, null, null, false), 401, "unauthenticated");
        Fixture fixture = fixture();
        var registered = register(fixture.email(), fixture.leetcodeHandle());
        assertThat(registered.statusCode()).isEqualTo(201);
        var login = login(fixture.email());
        assertThat(login.statusCode()).isEqualTo(200);

        String token = token(login);
        String[] sections = token.split("\\.");
        JsonNode claims = json.readTree(java.util.Base64.getUrlDecoder().decode(sections[1]));
        String subject = claims.path("sub").asText();
        String familyId = claims.path("sid").asText();
        assertError(send("GET", "/api/v1/me", null, "not-a-jwt", null, false), 401, "unauthenticated");
        Instant now = Instant.now();
        assertError(send("GET", "/api/v1/me", null, signedToken(subject, familyId, IdentityConfiguration.ISSUER,
                IdentityConfiguration.AUDIENCE, now.minusSeconds(120)), null, false), 401, "unauthenticated");
        assertError(send("GET", "/api/v1/me", null, signedToken(subject, familyId, IdentityConfiguration.ISSUER,
                "unrelated-api", now.plusSeconds(120)), null, false), 401, "unauthenticated");
        assertError(send("GET", "/api/v1/me", null, signedToken(subject, familyId, "https://other.test",
                IdentityConfiguration.AUDIENCE, now.plusSeconds(120)), null, false), 401, "unauthenticated");

        database.update("update accounts set active = false where email = ?", fixture.email());
        assertError(send("GET", "/api/v1/me", null, token, null, false), 401, "unauthenticated");
        assertError(send("POST", "/api/v1/auth/refresh", null, null, cookie(login), true), 401, "invalid_session");
    }

    @Test
    void identityResponsesMatchContract() throws Exception {
        var response = send("GET", "/openapi.yaml", null, null, null, false);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("openapi: 3.1.0");
        Map<?, ?> contract;
        try (InputStream source = getClass().getResourceAsStream("/static/openapi.yaml")) {
            contract = new Yaml().load(source);
        }
        Fixture fixture = fixture();
        var registration = register(fixture.email(), fixture.leetcodeHandle());
        assertContract(contract, "/auth/register", "post", registration, "201", "Profile");
        var login = login(fixture.email());
        assertContract(contract, "/auth/login", "post", login, "200", "Access");
        var error = send("PATCH", "/api/v1/me", Map.of("displayName", " "), token(login), null, false);
        assertError(error, 400, "invalid_request");
        assertContract(contract, "/me", "patch", error, "400", "ApiError");
    }

    private void assertContract(Map<?, ?> contract, String path, String method, HttpResponse<String> response,
                                String status, String schema) {
        assertThat(Integer.toString(response.statusCode())).isEqualTo(status);
        var operation = submap(contract, "paths", path, method, "responses", status);
        var definedResponse = submap(contract, "components", "responses", lastSegment(operation.get("$ref")));
        var schemaRef = submap(definedResponse, "content", "application/json", "schema").get("$ref");
        assertThat(lastSegment(schemaRef)).isEqualTo(schema);
        var required = (List<?>) submap(contract, "components", "schemas", schema).get("required");
        var fields = json.readTree(response.body()).properties().stream().map(Map.Entry::getKey).toList();
        assertThat(fields).containsExactlyInAnyOrderElementsOf(required.stream().map(Object::toString).toList());
        assertThat(response.headers().firstValue("X-Correlation-ID")).isPresent();
    }

    private static Map<?, ?> submap(Map<?, ?> node, String... keys) {
        for (String key : keys) node = (Map<?, ?>) node.get(key);
        return node;
    }

    private static String lastSegment(Object ref) {
        String value = ref.toString();
        return value.substring(value.lastIndexOf('/') + 1);
    }

    private String signedToken(String subject, String familyId, String issuer, String audience, Instant expiry) {
        var claims = JwtClaimsSet.builder().subject(subject).claim("sid", familyId).issuer(issuer)
                .audience(List.of(audience)).issuedAt(expiry.minusSeconds(180)).expiresAt(expiry).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 12).replace("-", "");
        return new Fixture("learner-" + suffix + "@example.test", "learner_" + suffix);
    }

    private HttpResponse<String> register(String email, String leetcodeHandle) throws Exception {
        return send("POST", "/api/v1/auth/register", registration(email, leetcodeHandle), null, null, true);
    }

    private Map<String, String> registration(String email, String leetcodeHandle) {
        return Map.of("email", email, "leetcodeHandle", leetcodeHandle, "displayName", "Test Learner", "password", PASSWORD);
    }

    private HttpResponse<String> login(String email) throws Exception {
        return send("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null, null, true);
    }

    private HttpResponse<String> send(String method, String path, Object body, String token, String cookie, boolean browser)
            throws Exception {
        return client.send(request(method, path, body, token, cookie, browser), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String method, String path, Object body, String token, String cookie, boolean browser) {
        var builder = HttpRequest.newBuilder(uri(path));
        if (browser) builder.header("Origin", ORIGIN).header("X-LeetGrind-Client", "browser");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (cookie != null) builder.header("Cookie", cookie);
        if (body != null) builder.header("Content-Type", "application/json");
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        var body = json.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("correlationId").asText()).isEqualTo(response.headers().firstValue("X-Correlation-ID").orElseThrow());
    }

    private String token(HttpResponse<String> response) {
        return json.readTree(response.body()).path("accessToken").asText();
    }

    private String cookie(HttpResponse<String> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private record Fixture(String email, String leetcodeHandle) {}
}
