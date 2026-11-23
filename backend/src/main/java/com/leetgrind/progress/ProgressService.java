package com.leetgrind.progress;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import com.leetgrind.catalog.ProblemCatalog.Difficulty;
import com.leetgrind.identity.ActiveAccountGuard;
import com.leetgrind.platform.ApiErrors.Failure;
import com.leetgrind.platform.OperationsMetrics;
import com.leetgrind.platform.Utc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProgressService {
    private final JdbcClient jdbc;
    private final OperationsMetrics metrics;
    private final ActiveAccountGuard accounts;
    private final Clock clock;

    ProgressService(JdbcClient jdbc, OperationsMetrics metrics, ActiveAccountGuard accounts, Clock clock) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void creditSolve(Credit credit) {
        int xp = switch (credit.difficulty()) {
            case EASY -> 10;
            case MEDIUM -> 25;
            case HARD -> 50;
        };
        var solveKey = new GeneratedKeyHolder();
        try {
            jdbc.sql("""
                    INSERT INTO solves (account_id, problem_id, submission_id, difficulty, occurred_at, recorded_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """).params(credit.accountId(), credit.problemId(), credit.submissionId(), credit.difficulty().name(),
                    Utc.sql(credit.occurredAt()), Utc.sql(credit.recordedAt())).update(solveKey, "id");
        } catch (DuplicateKeyException duplicateSolve) {
            metrics.deduplicatedXp();
            return;
        }
        long solveId = solveKey.getKey().longValue();
        reward(credit.accountId(), solveId, credit.provider(), "SOLVE:" + credit.problemId(), "BASE_SOLVE", xp,
                1, credit.occurredAt(), credit.recordedAt());
        jdbc.sql("""
                INSERT INTO outbox_events (event_type, event_version, aggregate_id, payload, recorded_at)
                SELECT 'SolveCredited', 1, ?, JSON_OBJECT('solveId', ?, 'accountId', ?, 'provider', ?, 'problemSlug', p.slug), ?
                FROM problems p WHERE p.id = ?
                """).params(solveId, solveId, credit.accountId(), credit.provider(), Utc.sql(credit.recordedAt()), credit.problemId()).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reward(long accountId, Long solveId, String provider, String key, String reason, int amount,
                       int ruleVersion, Instant effectiveAt, Instant recordedAt) {
        jdbc.sql("""
                INSERT INTO xp_entries
                    (account_id, solve_id, provider, reward_key, reason, amount, rule_version, effective_at, recorded_at)
                VALUES (:account, :solve, :provider, :key, :reason, :amount, :version, :effective, :recorded)
                ON DUPLICATE KEY UPDATE id = xp_entries.id
                """).param("account", accountId).param("solve", solveId).param("provider", provider).param("key", key)
                .param("reason", reason).param("amount", amount).param("version", ruleVersion)
                .param("effective", Utc.sql(effectiveAt)).param("recorded", Utc.sql(recordedAt)).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void creditLogin(long accountId, Instant now) {
        var nowUtc = Utc.sql(now);
        reward(accountId, null, "PERSONAL", "DAILY_LOGIN:" + nowUtc.toLocalDate(), "DAILY_LOGIN", 5, 1, now, now);
    }

    @Transactional
    public CheckIn checkIn(long accountId) {
        accounts.lock(accountId);
        Instant now = Utc.now(clock);
        creditLogin(accountId, now);
        return new CheckIn(Utc.sql(now).toLocalDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    List<RewardEvent> rewardEvents(long accountId) {
        Instant now = Utc.now(clock);
        return jdbc.sql("""
                SELECT * FROM (
                    SELECT CONCAT('xp:', x.id) AS event_id, x.reason AS kind, p.title AS label, x.amount, x.recorded_at AS occurred_at
                    FROM xp_entries x
                    LEFT JOIN solves s ON s.id = x.solve_id LEFT JOIN problems p ON p.id = s.problem_id
                    WHERE x.account_id = :account AND x.recorded_at > :since
                      AND ((x.provider = 'PERSONAL' AND x.reason = 'DAILY_LOGIN')
                           OR (x.provider = 'LEETCODE' AND x.reason = 'BASE_SOLVE'))
                    UNION ALL
                    SELECT CONCAT('badge:', code), 'ACHIEVEMENT', code, NULL, unlocked_at
                    FROM user_achievements
                    WHERE account_id = :account AND provider = 'LEETCODE' AND unlocked_at > :since
                ) events ORDER BY occurred_at DESC, event_id DESC LIMIT 20
                """).param("account", accountId).param("since", Utc.sql(now.minusSeconds(600))).query((row, index) ->
                new RewardEvent(row.getString("event_id"), row.getString("kind"), row.getString("label"),
                        row.getObject("amount", Integer.class), Utc.instant(row.getObject("occurred_at", LocalDateTime.class)))).list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void eraseCredits(long accountId) {
        jdbc.sql("""
                DELETE o FROM outbox_events o JOIN solves s ON o.aggregate_id = s.id
                WHERE o.event_type = 'SolveCredited' AND s.account_id = ?
                """).param(accountId).update();
        jdbc.sql("DELETE FROM xp_entries WHERE account_id = ?").param(accountId).update();
        jdbc.sql("DELETE FROM solves WHERE account_id = ?").param(accountId).update();
    }

    Summary summary(long accountId) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(CASE WHEN provider = 'LEETCODE' AND reason = 'BASE_SOLVE' THEN amount END), 0) AS total_xp,
                       COALESCE(SUM(CASE WHEN provider = 'FIXTURE' AND reason = 'BASE_SOLVE' THEN amount END), 0) AS fixture_xp,
                       COUNT(CASE WHEN provider = 'LEETCODE' AND reason = 'BASE_SOLVE' THEN 1 END) AS solved_count,
                       COUNT(CASE WHEN provider = 'FIXTURE' AND reason = 'BASE_SOLVE' THEN 1 END) AS fixture_solved_count
                FROM xp_entries WHERE account_id = ?
                """).param(accountId).query((row, index) -> new Summary(row.getLong("total_xp"),
                row.getLong("solved_count"), row.getLong("fixture_xp"), row.getLong("fixture_solved_count"))).single();
    }

    History history(long accountId, int limit, String cursor) {
        if (limit < 1 || limit > 100) {
            throw invalidPagination();
        }
        long before = decodeCursor(accountId, cursor);
        List<Entry> entries = jdbc.sql("""
                SELECT x.id, x.provider, x.reason, x.amount, x.rule_version, x.effective_at, x.recorded_at,
                       p.title, s.difficulty
                FROM xp_entries x LEFT JOIN solves s ON s.id = x.solve_id LEFT JOIN problems p ON p.id = s.problem_id
                WHERE x.account_id = ? AND x.id < ? AND x.provider IN ('FIXTURE', 'LEETCODE', 'PERSONAL')
                ORDER BY x.id DESC LIMIT ?
                """).params(accountId, before, limit + 1).query((row, index) -> new Entry(
                Long.toString(row.getLong("id")), row.getString("provider"), row.getString("reason"),
                row.getInt("amount"), row.getInt("rule_version"), row.getString("title"), row.getString("difficulty"),
                Utc.instant(row.getObject("effective_at", LocalDateTime.class)),
                Utc.instant(row.getObject("recorded_at", LocalDateTime.class)))).list();
        if (entries.size() <= limit) {
            return new History(entries, null);
        }
        String next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (accountId + ":" + entries.get(limit - 1).id()).getBytes(StandardCharsets.US_ASCII));
        return new History(entries.subList(0, limit), next);
    }

    private static long decodeCursor(long accountId, String cursor) {
        if (cursor == null) {
            return Long.MAX_VALUE;
        }
        try {
            if (cursor.length() > 64) {
                throw invalidPagination();
            }
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII).split(":", -1);
            if (parts.length == 2 && Long.parseLong(parts[0]) == accountId) {
                long id = Long.parseLong(parts[1]);
                if (id > 0) {
                    return id;
                }
            }
        } catch (IllegalArgumentException malformedCursor) {
            throw invalidPagination();
        }
        throw invalidPagination();
    }

    private static Failure invalidPagination() {
        return new Failure(HttpStatus.BAD_REQUEST, "invalid_pagination", "Use a valid cursor and a limit from 1 to 100.");
    }

    public record Credit(long accountId, long problemId, long submissionId, String provider, Difficulty difficulty,
                         Instant occurredAt, Instant recordedAt) {}
    record Summary(long totalXp, long solvedCount, long fixtureXp, long fixtureSolvedCount) {}
    record CheckIn(Instant nextCheckInAt) {}
    record RewardEvent(String id, String kind, String label, Integer amount, Instant occurredAt) {}
    record History(List<Entry> items, String nextCursor) {}
    record Entry(String id, String provider, String reason, int amount, int ruleVersion, String problemTitle,
                 String difficulty, Instant effectiveAt, Instant recordedAt) {}
}
