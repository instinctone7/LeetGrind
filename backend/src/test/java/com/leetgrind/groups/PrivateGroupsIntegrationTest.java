package com.leetgrind.groups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.leetgrind.catalog.ProblemCatalog.Difficulty;
import com.leetgrind.platform.ApiErrors.Failure;
import com.leetgrind.platform.Utc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
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
class PrivateGroupsIntegrationTest {
    private static final Instant JOIN = Instant.parse("2026-01-05T10:00:00Z");
    private static final Instant NEXT_WEEK = Instant.parse("2026-01-12T00:00:00Z");
    private final Map<Source, Long> links = new HashMap<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired GroupService groups;
    @Autowired GroupInvites invites;
    @Autowired GroupStandings standings;
    @Autowired ChallengeService challenges;
    @Autowired GroupActivity activity;
    @Autowired MutableClock clock;

    @BeforeEach
    void reset() {
        clock.now = JOIN.minusSeconds(60);
    }

    @Test
    void lastUseRaceAndMemberRetriesConsumeExactlyOneUse() throws Exception {
        long owner = account(), first = account(), second = account();
        long group = create(owner);
        var invite = invites.create(group, owner, 24, 1);
        var start = new CountDownLatch(1);
        var firstAttempt = CompletableFuture.supplyAsync(() -> attempt(start, first, invite.code()));
        var secondAttempt = CompletableFuture.supplyAsync(() -> attempt(start, second, invite.code()));
        start.countDown();
        CompletableFuture.allOf(firstAttempt, secondAttempt).get(15, TimeUnit.SECONDS);
        assertThat(List.of(firstAttempt.join(), secondAttempt.join())).containsExactlyInAnyOrder(true, false);

        long accepted = firstAttempt.join() ? first : second;
        assertThat(invites.redeem(accepted, invite.code()).id()).isEqualTo(Long.toString(group));
        assertThat(groups.detail(group, owner).members()).hasSize(2);
        assertThat(invites.list(group, owner)).singleElement().extracting(GroupInvites.Invite::uses).isEqualTo(1);
        String stored = jdbc.sql("SELECT code_digest FROM group_invites WHERE id = ?")
                .param(Long.parseLong(invite.id())).query(String.class).single();
        assertThat(stored).hasSize(64).isNotEqualTo(invite.code());
    }

    @Test
    void expiryRevocationAndOwnershipLifecycleRespectPermissions() {
        long owner = account(), member = account(), outsider = account();
        long group = create(owner);

        var expired = invites.create(group, owner, 1, 2);
        clock.now = expired.expiresAt();
        assertCode("invite_unavailable", () -> invites.redeem(member, expired.code()));
        var revoked = invites.create(group, owner, 24, 2);
        invites.revoke(group, owner, Long.parseLong(revoked.id()));
        assertCode("invite_unavailable", () -> invites.redeem(member, revoked.code()));
        var active = invites.create(group, owner, 24, 2);
        invites.redeem(member, active.code());

        assertCode("owner_must_transfer", () -> groups.remove(group, owner, owner));
        assertCode("group_owner_required", () -> groups.update(group, member, "Wrong", ""));
        assertCode("group_owner_required", () -> invites.create(group, member, 24, 1));
        assertCode("group_owner_required", () -> groups.remove(group, member, owner));
        assertCode("group_owner_required", () -> groups.close(group, member));
        assertCode("group_not_found", () -> groups.detail(group, outsider));
        assertCode("invalid_new_owner", () -> groups.transfer(group, owner, outsider));

        groups.update(group, owner, "Updated group", "Study each week");
        groups.transfer(group, owner, member);
        assertThat(groups.detail(group, member).group().role()).isEqualTo("OWNER");
        assertThat(groups.detail(group, member).group().name()).isEqualTo("Updated group");
        groups.remove(group, owner, owner);
        assertCode("group_not_found", () -> groups.detail(group, owner));
        groups.close(group, member);
        assertCode("group_not_found", () -> groups.detail(group, member));
        assertCode("invite_unavailable", () -> invites.redeem(outsider, active.code()));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM memberships WHERE group_id = ? AND left_at IS NULL")
                .param(group).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM memberships WHERE group_id = ?")
                .param(group).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void effectiveTimeUsesStrictMembershipAndHalfOpenUtcWeekAcrossRejoin() {
        long owner = account(), member = account();
        long group = create(owner);
        var invite = invites.create(group, owner, 168, 2);
        clock.now = JOIN;
        invites.redeem(member, invite.code());
        clock.now = JOIN.plusSeconds(3600);
        groups.remove(group, owner, member);
        assertCode("group_not_found", () -> page(group, member, "2026-01-05", "demo", 20, null));
        clock.now = JOIN.plusSeconds(7200);
        invites.redeem(member, invite.code());

        clock.now = NEXT_WEEK.plusSeconds(60);
        for (long offset : new long[] {-1, 0, 3600, 3610, 7200}) {
            credit(member, "FIXTURE", JOIN.plusSeconds(offset), 25, "BASE_SOLVE");
        }
        for (Instant event : List.of(JOIN.plusSeconds(300), JOIN.plusSeconds(7500), NEXT_WEEK.minusSeconds(60), NEXT_WEEK)) {
            credit(member, "FIXTURE", event, 25, "BASE_SOLVE");
        }

        var first = page(group, owner, "2026-01-05", "demo", 20, null);
        assertThat(first.periodStart()).isEqualTo(NEXT_WEEK.minusSeconds(7 * 86400));
        assertThat(first.periodEnd()).isEqualTo(NEXT_WEEK);
        assertThat(first.timezone()).isEqualTo("UTC");
        assertThat(xp(first, member)).isEqualTo(75);
        var current = page(group, owner, null, "demo", 20, null);
        assertThat(current.periodStart()).isEqualTo(NEXT_WEEK);
        assertThat(xp(current, member)).isEqualTo(25);

        groups.remove(group, member, member);
        assertThat(xp(page(group, owner, "2026-01-05", "demo", 20, null), member)).isEqualTo(75);
        assertThat(xp(page(group, owner, "2026-01-12", "demo", 20, null), member)).isEqualTo(25);
        assertCode("group_not_found", () -> page(group, member, null, "demo", 20, null));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM memberships WHERE group_id = ? AND account_id = ?")
                .params(group, member).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void denseRanksRemainTiedAcrossPagesAndCursorIsScoped() {
        long owner = account(), first = account(), second = account();
        long group = create(owner);
        long otherGroup = create(owner);
        var invite = invites.create(group, owner, 24, 2);
        clock.now = JOIN;
        invites.redeem(first, invite.code());
        invites.redeem(second, invite.code());
        clock.now = JOIN.plusSeconds(2);
        credit(first, "FIXTURE", JOIN.plusSeconds(1), 25, "BASE_SOLVE");
        credit(second, "FIXTURE", JOIN.plusSeconds(1), 25, "BASE_SOLVE");
        credit(owner, "LEETCODE", JOIN.plusSeconds(1), 50, "BASE_SOLVE");
        credit(owner, "FIXTURE", JOIN.plusSeconds(1), 100, "PERSONAL_BONUS");

        var firstPage = page(group, owner, null, "demo", 1, null);
        var secondPage = page(group, owner, null, "demo", 1, firstPage.nextCursor());
        var thirdPage = page(group, owner, null, "demo", 1, secondPage.nextCursor());
        assertThat(firstPage.items()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(Long.toString(first));
            assertThat(row.xp()).isEqualTo(25);
            assertThat(row.rank()).isEqualTo(1);
        });
        assertThat(secondPage.items()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(Long.toString(second));
            assertThat(row.rank()).isEqualTo(1);
        });
        assertThat(thirdPage.items()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(Long.toString(owner));
            assertThat(row.xp()).isZero();
            assertThat(row.rank()).isEqualTo(2);
        });
        assertThat(thirdPage.nextCursor()).isNull();
        var live = page(group, owner, null, "live", 20, null);
        assertThat(xp(live, owner)).isEqualTo(50);
        assertThat(xp(live, first)).isZero();

        assertCode("invalid_pagination", () -> page(group, owner, "2026-01-12", "demo", 1, firstPage.nextCursor()));
        assertCode("invalid_pagination", () -> page(otherGroup, owner, null, "demo", 1, firstPage.nextCursor()));
        assertCode("invalid_pagination", () -> page(group, owner, null, "live", 1, firstPage.nextCursor()));
        assertCode("invalid_week", () -> page(group, owner, "2026-01-06", "demo", 1, null));
    }

    @Test
    void httpRoutesProtectGroupRosterInvitationsAndStandings() throws Exception {
        Instant membershipStart = Instant.parse("2026-01-05T23:59:00Z");
        clock.now = membershipStart.minusSeconds(60);
        var owner = login();
        var member = login();
        var stranger = login();
        assertThat(call("GET", "/groups", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/groups/overview", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/leaderboard", null, null).statusCode()).isEqualTo(401);
        var created = call("POST", "/groups", Map.of("name", "Algorithms", "description", "Practice"), owner.token());
        assertThat(created.statusCode()).isEqualTo(201);
        String group = "/groups/" + body(created).path("id").asText();
        var renamed = call("PATCH", group, Map.of("name", "Interview Algorithms"), owner.token());
        assertThat(renamed.statusCode()).isEqualTo(200);
        assertThat(body(renamed).path("description").asText()).isEqualTo("Practice");
        assertThat(call("GET", group, null, stranger.token()).statusCode()).isEqualTo(404);
        assertThat(call("GET", group + "/leaderboard", null, stranger.token()).statusCode()).isEqualTo(404);
        assertThat(call("GET", group + "/featured-problem?mode=demo", null, stranger.token()).statusCode()).isEqualTo(404);
        var invite = call("POST", group + "/invites", Map.of("expiresInHours", 24, "maxUses", 1), owner.token());
        assertThat(invite.statusCode()).isEqualTo(201);
        String code = body(invite).path("code").asText();
        assertThat(call("GET", group + "/invites", null, owner.token()).body()).doesNotContain(code, "code_digest");

        clock.now = membershipStart;
        assertThat(call("POST", "/group-memberships", Map.of("code", code), member.token()).statusCode()).isEqualTo(200);
        var roster = call("GET", group, null, member.token());
        assertThat(roster.statusCode()).isEqualTo(200);
        assertThat(roster.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        for (JsonNode row : body(roster).path("members")) {
            assertThat(row.properties().stream().map(Map.Entry::getKey).toList())
                    .containsExactlyInAnyOrder("id", "displayName", "handle", "role");
        }
        assertThat(call("GET", group + "/invites", null, member.token()).statusCode()).isEqualTo(403);
        var board = call("GET", group + "/leaderboard", null, member.token());
        assertThat(board.statusCode()).isEqualTo(200);
        assertThat(board.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        for (JsonNode row : body(board).path("items")) {
            assertThat(row.properties().stream().map(Map.Entry::getKey).toList())
                    .containsExactlyInAnyOrder("id", "displayName", "handle", "rank", "xp");
        }

        credit(Long.parseLong(member.id()), "LEETCODE", membershipStart.plusSeconds(1), 25, "BASE_SOLVE");
        credit(Long.parseLong(member.id()), "FIXTURE", membershipStart.plusSeconds(1), 50, "BASE_SOLVE");
        clock.now = membershipStart.plusSeconds(2);
        var overview = body(call("GET", "/groups/overview", null, member.token())).get(0);
        assertThat(overview.path("memberCount").asInt()).isEqualTo(2);
        assertThat(overview.path("memberInitials").asText()).hasSize(2);
        assertThat(overview.path("weeklyXp").asLong()).isEqualTo(25);
        assertThat(overview.path("personalXp").asLong()).isEqualTo(25);
        assertThat(overview.path("weeklySolves").asLong()).isEqualTo(1);
        assertThat(body(call("GET", "/groups/overview", null, stranger.token())).size()).isZero();
        jdbc.sql("""
                INSERT INTO user_achievements (account_id, provider, code, rule_version, unlocked_at)
                VALUES (?, 'LEETCODE', 'FIRST_SOLVE', 1, ?)
                """).params(Long.parseLong(member.id()), Utc.sql(clock.now)).update();
        var rewardEvents = body(call("GET", "/me/reward-events", null, member.token()));
        assertThat(rewardEvents.values().stream().filter(event -> "BASE_SOLVE".equals(event.path("kind").asText()))
                .map(event -> event.path("amount").asInt()).toList()).containsExactly(25);
        assertThat(rewardEvents.values().stream().filter(event -> "ACHIEVEMENT".equals(event.path("kind").asText()))
                .map(event -> event.path("label").asText()).toList()).containsExactly("FIRST_SOLVE");

        clock.now = membershipStart.plusSeconds(60);
        credit(Long.parseLong(member.id()), "LEETCODE", clock.now, 30, "DAILY_QUEST");
        assertThat(call("POST", "/me/check-in", null, member.token()).statusCode()).isEqualTo(200);

        var updatedBoard = body(call("GET", group + "/leaderboard", null, owner.token())).path("items");
        assertThat(updatedBoard.get(0).path("id").asText()).isEqualTo(member.id());
        assertThat(updatedBoard.get(0).path("xp").asLong()).isEqualTo(60);
        var individual = body(call("GET", "/leaderboard", null, owner.token())).path("items");
        assertThat(individual.get(0).path("id").asText()).isEqualTo(member.id());
        assertThat(individual.get(0).path("xp").asLong()).isEqualTo(65);
        var privateIndividual = body(call("GET", "/leaderboard", null, stranger.token())).path("items");
        assertThat(privateIndividual.size()).isEqualTo(1);
        assertThat(privateIndividual.get(0).path("id").asText()).isEqualTo(stranger.id());
        var updatedOverview = body(call("GET", "/groups/overview", null, member.token())).get(0);
        assertThat(updatedOverview.path("weeklyXp").asLong()).isEqualTo(60);
        assertThat(updatedOverview.path("personalXp").asLong()).isEqualTo(60);
        assertThat(updatedOverview.path("weeklySolves").asLong()).isEqualTo(1);
        assertThat(updatedOverview.path("lastSolveAt").asText()).isEqualTo(membershipStart.plusSeconds(1).toString());

        assertThat(call("DELETE", group + "/members/" + member.id(), null, owner.token()).statusCode()).isEqualTo(204);
        assertThat(call("GET", group, null, member.token()).statusCode()).isEqualTo(404);
        assertThat(call("GET", group + "/leaderboard", null, member.token()).statusCode()).isEqualTo(404);
        assertThat(call("GET", group + "/featured-problem?mode=demo", null, member.token()).statusCode()).isEqualTo(404);
        assertThat(body(call("GET", "/groups", null, member.token())).path("items").size()).isZero();
        assertThat(body(call("GET", "/groups/overview", null, member.token())).size()).isZero();
    }

    private boolean attempt(CountDownLatch start, long account, String code) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
            invites.redeem(account, code);
            return true;
        } catch (Failure failure) {
            assertThat(failure.code()).isEqualTo("invite_unavailable");
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void assertCode(String code, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(Failure.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }

    @Test
    void monthEndpointsAndAllTimeUseTheSameMembershipIntervalsAsWeeklyStandings() {
        long owner = account(), member = account(), group = create(owner);
        Instant boundary = Instant.parse("2026-02-01T00:00:00Z");
        clock.now = boundary.minusSeconds(3700);
        var invite = invites.create(group, owner, 24, 2);
        clock.now = boundary.minusSeconds(3600);
        invites.redeem(member, invite.code());
        clock.now = boundary.minusSeconds(2400);
        groups.remove(group, owner, member);
        clock.now = boundary.minusSeconds(1200);
        invites.redeem(member, invite.code());
        clock.now = boundary.plus(Duration.ofDays(2));
        for (long excluded : new long[] {-3601, -3600, -2400, -2300, -1200}) {
            credit(member, "FIXTURE", boundary.plusSeconds(excluded), 25, "BASE_SOLVE");
        }
        for (Instant included : List.of(boundary.minusSeconds(3599), boundary.minusNanos(1000), boundary, boundary.plusSeconds(86400))) {
            credit(member, "FIXTURE", included, 25, "BASE_SOLVE");
        }
        credit(member, "FIXTURE", boundary, 50, "FEATURED");

        var jan = standings.page(group, owner, "monthly", null, "2026-01", "demo", 1, null);
        var feb = standings.page(group, owner, "monthly", null, null, "demo", 20, null);
        var all = standings.page(group, owner, "all-time", null, null, "demo", 20, null);
        assertThat(jan.periodEnd()).isEqualTo(boundary);
        assertThat(feb.periodStart()).isEqualTo(boundary);
        assertThat(all.periodStart()).isNull();
        assertThat(all.periodEnd()).isNull();
        assertThat(List.of(xp(jan, member), xp(feb, member), xp(all, member))).containsExactly(50L, 50L, 100L);
        assertThat(xp(page(group, owner, "2026-01-26", "demo", 20, null), member)).isEqualTo(75);
        assertThat(xp(page(group, owner, "2026-02-02", "demo", 20, null), member)).isEqualTo(25);
        assertCode("invalid_pagination", () -> standings.page(group, owner, "monthly", null, "2026-02", "demo", 20, jan.nextCursor()));
        assertCode("invalid_pagination", () -> standings.page(group, owner, "all-time", null, null, "demo", 20, jan.nextCursor()));
        assertCode("invalid_month", () -> standings.page(group, owner, "monthly", null, "2026-13", "demo", 20, null));
        groups.remove(group, member, member);
        assertThat(xp(standings.page(group, owner, "all-time", null, null, "demo", 20, null), member)).isEqualTo(100);
        assertCode("group_not_found", () -> standings.page(group, member, "all-time", null, null, "demo", 20, null));
    }

    @Test
    void signedInPublicDirectoryExposesOnlyPreviewAndPublicJoinIsIdempotent() throws Exception {
        var owner = login();
        var viewer = login();
        long privateGroup = create(Long.parseLong(owner.id()));
        var response = call("POST", "/groups", Map.of("name", "Open Study", "description", "Study in UTC",
                "visibility", "PUBLIC"), owner.token());
        assertThat(response.statusCode()).isEqualTo(201);
        String groupId = body(response).path("id").asText();
        String group = "/groups/" + groupId;
        assertThat(call("GET", "/groups/public", null, null).statusCode()).isEqualTo(401);
        var directory = body(call("GET", "/groups/public", null, viewer.token()));
        assertThat(directory.values().stream().map(row -> row.path("id").asText()).toList())
                .contains(groupId).doesNotContain(Long.toString(privateGroup));
        var preview = directory.values().stream().filter(row -> row.path("id").asText().equals(groupId)).findFirst().orElseThrow();
        assertThat(preview.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder("id", "name", "description");
        assertThat(call("GET", group, null, viewer.token()).statusCode()).isEqualTo(404);
        assertThat(call("GET", group + "/activity", null, viewer.token()).statusCode()).isEqualTo(404);
        assertThat(call("GET", group + "/challenges", null, viewer.token()).statusCode()).isEqualTo(404);
        assertThat(call("POST", "/groups/" + privateGroup + "/join", null, viewer.token()).statusCode()).isEqualTo(404);
        assertThat(call("POST", group + "/join", null, viewer.token()).statusCode()).isEqualTo(200);
        assertThat(call("POST", group + "/join", null, viewer.token()).statusCode()).isEqualTo(200);
        assertThat(call("GET", group + "/activity", null, viewer.token()).statusCode()).isEqualTo(200);
        assertThat(groups.detail(Long.parseLong(groupId), Long.parseLong(owner.id())).members()).hasSize(2);
        assertThat(body(call("GET", "/groups/public", null, viewer.token())).values().stream()
                .map(row -> row.path("id").asText()).toList()).doesNotContain(groupId);
    }

    @Test
    void finalizationReconcilesLateSolvesWithoutProjectionsAndPreservesSharedWinners() throws Exception {
        long owner = account(), member = account(), group = create(owner);
        var invite = invites.create(group, owner, 24, 2);
        invites.redeem(member, invite.code());
        clock.now = JOIN;
        Instant start = JOIN.plusSeconds(60), end = JOIN.plusSeconds(3600);
        var view = challenges.create(group, owner, new ChallengeService.Settings("Count challenge", ChallengeService.Kind.GROUP,
                ChallengeService.Rule.COUNT, "demo", member, null, null, 2, start, end));
        long challenge = Long.parseLong(view.challenge().id());
        Instant cutoff = end.plus(Duration.ofHours(24));
        assertThat(view.challenge().status()).isEqualTo("SCHEDULED");
        assertThat(view.challenge().cutoffAt()).isEqualTo(cutoff);
        clock.now = JOIN.plusSeconds(120);
        credit(owner, "FIXTURE", start.minusNanos(1000), 25, "BASE_SOLVE");
        credit(owner, "FIXTURE", start, 25, "BASE_SOLVE");
        credit(member, "FIXTURE", JOIN.plusSeconds(100), 25, "BASE_SOLVE");
        assertThat(challenges.enroll(group, member, challenge).challenge().status()).isEqualTo("ACTIVE");
        assertThat(challenges.enroll(group, member, challenge).participants().getLast().enrolledAt()).isEqualTo(clock.now);
        clock.now = JOIN.plusSeconds(240);
        groups.remove(group, owner, member);
        credit(member, "FIXTURE", JOIN.plusSeconds(200), 25, "BASE_SOLVE");
        credit(member, "FIXTURE", JOIN.plusSeconds(240), 25, "BASE_SOLVE");
        clock.now = JOIN.plusSeconds(400);
        invites.redeem(member, invite.code());
        credit(member, "FIXTURE", JOIN.plusSeconds(300), 25, "BASE_SOLVE");
        credit(member, "FIXTURE", JOIN.plusSeconds(400), 25, "BASE_SOLVE");
        clock.now = cutoff.minusNanos(1000);
        credit(owner, "FIXTURE", end.minusNanos(1000), 25, "BASE_SOLVE");
        credit(owner, "FIXTURE", end, 25, "BASE_SOLVE");
        credit(member, "FIXTURE", JOIN.plusSeconds(410), 25, "BASE_SOLVE");
        assertThat(challenges.detail(group, owner, challenge).challenge().status()).isEqualTo("GRACE");
        clock.now = cutoff;
        credit(member, "FIXTURE", JOIN.plusSeconds(411), 25, "BASE_SOLVE");
        var one = CompletableFuture.supplyAsync(challenges::finalizeNext);
        var two = CompletableFuture.supplyAsync(challenges::finalizeNext);
        CompletableFuture.allOf(one, two).get(15, TimeUnit.SECONDS);
        var result = challenges.detail(group, owner, challenge);
        assertThat(result.challenge().outcome()).isEqualTo("TIE");
        assertThat(result.participants()).extracting(ChallengeService.Participant::score).containsExactly(2, 2);
        assertThat(result.participants()).allMatch(ChallengeService.Participant::winner);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM challenge_evidence WHERE challenge_id = ?").param(challenge)
                .query(Integer.class).single()).isEqualTo(4);
        clock.now = cutoff.plusSeconds(60);
        credit(member, "FIXTURE", JOIN.plusSeconds(500), 25, "BASE_SOLVE");
        challenges.finalizeNext();
        assertThat(challenges.detail(group, owner, challenge)).isEqualTo(result);
        assertThat(activity.inbox(member, Long.MAX_VALUE).stream().filter(n -> view.challenge().id().equals(n.challengeId())))
                .extracting(GroupActivity.Notice::kind).containsExactly("CHALLENGE_RESULT", "INVITATION");
    }

    @Test
    void duelAndDifficultyGoalsScopeProgressAndOldNoticeLinksToCurrentAccess() throws Exception {
        var owner = login();
        var member = login();
        var observer = login();
        long ownerId = Long.parseLong(owner.id()), memberId = Long.parseLong(member.id());
        long group = create(ownerId);
        var code = invites.create(group, ownerId, 24, 2).code();
        invites.redeem(memberId, code);
        invites.redeem(Long.parseLong(observer.id()), code);
        clock.now = JOIN;
        Instant end = JOIN.plusSeconds(500);
        String base = "/groups/" + group;
        var created = call("POST", base + "/challenges", Map.of("title", "Array Duel", "kind", "DUEL", "rule", "PROBLEM",
                "mode", "demo", "inviteeId", member.id(), "problemSlug", "phase-six-array", "endsAt", end.toString()), owner.token());
        assertThat(created.statusCode()).isEqualTo(201);
        String id = body(created).path("challenge").path("id").asText();
        String duel = base + "/challenges/" + id;
        assertThat(body(created).path("challenge").path("status").asText()).isEqualTo("AWAITING_OPPONENT");
        assertThat(call("GET", duel, null, observer.token()).statusCode()).isEqualTo(404);
        assertThat(call("POST", duel + "/enrollment", null, observer.token()).statusCode()).isEqualTo(404);
        assertThat(body(call("GET", base + "/activity?mode=demo", null, observer.token())).size()).isZero();
        var item = body(call("GET", "/me/notifications", null, member.token())).get(0);
        assertThat(item.path("kind").asText()).isEqualTo("INVITATION");
        activity.challengeNotice(memberId, "INVITATION", group, Long.parseLong(id), clock.now.plusSeconds(1));
        assertThat(body(call("GET", "/me/notifications", null, member.token())).values()).containsExactly(item);
        String notice = "/me/notifications/" + item.path("id").asText() + "/read";
        assertThat(call("POST", notice, null, observer.token()).statusCode()).isEqualTo(404);
        assertThat(call("POST", notice, null, member.token()).statusCode()).isEqualTo(204);
        var readAt = body(call("GET", "/me/notifications", null, member.token())).get(0).path("readAt").asText();
        clock.now = JOIN.plusSeconds(30);
        assertThat(call("POST", notice, null, member.token()).statusCode()).isEqualTo(204);
        assertThat(body(call("GET", "/me/notifications", null, member.token())).get(0).path("readAt").asText()).isEqualTo(readAt);

        var goal = challenges.create(group, ownerId, new ChallengeService.Settings("Medium warm-up", ChallengeService.Kind.GROUP,
                ChallengeService.Rule.DIFFICULTY, "demo", null, null, Difficulty.MEDIUM, 2, null, end));
        long goalId = Long.parseLong(goal.challenge().id());
        clock.now = JOIN.plusSeconds(90);
        credit(ownerId, "FIXTURE", JOIN.plusSeconds(60), 25, "BASE_SOLVE", "phase-six-array", Difficulty.MEDIUM);
        assertThat(call("POST", duel + "/enrollment", null, member.token()).statusCode()).isEqualTo(200);
        challenges.enroll(group, memberId, goalId);
        clock.now = JOIN.plusSeconds(150);
        credit(memberId, "FIXTURE", JOIN.plusSeconds(115), 25, "BASE_SOLVE", "phase-six-array", Difficulty.MEDIUM);
        credit(ownerId, "FIXTURE", JOIN.plusSeconds(116), 50, "BASE_SOLVE", "other-hard", Difficulty.HARD);
        assertThat(challenges.detail(group, memberId, goalId).participants()).extracting(ChallengeService.Participant::score)
                .containsExactly(1, 1);
        assertThat(challenges.detail(group, memberId, Long.parseLong(id)).participants()).extracting(ChallengeService.Participant::score)
                .containsExactly(0, 1);
        groups.remove(group, ownerId, memberId);
        assertThat(call("POST", notice, null, member.token()).statusCode()).isEqualTo(404);
        assertThat(call("GET", duel, null, member.token()).statusCode()).isEqualTo(404);
        assertThat(call("GET", base + "/activity?mode=demo", null, member.token()).statusCode()).isEqualTo(404);
        assertThat(body(call("GET", "/me/notifications", null, member.token())).size()).isZero();
        clock.now = end;
        assertCode("enrollment_closed", () -> challenges.enroll(group, Long.parseLong(observer.id()), goalId));
        clock.now = end.plus(Duration.ofHours(24));
        for (int i = 0; i < 10 && challenges.finalizeNext(); i++) {
            // Process pending fixtures on the same worker entry point.
        }
        assertThat(challenges.detail(group, ownerId, Long.parseLong(id)).challenge().outcome()).isEqualTo("WIN");
        assertThat(challenges.detail(group, ownerId, Long.parseLong(id)).participants().getLast().winner()).isTrue();
        assertThat(activity.inbox(memberId, Long.MAX_VALUE)).isEmpty();
    }

    private long account() {
        String handle = "group_" + UUID.randomUUID().toString().substring(0, 8);
        return insert("INSERT INTO accounts (email, handle, display_name, password_hash, active) VALUES (?, ?, ?, ?, TRUE)",
                handle + "@example.test", handle, handle, "unused");
    }

    private long create(long owner) {
        return Long.parseLong(groups.create(owner, "Study group", "", "PRIVATE").id());
    }

    private GroupStandings.Page page(long group, long actor, String week, String mode, int limit, String cursor) {
        return standings.page(group, actor, "weekly", week, null, mode, limit, cursor);
    }

    private static long xp(GroupStandings.Page page, long account) {
        return page.items().stream().filter(row -> row.id().equals(Long.toString(account))).findFirst().orElseThrow().xp();
    }

    private void credit(long account, String provider, Instant effectiveAt, int amount, String reason) {
        credit(account, provider, effectiveAt, amount, reason, UUID.randomUUID().toString(), Difficulty.MEDIUM);
    }

    private void credit(long account, String provider, Instant effectiveAt, int amount, String reason, String slug, Difficulty difficulty) {
        String evidence = UUID.randomUUID().toString();
        String profile = provider.toLowerCase(java.util.Locale.ROOT) + "-" + account;
        long link = links.computeIfAbsent(new Source(account, provider), source -> {
            jdbc.sql("INSERT INTO provider_identities (provider, provider_account_id, account_id) VALUES (?, ?, ?)")
                    .params(provider, profile, account).update();
            return insert("""
                    INSERT INTO leetcode_links (account_id, handle, state, provider, provider_account_id)
                    VALUES (?, ?, 'DISCONNECTED', ?, ?)
                    """, account, profile, provider, profile);
        });
        long problem = insert("""
                INSERT INTO problems (provider, provider_problem_id, slug, title, difficulty)
                VALUES (?, ?, ?, 'Synthetic solve', ?)
                """, provider, evidence, slug, difficulty.name());
        long submission = insert("""
                INSERT INTO submissions (account_id, link_id, provider, provider_account_id, provider_submission_id,
                    problem_id, outcome, difficulty, occurred_at, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, 'ACCEPTED', ?, ?, ?)
                """, account, link, provider, profile, evidence, problem, difficulty.name(), Utc.sql(effectiveAt), Utc.sql(clock.now));
        long solve = insert("""
                INSERT INTO solves (account_id, problem_id, submission_id, difficulty, occurred_at, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, account, problem, submission, difficulty.name(), Utc.sql(effectiveAt), Utc.sql(clock.now));
        jdbc.sql("""
                INSERT INTO xp_entries (account_id, solve_id, provider, reward_key, reason,
                    amount, rule_version, effective_at, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)
                """).params(account, solve, provider, "TEST:" + evidence, reason, amount,
                Utc.sql(effectiveAt), Utc.sql(clock.now)).update();
    }

    private long insert(String statement, Object... parameters) {
        var key = new GeneratedKeyHolder();
        jdbc.sql(statement).params(parameters).update(key, "id");
        return key.getKey().longValue();
    }

    private SignedIn login() throws Exception {
        String handle = "group_api_" + UUID.randomUUID().toString().substring(0, 8);
        String email = handle + "@example.test";
        String password = "phase-three-only-password";
        var registered = call("POST", "/auth/register", Map.of("email", email, "leetcodeHandle", handle,
                "displayName", handle, "password", password), null);
        assertThat(registered.statusCode()).isEqualTo(201);
        var access = call("POST", "/auth/login", Map.of("email", email, "password", password), null);
        assertThat(access.statusCode()).isEqualTo(200);
        return new SignedIn(body(registered).path("id").asText(), body(access).path("accessToken").asText());
    }

    private HttpResponse<String> call(String method, String path, Map<String, ?> input, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Origin", "https://leetgrind.test").header("X-LeetGrind-Client", "browser");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (input != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, input == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private record Source(long account, String provider) {}
    private record SignedIn(String id, String token) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean @Primary
        MutableClock groupClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private volatile Instant now = JOIN;
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
