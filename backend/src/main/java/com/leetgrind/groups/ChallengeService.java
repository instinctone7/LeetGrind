package com.leetgrind.groups;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.leetgrind.catalog.ProblemCatalog.Difficulty;
import com.leetgrind.identity.ActiveAccountGuard;
import com.leetgrind.platform.ApiErrors.Failure;
import com.leetgrind.platform.Utc;
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
    private final JdbcClient jdbc;
    private final GroupService groups;
    private final GroupStandings standings;
    private final GroupActivity activity;
    private final ActiveAccountGuard accounts;
    private final Clock clock;

    ChallengeService(JdbcClient jdbc, GroupService groups, GroupStandings standings, GroupActivity activity,
                     ActiveAccountGuard accounts, Clock clock) {
        this.jdbc = jdbc;
        this.groups = groups;
        this.standings = standings;
        this.activity = activity;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View create(long group, long actor, Settings settings) {
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
        StartAndEnd dates = dates(settings.startsAt(), settings.endsAt(), now);
        int target = target(settings);
        String provider = standings.provider(settings.mode());
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO challenges (group_id, creator_id, title, kind, rule, provider, problem_slug, difficulty,
                    target_count, starts_at, ends_at, cutoff_at, rule_version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(group, actor, settings.title().strip(), settings.kind().name(), settings.rule().name(), provider,
                settings.problemSlug(), settings.difficulty() == null ? null : settings.difficulty().name(),
                target, Utc.sql(dates.start()), Utc.sql(dates.end()), Utc.sql(dates.end().plus(GRACE)), RULE_VERSION, Utc.sql(now))
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
                              WHERE p.challenge_id = c.id AND p.enrolled_at IS NOT NULL) AS enrolled
                FROM challenges c WHERE c.group_id = :group AND c.provider = :provider AND c.id < :before
                  AND (c.kind = 'GROUP' OR EXISTS (
                      SELECT 1 FROM challenge_participants p WHERE p.challenge_id = c.id AND p.account_id = :actor))
                ORDER BY c.id DESC LIMIT 30
                """).param("group", group).param("provider", provider).param("actor", actor).param("before", before)
                .query((row, index) -> overview(read(row), row.getInt("enrolled"), now)).list();
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
            addParticipant(id, actor, now);
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
        if (current.finalized() != null || !snapshot.equals(participantIds(current.id()))) return true;
        finalizeChallenge(current, open, Utc.now(clock));
        return true;
    }

    private void finalizeChallenge(Challenge challenge, boolean open, Instant now) {
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

    private View view(Challenge challenge, long actor, Instant now) {
        var entrants = participants(challenge.id());
        Map<Long, Integer> current = challenge.finalized() != null ? Map.of() : scores(challenge,
                "SELECT account_id, COUNT(*) AS total FROM (" + ELIGIBLE_SOLVES + ") eligible GROUP BY account_id");
        int enrolled = 0;
        boolean actorEnrolled = false;
        for (var entrant : entrants) {
            if (entrant.enrolled() == null) continue;
            enrolled++;
            actorEnrolled |= entrant.id() == actor;
        }
        var visible = entrants.stream().filter(p -> p.enrolled() != null || challenge.kind() == Kind.DUEL || p.id() == actor)
                .map(p -> new Participant(Long.toString(p.id()), p.handle(), p.displayName(), p.enrolled(),
                        p.finalScore() == null ? current.getOrDefault(p.id(), 0) : p.finalScore(), p.winner())).toList();
        return new View(overview(challenge, enrolled, now), visible,
                !actorEnrolled && challenge.finalized() == null && now.isBefore(challenge.end()));
    }

    private static Overview overview(Challenge c, int enrolled, Instant now) {
        String status;
        if (c.finalized() != null) status = "FINALIZED";
        else if (!now.isBefore(c.end())) status = "GRACE";
        else if (c.kind() == Kind.DUEL && enrolled < 2) status = "AWAITING_OPPONENT";
        else status = now.isBefore(c.start()) ? "SCHEDULED" : "ACTIVE";
        return new Overview(Long.toString(c.id()), c.title(), c.kind(), c.rule(), c.problemSlug(), c.difficulty(), c.target(),
                c.start(), c.end(), c.cutoff(), c.version(), status, enrolled, c.finalized(), c.outcome(),
                "FIXTURE".equals(c.provider()) ? "demo" : "live");
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
                row.getObject("final_score", Integer.class), row.getBoolean("winner"))).list();
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
        return new Challenge(row.getLong("id"), row.getLong("group_id"), row.getString("title"),
                Kind.valueOf(row.getString("kind")), Rule.valueOf(row.getString("rule")), row.getString("provider"),
                row.getString("problem_slug"), row.getString("difficulty"), row.getInt("target_count"),
                GroupService.time(row, "starts_at"), GroupService.time(row, "ends_at"), GroupService.time(row, "cutoff_at"),
                row.getInt("rule_version"), GroupService.time(row, "finalized_at"), row.getString("outcome"));
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
    private record Challenge(long id, long group, String title, Kind kind, Rule rule, String provider, String problemSlug,
                             String difficulty, int target, Instant start, Instant end, Instant cutoff, int version,
                             Instant finalized, String outcome) {}
    private record Entrant(long id, String handle, String displayName, Instant enrolled, Integer finalScore, boolean winner) {}
    record Overview(String id, String title, Kind kind, Rule rule, String problemSlug, String difficulty, int target,
                    Instant startsAt, Instant endsAt, Instant cutoffAt, int ruleVersion, String status, int enrolled,
                    Instant finalizedAt, String outcome, String mode) {}
    record Participant(String id, String handle, String displayName, Instant enrolledAt, int score, boolean winner) {}
    record View(Overview challenge, List<Participant> participants, boolean canEnroll) {}
}
