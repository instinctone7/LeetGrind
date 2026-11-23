package com.leetgrind.groups;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.Base64;
import java.util.List;

import com.leetgrind.engagement.EngagementService;
import com.leetgrind.platform.ApiErrors.Failure;
import com.leetgrind.platform.OperationsMetrics;
import com.leetgrind.platform.Utc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class GroupStandings {
    private static final String ELIGIBLE_MEMBERSHIP_XP = """
            x.account_id = m.account_id AND x.effective_at > m.joined_at
            AND (m.left_at IS NULL OR x.effective_at < m.left_at)
            """;
    private static final String LIVE_XP = "x.provider IN ('LEETCODE', 'PERSONAL')";
    private final JdbcClient jdbc;
    private final GroupService groups;
    private final Clock clock;
    private final OperationsMetrics metrics;
    private final boolean fixturesEnabled;
    private final EngagementService engagement;

    GroupStandings(JdbcClient jdbc, GroupService groups, Clock clock, OperationsMetrics metrics, EngagementService engagement,
                   @Value("${leetgrind.integration.fixtures-enabled}") boolean fixturesEnabled) {
        this.jdbc = jdbc;
        this.groups = groups;
        this.clock = clock;
        this.metrics = metrics;
        this.engagement = engagement;
        this.fixturesEnabled = fixturesEnabled;
    }

    boolean demoAvailable() {
        return fixturesEnabled;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Page page(long groupId, long actor, String period, String week, String month, String mode, int limit, String cursor) {
        groups.requireMember(groupId, actor, false);
        String xpFilter = "FIXTURE".equals(provider(mode))
                ? "x.provider = 'FIXTURE' AND x.reason = 'BASE_SOLVE'" : LIVE_XP;
        return rankedPage(groupId, Long.toString(groupId), period, week, month, mode, limit, cursor, true, """
                SELECT x.account_id, SUM(x.amount) AS xp
                FROM memberships m JOIN xp_entries x ON %s
                WHERE m.group_id = :scopeId AND %s
                """.formatted(ELIGIBLE_MEMBERSHIP_XP, xpFilter), """
                SELECT active_account_id AS account_id FROM memberships
                WHERE group_id = :scopeId AND active_account_id IS NOT NULL
                """);
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Page individualPage(long actor, String period, String week, String month, int limit, String cursor) {
        return rankedPage(actor, "individual:" + actor, period, week, month, "live", limit, cursor, false, """
                SELECT x.account_id, SUM(x.amount) AS xp
                FROM candidates c JOIN xp_entries x ON x.account_id = c.account_id
                WHERE %s
                """.formatted(LIVE_XP), """
                SELECT :scopeId AS account_id
                UNION
                SELECT peer.active_account_id
                FROM memberships mine
                JOIN study_groups g ON g.id = mine.group_id AND g.closed_at IS NULL
                JOIN memberships peer ON peer.group_id = g.id AND peer.active_account_id IS NOT NULL
                WHERE mine.active_account_id = :scopeId
                """);
    }

    private Page rankedPage(long scopeId, String scopeKey, String period, String week, String month, String mode,
                            int limit, String cursor, boolean includeFormerMembers, String points, String candidates) {
        if (limit < 1 || limit > 100) {
            throw badPage();
        }

        Instant generated = Utc.now(clock);
        Window window = window(period, week, month, generated);
        String scope = scopeKey + ":" + window.key() + ":" + mode + ":";
        Position after = decode(scope, cursor);
        String periodFilter = window.start() == null ? "" : "AND x.effective_at >= :start AND x.effective_at < :end";
        var query = jdbc.sql("""
                WITH candidates AS (
                    %s
                ), points AS (
                    %s
                    %s
                    GROUP BY x.account_id
                ), entrants AS (
                    SELECT account_id FROM candidates
                    %s
                ), totals AS (
                    SELECT e.account_id, COALESCE(p.xp, 0) AS xp
                    FROM entrants e LEFT JOIN points p USING (account_id)
                ), ranked AS (
                    SELECT account_id, xp, DENSE_RANK() OVER (ORDER BY xp DESC) AS place
                    FROM totals
                )
                SELECT r.account_id, r.xp, r.place, a.handle, a.display_name
                FROM ranked r JOIN accounts a ON a.id = r.account_id
                WHERE :firstPage OR r.xp < :afterXp OR (r.xp = :afterXp AND r.account_id > :afterId)
                ORDER BY r.xp DESC, r.account_id ASC LIMIT :limit
                """.formatted(candidates, points, periodFilter, includeFormerMembers ? "UNION SELECT account_id FROM points" : ""))
                .param("scopeId", scopeId)
                .param("firstPage", after == null).param("afterXp", after == null ? 0 : after.xp())
                .param("afterId", after == null ? 0 : after.accountId()).param("limit", limit + 1);
        if (window.start() != null) query.param("start", Utc.sql(window.start())).param("end", Utc.sql(window.end()));
        List<Row> rows = metrics.leaderboardQuery(() -> query.query((row, index) ->
                new Row(Long.toString(row.getLong("account_id")), row.getString("handle"),
                        row.getString("display_name"), row.getLong("place"), row.getLong("xp"))).list());

        String next = null;
        if (rows.size() > limit) {
            Row last = rows.get(limit - 1);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (scope + last.xp() + ":" + last.id()).getBytes(StandardCharsets.US_ASCII));
            rows = rows.subList(0, limit);
        }
        return new Page(period, mode, window.start(), window.end(), "UTC", generated, rows, next);
    }

    List<DirectoryMetrics> directoryMetrics(long actor) {
        Window week = window("weekly", null, null, Utc.now(clock));
        return jdbc.sql("""
                WITH joined_groups AS (
                    SELECT g.id FROM memberships viewer JOIN study_groups g ON g.id = viewer.group_id
                    WHERE viewer.active_account_id = :actor AND g.closed_at IS NULL
                ), roster AS (
                    SELECT m.group_id, a.display_name,
                           ROW_NUMBER() OVER (PARTITION BY m.group_id ORDER BY a.id) AS member_order
                    FROM joined_groups g JOIN memberships m ON m.group_id = g.id
                    JOIN accounts a ON a.id = m.active_account_id
                ), members AS (
                    SELECT group_id, COUNT(*) AS member_count,
                           GROUP_CONCAT(CASE WHEN member_order <= 3 THEN LEFT(display_name, 1) END
                               ORDER BY member_order SEPARATOR '') AS member_initials
                    FROM roster GROUP BY group_id
                ), scores AS (
                    SELECT m.group_id, SUM(x.amount) AS weekly_xp,
                           COUNT(CASE WHEN x.provider = 'LEETCODE' AND x.reason = 'BASE_SOLVE' THEN 1 END) AS weekly_solves,
                           MAX(CASE WHEN x.provider = 'LEETCODE' AND x.reason = 'BASE_SOLVE' THEN x.effective_at END) AS last_solve,
                           SUM(CASE WHEN m.account_id = :actor THEN x.amount ELSE 0 END) AS personal_xp
                    FROM joined_groups g JOIN memberships m ON m.group_id = g.id
                    JOIN xp_entries x ON %s
                    WHERE %s
                      AND x.effective_at >= :start AND x.effective_at < :end
                    GROUP BY m.group_id
                )
                SELECT g.id, members.member_count, members.member_initials,
                       COALESCE(scores.weekly_xp, 0) AS weekly_xp, COALESCE(scores.weekly_solves, 0) AS weekly_solves,
                       COALESCE(scores.personal_xp, 0) AS personal_xp, scores.last_solve
                FROM joined_groups g JOIN members ON members.group_id = g.id LEFT JOIN scores ON scores.group_id = g.id
                """.formatted(ELIGIBLE_MEMBERSHIP_XP, LIVE_XP)).param("actor", actor)
                .param("start", Utc.sql(week.start())).param("end", Utc.sql(week.end())).query((row, index) ->
                    new DirectoryMetrics(row.getString("id"), row.getLong("weekly_xp"), row.getLong("weekly_solves"), row.getLong("personal_xp"),
                            row.getLong("member_count"), row.getString("member_initials"), GroupService.time(row, "last_solve"))).list();
    }

    String provider(String mode) {
        if ("live".equals(mode)) return "LEETCODE";
        if ("demo".equals(mode) && fixturesEnabled) return "FIXTURE";
        throw new Failure(HttpStatus.BAD_REQUEST, "invalid_standings", "Choose an available standings view.");
    }

    private static Window window(String period, String week, String month, Instant now) {
        if ("weekly".equals(period) && month == null) {
            LocalDate start = monday(week, now);
            return new Window(start.toString(), utc(start), utc(start.plusWeeks(1)));
        }
        if ("monthly".equals(period) && week == null) {
            YearMonth selected = month(month, now);
            return new Window(selected.toString(), utc(selected.atDay(1)), utc(selected.plusMonths(1).atDay(1)));
        }
        if ("all-time".equals(period) && week == null && month == null) return new Window("all-time", null, null);
        throw new Failure(HttpStatus.BAD_REQUEST, "invalid_standings", "Choose weekly, monthly, or all-time standings with the matching date filter.");
    }

    private static YearMonth month(String value, Instant now) {
        if (value == null) return YearMonth.from(now.atOffset(ZoneOffset.UTC));
        try {
            YearMonth month = YearMonth.parse(value);
            if (value.equals(month.toString()) && month.getYear() >= 1000 && month.getYear() < 9999) return month;
        } catch (DateTimeParseException ignored) {
            // Invalid months share the same response.
        }
        throw new Failure(HttpStatus.BAD_REQUEST, "invalid_month", "Choose a month in YYYY-MM format.");
    }

    private static Instant utc(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EngagementService.GroupFeatured featured(long groupId, long actor, String mode) {
        groups.requireMember(groupId, actor, false);
        return engagement.groupFeatured(groupId, mode);
    }

    private static LocalDate monday(String value, Instant now) {
        if (value == null) {
            return now.atOffset(ZoneOffset.UTC).toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }
        try {
            LocalDate day = LocalDate.parse(value);
            if (day.getYear() >= 1000 && day.getYear() < 9999 && day.getDayOfWeek() == DayOfWeek.MONDAY) {
                return day;
            }
        } catch (DateTimeParseException ignored) {
            // Use the same error for any invalid week.
        }
        throw new Failure(HttpStatus.BAD_REQUEST, "invalid_week", "Choose a Monday in YYYY-MM-DD format.");
    }

    private static Position decode(String scope, String cursor) {
        if (cursor == null) return null;
        if (cursor.length() > 128) throw badPage();
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            if (decoded.startsWith(scope)) {
                String[] position = decoded.substring(scope.length()).split(":", -1);
                if (position.length == 2) {
                    long xp = Long.parseLong(position[0]);
                    long account = Long.parseLong(position[1]);
                    if (account > 0) return new Position(xp, account);
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Malformed and wrong-scope cursors use the same error.
        }
        throw badPage();
    }

    private static Failure badPage() {
        return new Failure(HttpStatus.BAD_REQUEST, "invalid_pagination", "Use a cursor for this leaderboard and period.");
    }

    private record Window(String key, Instant start, Instant end) {}
    private record Position(long xp, long accountId) {}
    record Page(String period, String mode, Instant periodStart, Instant periodEnd, String timezone,
                Instant generatedAt, List<Row> items, String nextCursor) {}
    record Row(String id, String handle, String displayName, long rank, long xp) {}
    record DirectoryMetrics(String id, long weeklyXp, long weeklySolves, long personalXp,
                            long memberCount, String memberInitials, Instant lastSolveAt) {}
}
