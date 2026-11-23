package com.leetgrind.engagement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import com.leetgrind.catalog.ProblemCatalog;
import com.leetgrind.catalog.ProblemCatalog.Difficulty;
import com.leetgrind.catalog.ProblemCatalog.Problem;
import com.leetgrind.platform.Utc;
import com.leetgrind.progress.ProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "leetgrind.engagement.worker-enabled=false",
        "leetgrind.competition.worker-enabled=false",
        "leetgrind.integration.fixtures-enabled=true",
        "leetgrind.integration.scheduler-enabled=false",
        "leetgrind.auth.browser-origin=https://leetgrind.test",
        "leetgrind.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "leetgrind.auth.secure-cookie=true",
        "spring.datasource.password=${LEETGRIND_DB_PASSWORD:phase1-test-password}"
})
class PracticeRewardsIntegrationTest {
    private static final Instant MONDAY = Instant.parse("2026-01-05T00:00:00Z");
    private static final Instant TUESDAY = MONDAY.plusSeconds(86400);
    private static final Instant WEDNESDAY = TUESDAY.plusSeconds(86400);
    private static final String FEATURED = "fixture-array-window";

    @Autowired JdbcClient jdbc;
    @Autowired ProblemCatalog catalog;
    @Autowired ProgressService progress;
    @Autowired RewardConsumer consumer;
    @Autowired EngagementService service;
    @Autowired RewardWindows windows;
    @Autowired TestClock clock;
    @Autowired PlatformTransactionManager manager;

    @BeforeEach
    void reset() {
        clock.now = WEDNESDAY.plusSeconds(200);
        drain();
    }

    @Test
    void consecutiveUtcSignInsCountOncePerDayAndDoNotCreateCodingDays() {
        var learner = learner();
        var transaction = new TransactionTemplate(manager);
        transaction.executeWithoutResult(status -> {
            progress.creditLogin(learner.id(), MONDAY.plusSeconds(10));
            progress.creditLogin(learner.id(), TUESDAY.plusSeconds(10));
            progress.creditLogin(learner.id(), TUESDAY.plusSeconds(20));
        });

        var rewards = service.rewards(learner.id(), "live");
        assertThat(rewards.totalXp()).isEqualTo(10);
        assertThat(rewards.loginStreak()).isEqualTo(new EngagementService.Streak(2, 2));
        assertThat(rewards.activity().codingDates()).isEmpty();
        assertThat(rewards.recentSolves()).isEmpty();

        clock.now = WEDNESDAY.plusSeconds(86400);
        assertThat(service.rewards(learner.id(), "live").loginStreak()).isEqualTo(new EngagementService.Streak(0, 2));
    }

    @Test
    void lateSolveFillsAGapWithoutCountingTwoSolvesOnOneDay() {
        var learner = learner();
        credit(learner, "gap-first", MONDAY, MONDAY.plusSeconds(5));
        credit(learner, "gap-second", MONDAY.plusSeconds(6), MONDAY.plusSeconds(7));
        credit(learner, "gap-fourth", WEDNESDAY, WEDNESDAY.plusSeconds(8));
        drain();

        var before = service.rewards(learner.id(), "demo");
        assertThat(before.activity().codingDates()).containsExactly(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7));
        assertThat(before.activity().currentStreak()).isEqualTo(1);
        assertThat(before.badges()).extracting(EngagementService.Badge::code).containsExactly("FIRST_SOLVE");

        credit(learner, "gap-third", TUESDAY.plusSeconds(321), WEDNESDAY.plusSeconds(10));
        drain();
        var after = service.rewards(learner.id(), "demo");
        assertThat(after.activity().codingDates()).hasSize(3);
        assertThat(after.activity().currentStreak()).isEqualTo(3);
        assertThat(after.activity().longestStreak()).isEqualTo(3);
        assertThat(after.badges()).extracting(EngagementService.Badge::code).containsExactlyInAnyOrder("FIRST_SOLVE", "THREE_DAY_STREAK");
        assertThat(service.rewards(learner.id(), "live").activity().codingDates()).isEmpty();

        clock.now = WEDNESDAY.plusSeconds(86400);
        assertThat(service.rewards(learner.id(), "demo").activity().currentStreak()).isEqualTo(3);
        clock.now = WEDNESDAY.plusSeconds(2 * 86400);
        var expired = service.rewards(learner.id(), "demo").activity();
        assertThat(expired.currentStreak()).isZero();
        assertThat(expired.longestStreak()).isEqualTo(3);
    }

    @Test
    void featuredUsesHalfOpenUtcWindowAndRecordedCutoffIncludingGroupRollup() {
        var start = learner();
        var end = learner();
        var justBeforeCutoff = learner();
        var atCutoff = learner();
        credit(start, FEATURED, MONDAY, MONDAY.plusSeconds(10));
        credit(end, FEATURED, TUESDAY, TUESDAY.plusSeconds(10));
        credit(justBeforeCutoff, FEATURED, MONDAY.plusSeconds(10), WEDNESDAY.minusNanos(1_000));
        credit(atCutoff, FEATURED, MONDAY.plusSeconds(15), WEDNESDAY);
        drain();

        var monday = windows.window("FIXTURE", RewardWindows.Kind.FEATURED, LocalDate.of(2026, 1, 5));
        assertThat(monday.start()).isEqualTo(MONDAY);
        assertThat(monday.end()).isEqualTo(TUESDAY);
        assertThat(monday.cutoff()).isEqualTo(WEDNESDAY);
        assertThat(evidence(monday.id(), start.id())).isEqualTo(1);
        assertThat(evidence(monday.id(), end.id())).isZero();
        assertThat(evidence(monday.id(), justBeforeCutoff.id())).isEqualTo(1);
        assertThat(evidence(monday.id(), atCutoff.id())).isZero();
        assertThat(rewardAmounts(atCutoff.id())).containsExactlyEntriesOf(Map.of("BASE_SOLVE", 25));

        long group = insert("INSERT INTO study_groups (owner_id, name, description, created_at) VALUES (?, 'Fixture', '', ?)",
                start.id(), Utc.sql(MONDAY.minusSeconds(1)));
        for (Learner member : new Learner[] {start, end, justBeforeCutoff, atCutoff}) {
            // The late learner joined after their solve, so their personal completion stays out of the group count.
            Instant joined = member == justBeforeCutoff ? MONDAY.plusSeconds(11) : MONDAY.minusSeconds(1);
            jdbc.sql("INSERT INTO memberships (group_id, account_id, joined_at) VALUES (?, ?, ?)")
                    .params(group, member.id(), Utc.sql(joined)).update();
        }
        clock.now = MONDAY.plusSeconds(3600);
        var groupView = service.groupFeatured(group, "demo");
        assertThat(groupView.members()).isEqualTo(4);
        assertThat(groupView.completed()).isEqualTo(1);
        assertThat(service.rewards(start.id(), "demo").featured().task().completed()).isEqualTo(1);
        assertThat(service.rewards(end.id(), "demo").featured().task().completed()).isZero();
        assertThat(service.groupFeatured(group, "live").completed()).isZero();
    }

    @Test
    void weeklyCutoffSeparatesLateEvidenceFromTheNextMonday() {
        var learner = learner();
        Instant nextMonday = MONDAY.plusSeconds(7 * 86400);
        Instant cutoff = nextMonday.plusSeconds(86400);
        clock.now = cutoff.plusSeconds(15);
        credit(learner, "weekly-first", MONDAY, MONDAY.plusSeconds(10));
        credit(learner, "weekly-second", TUESDAY, TUESDAY.plusSeconds(10));
        credit(learner, "weekly-too-late", nextMonday.minusSeconds(60), cutoff);
        credit(learner, "weekly-next-week", nextMonday, nextMonday.plusSeconds(10));
        drain();
        var oldWeek = windows.window("FIXTURE", RewardWindows.Kind.WEEKLY_QUEST, LocalDate.of(2026, 1, 5));
        assertThat(oldWeek.cutoff()).isEqualTo(cutoff);
        assertThat(evidence(oldWeek.id(), learner.id())).isEqualTo(2);
        assertThat(rewardAmounts(learner.id())).doesNotContainKey("WEEKLY_QUEST");
        assertThat(service.rewards(learner.id(), "demo").quests().getLast().completed()).isEqualTo(1);

        credit(learner, "weekly-just-in-time", nextMonday.minusSeconds(1), cutoff.minusNanos(1_000));
        drain();
        assertThat(evidence(oldWeek.id(), learner.id())).isEqualTo(3);
        assertThat(rewardAmounts(learner.id())).containsEntry("WEEKLY_QUEST", 100);
    }

    @Test
    void oneSolveCanFinishThreeRulesAndReplayPreservesRewardsAndEvidence() {
        var learner = learner();
        clock.now = MONDAY.plusSeconds(600);
        long group = insert("INSERT INTO study_groups (owner_id, name, description, created_at) VALUES (?, 'Fixture', '', ?)",
                learner.id(), Utc.sql(MONDAY));
        jdbc.sql("INSERT INTO memberships (group_id, account_id, joined_at) VALUES (?, ?, ?)")
                .params(group, learner.id(), Utc.sql(MONDAY)).update();
        credit(learner, "final-first", MONDAY.plusSeconds(1), MONDAY.plusSeconds(11));
        credit(learner, "final-second", MONDAY.plusSeconds(2), MONDAY.plusSeconds(12));
        drain();
        long finalSolve = credit(learner, FEATURED, MONDAY.plusSeconds(3), MONDAY.plusSeconds(13));

        var transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.executeWithoutResult(status -> {
            assertThat(consumer.consumeNext()).isTrue();
            assertThat(rewardAmounts(learner.id())).containsEntry("WEEKLY_QUEST", 100);
            status.setRollbackOnly();
        });
        assertThat(rewardAmounts(learner.id())).containsExactlyEntriesOf(Map.of("BASE_SOLVE", 75));

        drain();
        var result = service.rewards(learner.id(), "demo");
        assertThat(result.totalXp()).isEqualTo(255);
        assertThat(result.level().number()).isEqualTo(3);
        assertThat(result.level().ruleVersion()).isEqualTo(1);
        assertThat(result.badges()).hasSize(1);
        assertThat(rewardAmounts(learner.id())).containsExactlyInAnyOrderEntriesOf(
                Map.of("BASE_SOLVE", 75, "FEATURED", 50, "DAILY_QUEST", 30, "WEEKLY_QUEST", 100));
        assertThat(rows("reward_evidence", learner.id())).isEqualTo(5);
        var notices = jdbc.sql("SELECT kind FROM notifications WHERE recipient_id = ? ORDER BY id")
                .param(learner.id()).query(String.class).list();
        assertThat(notices).containsExactly("ACHIEVEMENT", "STANDINGS_CHANGE", "STANDINGS_CHANGE", "STANDINGS_CHANGE");

        jdbc.sql("UPDATE outbox_events SET dispatched_at = NULL WHERE event_type = 'SolveCredited' AND aggregate_id = ?")
                .param(finalSolve).update();
        drain();
        assertThat(service.rewards(learner.id(), "demo")).isEqualTo(result);
        assertThat(rows("xp_entries", learner.id())).isEqualTo(6);
        assertThat(rows("reward_evidence", learner.id())).isEqualTo(5);
        assertThat(rows("user_achievements", learner.id())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT kind FROM notifications WHERE recipient_id = ? ORDER BY id")
                .param(learner.id()).query(String.class).list()).isEqualTo(notices);
    }

    private void drain() {
        for (int i = 0; i < 1000; i++) {
            if (!consumer.consumeNext()) return;
        }
        throw new AssertionError("Fixture outbox exceeded the test bound");
    }

    private Map<String, Integer> rewardAmounts(long account) {
        return jdbc.sql("SELECT reason, SUM(amount) AS amount FROM xp_entries WHERE account_id = ? GROUP BY reason")
                .param(account).query((row, index) -> Map.entry(row.getString("reason"), row.getInt("amount"))).list()
                .stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private int rows(String table, long account) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE account_id = ?").param(account).query(Integer.class).single();
    }

    private int evidence(long window, long account) {
        return jdbc.sql("SELECT COUNT(*) FROM reward_evidence WHERE window_id = ? AND account_id = ?")
                .params(window, account).query(Integer.class).single();
    }

    private Learner learner() {
        String handle = "reward_" + UUID.randomUUID().toString().substring(0, 12).replace("-", "");
        long account = insert("INSERT INTO accounts (email, handle, display_name, password_hash, active) VALUES (?, ?, ?, 'unused', TRUE)",
                handle + "@example.test", handle, handle);
        jdbc.sql("INSERT INTO provider_identities (provider, provider_account_id, account_id) VALUES ('FIXTURE', ?, ?)")
                .params(handle, account).update();
        long link = insert("""
                INSERT INTO leetcode_links (account_id, handle, state, provider, provider_account_id)
                VALUES (?, ?, 'DISCONNECTED', 'FIXTURE', ?)
                """, account, handle, handle);
        return new Learner(account, handle, link);
    }

    private long credit(Learner learner, String slug, Instant effective, Instant recorded) {
        return new TransactionTemplate(manager).execute(status -> {
            long problem = catalog.resolve("FIXTURE", new Problem(slug, slug, "Fixture " + slug, Difficulty.MEDIUM));
            long submission = insert("""
                    INSERT INTO submissions (account_id, link_id, provider, provider_account_id, provider_submission_id,
                        problem_id, outcome, difficulty, occurred_at, recorded_at)
                    VALUES (?, ?, 'FIXTURE', ?, ?, ?, 'ACCEPTED', 'MEDIUM', ?, ?)
                    """, learner.id(), learner.link(), learner.handle(), UUID.randomUUID().toString(), problem,
                    Utc.sql(effective), Utc.sql(recorded));
            progress.creditSolve(new ProgressService.Credit(learner.id(), problem, submission, "FIXTURE",
                    Difficulty.MEDIUM, effective, recorded));
            return jdbc.sql("SELECT id FROM solves WHERE submission_id = ?").param(submission).query(Long.class).single();
        });
    }

    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.sql(sql).params(values).update(key, "id");
        return key.getKey().longValue();
    }

    private record Learner(long id, String handle, long link) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean @Primary
        TestClock rewardClock() { return new TestClock(); }
    }

    static class TestClock extends Clock {
        private volatile Instant now = MONDAY;
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
