package com.leetgrind.analytics;

import static com.leetgrind.catalog.ProblemCatalog.Difficulty.EASY;
import static com.leetgrind.catalog.ProblemCatalog.Difficulty.HARD;
import static com.leetgrind.catalog.ProblemCatalog.Difficulty.MEDIUM;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import com.leetgrind.catalog.ProblemCatalog;
import com.leetgrind.catalog.ProblemCatalog.Difficulty;
import com.leetgrind.catalog.ProblemCatalog.Problem;
import com.leetgrind.platform.Utc;
import com.leetgrind.progress.ProgressService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "leetgrind.engagement.worker-enabled=false",
        "leetgrind.competition.worker-enabled=false",
        "leetgrind.integration.scheduler-enabled=false",
        "leetgrind.integration.fixtures-enabled=true",
        "leetgrind.auth.browser-origin=https://leetgrind.test",
        "leetgrind.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "leetgrind.auth.secure-cookie=true",
        "spring.datasource.password=${LEETGRIND_DB_PASSWORD:phase1-test-password}"
})
class AnalyticsIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-02-03T12:00:00Z");
    private static final Instant TRACKING_START = Instant.parse("2025-08-01T00:00:00Z");
    private static final Instant FEBRUARY = Instant.parse("2026-02-01T00:00:00Z");
    private static final Instant MONDAY = Instant.parse("2026-02-02T00:00:00Z");
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired AnalyticsService analytics;
    @Autowired ProblemCatalog catalog;
    @Autowired ProgressService progress;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired PlatformTransactionManager manager;

    @Test
    void summaryAndPeriodBucketsReconcileToCanonicalSolvesAndTheLedger() throws Exception {
        var learner = login();
        var demo = source(learner.id(), "FIXTURE");
        seed(demo, "graph", "fixture-graph", HARD, TRACKING_START.plusSeconds(400));
        long array = seed(demo, "array", "fixture-array-window", MEDIUM, FEBRUARY.minusNanos(1000));
        assertThat(seed(demo, "array", "renamed-array-window", MEDIUM, MONDAY.plusSeconds(30))).isEqualTo(array);
        seed(demo, "hash", "fixture-hash", EASY, FEBRUARY);
        seed(demo, "unmapped", "untagged-analytics", MEDIUM, FEBRUARY.plusSeconds(100));
        seed(demo, "stack", "fixture-stack", EASY, MONDAY);
        new TransactionTemplate(manager).executeWithoutResult(tx ->
                progress.reward(learner.id(), array, "FIXTURE", "test-late-bonus", "DAILY_QUEST", 30, 1,
                        FEBRUARY.minusNanos(1000), NOW));

        var all = analytics.read(learner.id(), "demo", "all-time", null);
        assertThat(all.summary().solved()).isEqualTo(5);
        assertThat(all.summary().xpEarned()).isEqualTo(150);
        assertThat(all.summary().unmapped()).isEqualTo(1);
        assertThat(all.summary().codingDays()).isEqualTo(4);
        assertThat(all.summary().byDifficulty()).extracting(AnalyticsService.DifficultyTotal::solved).containsExactly(2L, 2L, 1L);
        assertThat(all.summary().topics()).containsExactly(new AnalyticsService.TopicTotal("Array", 2),
                new AnalyticsService.TopicTotal("Breadth-First Search", 1), new AnalyticsService.TopicTotal("Graph", 1),
                new AnalyticsService.TopicTotal("Hash Table", 1), new AnalyticsService.TopicTotal("Sliding Window", 1),
                new AnalyticsService.TopicTotal("Stack", 1));
        assertThat(all.streaks()).isEqualTo(new AnalyticsService.Streaks(3, 3));
        assertThat(all.weekly()).hasSize(8);
        assertThat(all.monthly()).hasSize(6);
        assertThat(all.monthly().getFirst().totalXp()).isEqualTo(50);
        assertThat(all.monthly().getFirst().xpEarned()).isZero();
        assertThat(all.monthly().get(4)).isEqualTo(new AnalyticsService.Bucket(FEBRUARY.atOffset(ZoneOffset.UTC).toLocalDate().minusMonths(1),
                FEBRUARY.atOffset(ZoneOffset.UTC).toLocalDate(), 1, 1, 55, 105));
        assertThat(all.monthly().getLast().totalXp()).isEqualTo(150);
        assertThat(all.weekly().get(6).xpEarned()).isEqualTo(90);
        assertThat(all.weekly().get(6).codingDays()).isEqualTo(2);
        assertThat(all.weekly().getLast().xpEarned()).isEqualTo(10);
        assertThat(all.weekly().getLast().solved()).isEqualTo(1);
        assertThat(all.dailyActivity()).containsExactly(
                new AnalyticsService.ActivityDay(LocalDate.parse("2025-08-01"), 1),
                new AnalyticsService.ActivityDay(LocalDate.parse("2026-01-31"), 1),
                new AnalyticsService.ActivityDay(LocalDate.parse("2026-02-01"), 2),
                new AnalyticsService.ActivityDay(LocalDate.parse("2026-02-02"), 1));

        var january = read(learner, "?mode=demo&period=monthly&start=2026-01-01").path("summary");
        var february = read(learner, "?mode=demo&period=monthly").path("summary");
        assertThat(january.path("solved").asLong()).isEqualTo(1);
        assertThat(january.path("xpEarned").asLong()).isEqualTo(55);
        assertThat(february.path("solved").asLong()).isEqualTo(3);
        assertThat(february.path("codingDays").asLong()).isEqualTo(2);
        assertThat(february.path("xpEarned").asLong()).isEqualTo(45);
        assertThat(read(learner, "?period=weekly").path("summary").path("xpEarned").asLong()).isEqualTo(5);
        assertThat(all.recommendations()).extracting(AnalyticsService.Recommendation::slug).containsExactly("fixture-tree");
    }

    @Test
    void incompleteHistoryIsVisibleAndAccountAndProviderReadsAreIsolated() throws Exception {
        var learner = login();
        var other = login();
        assertThat(read(learner, "?mode=demo").path("coverage").path("state").asText()).isEqualTo("NO_LINK");
        var demo = source(learner.id(), "FIXTURE");
        assertThat(read(learner, "?mode=demo").path("coverage").path("state").asText()).isEqualTo("AWAITING_SYNC");
        jdbc.sql("UPDATE leetcode_links SET last_success_at = ? WHERE id = ?").params(Utc.sql(FEBRUARY), demo.link()).update();
        jdbc.sql("""
                INSERT INTO sync_jobs (link_id, link_version, state, next_run_at, last_attempt_at, failure_code)
                VALUES (?, 1, 'RETRY', ?, ?, 'COVERAGE_UNKNOWN')
                """).params(demo.link(), Utc.sql(NOW.plusSeconds(600)), Utc.sql(NOW)).update();
        seed(demo, "array", "fixture-array-window", MEDIUM, MONDAY);
        seed(source(other.id(), "FIXTURE"), "tree", "fixture-tree", MEDIUM, MONDAY);

        var response = read(learner, "?mode=demo&accountId=" + other.id());
        assertThat(response.path("coverage").path("state").asText()).isEqualTo("PARTIAL");
        assertThat(response.path("coverage").path("historicalImportAvailable").asBoolean()).isFalse();
        assertThat(response.path("coverage").path("lastSuccessfulSyncAt").asText()).isEqualTo(FEBRUARY.toString());
        assertThat(response.path("coverage").path("trackedSince").asText()).isEqualTo(TRACKING_START.toString());
        assertThat(response.path("summary").path("solved").asLong()).isEqualTo(1);
        assertThat(response.path("summary").path("topics").get(0).path("topic").asText()).isEqualTo("Array");
        var live = read(learner, "");
        assertThat(live.path("coverage").path("state").asText()).isEqualTo("DISCONNECTED");
        assertThat(live.path("summary").path("solved").asLong()).isZero();

        assertThat(call("GET", "/me/analytics", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/me/analytics?period=weekly&start=2026-02-01", null, learner.token()).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/me/analytics?period=monthly&start=2026-03-01", null, learner.token()).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/me/analytics?mode=unknown", null, learner.token()).statusCode()).isEqualTo(400);
    }

    @Test
    void recommendationsUseStableFallbackTopicCoverageAndCanonicalExclusion() throws Exception {
        var learner = login();
        var demo = source(learner.id(), "FIXTURE");
        var starter = analytics.read(learner.id(), "demo", "all-time", null).recommendations();
        assertThat(starter.subList(0, 2)).extracting(AnalyticsService.Recommendation::slug)
                .containsExactly("fixture-hash", "fixture-stack");
        assertThat(starter.getFirst().reason()).contains("fewer than three");
        for (int i = 1; i <= 3; i++) {
            seed(demo, "unknown-" + i, "unmapped-candidate-" + i, HARD, MONDAY);
        }
        assertThat(analytics.read(learner.id(), "demo", "all-time", null).recommendations()).isEqualTo(starter);

        // These live-shaped records are synthetic and are written directly to test recommendation behavior.
        var other = login();
        var live = source(other.id(), "LEETCODE");
        seed(live, "two", "two-sum", EASY, MONDAY);
        seed(live, "intervals", "merge-intervals", MEDIUM, MONDAY);
        seed(live, "parentheses", "valid-parentheses", EASY, MONDAY);
        seed(live, "two", "two-sum-renamed", EASY, MONDAY.plusSeconds(60));
        var tailored = analytics.read(other.id(), "live", "all-time", null).recommendations();
        assertThat(tailored.subList(0, 4)).extracting(AnalyticsService.Recommendation::slug)
                .containsExactly("best-time-to-buy-and-sell-stock", "binary-search", "invert-binary-tree", "valid-anagram");
        assertThat(tailored.getFirst().recordedInFocusTopic()).isZero();
        assertThat(tailored.get(3).reason()).isEqualTo("Practice Hash Table, with 1 recorded solve in this topic.");
        assertThat(tailored).allMatch(item -> item.url().startsWith("https://leetcode.com/problems/"));
        assertThat(tailored).extracting(AnalyticsService.Recommendation::slug).doesNotContain("two-sum", "valid-parentheses", "merge-intervals");
        assertThat(analytics.read(other.id(), "live", "monthly", "2026-01-01").recommendations()).isEqualTo(tailored);

        for (var candidate : starter) {
            seed(demo, candidate.slug(), candidate.slug(), candidate.difficulty(), MONDAY);
        }
        assertThat(analytics.read(learner.id(), "demo", "all-time", null).recommendations()).isEmpty();
    }

    private Source source(long account, String provider) {
        String handle = "analytics-" + account + "-" + provider.toLowerCase(java.util.Locale.ROOT);
        jdbc.sql("INSERT INTO provider_identities (provider, provider_account_id, account_id) VALUES (?, ?, ?)")
                .params(provider, handle, account).update();
        jdbc.sql("UPDATE leetcode_links SET state = 'DISCONNECTED' WHERE account_id = ? AND active_account_id IS NOT NULL")
                .param(account).update();
        long link = insert("""
                INSERT INTO leetcode_links (account_id, handle, state, provider, provider_account_id, verified_at, activated_at)
                VALUES (?, ?, 'VERIFIED', ?, ?, ?, ?)
                """, account, handle, provider, handle, Utc.sql(TRACKING_START), Utc.sql(TRACKING_START));
        return new Source(account, provider, handle, link);
    }

    private long seed(Source source, String id, String slug, Difficulty difficulty, Instant at) {
        return new TransactionTemplate(manager).execute(tx -> {
            long problem = catalog.resolve(source.provider(), new Problem("analytics-" + source.account() + "-" + id, slug,
                    "Analytics " + slug, difficulty));
            long submission = insert("""
                    INSERT INTO submissions (account_id, link_id, provider, provider_account_id, provider_submission_id,
                        problem_id, outcome, difficulty, occurred_at, recorded_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'ACCEPTED', ?, ?, ?)
                    """, source.account(), source.link(), source.provider(), source.handle(), UUID.randomUUID().toString(),
                    problem, difficulty.name(), Utc.sql(at), Utc.sql(NOW));
            progress.creditSolve(new ProgressService.Credit(source.account(), problem, submission, source.provider(), difficulty, at, NOW));
            return jdbc.sql("SELECT id FROM solves WHERE account_id = ? AND problem_id = ?")
                    .params(source.account(), problem).query(Long.class).single();
        });
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.sql(sql).params(args).update(key, "id");
        return key.getKey().longValue();
    }

    private SignedIn login() throws Exception {
        String handle = "analytics_" + UUID.randomUUID().toString().substring(0, 8);
        String email = handle + "@example.test";
        String password = "phase-seven-fixture-password";
        var registered = call("POST", "/auth/register",
                Map.of("email", email, "password", password, "leetcodeHandle", handle, "displayName", handle), null);
        assertThat(registered.statusCode()).isEqualTo(201);
        var login = call("POST", "/auth/login", Map.of("email", email, "password", password), null);
        assertThat(login.statusCode()).isEqualTo(200);
        return new SignedIn(json.readTree(registered.body()).path("id").asLong(), json.readTree(login.body()).path("accessToken").asText());
    }

    private JsonNode read(SignedIn account, String filters) throws Exception {
        var response = call("GET", "/me/analytics" + filters, null, account.token());
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        return json.readTree(response.body());
    }

    private HttpResponse<String> call(String method, String path, Map<String, ?> body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Origin", "https://leetgrind.test").header("X-LeetGrind-Client", "browser");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Source(long account, String provider, String handle, long link) {}
    private record SignedIn(long id, String token) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean @Primary Clock analyticsClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
