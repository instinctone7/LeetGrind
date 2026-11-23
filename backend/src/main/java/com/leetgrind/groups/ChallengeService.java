package com.leetgrind.groups;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.leetgrind.catalog.ProblemCatalog.Difficulty;
import com.leetgrind.catalog.ProblemCatalog.Problem;
import com.leetgrind.identity.ActiveAccountGuard;
import com.leetgrind.integration.ProfilePreview;
import com.leetgrind.platform.ApiErrors.Failure;
import com.leetgrind.platform.FixedWindowRateLimiter;
import com.leetgrind.platform.Utc;
import com.leetgrind.progress.ProgressService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChallengeService {
    private static final int RULE_VERSION = 1;
    private static final Duration GRACE = Duration.ofHours(24);
    private static final Duration DUEL_INVITE_WINDOW = Duration.ofHours(24);
    private static final String ELIGIBLE_SOLVES = """
            SELECT cp.account_id, s.id AS solve_id
            FROM challenges c JOIN challenge_participants cp ON cp.challenge_id = c.id
            JOIN solves s ON s.account_id = cp.account_id
            JOIN submissions sub ON sub.id = s.submission_id AND sub.provider = c.provider
            JOIN problems p ON p.id = s.problem_id
            LEFT JOIN outbox_events o ON o.aggregate_id = s.id AND o.event_type = 'SolveCredited'
            WHERE c.id = :id AND cp.enrolled_at IS NOT NULL
              AND s.occurred_at >= c.starts_at AND s.occurred_at < c.ends_at
              AND s.occurred_at >= cp.enrolled_at AND s.recorded_at < c.cutoff_at
              AND (c.kind = 'GROUP' OR (
                  SELECT COUNT(*) FROM challenge_participants opponent
                  WHERE opponent.challenge_id = c.id AND opponent.enrolled_at <= s.occurred_at
              ) = 2)
              AND (c.rule = 'COUNT' OR (c.rule = 'DIFFICULTY' AND s.difficulty = c.difficulty)
                   OR (c.rule = 'PROBLEM' AND c.problem_slug =
                       COALESCE(JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.problemSlug')), p.slug)))
              AND EXISTS (
                  SELECT 1 FROM memberships m WHERE m.group_id = c.group_id AND m.account_id = cp.account_id
                    AND s.occurred_at > m.joined_at AND (m.left_at IS NULL OR s.occurred_at < m.left_at)
              )
            """;
    private static final String DUEL_SOLVES = """
            SELECT p.account_id, MIN(s.occurred_at) AS accepted_at
            FROM challenges c JOIN challenge_participants p ON p.challenge_id = c.id
            JOIN submissions s ON s.account_id = p.account_id AND s.provider = c.provider AND s.outcome = 'ACCEPTED'
            JOIN problems problem ON problem.id = s.problem_id AND problem.slug = c.problem_slug
            WHERE c.id = :id AND p.enrolled_at IS NOT NULL
              AND s.occurred_at >= c.starts_at AND s.occurred_at < c.ends_at AND s.recorded_at < c.cutoff_at
              AND EXISTS (SELECT 1 FROM memberships m WHERE m.group_id = c.group_id AND m.account_id = p.account_id
                          AND s.occurred_at > m.joined_at AND (m.left_at IS NULL OR s.occurred_at < m.left_at))
            GROUP BY p.account_id
            """;
    private final JdbcClient jdbc;
    private final GroupService groups;
    private final GroupStandings standings;
    private final GroupActivity activity;
    private final ActiveAccountGuard accounts;
    private final Clock clock;
    private final ProfilePreview previews;
    private final ProgressService progress;
    private final FixedWindowRateLimiter problemAttempts;

    ChallengeService(JdbcClient jdbc, GroupService groups, GroupStandings standings, GroupActivity activity,
                     ActiveAccountGuard accounts, Clock clock, ProfilePreview previews, ProgressService progress) {
        this.jdbc = jdbc;
        this.groups = groups;
        this.standings = standings;
        this.activity = activity;
        this.accounts = accounts;
        this.clock = clock;
        this.previews = previews;
        this.progress = progress;
        this.problemAttempts = new FixedWindowRateLimiter(clock, 8);
    }

    Problem resolveDuelProblem(long group, long actor, String problemUrl) {
        groups.requireMember(group, actor, false);
        String slug = duelSlug(problemUrl);
        var known = jdbc.sql("""
                SELECT slug, title, difficulty FROM practice_catalog WHERE provider = 'LEETCODE' AND slug = ?
                UNION ALL
                SELECT slug, title, difficulty FROM problems WHERE provider = 'LEETCODE' AND slug = ?
                LIMIT 1
                """).params(slug, slug).query((row, index) -> new Problem(slug, slug, row.getString("title"),
                Difficulty.valueOf(row.getString("difficulty")))).optional();
        Problem problem = known.orElseGet(() -> {
            if (!problemAttempts.allow(Long.toString(actor))) {
                throw new Failure(HttpStatus.TOO_MANY_REQUESTS, "duel_problem_rate_limited", "Wait a minute before checking another problem link.");
            }
            return previews.duelProblem(slug);
        });
        if (problem == null) throw invalid("Could not confirm that LeetCode problem. Check the URL or choose a problem from Practice.");
        return problem;
    }

    private static String duelSlug(String value) {
        try {
            URI url = URI.create(value.strip());
            if ("https".equalsIgnoreCase(url.getScheme()) && ("leetcode.com".equalsIgnoreCase(url.getHost())
                    || "www.leetcode.com".equalsIgnoreCase(url.getHost()))
                    && url.getUserInfo() == null && url.getPort() == -1 && url.getRawPath() != null
                    && url.getRawPath().matches("/problems/[a-z0-9]+(?:-[a-z0-9]+)*(?:/description)?/?")) {
                String slug = url.getRawPath().substring("/problems/".length()).split("/", 2)[0];
                if (slug.length() <= 128) return slug;
            }
        } catch (IllegalArgumentException invalidUrl) {
            // Use the same validation response for malformed and unsupported URLs.
        }
        throw invalid("Use an HTTPS LeetCode problem URL, such as https://leetcode.com/problems/two-sum/.");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View createDuel(long group, long actor, long invitee, Problem problem, int minutes, int stake) {
        if (minutes < 10 || minutes > 120 || !List.of(5, 25, 50, 100).contains(stake)) {
            throw invalid("Choose a duration from 10 to 120 minutes and a listed XP stake.");
        }
        String title = problem.title().length() <= 80 ? problem.title() : problem.title().substring(0, 80).strip();
        return create(group, actor, new Settings(title, Kind.DUEL, Rule.PROBLEM, "live", invitee, problem.slug(),
                null, 1, null, null), new DuelTerms(minutes, stake, problem.title(), problem.difficulty().name()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View create(long group, long actor, Settings settings) {
        if (settings.kind() == Kind.DUEL && !"demo".equals(settings.mode())) {
            throw invalid("Create live duels through the timed duel composer.");
        }
        if (settings.endsAt() == null) throw invalid("Choose a challenge deadline.");
        return create(group, actor, settings, null);
    }

    private View create(long group, long actor, Settings settings, DuelTerms duel) {
        Long invitee = settings.invitee();
        List<Long> locked = lockAccounts(invitee == null ? List.of(actor) : List.of(actor, invitee));
        if (settings.kind() == Kind.GROUP) groups.requireOwner(group, actor, true);
        else groups.requireMember(group, actor, true);
        if (!locked.contains(actor)) throw new Failure(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in to continue.");
        if (invitee != null && (invitee == actor || !locked.contains(invitee) || !groups.isMember(group, invitee))) {
            throw invalid("Choose another current group member to invite.");
        }
        if (settings.kind() == Kind.DUEL && invitee == null) throw invalid("Choose a duel opponent.");
        Instant now = Utc.now(clock);
        if (duel != null) requireBalance(actor, duel.stake());
        StartAndEnd dates = duel == null ? dates(settings.startsAt(), settings.endsAt(), now)
                : new StartAndEnd(now, now.plus(DUEL_INVITE_WINDOW));
        Instant cutoff = duel == null ? dates.end().plus(GRACE) : dates.end();
        int target = target(settings);
        String provider = standings.provider(settings.mode());
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO challenges (group_id, creator_id, title, kind, rule, provider, problem_slug, difficulty,
                    target_count, starts_at, ends_at, cutoff_at, rule_version, created_at, duel_minutes, duel_stake, duel_problem_title)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(group, actor, settings.title().strip(), settings.kind().name(), settings.rule().name(), provider,
                settings.problemSlug(), duel != null ? duel.difficulty() : settings.difficulty() == null ? null : settings.difficulty().name(),
                target, Utc.sql(dates.start()), Utc.sql(dates.end()), Utc.sql(cutoff), duel == null ? RULE_VERSION : 2, Utc.sql(now),
                duel == null ? null : duel.minutes(), duel == null ? null : duel.stake(), duel == null ? null : duel.problemTitle())
                .update(key, "id");
        long id = key.getKey().longValue();
        addParticipant(id, actor, now);
        if (invitee != null) {
            addParticipant(id, invitee, null);
            activity.challengeNotice(invitee, "INVITATION", group, id, now);
        }
        return view(load(group, id), actor, now);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Overview> list(long group, long actor, String mode, long before) {
        groups.requireMember(group, actor, false);
        String provider = standings.provider(mode);
        Instant now = Utc.now(clock);
        return jdbc.sql("""
                SELECT c.*, (SELECT COUNT(*) FROM challenge_participants p
                              WHERE p.challenge_id = c.id AND p.enrolled_at IS NOT NULL) AS enrolled,
                       (SELECT xp_delta FROM challenge_participants p WHERE p.challenge_id = c.id AND p.account_id = :actor) AS xp_delta,
                       (SELECT a.display_name FROM challenge_participants p JOIN accounts a ON a.id = p.account_id
                        WHERE p.challenge_id = c.id AND p.account_id <> :actor LIMIT 1) AS opponent
                FROM challenges c WHERE c.group_id = :group AND c.provider = :provider AND c.id < :before
                  AND c.kind = 'DUEL' AND EXISTS (
                      SELECT 1 FROM challenge_participants p WHERE p.challenge_id = c.id AND p.account_id = :actor)
                ORDER BY c.id DESC LIMIT 30
                """).param("group", group).param("provider", provider).param("actor", actor).param("before", before)
                .query((row, index) -> overview(read(row), row.getInt("enrolled"), now,
                        row.getObject("xp_delta", Integer.class), row.getString("opponent"))).list();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View detail(long group, long actor, long id) {
        groups.requireMember(group, actor, false);
        return view(visible(group, actor, id), actor, Utc.now(clock));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View enroll(long group, long actor, long id) {
        accounts.lock(actor);
        groups.requireMember(group, actor, true);
        Challenge challenge = visible(group, actor, id);
        Instant now = Utc.now(clock);
        boolean enrolled = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM challenge_participants
                               WHERE challenge_id = ? AND account_id = ? AND enrolled_at IS NOT NULL)
                """).params(id, actor).query(Boolean.class).single();
        if (!enrolled) {
            if (challenge.finalized() != null || !now.isBefore(challenge.end())) {
                throw new Failure(HttpStatus.CONFLICT, "enrollment_closed", "Enrollment has closed.");
            }
            if (challenge.duel() != null) {
                requireBalance(actor, challenge.duel().stake());
                Instant end = now.plus(Duration.ofMinutes(challenge.duel().minutes()));
                jdbc.sql("UPDATE challenges SET starts_at = ?, ends_at = ?, cutoff_at = ? WHERE id = ?")
                        .params(Utc.sql(now), Utc.sql(end), Utc.sql(end), id).update();
            }
            addParticipant(id, actor, now);
            challenge = load(group, id);
        }
        return view(challenge, actor, now);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean finalizeNext() {
        Instant now = Utc.now(clock);
        var due = jdbc.sql("""
                SELECT * FROM challenges WHERE finalized_at IS NULL AND cutoff_at <= ? ORDER BY cutoff_at, id LIMIT 1
                """).param(Utc.sql(now)).query((row, index) -> read(row)).optional();
        if (due.isEmpty()) return false;
        Challenge challenge = due.get();
        // Sync and deletion lock accounts first. Wait for any in-flight solve writes before reconciling.
        List<Long> snapshot = participantIds(challenge.id());
        lockAccounts(snapshot);
        boolean open = jdbc.sql("SELECT closed_at IS NULL FROM study_groups WHERE id = ? FOR UPDATE")
                .param(challenge.group()).query(Boolean.class).single();
        Challenge current = load(challenge.group(), challenge.id());
        if (current.finalized() != null || current.cutoff().isAfter(Utc.now(clock))
                || !snapshot.equals(participantIds(current.id()))) return true;
        finalizeChallenge(current, open, Utc.now(clock));
        return true;
    }

    private void finalizeChallenge(Challenge challenge, boolean open, Instant now) {
        if (challenge.duel() != null) {
            finalizeDuel(challenge, open, now);
            return;
        }
        var entrants = participants(challenge.id()).stream().filter(p -> p.enrolled() != null).toList();
        boolean held = open && entrants.size() >= (challenge.kind() == Kind.DUEL ? 2 : 1);
        Map<Long, Integer> totals = Map.of();
        if (held) {
            jdbc.sql("INSERT INTO challenge_evidence (challenge_id, account_id, solve_id) "
                    + "SELECT :id, eligible.account_id, eligible.solve_id FROM (" + ELIGIBLE_SOLVES + ") eligible")
                    .param("id", challenge.id()).update();
            totals = scores(challenge, """
                    SELECT account_id, COUNT(*) AS total FROM challenge_evidence WHERE challenge_id = :id GROUP BY account_id
                    """);
        }
        int best = totals.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        long leaders = totals.values().stream().filter(score -> score == best && best > 0).count();
        String outcome = !held ? "CANCELLED" : best == 0 ? "NO_SCORE" : leaders > 1 ? "TIE" : "WIN";
        for (var entrant : entrants) {
            int score = totals.getOrDefault(entrant.id(), 0);
            jdbc.sql("UPDATE challenge_participants SET final_score = ?, winner = ? WHERE challenge_id = ? AND account_id = ?")
                    .params(score, best > 0 && score == best, challenge.id(), entrant.id()).update();
            if (open) activity.challengeNotice(entrant.id(), "CHALLENGE_RESULT", challenge.group(), challenge.id(), now);
        }
        jdbc.sql("UPDATE challenges SET finalized_at = ?, outcome = ? WHERE id = ?")
                .params(Utc.sql(now), outcome, challenge.id()).update();
    }

    private void finalizeDuel(Challenge challenge, boolean open, Instant now) {
        var entrants = participants(challenge.id()).stream().filter(p -> p.enrolled() != null).toList();
        boolean held = open && entrants.size() == 2;
        Map<Long, Instant> accepted = held ? duelSolves(challenge.id()) : Map.of();
        Instant first = accepted.values().stream().min(Instant::compareTo).orElse(null);
        boolean tie = first != null && accepted.values().stream().allMatch(first::equals) && accepted.size() == 2;
        String outcome = !held ? "CANCELLED" : first == null ? "NO_SCORE" : tie ? "TIE" : "WIN";
        for (Entrant entrant : entrants) {
            boolean winner = held && first != null && first.equals(accepted.get(entrant.id()));
            int delta = !held || tie ? 0 : winner ? challenge.duel().stake() : -challenge.duel().stake();
            jdbc.sql("""
                    UPDATE challenge_participants SET final_score = ?, winner = ?, xp_delta = ?
                    WHERE challenge_id = ? AND account_id = ?
                    """).params(accepted.containsKey(entrant.id()) ? 1 : 0, winner, delta, challenge.id(), entrant.id()).update();
            if (delta != 0) {
                progress.reward(entrant.id(), null, "PERSONAL", "DUEL:" + challenge.id(),
                        delta > 0 ? "DUEL_WIN" : "DUEL_LOSS", delta, challenge.version(), challenge.end(), now);
            }
            if (open) activity.challengeNotice(entrant.id(), "CHALLENGE_RESULT", challenge.group(), challenge.id(), now);
        }
        jdbc.sql("UPDATE challenges SET finalized_at = ?, outcome = ? WHERE id = ?")
                .params(Utc.sql(now), outcome, challenge.id()).update();
    }

    private Map<Long, Instant> duelSolves(long challenge) {
        Map<Long, Instant> accepted = new HashMap<>();
        jdbc.sql(DUEL_SOLVES).param("id", challenge).query((row, index) -> {
            accepted.put(row.getLong("account_id"), GroupService.time(row, "accepted_at"));
            return true;
        }).list();
        return accepted;
    }

    @Transactional(readOnly = true)
    public DuelSummary duelSummary(long group, long actor) {
        groups.requireMember(group, actor, false);
        long balance = availableBalance(actor);
        return jdbc.sql("""
                SELECT COUNT(CASE WHEN p.xp_delta > 0 THEN 1 END) AS wins,
                       COUNT(CASE WHEN p.xp_delta < 0 THEN 1 END) AS losses,
                       COALESCE(SUM(p.xp_delta), 0) AS net
                FROM challenge_participants p JOIN challenges c ON c.id = p.challenge_id
                WHERE c.group_id = ? AND p.account_id = ? AND c.duel_stake IS NOT NULL
                """).params(group, actor).query((row, index) ->
                new DuelSummary(balance, row.getLong("wins"), row.getLong("losses"), row.getLong("net"))).single();
    }

    private long availableBalance(long account) {
        return jdbc.sql("""
                SELECT COALESCE((SELECT SUM(x.amount) FROM xp_entries x
                    WHERE x.account_id = :account AND x.provider IN ('LEETCODE', 'PERSONAL')), 0)
                    - COALESCE((SELECT SUM(c.duel_stake) FROM challenge_participants p JOIN challenges c ON c.id = p.challenge_id
                    WHERE p.account_id = :account AND p.enrolled_at IS NOT NULL AND c.finalized_at IS NULL), 0)
                """).param("account", account).query(Long.class).single();
    }

    private void requireBalance(long account, int stake) {
        if (availableBalance(account) < stake) {
            throw new Failure(HttpStatus.CONFLICT, "insufficient_duel_xp", "You do not have enough available XP for this stake.");
        }
    }

    private View view(Challenge challenge, long actor, Instant now) {
        var entrants = participants(challenge.id());
        Map<Long, Integer> current = new HashMap<>();
        if (challenge.finalized() == null) {
            if (challenge.duel() != null && entrants.stream().allMatch(p -> p.enrolled() != null) && entrants.size() == 2) {
                duelSolves(challenge.id()).keySet().forEach(id -> current.put(id, 1));
            } else if (challenge.duel() == null) {
                current.putAll(scores(challenge, "SELECT account_id, COUNT(*) AS total FROM (" + ELIGIBLE_SOLVES + ") eligible GROUP BY account_id"));
            }
        }
        int enrolled = 0;
        boolean actorEnrolled = false;
        for (var entrant : entrants) {
            if (entrant.enrolled() == null) continue;
            enrolled++;
            actorEnrolled |= entrant.id() == actor;
        }
        var visible = entrants.stream().filter(p -> p.enrolled() != null || challenge.kind() == Kind.DUEL || p.id() == actor)
                .map(p -> new Participant(Long.toString(p.id()), p.handle(), p.displayName(), p.enrolled(),
                        p.finalScore() == null ? current.getOrDefault(p.id(), 0) : p.finalScore(), p.winner(), p.xpDelta())).toList();
        Integer xpDelta = entrants.stream().filter(p -> p.id() == actor).findFirst().map(Entrant::xpDelta).orElse(null);
        String opponent = entrants.stream().filter(p -> p.id() != actor).findFirst().map(Entrant::displayName).orElse(null);
        return new View(overview(challenge, enrolled, now, xpDelta, opponent), visible,
                !actorEnrolled && challenge.finalized() == null && now.isBefore(challenge.end()));
    }

    private static Overview overview(Challenge c, int enrolled, Instant now, Integer xpDelta, String opponent) {
        String status;
        if (c.finalized() != null) status = "FINALIZED";
        else if (!now.isBefore(c.end())) status = "GRACE";
        else if (c.kind() == Kind.DUEL && enrolled < 2) status = "AWAITING_OPPONENT";
        else status = now.isBefore(c.start()) ? "SCHEDULED" : "ACTIVE";
        return new Overview(Long.toString(c.id()), c.duel() == null ? c.title() : c.duel().problemTitle(),
                c.kind(), c.rule(), c.problemSlug(), c.difficulty(), c.target(),
                c.start(), c.end(), c.cutoff(), c.version(), status, enrolled, c.finalized(), c.outcome(),
                "FIXTURE".equals(c.provider()) ? "demo" : "live",
                c.duel() == null ? null : c.duel().minutes(), c.duel() == null ? null : c.duel().stake(),
                c.duel() == null ? null : "https://leetcode.com/problems/" + c.problemSlug() + "/", xpDelta, opponent);
    }

    private Map<Long, Integer> scores(Challenge challenge, String sql) {
        Map<Long, Integer> scores = new HashMap<>();
        jdbc.sql(sql).param("id", challenge.id()).query((row, index) -> {
            scores.put(row.getLong("account_id"), Math.min(row.getInt("total"), challenge.target()));
            return true;
        }).list();
        return scores;
    }

    private void addParticipant(long id, long account, Instant at) {
        jdbc.sql("""
                INSERT INTO challenge_participants (challenge_id, account_id, enrolled_at) VALUES (?, ?, ?) AS incoming
                ON DUPLICATE KEY UPDATE enrolled_at = COALESCE(challenge_participants.enrolled_at, incoming.enrolled_at)
                """).params(id, account, at == null ? null : Utc.sql(at)).update();
    }

    private List<Long> lockAccounts(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT id FROM accounts WHERE id IN (:ids) AND active = TRUE ORDER BY id FOR UPDATE")
                .param("ids", ids).query(Long.class).list();
    }

    private List<Long> participantIds(long id) {
        return jdbc.sql("SELECT account_id FROM challenge_participants WHERE challenge_id = ? ORDER BY account_id")
                .param(id).query(Long.class).list();
    }

    private List<Entrant> participants(long id) {
        return jdbc.sql("""
                SELECT p.*, a.handle, a.display_name FROM challenge_participants p JOIN accounts a ON a.id = p.account_id
                WHERE p.challenge_id = ? ORDER BY p.account_id
                """).param(id).query((row, index) -> new Entrant(row.getLong("account_id"), row.getString("handle"),
                row.getString("display_name"), GroupService.time(row, "enrolled_at"),
                row.getObject("final_score", Integer.class), row.getBoolean("winner"), row.getObject("xp_delta", Integer.class))).list();
    }

    private Challenge visible(long group, long actor, long id) {
        Challenge challenge = load(group, id);
        if (challenge.kind() == Kind.DUEL && !participantIds(id).contains(actor)) throw unavailable();
        return challenge;
    }

    private Challenge load(long group, long id) {
        return jdbc.sql("SELECT * FROM challenges WHERE group_id = ? AND id = ?").params(group, id)
                .query((row, index) -> read(row)).optional().orElseThrow(ChallengeService::unavailable);
    }

    private static Challenge read(ResultSet row) throws SQLException {
        Integer minutes = row.getObject("duel_minutes", Integer.class);
        return new Challenge(row.getLong("id"), row.getLong("group_id"), row.getString("title"),
                Kind.valueOf(row.getString("kind")), Rule.valueOf(row.getString("rule")), row.getString("provider"),
                row.getString("problem_slug"), row.getString("difficulty"), row.getInt("target_count"),
                GroupService.time(row, "starts_at"), GroupService.time(row, "ends_at"), GroupService.time(row, "cutoff_at"),
                row.getInt("rule_version"), GroupService.time(row, "finalized_at"), row.getString("outcome"),
                minutes == null ? null : new DuelTerms(minutes, row.getInt("duel_stake"), row.getString("duel_problem_title"), row.getString("difficulty")));
    }

    private static StartAndEnd dates(Instant requestedStart, Instant requestedEnd, Instant now) {
        Instant start = requestedStart == null ? now : requestedStart.truncatedTo(ChronoUnit.MICROS);
        Instant end = requestedEnd.truncatedTo(ChronoUnit.MICROS);
        if (start.isBefore(now) || start.isAfter(now.plus(Duration.ofDays(90))) || !end.isAfter(start)
                || end.isAfter(start.plus(Duration.ofDays(30)))) {
            throw invalid("Schedule the start within 90 days and set a deadline within 30 days of the start.");
        }
        return new StartAndEnd(start, end);
    }

    private static int target(Settings s) {
        if (s.rule() == Rule.PROBLEM && s.problemSlug() != null && s.difficulty() == null
                && (s.target() == null || s.target() == 1)) return 1;
        if (s.problemSlug() == null && s.target() != null
                && ((s.rule() == Rule.COUNT && s.difficulty() == null) || (s.rule() == Rule.DIFFICULTY && s.difficulty() != null))) {
            return s.target();
        }
        throw invalid("Provide the problem slug or the solve target and difficulty required by this rule.");
    }

    private static Failure invalid(String message) {
        return new Failure(HttpStatus.BAD_REQUEST, "invalid_challenge", message);
    }

    private static Failure unavailable() {
        return new Failure(HttpStatus.NOT_FOUND, "challenge_not_found", "This challenge is unavailable.");
    }

    enum Kind { GROUP, DUEL }
    enum Rule { PROBLEM, COUNT, DIFFICULTY }
    record Settings(String title, Kind kind, Rule rule, String mode, Long invitee, String problemSlug, Difficulty difficulty,
                    Integer target, Instant startsAt, Instant endsAt) {}
    private record StartAndEnd(Instant start, Instant end) {}
    private record DuelTerms(int minutes, int stake, String problemTitle, String difficulty) {}
    private record Challenge(long id, long group, String title, Kind kind, Rule rule, String provider, String problemSlug,
                             String difficulty, int target, Instant start, Instant end, Instant cutoff, int version,
                             Instant finalized, String outcome, DuelTerms duel) {}
    private record Entrant(long id, String handle, String displayName, Instant enrolled, Integer finalScore, boolean winner, Integer xpDelta) {}
    record Overview(String id, String title, Kind kind, Rule rule, String problemSlug, String difficulty, int target,
                    Instant startsAt, Instant endsAt, Instant cutoffAt, int ruleVersion, String status, int enrolled,
                    Instant finalizedAt, String outcome, String mode, Integer durationMinutes, Integer stakeXp,
                    String problemUrl, Integer xpDelta, String opponent) {}
    record Participant(String id, String handle, String displayName, Instant enrolledAt, int score, boolean winner, Integer xpDelta) {}
    record View(Overview challenge, List<Participant> participants, boolean canEnroll) {}
    record DuelSummary(long availableXp, long wins, long losses, long netXp) {}
}
