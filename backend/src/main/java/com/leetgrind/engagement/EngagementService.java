package com.leetgrind.engagement;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.leetgrind.platform.ApiErrors.Failure;
import com.leetgrind.platform.Utc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EngagementService {
    private final JdbcClient jdbc;
    private final RewardWindows windows;
    private final CodingDays days;
    private final Clock clock;
    private final boolean fixturesEnabled;

    EngagementService(JdbcClient jdbc, RewardWindows windows, CodingDays days, Clock clock,
                      @Value("${leetgrind.integration.fixtures-enabled}") boolean fixturesEnabled) {
        this.jdbc = jdbc;
        this.windows = windows;
        this.days = days;
        this.clock = clock;
        this.fixturesEnabled = fixturesEnabled;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Rewards rewards(long account, String mode) {
        String provider = provider(mode);
        Instant now = Utc.now(clock);
        var today = RewardWindows.day(now);
        var offers = windows.forDay(provider, today);
        Ledger ledger = jdbc.sql("""
                SELECT COALESCE(SUM(CASE WHEN provider = :provider
                    OR (:provider = 'LEETCODE' AND provider = 'PERSONAL') THEN amount END), 0) AS total,
                    CAST(MAX(id) AS CHAR) AS last_entry
                FROM xp_entries WHERE account_id = :account AND provider IN ('LEETCODE', 'FIXTURE', 'PERSONAL')
                """).param("account", account).param("provider", provider).query((row, index) ->
                new Ledger(row.getLong("total"), row.getString("last_entry"))).single();
        List<Badge> badges = jdbc.sql("""
                SELECT code, rule_version, unlocked_at FROM user_achievements
                WHERE account_id = ? AND provider = ? ORDER BY unlocked_at, code
                """).params(account, provider).query((row, index) -> new Badge(row.getString("code"),
                row.getInt("rule_version"), RewardWindows.time(row, "unlocked_at"))).list();

        var tasks = offers.stream().map(offer -> task(account, offer)).toList();
        var recentSolves = jdbc.sql("""
                SELECT DATE(s.occurred_at) AS day, p.slug, p.title, sub.provider
                FROM solves s JOIN submissions sub ON sub.id = s.submission_id
                JOIN problems p ON p.id = s.problem_id
                WHERE s.account_id = :account AND s.occurred_at >= :start AND s.occurred_at <= :now
                  AND sub.provider = :provider
                ORDER BY s.occurred_at DESC, s.id DESC
                """).param("account", account).param("provider", provider).param("start", today.minusDays(6).atStartOfDay())
                .param("now", Utc.sql(now)).query((row, index) -> new RecentSolve(row.getObject("day", LocalDate.class),
                        row.getString("slug"), row.getString("title"), row.getString("provider"))).list();
        var signIns = days.loginActivity(account, today);
        return new Rewards(mode, ledger.total(), ledger.lastEntry(), level(ledger.total()), days.activity(account, provider, today),
                new Streak(signIns.currentStreak(), signIns.longestStreak()), recentSolves,
                featured(offers.getFirst(), tasks.getFirst()), tasks.subList(1, tasks.size()), badges);
    }

    // The group module checks current membership and holds its group lock before invoking this projection.
    public GroupFeatured groupFeatured(long group, String mode) {
        String provider = provider(mode);
        var window = windows.window(provider, RewardWindows.Kind.FEATURED, RewardWindows.day(Utc.now(clock)));
        return jdbc.sql("""
                SELECT COUNT(DISTINCT m.account_id) AS members,
                       COUNT(DISTINCT CASE WHEN sub.id IS NOT NULL THEN m.account_id END) AS completed
                FROM memberships m
                LEFT JOIN reward_evidence e ON e.account_id = m.account_id AND e.window_id = ?
                LEFT JOIN solves s ON s.id = e.solve_id AND s.occurred_at > m.joined_at
                LEFT JOIN submissions sub ON sub.id = s.submission_id AND sub.provider = ?
                WHERE m.group_id = ? AND m.left_at IS NULL
                """).params(window.id(), provider, group).query((row, index) -> new GroupFeatured(window.featured().title(),
                window.start(), window.end(), window.cutoff(), row.getInt("members"), row.getInt("completed"))).single();
    }

    private Task task(long account, RewardWindows.Window window) {
        int observed = jdbc.sql("""
                SELECT COUNT(*) FROM reward_evidence e JOIN solves s ON s.id = e.solve_id
                JOIN submissions sub ON sub.id = s.submission_id
                WHERE e.account_id = ? AND e.window_id = ? AND sub.provider = ?
                """).params(account, window.id(), window.provider()).query(Integer.class).single();
        return new Task(window.kind().name(), window.start(), window.end(), window.cutoff(), window.ruleVersion(),
                window.xp(), window.target(), Math.min(observed, window.target()), window.topic(), window.eligibleProblems());
    }

    private Level level(long xp) {
        List<Threshold> thresholds = jdbc.sql("""
                SELECT level_number, minimum_xp FROM level_thresholds WHERE rule_version = ? ORDER BY minimum_xp
                """).param(RewardWindows.RULE_VERSION)
                .query((row, index) -> new Threshold(row.getInt("level_number"), row.getInt("minimum_xp"))).list();
        Threshold current = thresholds.getFirst();
        for (Threshold next : thresholds.subList(1, thresholds.size())) {
            if (xp < next.minimum()) return new Level(current.number(), current.minimum(), next.minimum(), RewardWindows.RULE_VERSION);
            current = next;
        }
        return new Level(current.number(), current.minimum(), null, RewardWindows.RULE_VERSION);
    }

    private static FeaturedView featured(RewardWindows.Window window, Task task) {
        var problem = window.featured();
        String url = "LEETCODE".equals(window.provider()) ? "https://leetcode.com/problems/" + problem.slug() + "/" : null;
        return new FeaturedView(problem.title(), problem.difficulty(), problem.topics(), url, task);
    }

    private String provider(String mode) {
        if ("live".equals(mode)) return "LEETCODE";
        if ("demo".equals(mode) && fixturesEnabled) return "FIXTURE";
        throw new Failure(HttpStatus.BAD_REQUEST, "invalid_rewards_mode", "Choose an available rewards view.");
    }

    private record Ledger(long total, String lastEntry) {}
    private record Threshold(int number, int minimum) {}
    record Level(int number, int minimumXp, Integer nextXp, int ruleVersion) {}
    record Task(String kind, Instant startsAt, Instant endsAt, Instant cutoffAt, int ruleVersion, int rewardXp,
                int target, int completed, String topic, List<String> eligibleProblemSlugs) {}
    record FeaturedView(String title, String difficulty, List<String> topics, String url, Task task) {}
    record Badge(String code, int ruleVersion, Instant unlockedAt) {}
    record Streak(int currentStreak, int longestStreak) {}
    record RecentSolve(LocalDate date, String slug, String title, String provider) {}
    record Rewards(String mode, long totalXp, String ledgerRevision, Level level, CodingDays.Activity activity,
                   Streak loginStreak, List<RecentSolve> recentSolves, FeaturedView featured, List<Task> quests, List<Badge> badges) {}
    public record GroupFeatured(String title, Instant startsAt, Instant endsAt, Instant cutoffAt, int members, int completed) {}
}
