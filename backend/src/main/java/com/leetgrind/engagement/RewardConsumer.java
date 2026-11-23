package com.leetgrind.engagement;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.leetgrind.groups.GroupActivity;
import com.leetgrind.platform.Utc;
import com.leetgrind.progress.ProgressService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class RewardConsumer {
    private final JdbcClient jdbc;
    private final RewardWindows windows;
    private final CodingDays coding;
    private final ProgressService progress;
    private final Clock clock;
    private final GroupActivity activity;

    RewardConsumer(JdbcClient jdbc, RewardWindows windows, CodingDays coding, ProgressService progress,
                   Clock clock, GroupActivity activity) {
        this.jdbc = jdbc;
        this.windows = windows;
        this.coding = coding;
        this.progress = progress;
        this.clock = clock;
        this.activity = activity;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean consumeNext() {
        var pending = jdbc.sql("""
                SELECT o.id, s.account_id FROM outbox_events o JOIN solves s ON s.id = o.aggregate_id
                JOIN submissions sub ON sub.id = s.submission_id
                WHERE o.event_type = 'SolveCredited' AND o.event_version = 1 AND o.dispatched_at IS NULL
                  AND sub.provider IN ('LEETCODE', 'FIXTURE')
                ORDER BY o.id LIMIT 1
                """).query((row, index) -> new Candidate(row.getLong("id"), row.getLong("account_id"))).optional();
        if (pending.isEmpty()) return false;
        consume(pending.get());
        return true;
    }

    private void consume(Candidate candidate) {
        // Sync, login and erasure all take the account lock first.
        if (jdbc.sql("SELECT id FROM accounts WHERE id = ? FOR UPDATE")
                .param(candidate.account()).query(Long.class).optional().isEmpty()) return;

        Optional<Evidence> evidence = jdbc.sql("""
                SELECT aggregate_id, JSON_UNQUOTE(JSON_EXTRACT(payload, '$.problemSlug')) AS slug FROM outbox_events WHERE id = ? AND dispatched_at IS NULL FOR UPDATE
                """).param(candidate.event()).query((row, index) -> new Evidence(row.getLong("aggregate_id"), row.getString("slug"))).optional();
        if (evidence.isEmpty()) return;

        Solve solve = jdbc.sql("""
                SELECT s.id, sub.provider, p.slug, s.occurred_at, s.recorded_at
                FROM solves s JOIN submissions sub ON sub.id = s.submission_id
                JOIN problems p ON p.id = s.problem_id
                WHERE s.id = ? AND s.account_id = ?
                """).params(evidence.get().solve(), candidate.account()).query((row, index) ->
                new Solve(row.getLong("id"), row.getString("provider"), row.getString("slug"),
                        RewardWindows.time(row, "occurred_at"), RewardWindows.time(row, "recorded_at"))).single();

        Instant now = Utc.now(clock);
        long account = candidate.account();
        for (var window : windows.forDay(solve.provider(), RewardWindows.day(solve.occurred()))) {
            if (window.matches(evidence.get().slug() == null ? solve.slug() : evidence.get().slug(), solve.occurred(), solve.recorded())) {
                addEvidence(account, solve, window, now);
            }
        }
        unlock(account, solve.provider(), "FIRST_SOLVE", now);
        if (coding.activity(account, solve.provider(), RewardWindows.day(now)).longestStreak() >= 3) {
            unlock(account, solve.provider(), "THREE_DAY_STREAK", now);
        }

        activity.committedSolve(solve.id(), account, now);
        jdbc.sql("UPDATE outbox_events SET dispatched_at = ? WHERE id = ?")
                .params(Utc.sql(now), candidate.event()).update();
    }

    private void addEvidence(long account, Solve solve, RewardWindows.Window window, Instant now) {
        jdbc.sql("""
                INSERT INTO reward_evidence (window_id, account_id, solve_id) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE solve_id = reward_evidence.solve_id
                """).params(window.id(), account, solve.id()).update();
        long observed = jdbc.sql("""
                SELECT COUNT(*) FROM reward_evidence e JOIN solves s ON s.id = e.solve_id
                JOIN submissions sub ON sub.id = s.submission_id
                WHERE e.window_id = ? AND e.account_id = ? AND sub.provider = ?
                """).params(window.id(), account, solve.provider()).query(Long.class).single();
        if (observed >= window.target()) {
            progress.reward(account, solve.id(), solve.provider(), window.key(), window.kind().name(), window.xp(),
                    window.ruleVersion(), solve.occurred(), now);
        }
    }

    private void unlock(long account, String provider, String code, Instant now) {
        jdbc.sql("""
                INSERT INTO user_achievements (account_id, provider, code, rule_version, unlocked_at)
                VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE code = user_achievements.code
                """).params(account, provider, code, RewardWindows.RULE_VERSION, Utc.sql(now)).update();
        activity.achievement(account, provider, code, now);
    }

    private record Evidence(long solve, String slug) {}
    private record Candidate(long event, long account) {}
    private record Solve(long id, String provider, String slug, Instant occurred, Instant recorded) {}
}
