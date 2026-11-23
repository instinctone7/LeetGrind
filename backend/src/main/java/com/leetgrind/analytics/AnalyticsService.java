package com.leetgrind.analytics;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.leetgrind.catalog.ProblemCatalog.Difficulty;
import com.leetgrind.engagement.CodingDays;
import com.leetgrind.integration.LinkService;
import com.leetgrind.platform.ApiErrors.Failure;
import com.leetgrind.platform.Utc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class AnalyticsService {
    private static final String OBSERVED = """
            SELECT s.difficulty, p.practice_catalog_id
            FROM solves s JOIN submissions sub ON sub.id = s.submission_id JOIN problems p ON p.id = s.problem_id
            WHERE s.account_id = :account AND s.occurred_at <= :now
              AND sub.provider = :provider
              AND (:start IS NULL OR s.occurred_at >= :start) AND (:end IS NULL OR s.occurred_at < :end)
            """;
    private static final String XP_SOURCE = """
            account_id = :account AND (
                provider = :provider OR (:provider = 'LEETCODE' AND provider = 'PERSONAL')
            )
            """;
    private static final Bounds ALL_TIME = new Bounds(null, null);
    private static final Daily INACTIVE = new Daily(0, 0);
    private final JdbcClient jdbc;
    private final LinkService links;
    private final CodingDays codingDays;
    private final Clock clock;
    private final boolean fixturesEnabled;

    AnalyticsService(JdbcClient jdbc, LinkService links, CodingDays codingDays, Clock clock,
                     @Value("${leetgrind.integration.fixtures-enabled}") boolean fixturesEnabled) {
        this.jdbc = jdbc;
        this.links = links;
        this.codingDays = codingDays;
        this.clock = clock;
        this.fixturesEnabled = fixturesEnabled;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Analytics read(long account, String mode, String period, String start) {
        String provider = provider(mode);
        Instant now = Utc.now(clock);
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        Bounds bounds = bounds(period, start, today);
        Source source = new Source(account, provider, Utc.sql(now));
        Breakdown selected = breakdown(source, bounds);
        Breakdown all = bounds.equals(ALL_TIME) ? selected : breakdown(source, ALL_TIME);
        var summary = new Summary(period, bounds.start(), bounds.end(), selected.solved(), selected.unmapped(),
                xp(source, bounds), countCodingDays(source, bounds, today), selected.difficulty(), selected.topics());

        LocalDate currentWeek = weekOf(today);
        LocalDate currentMonth = today.withDayOfMonth(1);
        LocalDate firstWeek = currentWeek.minusWeeks(7);
        LocalDate firstMonth = currentMonth.minusMonths(5);
        LocalDate first = today.minusDays(364);
        Map<LocalDate, Daily> days = activityByDate(source, first);
        long opening = xp(source, new Bounds(null, first));
        var streaks = codingDays.activity(account, provider, today);
        return new Analytics(mode, fixturesEnabled, "UTC", now, links.historyCoverage(account, provider), summary,
                new Streaks(streaks.currentStreak(), streaks.longestStreak()),
                timeline(days, opening, first, firstWeek, currentWeek, false),
                timeline(days, opening, first, firstMonth, currentMonth, true),
                recommendations(source, all), days.entrySet().stream().filter(day -> day.getValue().solved() > 0)
                        .sorted(Map.Entry.comparingByKey()).map(day -> new ActivityDay(day.getKey(), day.getValue().solved())).toList());
    }

    private Breakdown breakdown(Source source, Bounds bounds) {
        List<DifficultyTotal> counts = bind(jdbc.sql("""
                WITH observed AS (%s)
                SELECT difficulty, COUNT(*) AS solved, SUM(CASE WHEN NOT EXISTS (
                    SELECT 1 FROM practice_catalog_topics t WHERE t.catalog_id = observed.practice_catalog_id
                ) THEN 1 ELSE 0 END) AS unmapped
                FROM observed GROUP BY difficulty
                """.formatted(OBSERVED)), source, bounds).query((row, index) -> new DifficultyTotal(Difficulty.valueOf(row.getString("difficulty")),
                        row.getLong("solved"), row.getLong("unmapped"))).list();
        List<TopicTotal> topics = bind(jdbc.sql("""
                WITH observed AS (%s)
                SELECT t.topic, COUNT(*) AS solved FROM observed o JOIN practice_catalog_topics t ON t.catalog_id = o.practice_catalog_id
                GROUP BY t.topic ORDER BY solved DESC, t.topic
                """.formatted(OBSERVED)), source, bounds)
                .query((row, index) -> new TopicTotal(row.getString("topic"), row.getLong("solved"))).list();
        var byDifficulty = new EnumMap<Difficulty, DifficultyTotal>(Difficulty.class);
        for (Difficulty difficulty : Difficulty.values()) byDifficulty.put(difficulty, new DifficultyTotal(difficulty, 0, 0));
        for (var count : counts) byDifficulty.put(count.difficulty(), count);
        return new Breakdown(counts.stream().mapToLong(DifficultyTotal::solved).sum(),
                counts.stream().mapToLong(DifficultyTotal::unmapped).sum(), List.copyOf(byDifficulty.values()), topics);
    }

    private long xp(Source source, Bounds bounds) {
        return bind(jdbc.sql("""
                SELECT COALESCE(SUM(amount), 0) FROM xp_entries
                WHERE %s AND effective_at <= :now
                  AND (:start IS NULL OR effective_at >= :start) AND (:end IS NULL OR effective_at < :end)
                """.formatted(XP_SOURCE)), source, bounds).query(Long.class).single();
    }

    private long countCodingDays(Source source, Bounds bounds, LocalDate today) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM coding_days WHERE account_id = :account AND coding_date <= :today
                AND provider = :provider
                AND (:start IS NULL OR coding_date >= :start) AND (:end IS NULL OR coding_date < :end)
                """).param("account", source.account()).param("provider", source.provider()).param("today", today)
                .param("start", bounds.start()).param("end", bounds.end()).query(Long.class).single();
    }

    private Map<LocalDate, Daily> activityByDate(Source source, LocalDate first) {
        return jdbc.sql("""
                SELECT day, SUM(solved) AS solved, SUM(earned) AS earned FROM (
                    SELECT DATE(s.occurred_at) AS day, COUNT(*) AS solved, 0 AS earned
                    FROM solves s JOIN submissions sub ON sub.id = s.submission_id
                    WHERE s.account_id = :account
                      AND sub.provider = :provider
                      AND s.occurred_at >= :first AND s.occurred_at <= :now
                    GROUP BY DATE(s.occurred_at)
                    UNION ALL
                    SELECT DATE(effective_at), 0, SUM(amount) FROM xp_entries
                    WHERE %s AND effective_at >= :first AND effective_at <= :now
                    GROUP BY DATE(effective_at)
                ) observed GROUP BY day
                """.formatted(XP_SOURCE)).param("account", source.account()).param("provider", source.provider())
                .param("first", first.atStartOfDay()).param("now", source.now())
                .query((row, index) -> Map.entry(row.getObject("day", LocalDate.class), new Daily(row.getLong("solved"),
                        row.getLong("earned")))).list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    List<CatalogProblem> practiceCatalog(long account, String mode) {
        String provider = provider(mode);
        var source = new Source(account, provider, Utc.sql(Utc.now(clock)));
        var entries = bind(jdbc.sql("""
                WITH observed AS (%s)
                SELECT c.slug, c.title, c.difficulty, t.topic,
                    EXISTS (SELECT 1 FROM observed WHERE practice_catalog_id = c.id) AS solved
                FROM practice_catalog c LEFT JOIN practice_catalog_topics t ON t.catalog_id = c.id
                WHERE c.provider = :provider
                ORDER BY c.title, c.slug, t.topic
                """.formatted(OBSERVED)), source, ALL_TIME)
                .query((row, index) -> new CatalogProblem(row.getString("slug"), row.getString("title"),
                        Difficulty.valueOf(row.getString("difficulty")), row.getBoolean("solved"),
                        row.getString("topic") == null ? List.of() : List.of(row.getString("topic"))))
                .list().stream().collect(Collectors.groupingBy(CatalogProblem::slug, LinkedHashMap::new, Collectors.toList()));
        return entries.values().stream().map(rows -> {
            var first = rows.getFirst();
            return new CatalogProblem(first.slug(), first.title(), first.difficulty(), first.solved(),
                    rows.stream().flatMap(row -> row.topics().stream()).toList());
        }).toList();
    }

    private static List<Bucket> timeline(Map<LocalDate, Daily> days, long opening, LocalDate first, LocalDate from,
                                         LocalDate current, boolean monthly) {
        long total = opening;
        for (LocalDate date = first; date.isBefore(from); date = date.plusDays(1)) {
            total += days.getOrDefault(date, INACTIVE).xp();
        }
        var buckets = new ArrayList<Bucket>();
        for (LocalDate start = from; !start.isAfter(current); start = next(start, monthly)) {
            LocalDate end = next(start, monthly);
            long earned = 0;
            long solved = 0;
            int active = 0;
            for (LocalDate date = start; date.isBefore(end); date = date.plusDays(1)) {
                var day = days.getOrDefault(date, INACTIVE);
                earned += day.xp();
                solved += day.solved();
                if (day.solved() > 0) active++;
            }
            total += earned;
            buckets.add(new Bucket(start, end, solved, active, earned, total));
        }
        return List.copyOf(buckets);
    }

    private List<Recommendation> recommendations(Source source, Breakdown all) {
        Map<String, Long> practice = all.topics().stream().collect(Collectors.toMap(TopicTotal::topic, TopicTotal::solved));
        long mapped = all.solved() - all.unmapped();
        boolean starter = mapped < 3;
        Difficulty preferred = preferredDifficulty(all, mapped);
        var rows = jdbc.sql("""
                SELECT c.id, c.slug, c.title, c.difficulty, t.topic
                FROM practice_catalog c JOIN practice_catalog_topics t ON t.catalog_id = c.id
                WHERE c.provider = :provider AND NOT EXISTS (
                    SELECT 1 FROM problems p JOIN solves s ON s.problem_id = p.id
                    JOIN submissions sub ON sub.id = s.submission_id
                    WHERE p.practice_catalog_id = c.id AND sub.provider = c.provider
                      AND s.account_id = :account AND s.occurred_at <= :now
                )
                ORDER BY c.id, t.topic
                """).param("account", source.account()).param("provider", source.provider()).param("now", source.now())
                .query((row, index) -> new CandidateTopic(row.getLong("id"), row.getString("slug"), row.getString("title"),
                        Difficulty.valueOf(row.getString("difficulty")), row.getString("topic"))).list()
                .stream().collect(Collectors.groupingBy(CandidateTopic::id));
        Comparator<String> topicPriority = Comparator.comparingLong((String topic) -> practice.getOrDefault(topic, 0L))
                .thenComparing(Comparator.naturalOrder());
        return rows.values().stream().map(candidate -> suggestion(candidate, topicPriority, practice, starter, source.provider()))
                .sorted(Comparator.comparingInt((Recommendation item) -> Math.abs(item.difficulty().ordinal() - preferred.ordinal()))
                        .thenComparingLong(Recommendation::recordedInFocusTopic).thenComparing(Recommendation::slug))
                .limit(5).toList();
    }

    private static Recommendation suggestion(List<CandidateTopic> items, Comparator<String> priority, Map<String, Long> practice,
                                             boolean starter, String provider) {
        var first = items.getFirst();
        var topics = items.stream().map(CandidateTopic::topic).toList();
        String focus = topics.stream().min(priority).orElseThrow();
        long count = practice.getOrDefault(focus, 0L);
        String reason = starter
                ? "Start with " + focus + ". Your record contains fewer than three mapped problems."
                : "Practice " + focus + ", with " + count + (count == 1 ? " recorded solve" : " recorded solves") + " in this topic.";
        String url = "LEETCODE".equals(provider) ? "https://leetcode.com/problems/" + first.slug() + "/" : null;
        return new Recommendation(Long.toString(first.id()), first.slug(), first.title(), first.difficulty(), topics, focus, count, reason, url);
    }

    private static Difficulty preferredDifficulty(Breakdown all, long mapped) {
        if (mapped < 3) return Difficulty.EASY;
        long middle = (mapped + 1) / 2;
        for (DifficultyTotal row : all.difficulty()) {
            middle -= row.solved() - row.unmapped();
            if (middle <= 0) return row.difficulty();
        }
        return Difficulty.EASY;
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec sql, Source source, Bounds bounds) {
        return sql.param("account", source.account()).param("provider", source.provider()).param("now", source.now())
                .param("start", bounds.start() == null ? null : bounds.start().atStartOfDay())
                .param("end", bounds.end() == null ? null : bounds.end().atStartOfDay());
    }

    private static Bounds bounds(String period, String requestedStart, LocalDate today) {
        if ("all-time".equals(period) && requestedStart == null) return ALL_TIME;
        boolean monthly = "monthly".equals(period);
        if (!monthly && !"weekly".equals(period)) throw invalidPeriod();
        LocalDate start = monthly ? today.withDayOfMonth(1) : weekOf(today);
        if (requestedStart != null) {
            try {
                start = LocalDate.parse(requestedStart);
            } catch (DateTimeParseException invalid) {
                throw invalidPeriod();
            }
            if (start.isAfter(today) || start.getYear() < 1000 || start.getYear() >= 9999
                    || (monthly ? start.getDayOfMonth() != 1 : start.getDayOfWeek() != DayOfWeek.MONDAY)) throw invalidPeriod();
        }
        return new Bounds(start, next(start, monthly));
    }

    private static LocalDate weekOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static LocalDate next(LocalDate start, boolean monthly) {
        return monthly ? start.plusMonths(1) : start.plusWeeks(1);
    }

    private String provider(String mode) {
        if ("live".equals(mode)) return "LEETCODE";
        if ("demo".equals(mode) && fixturesEnabled) return "FIXTURE";
        throw new Failure(HttpStatus.BAD_REQUEST, "invalid_analytics_mode", "Choose an available analytics view.");
    }

    private static Failure invalidPeriod() {
        return new Failure(HttpStatus.BAD_REQUEST, "invalid_analytics_period",
                "Choose all time, a Monday for a weekly view, or the first day of a month for a monthly view.");
    }

    private record Source(long account, String provider, LocalDateTime now) {}
    private record Bounds(LocalDate start, LocalDate end) {}
    private record Daily(long solved, long xp) {}
    record ActivityDay(LocalDate date, long solves) {}
    record CatalogProblem(String slug, String title, Difficulty difficulty, boolean solved, List<String> topics) {}
    private record Breakdown(long solved, long unmapped, List<DifficultyTotal> difficulty, List<TopicTotal> topics) {}
    private record CandidateTopic(long id, String slug, String title, Difficulty difficulty, String topic) {}
    record DifficultyTotal(Difficulty difficulty, long solved, long unmapped) {}
    record TopicTotal(String topic, long solved) {}
    record Summary(String period, LocalDate start, LocalDate end, long solved, long unmapped, long xpEarned, long codingDays,
                   List<DifficultyTotal> byDifficulty, List<TopicTotal> topics) {}
    record Streaks(int current, int longest) {}
    record Bucket(LocalDate start, LocalDate end, long solved, int codingDays, long xpEarned, long totalXp) {}
    record Recommendation(String id, String slug, String title, Difficulty difficulty, List<String> topics, String focusTopic,
                          long recordedInFocusTopic, String reason, String url) {}
    record Analytics(String mode, boolean demoAvailable, String timezone, Instant generatedAt, LinkService.HistoryCoverage coverage,
                     Summary summary, Streaks streaks, List<Bucket> weekly, List<Bucket> monthly, List<Recommendation> recommendations,
                     List<ActivityDay> dailyActivity) {}
}
