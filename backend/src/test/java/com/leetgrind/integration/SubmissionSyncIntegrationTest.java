package com.leetgrind.integration;

import static com.leetgrind.catalog.ProblemCatalog.Difficulty.EASY;
import static com.leetgrind.catalog.ProblemCatalog.Difficulty.HARD;
import static com.leetgrind.catalog.ProblemCatalog.Difficulty.MEDIUM;
import static com.leetgrind.integration.ActivityProvider.Outcome.ACCEPTED;
import static com.leetgrind.integration.ActivityProvider.Outcome.REJECTED;
import static com.leetgrind.integration.ActivityProvider.Result.COMPLETE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.leetgrind.catalog.ProblemCatalog.Difficulty;
import com.leetgrind.catalog.ProblemCatalog.Problem;
import com.leetgrind.integration.ActivityProvider.Batch;
import com.leetgrind.integration.ActivityProvider.Result;
import com.leetgrind.integration.ActivityProvider.Submission;
import com.leetgrind.platform.ApiErrors.Failure;
import com.leetgrind.platform.OperationsMetrics;
import com.leetgrind.platform.Utc;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "leetgrind.engagement.worker-enabled=false",
        "leetgrind.competition.worker-enabled=false",
        "leetgrind.auth.browser-origin=https://leetgrind.test",
        "leetgrind.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "leetgrind.auth.secure-cookie=true",
        "leetgrind.integration.fixtures-enabled=true",
        "leetgrind.integration.scheduler-enabled=false",
        "spring.datasource.password=${LEETGRIND_DB_PASSWORD:phase1-test-password}"
})
class SubmissionSyncIntegrationTest {
    private static final Instant START = Instant.parse("2026-01-05T10:00:00Z");
    private final List<Long> accounts = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired JsonMapper json;
    @Autowired LinkService links;
    @Autowired SyncStore sync;
    @Autowired ActivityProvider adapter;
    @Autowired TestClock clock;
    @Autowired MeterRegistry meters;
    @Autowired OperationsMetrics operations;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean ProfilePreview previews;

    @BeforeEach
    void resetClock() {
        clock.now = START;
    }

    @AfterEach
    void disconnectFixtures() {
        db.update("UPDATE sync_control SET paused = FALSE WHERE id = 1");
        accounts.stream().filter(id -> db.queryForObject("SELECT COUNT(*) FROM accounts WHERE id = ?", Integer.class, id) > 0)
                .forEach(links::disconnect);
    }

    @Test
    void signupRequiresAndSavesAValidLeetCodeProfile() throws Exception {
        String email = "profile-" + UUID.randomUUID() + "@example.test";
        String password = "fixture-password-123";
        var form = new HashMap<>(Map.of("email", email, "displayName", "Test Learner", "password", password));
        var missing = call("POST", "/auth/register", form, null);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(body(missing).path("code").asText()).isEqualTo("invalid_request");

        form.put("leetcodeHandle", "  ");
        var blank = call("POST", "/auth/register", form, null);
        assertThat(blank.statusCode()).isEqualTo(400);
        assertThat(body(blank).path("code").asText()).isEqualTo("invalid_request");

        form.put("leetcodeHandle", "https://example.test/u/learner/");
        var invalid = call("POST", "/auth/register", form, null);
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(body(invalid).path("code").asText()).isEqualTo("invalid_handle");

        String handle = "example-" + UUID.randomUUID();
        form.put("leetcodeHandle", "https://leetcode.com/u/" + handle.toUpperCase(java.util.Locale.ROOT) + "/");
        var registered = call("POST", "/auth/register", form, null);
        assertThat(registered.statusCode()).isEqualTo(201);
        accounts.add(body(registered).path("id").asLong());
        var login = call("POST", "/auth/login", Map.of("email", email, "password", password), null);
        assertThat(login.statusCode()).isEqualTo(200);
        var link = call("GET", "/me/leetcode-link", null, body(login).path("accessToken").asText());
        assertThat(link.statusCode()).isEqualTo(200);
        assertThat(body(link).path("handle").asText()).isEqualTo(handle);
        assertThat(body(link).path("syncAvailable").asBoolean()).isTrue();
    }

    @Test
    void ownerRoutesExposePublicLinkDemoStatusAndSeparateXpHistory() throws Exception {
        var owner = registerAndLogin();
        var other = registerAndLogin();
        assertThat(call("GET", "/me/leetcode-link", null, null).statusCode()).isEqualTo(401);
        assertThat(body(call("GET", "/me/leetcode-link", null, owner.token())).path("syncAvailable").asBoolean()).isTrue();
        String handle = "sample-" + owner.id();
        var saved = call("PUT", "/me/leetcode-link", Map.of("handle", "  Sample-" + owner.id() + "  "), owner.token());
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(body(saved).path("handle").asText()).isEqualTo(handle);
        assertThat(body(saved).path("syncAvailable").asBoolean()).isTrue();
        var duplicate = call("PUT", "/me/leetcode-link", Map.of("handle", handle), other.token());
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(body(duplicate).path("code").asText()).isEqualTo("provider_conflict");
        assertThat(sync.claim()).isEmpty();

        var verified = call("PUT", "/me/leetcode-link", Map.of("handle", "fixture-" + owner.id()), owner.token());
        assertThat(body(verified).path("state").asText()).isEqualTo("VERIFIED");
        assertThat(call("POST", "/me/leetcode-link/sync", null, owner.token()).statusCode()).isEqualTo(202);
        clock.advance(1);
        SyncStore.Lease lease = claim();
        assertThat(sync.finish(lease, adapter.fetch(sync.request(lease).orElseThrow()))).isTrue();

        var ownProgress = body(call("GET", "/me/progress", null, owner.token()));
        assertThat(ownProgress.path("fixtureXp").asInt()).isEqualTo(25);
        assertThat(ownProgress.path("totalXp").asInt()).isZero();
        var ownHistory = body(call("GET", "/me/xp-entries?limit=1", null, owner.token()));
        assertThat(ownHistory.path("items").get(0).path("provider").asText()).isEqualTo("FIXTURE");
        assertThat(ownHistory.path("items").get(0).path("amount").asInt()).isEqualTo(25);
        assertThat(body(call("GET", "/me/xp-entries", null, other.token())).path("items").get(0).path("reason").asText())
                .isEqualTo("DAILY_LOGIN");
        assertThat(body(call("GET", "/me/leetcode-link", null, other.token())).path("handle").asText()).isNotEqualTo(handle);
        assertThat(call("GET", "/me/xp-entries?limit=abc", null, owner.token()).statusCode()).isEqualTo(400);

        clock.advance(31);
        assertThat(call("POST", "/me/leetcode-link/sync", null, owner.token()).statusCode()).isEqualTo(202);
        var second = claim();
        assertThat(sync.finish(second, batch(second, COMPLETE, submission("page-easy", "page-easy", EASY, 5)))).isTrue();
        var firstPage = body(call("GET", "/me/xp-entries?limit=1", null, owner.token()));
        String cursor = firstPage.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        assertThat(firstPage.path("items").get(0).path("amount").asInt()).isEqualTo(10);
        var secondPage = body(call("GET", "/me/xp-entries?limit=1&cursor=" + cursor, null, owner.token()));
        assertThat(secondPage.path("items").get(0).path("amount").asInt()).isEqualTo(25);
        assertThat(call("GET", "/me/xp-entries?cursor=" + cursor, null, other.token()).statusCode()).isEqualTo(400);
        assertThat(call("DELETE", "/me/leetcode-link", null, owner.token()).statusCode()).isEqualTo(204);
        assertThat(links.status(owner.id()).state()).isEqualTo("DISCONNECTED");
        assertThat(total(owner.id())).isEqualTo(35);
    }

    @Test
    void publicSolveWithoutOwnershipCodeCreditsPracticeAndBothLeaderboardsOnlyOnce() throws Exception {
        var owner = registerAndLogin();
        var link = body(call("GET", "/me/leetcode-link", null, owner.token()));
        String handle = link.path("handle").asText();
        assertThat(link.path("syncAvailable").asBoolean()).isTrue();
        var group = body(call("POST", "/groups", Map.of("name", "Public solve test"), owner.token()));
        String groupId = group.path("id").asText();

        when(previews.read(owner.id(), handle)).thenReturn(publicSnapshot("different-handle", null, START));
        assertThat(call("POST", "/me/leetcode-link/sync", null, owner.token()).statusCode()).isEqualTo(502);
        assertThat(total(owner.id())).isZero();

        clock.advance(4);
        when(previews.read(owner.id(), handle)).thenReturn(publicSnapshot(handle, null, START.plusSeconds(2)));
        assertThat(call("POST", "/me/leetcode-link/sync", null, owner.token()).statusCode()).isEqualTo(202);
        assertThat(total(owner.id())).isEqualTo(25);

        clock.advance(3);
        assertThat(call("POST", "/me/leetcode-link/sync", null, owner.token()).statusCode()).isEqualTo(202);
        assertThat(call("POST", "/me/leetcode-link/sync", null, owner.token()).statusCode()).isEqualTo(202);
        assertThat(total(owner.id())).isEqualTo(25);
        assertThat(outboxCount(owner.id())).isEqualTo(1);
        assertThat(body(call("GET", "/me/rewards", null, owner.token())).path("totalXp").asInt()).isEqualTo(30);
        assertThat(body(call("GET", "/leaderboard", null, owner.token())).path("items").get(0).path("xp").asInt()).isEqualTo(30);
        assertThat(body(call("GET", "/groups/" + groupId + "/leaderboard", null, owner.token()))
                .path("items").get(0).path("xp").asInt()).isEqualTo(25);

    }

    private static ProfilePreview.Snapshot publicSnapshot(String handle, String name, Instant acceptedAt) {
        return new ProfilePreview.Snapshot(handle, name, null, new ProfilePreview.Counts(1, 0, 1, 0),
                new ProfilePreview.Heatmap(0, 0, List.of()),
                List.of(new ProfilePreview.Submission("Merge Intervals", "merge-intervals", acceptedAt.getEpochSecond(), null, null)));
    }

    @Test
    void loginCreditsOncePerUtcDayAndRefreshDoesNotCreateCodingDays() throws Exception {
        var account = registerAndLogin();
        String email = db.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, account.id());
        clock.advance(300);
        var sameDay = call("POST", "/auth/login", Map.of("email", email, "password", "fixture-password-123"), null);
        assertThat(sameDay.statusCode()).isEqualTo(200);
        assertThat(rowCount("xp_entries", account.id())).isEqualTo(1);

        clock.advance(86400);
        assertThat(call("POST", "/auth/refresh", null, null, account.cookie()).statusCode()).isEqualTo(200);
        assertThat(rowCount("xp_entries", account.id())).isEqualTo(1);
        var tomorrow = call("POST", "/auth/login", Map.of("email", email, "password", "fixture-password-123"), null);
        assertThat(tomorrow.statusCode()).isEqualTo(200);
        var rewards = body(call("GET", "/me/rewards", null, body(tomorrow).path("accessToken").asText()));
        assertThat(rewards.path("totalXp").asInt()).isEqualTo(10);
        assertThat(rewards.path("activity").path("codingDates").size()).isZero();
        assertThat(rewards.path("featured").path("url").asText()).startsWith("https://leetcode.com/problems/");
        assertThat(db.queryForList("SELECT amount FROM xp_entries WHERE account_id = ? AND reason = 'DAILY_LOGIN'",
                Integer.class, account.id())).containsExactly(5, 5);

        clock.advance(86400);
        String cookie = tomorrow.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        var restored = call("POST", "/auth/refresh", null, null, cookie);
        assertThat(restored.statusCode()).isEqualTo(200);
        String access = body(restored).path("accessToken").asText();
        for (int i = 0; i < 2; i++) {
            var checkIn = call("POST", "/me/check-in", null, access);
            assertThat(checkIn.statusCode()).isEqualTo(200);
            assertThat(body(checkIn).path("nextCheckInAt").asText()).isEqualTo("2026-01-08T00:00:00Z");
        }
        assertThat(rowCount("xp_entries", account.id())).isEqualTo(3);
        var notifications = body(call("GET", "/me/reward-events", null, access));
        assertThat(notifications.size()).isEqualTo(1);
        assertThat(notifications.get(0).path("kind").asText()).isEqualTo("DAILY_LOGIN");
        assertThat(notifications.get(0).path("amount").asInt()).isEqualTo(5);
    }

    @Test
    void duplicateProofAndReplacementKeepOneOwnerAndFenceOutstandingWork() {
        long account = newAccount();
        long other = newAccount();
        String shared = "fixture-shared-" + UUID.randomUUID();
        links.link(account, shared);
        clock.advance(1);
        var stale = claim();
        var oldRequest = sync.request(stale).orElseThrow();

        assertThatThrownBy(() -> links.link(other, shared)).isInstanceOfSatisfying(Failure.class,
                error -> assertThat(error.code()).isEqualTo("provider_conflict"));
        assertThat(links.status(other).state()).isEqualTo("NONE");
        links.link(account, "waiting-on-provider");
        assertThat(sync.finish(stale, adapter.fetch(oldRequest))).isFalse();
        assertThat(links.status(account).lastSuccessfulSyncAt()).isNull();
        assertThat(total(account)).isZero();
        assertThat(rowCount("leetcode_links", account)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT version FROM leetcode_links WHERE id = ?", Long.class, stale.linkId()))
                .isEqualTo(stale.version() + 1);

        links.disconnect(account);
        assertThat(links.status(account).state()).isEqualTo("DISCONNECTED");
        var relinked = links.link(account, shared);
        assertThat(relinked.state()).isEqualTo("VERIFIED");
        assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM provider_identities WHERE account_id = ? AND provider_account_id = ?
                """, Integer.class, account, shared)).isEqualTo(1);
    }

    @Test
    void creditsDistinctSolvesAndKeepsOriginalEvidenceOnReplayAndLateDiscovery() {
        long account = newAccount();
        links.link(account, "fixture-" + account);
        clock.advance(10);
        var lease = claim();
        var easy = submission("easy", "easy", EASY, 1);
        var medium = submission("medium", "medium", MEDIUM, 2);
        var hard = submission("hard", "hard", HARD, 3);
        var rejected = new Submission("rejected", medium.problem(), START.plusSeconds(4), REJECTED);
        var beforeActivation = submission("before-activation", "before-activation", HARD, -1);
        assertThat(sync.finish(lease, batch(lease, COMPLETE, hard, medium, easy, rejected, beforeActivation))).isTrue();
        assertThat(total(account)).isEqualTo(85);
        assertThat(rowCount("solves", account)).isEqualTo(3);
        assertThat(rowCount("xp_entries", account)).isEqualTo(3);
        assertThat(rowCount("submissions", account)).isEqualTo(4);
        assertThat(outboxCount(account)).isEqualTo(3);
        assertThat(db.queryForList("SELECT amount FROM xp_entries WHERE account_id = ? ORDER BY effective_at",
                Integer.class, account)).containsExactly(10, 25, 50);

        clock.advance(31);
        links.requestSync(account);
        var replay = claim();
        var retryProblem = new Problem("medium", "medium-renamed", "Medium Renamed", MEDIUM);
        var retry = new Submission("medium-retry", retryProblem, START.plusSeconds(5), ACCEPTED);
        var older = new Submission("medium-older", retryProblem, START, ACCEPTED);
        assertThat(sync.finish(replay, batch(replay, COMPLETE, easy, medium, medium, hard, retry, older))).isTrue();
        assertThat(total(account)).isEqualTo(85);
        assertThat(outboxCount(account)).isEqualTo(3);
        assertThat(rowCount("submissions", account)).isEqualTo(6);
        assertThat(db.queryForObject("SELECT effective_at FROM xp_entries WHERE account_id = ? AND amount = 25",
                java.time.LocalDateTime.class, account)).isEqualTo(Utc.sql(START.plusSeconds(2)));
    }

    @Test
    void scheduleAndManualShareAJobAndExpiredLeaseCannotCommit() {
        var queueWait = meters.timer("leetgrind.sync.queue.wait.duration");
        long previousClaims = queueWait.count();
        double previousWait = queueWait.totalTime(TimeUnit.SECONDS);
        long account = newAccount();
        links.link(account, "fixture-" + account);
        clock.advance(1);
        var scheduled = CompletableFuture.supplyAsync(sync::claim);
        var manual = CompletableFuture.supplyAsync(() -> links.requestSync(account));
        var first = scheduled.join().orElseThrow();
        manual.join();
        assertThat(sync.claim()).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sync_jobs WHERE link_id = ?", Integer.class, first.linkId()))
                .isEqualTo(1);

        var observed = batch(first, COMPLETE, submission("medium", "medium", MEDIUM, 0));
        clock.advance(31);
        assertThat(sync.finish(first, observed)).isFalse();
        var reclaimed = claim();
        assertThat(reclaimed.token()).isNotEqualTo(first.token());
        assertThat(meters.counter("leetgrind.sync.expired.leases").count()).isPositive();
        assertThat(queueWait.count() - previousClaims).isEqualTo(2);
        assertThat(queueWait.totalTime(TimeUnit.SECONDS) - previousWait).isEqualTo(2);
        assertThat(sync.finish(first, observed)).isFalse();
        assertThat(sync.finish(reclaimed, observed)).isTrue();
        assertThat(sync.finish(reclaimed, observed)).isFalse();
        assertThat(total(account)).isEqualTo(25);
        assertThat(outboxCount(account)).isEqualTo(1);
    }

    @Test
    void oneDispatchBatchesDueJobsAndLeavesOverflowForTheNextDispatch() {
        var learners = new ArrayList<Long>();
        for (int i = 0; i < 9; i++) {
            long account = newAccount();
            links.link(account, "fixture-" + account);
            learners.add(account);
        }
        clock.advance(1);
        var fetch = meters.timer("leetgrind.sync.provider.fetch.duration");
        long previousFetches = fetch.count();
        var worker = new SyncWorker(sync, adapter, Runnable::run, operations);

        worker.dispatch();
        assertThat(learners.stream().mapToLong(this::total).sum()).isEqualTo(200);
        assertThat(fetch.count() - previousFetches).isEqualTo(8);

        worker.dispatch();
        worker.dispatch();
        assertThat(learners).allSatisfy(account -> {
            assertThat(total(account)).isEqualTo(25);
            assertThat(outboxCount(account)).isEqualTo(1);
        });
        assertThat(fetch.count() - previousFetches).isEqualTo(9);
    }

    @Test
    void rollbackLeavesNoPartialArtifactsAndJobCanBeReplayed() {
        long account = newAccount();
        links.link(account, "fixture-" + account);
        clock.advance(1);
        var lease = claim();
        var result = batch(lease, COMPLETE, submission("medium", "medium", MEDIUM, 0));
        var transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.executeWithoutResult(status -> {
            assertThat(sync.finish(lease, result)).isTrue();
            assertThat(outboxCount(account)).isEqualTo(1);
            status.setRollbackOnly();
        });

        assertThat(rowCount("submissions", account)).isZero();
        assertThat(rowCount("solves", account)).isZero();
        assertThat(rowCount("xp_entries", account)).isZero();
        assertThat(outboxCount(account)).isZero();
        assertThat(links.status(account).lastSuccessfulSyncAt()).isNull();
        assertThat(sync.finish(lease, result)).isTrue();
        assertThat(total(account)).isEqualTo(25);
    }

    @Test
    void completionAndDisconnectSerializeWithoutPartialCredit() {
        long account = newAccount();
        links.link(account, "fixture-" + account);
        clock.advance(1);
        var lease = claim();
        var observed = batch(lease, COMPLETE, submission("medium", "medium", MEDIUM, 0));
        var completion = CompletableFuture.supplyAsync(() -> sync.finish(lease, observed));
        var disconnect = CompletableFuture.runAsync(() -> links.disconnect(account));
        CompletableFuture.allOf(completion, disconnect).join();

        assertThat(links.status(account).state()).isEqualTo("DISCONNECTED");
        int expectedAwards = completion.join() ? 1 : 0;
        assertThat(rowCount("xp_entries", account)).isEqualTo(expectedAwards);
        assertThat(outboxCount(account)).isEqualTo(expectedAwards);
        assertThat(total(account)).isEqualTo(25 * expectedAwards);
        assertThat(sync.finish(lease, observed)).isFalse();
    }

    @Test
    void partialAndThrottledKeepCheckpointAndScheduleBoundedRetry() {
        long account = newAccount();
        links.link(account, "fixture-" + account);
        clock.advance(10);
        var first = claim();
        assertThat(sync.finish(first, batch(first, COMPLETE, submission("medium", "medium", MEDIUM, 2)))).isTrue();
        Instant checkpoint = links.status(account).lastSuccessfulSyncAt();
        assertThat(checkpoint).isEqualTo(clock.instant());

        clock.advance(31);
        links.requestSync(account);
        var second = claim();
        var incomplete = new Submission("incomplete", new Problem("unknown", "unknown", "Unknown", null),
                START.plusSeconds(3), ACCEPTED);
        var valid = submission("easy", "easy", EASY, 4);
        assertThat(sync.finish(second, batch(second, COMPLETE, incomplete, valid))).isTrue();
        var partial = links.status(account);
        assertThat(partial.failureCode()).isEqualTo("PARTIAL");
        assertThat(partial.coverageUncertain()).isTrue();
        assertThat(partial.lastSuccessfulSyncAt()).isEqualTo(checkpoint);
        assertThat(partial.lastAttemptAt()).isEqualTo(clock.instant());
        assertThat(total(account)).isEqualTo(35);

        assertThat(links.requestSync(account).syncState()).isEqualTo("RETRY");
        clock.advance(9);
        var third = claim();
        assertThat(sync.finish(third, new Batch(null, Result.THROTTLED, List.of(), Duration.ofSeconds(90)))).isTrue();
        assertThat(meters.counter("leetgrind.sync.outcomes", "result", "THROTTLED").count()).isPositive();
        assertThat(links.status(account).nextSyncAt()).isEqualTo(clock.instant().plusSeconds(90));
        assertThat(links.status(account).lastSuccessfulSyncAt()).isEqualTo(checkpoint);
        clock.advance(89);
        assertThat(sync.claim()).isEmpty();
        clock.advance(1);
        var recovered = claim();
        assertThat(sync.finish(recovered, batch(recovered, COMPLETE, valid))).isTrue();
        assertThat(links.status(account).lastSuccessfulSyncAt()).isEqualTo(clock.instant());
        assertThat(links.status(account).failureCode()).isNull();
        assertThat(links.status(account).coverageUncertain()).isFalse();
        assertThat(total(account)).isEqualTo(35);

        clock.advance(900);
        var scheduled = claim();
        assertThat(sync.finish(scheduled, batch(scheduled, COMPLETE))).isTrue();
        assertThat(total(account)).isEqualTo(35);
    }

    @Test
    void mismatchedIdentityPausesAndNeverCreditsTheMismatchedPayload() {
        long account = newAccount();
        links.link(account, "fixture-" + account);
        clock.advance(1);
        var lease = claim();
        assertThat(sync.finish(lease, new Batch("different-profile", COMPLETE,
                List.of(submission("medium", "medium", MEDIUM, 0)), null))).isTrue();
        assertThat(links.status(account).failureCode()).isEqualTo("IDENTITY_MISMATCH");
        assertThat(links.status(account).syncState()).isEqualTo("PAUSED");
        assertThat(total(account)).isZero();
        assertThatThrownBy(() -> links.requestSync(account)).isInstanceOfSatisfying(Failure.class,
                failure -> assertThat(failure.code()).isEqualTo("sync_paused"));
    }

    @Test
    void operatorPauseAndMalformedResponsePreserveConfirmedAwards() {
        long account = newAccount();
        links.link(account, "fixture-" + account);
        clock.advance(1);
        var first = claim();
        var medium = submission("medium", "medium", MEDIUM, 0);
        var credited = batch(first, COMPLETE, medium);
        db.update("UPDATE sync_control SET paused = TRUE WHERE id = 1");
        assertThat(links.status(account).providerPaused()).isTrue();
        assertThat(sync.claim()).isEmpty();
        assertThat(sync.finish(first, credited)).isTrue(); // An existing lease may finish.
        Instant checkpoint = links.status(account).lastSuccessfulSyncAt();
        clock.advance(31);

        assertThatThrownBy(() -> links.requestSync(account)).isInstanceOfSatisfying(Failure.class,
                error -> assertThat(error.code()).isEqualTo("sync_paused"));
        db.update("UPDATE sync_control SET paused = FALSE WHERE id = 1");
        links.requestSync(account);
        var malformed = claim();
        assertThat(sync.finish(malformed, new Batch(credited.profileId(), COMPLETE, null, null))).isTrue();
        assertThat(links.status(account).failureCode()).isEqualTo("SCHEMA_ERROR");
        assertThat(links.status(account).syncState()).isEqualTo("PAUSED");
        assertThat(links.status(account).lastSuccessfulSyncAt()).isEqualTo(checkpoint);
        assertThat(meters.counter("leetgrind.sync.outcomes", "result", "SCHEMA_ERROR").count()).isPositive();

        db.update("UPDATE sync_jobs SET state = 'QUEUED', next_run_at = ?, attempts = 0 WHERE link_id = ?",
                Utc.sql(clock.instant()), malformed.linkId());
        var replay = claim();
        assertThat(sync.finish(replay, credited)).isTrue();
        assertThat(total(account)).isEqualTo(25);
        assertThat(outboxCount(account)).isEqualTo(1);
        assertThat(meters.counter("leetgrind.xp.deduplications").count()).isPositive();
    }

    @Test
    void accountDeletionRemovesScoresAndRejectsInFlightWritesAndExistingSessions() throws Exception {
        var owner = registerAndLogin();
        var learner = registerAndLogin();
        String password = "fixture-password-123";
        var created = call("POST", "/groups", Map.of("name", "Deletion test"), owner.token());
        assertThat(created.statusCode()).isEqualTo(201);
        String groupId = body(created).path("id").asText();
        String group = "/groups/" + groupId;
        var invite = call("POST", group + "/invites", Map.of("expiresInHours", 24, "maxUses", 1), owner.token());
        assertThat(invite.statusCode()).isEqualTo(201);
        clock.advance(1);
        assertThat(call("POST", "/group-memberships", Map.of("code", body(invite).path("code").asText()),
                learner.token()).statusCode()).isEqualTo(200);
        assertThat(call("PUT", "/me/leetcode-link", Map.of("handle", "fixture-" + learner.id()),
                learner.token()).statusCode()).isEqualTo(200);
        clock.advance(1);
        var original = claim();
        assertThat(sync.finish(original, adapter.fetch(sync.request(original).orElseThrow()))).isTrue();
        assertThat(total(learner.id())).isEqualTo(25);
        assertThat(body(call("GET", group + "/leaderboard?mode=demo", null, owner.token())).path("items").size()).isEqualTo(2);
        assertThat(outboxCount(learner.id())).isEqualTo(1);
        assertThat(call("GET", "/me/rewards?mode=demo", null, learner.token()).statusCode()).isEqualTo(200);
        assertThat(db.update("""
                INSERT INTO reward_evidence (window_id, account_id, solve_id)
                SELECT w.id, s.account_id, s.id FROM solves s JOIN reward_windows w
                  ON w.provider = 'FIXTURE' AND w.kind = 'FEATURED' AND w.starts_at = CAST(DATE(s.occurred_at) AS DATETIME)
                WHERE s.account_id = ?
                """, learner.id())).isEqualTo(1);
        db.update("""
                INSERT INTO user_achievements (account_id, provider, code, rule_version, unlocked_at)
                VALUES (?, 'FIXTURE', 'FIRST_SOLVE', 1, ?)
                """, learner.id(), Utc.sql(clock.instant()));
        assertThat(call("POST", "/me/delete", Map.of("password", "wrong-password"), learner.token()).statusCode()).isEqualTo(403);
        var rejectedOwner = call("POST", "/me/delete", Map.of("password", password), owner.token());
        assertThat(rejectedOwner.statusCode()).isEqualTo(409);
        assertThat(body(rejectedOwner).path("code").asText()).isEqualTo("owned_groups");

        clock.advance(31);
        links.requestSync(learner.id());
        var stale = claim();
        var payload = adapter.fetch(sync.request(stale).orElseThrow());
        var deletion = call("POST", "/me/delete", Map.of("password", password), learner.token());
        assertThat(deletion.statusCode()).isEqualTo(204);
        assertThat(deletion.headers().firstValue("Set-Cookie").orElseThrow()).contains("Max-Age=0");
        assertThat(sync.finish(stale, payload)).isFalse();
        assertThat(call("GET", "/me", null, learner.token()).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/auth/refresh", null, null, learner.cookie()).statusCode()).isEqualTo(401);
        for (String table : List.of("provider_identities", "leetcode_links", "submissions", "solves", "xp_entries",
                "memberships", "session_families", "reward_evidence", "user_achievements", "coding_days")) {
            assertThat(rowCount(table, learner.id())).as(table).isZero();
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sync_jobs WHERE link_id = ?",
                Integer.class, stale.linkId())).isZero();
        assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM outbox_events WHERE JSON_EXTRACT(payload, '$.accountId') = ?
                """, Integer.class, learner.id())).isZero();
        assertThat(body(call("GET", group + "/leaderboard?mode=demo", null, owner.token())).path("items").size()).isEqualTo(1);

        assertThat(call("DELETE", group, null, owner.token()).statusCode()).isEqualTo(204);
        assertThat(call("POST", "/me/delete", Map.of("password", password), owner.token()).statusCode()).isEqualTo(204);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM study_groups WHERE id = ? AND owner_id IS NULL",
                Integer.class, Long.parseLong(groupId))).isEqualTo(1);
    }

    private SyncStore.Lease claim() {
        return sync.claim().orElseThrow();
    }

    private Batch batch(SyncStore.Lease lease, Result result, Submission... submissions) {
        return new Batch(sync.request(lease).orElseThrow().profileId(), result, List.of(submissions), null);
    }

    private Submission submission(String id, String problem, Difficulty difficulty, long offset) {
        return new Submission(id, new Problem(problem, problem, "Fixture " + problem, difficulty),
                START.plusSeconds(offset), ACCEPTED);
    }

    private long total(long account) {
        return db.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM xp_entries WHERE account_id = ? AND reason = 'BASE_SOLVE'", Long.class, account);
    }

    private int rowCount(String table, long account) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE account_id = ?", Integer.class, account);
    }

    private int outboxCount(long account) {
        return db.queryForObject("""
                SELECT COUNT(*) FROM outbox_events o JOIN solves s ON s.id = o.aggregate_id WHERE s.account_id = ?
                """, Integer.class, account);
    }

    private long newAccount() {
        String handle = "test_" + UUID.randomUUID().toString().substring(0, 8);
        db.update("INSERT INTO accounts (email, handle, display_name, password_hash, active) VALUES (?, ?, ?, ?, TRUE)",
                handle + "@example.test", handle, "Fixture", "unused");
        long id = db.queryForObject("SELECT id FROM accounts WHERE handle = ?", Long.class, handle);
        accounts.add(id);
        return id;
    }

    private ApiAccount registerAndLogin() throws Exception {
        String handle = "api_" + UUID.randomUUID().toString().substring(0, 8);
        String email = handle + "@example.test";
        String password = "fixture-password-123";
        var registered = call("POST", "/auth/register", Map.of("email", email, "leetcodeHandle", handle,
                "displayName", "Fixture Account", "password", password), null);
        assertThat(registered.statusCode()).isEqualTo(201);
        long id = body(registered).path("id").asLong();
        accounts.add(id);
        var login = call("POST", "/auth/login", Map.of("email", email, "password", password), null);
        assertThat(login.statusCode()).isEqualTo(200);
        return new ApiAccount(id, body(login).path("accessToken").asText(),
                login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0]);
    }

    private HttpResponse<String> call(String method, String path, Map<String, ?> data, String token) throws Exception {
        return call(method, path, data, token, null);
    }

    private HttpResponse<String> call(String method, String path, Map<String, ?> data, String token, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Origin", "https://leetgrind.test").header("X-LeetGrind-Client", "browser");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (cookie != null) request.header("Cookie", cookie);
        if (data != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, data == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private record ApiAccount(long id, String token, String cookie) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean @Primary
        TestClock testClock() { return new TestClock(); }

        @Bean @Primary
        ActivityProvider testProvider() {
            return new FixtureActivityProvider() {
                @Override
                public Optional<String> fixtureIdentity(long accountId, String handle) {
                    return handle.startsWith("fixture-shared-") ? Optional.of(handle) : super.fixtureIdentity(accountId, handle);
                }
            };
        }
    }

    static class TestClock extends Clock {
        private volatile Instant now = START;
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
