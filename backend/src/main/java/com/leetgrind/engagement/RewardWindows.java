package com.leetgrind.engagement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import com.leetgrind.platform.Utc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class RewardWindows {
    static final int RULE_VERSION = 1;
    static final String ARRAY = "Array";
    private static final Featured DEMO = new Featured("fixture-array-window", "Array Window", "MEDIUM",
            List.of(ARRAY, "Sliding Window"));
    private static final List<Featured> LIVE = List.of(
            new Featured("two-sum", "Two Sum", "EASY", List.of(ARRAY, "Hash Table")),
            new Featured("binary-search", "Binary Search", "EASY", List.of(ARRAY, "Binary Search")),
            new Featured("best-time-to-buy-and-sell-stock", "Best Time to Buy and Sell Stock", "EASY",
                    List.of(ARRAY, "Dynamic Programming")));

    private final JdbcClient jdbc;

    RewardWindows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Window> forDay(String provider, LocalDate date) {
        return List.of(window(provider, Kind.FEATURED, date), window(provider, Kind.DAILY_QUEST, date),
                window(provider, Kind.WEEKLY_QUEST, date));
    }

    Window window(String provider, Kind kind, LocalDate date) {
        LocalDate first = kind == Kind.WEEKLY_QUEST ? date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : date;
        LocalDateTime start = first.atStartOfDay();
        var read = jdbc.sql("SELECT * FROM reward_windows WHERE provider = ? AND kind = ? AND starts_at = ?")
                .params(provider, kind.name(), start).query((row, index) -> from(row));
        var known = read.optional();
        if (known.isPresent()) return known.get();

        LocalDateTime end = first.plusDays(kind == Kind.WEEKLY_QUEST ? 7 : 1).atStartOfDay();
        Featured featured = kind == Kind.FEATURED ? featured(provider, first) : null;
        String topic = kind == Kind.DAILY_QUEST ? ARRAY : null;
        jdbc.sql("""
                INSERT INTO reward_windows (provider, kind, starts_at, ends_at, cutoff_at, rule_version, reward_xp,
                    target_count, topic, eligible_problem_slugs, problem_slug, problem_title, problem_difficulty, problem_topics)
                VALUES (:provider, :kind, :start, :end, :cutoff, :version, :xp, :target,
                    :topic, :eligible, :slug, :title, :difficulty, :topics)
                ON DUPLICATE KEY UPDATE id = reward_windows.id
                """).param("provider", provider).param("kind", kind.name()).param("start", start)
                .param("end", end).param("cutoff", end.plusDays(1)).param("version", RULE_VERSION)
                .param("xp", kind.xp).param("target", kind.target)
                .param("topic", topic).param("eligible", topic == null ? null : eligibleProblems(provider, topic))
                .param("slug", featured == null ? null : featured.slug())
                .param("title", featured == null ? null : featured.title())
                .param("difficulty", featured == null ? null : featured.difficulty())
                .param("topics", featured == null ? null : String.join(",", featured.topics())).update();
        return read.single();
    }

    private static Window from(ResultSet row) throws SQLException {
        String topics = row.getString("problem_topics");
        String eligible = row.getString("eligible_problem_slugs");
        Featured featured = topics == null ? null : new Featured(row.getString("problem_slug"), row.getString("problem_title"),
                row.getString("problem_difficulty"), List.of(topics.split(",")));
        return new Window(row.getLong("id"), row.getString("provider"), Kind.valueOf(row.getString("kind")),
                time(row, "starts_at"), time(row, "ends_at"), time(row, "cutoff_at"), row.getInt("rule_version"),
                row.getInt("reward_xp"), row.getInt("target_count"), row.getString("topic"),
                eligible == null || eligible.isEmpty() ? List.of() : List.of(eligible.split(",")), featured);
    }

    private static Featured featured(String provider, LocalDate date) {
        return "FIXTURE".equals(provider) ? DEMO : LIVE.get(Math.floorMod(date.toEpochDay(), LIVE.size()));
    }

    private static String eligibleProblems(String provider, String topic) {
        var catalog = "FIXTURE".equals(provider) ? List.of(DEMO) : LIVE;
        return String.join(",", catalog.stream()
                .filter(problem -> problem.topics().contains(topic)).map(Featured::slug).toList());
    }

    static Instant time(ResultSet row, String name) throws SQLException {
        return Utc.instant(row.getObject(name, LocalDateTime.class));
    }

    static LocalDate day(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    enum Kind {
        FEATURED(50, 1), DAILY_QUEST(30, 1), WEEKLY_QUEST(100, 3);
        final int xp;
        final int target;
        Kind(int xp, int target) { this.xp = xp; this.target = target; }
    }

    record Featured(String slug, String title, String difficulty, List<String> topics) {}
    record Window(long id, String provider, Kind kind, Instant start, Instant end, Instant cutoff, int ruleVersion,
                  int xp, int target, String topic, List<String> eligibleProblems, Featured featured) {
        boolean matches(String slug, Instant occurredAt, Instant recordedAt) {
            if (occurredAt.isBefore(start) || !occurredAt.isBefore(end) || !recordedAt.isBefore(cutoff)) return false;
            return switch (kind) {
                case FEATURED -> featured.slug().equals(slug);
                case DAILY_QUEST -> eligibleProblems.contains(slug);
                case WEEKLY_QUEST -> true;
            };
        }

        String key() { return kind.name() + ":" + id; }
    }
}
