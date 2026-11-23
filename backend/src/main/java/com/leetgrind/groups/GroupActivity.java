package com.leetgrind.groups;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.leetgrind.platform.ApiErrors.Failure;
import com.leetgrind.platform.Utc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupActivity {
    private static final String VISIBLE_NOTICES = """
            FROM notifications n
            LEFT JOIN study_groups g ON g.id = n.group_id
            LEFT JOIN challenges c ON c.id = n.challenge_id
            WHERE n.recipient_id = :actor AND n.source_key NOT LIKE 'RETIRED:%'
              AND (n.group_id IS NULL OR (g.closed_at IS NULL AND EXISTS (
                  SELECT 1 FROM memberships m WHERE m.group_id = n.group_id AND m.active_account_id = :actor
              )))
              AND (c.id IS NULL OR c.kind = 'GROUP' OR EXISTS (
                  SELECT 1 FROM challenge_participants cp WHERE cp.challenge_id = c.id AND cp.account_id = :actor
              ))
            """;
    private final JdbcClient jdbc;
    private final GroupService groups;
    private final Clock clock;

    GroupActivity(JdbcClient jdbc, GroupService groups, Clock clock) {
        this.jdbc = jdbc;
        this.groups = groups;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<FeedItem> feed(long group, long actor, String provider) {
        groups.requireMember(group, actor, false);
        return jdbc.sql("""
                SELECT * FROM (
                    SELECT s.id, 'SOLVE' AS kind, s.occurred_at AS event_at,
                           p.title AS label, a.display_name AS display_name, a.handle AS handle,
                           x.amount AS xp, CAST(NULL AS SIGNED) AS challenge_id
                    FROM solves s
                    JOIN accounts a ON a.id = s.account_id
                    JOIN problems p ON p.id = s.problem_id
                    JOIN xp_entries x ON x.solve_id = s.id AND x.reason = 'BASE_SOLVE' AND x.provider = :provider
                    WHERE EXISTS (
                        SELECT 1 FROM memberships m WHERE m.group_id = :group AND m.account_id = s.account_id
                          AND s.occurred_at > m.joined_at AND (m.left_at IS NULL OR s.occurred_at < m.left_at)
                    )
                    UNION ALL
                    SELECT c.id, 'CHALLENGE_CREATED', c.created_at, c.title, NULL, NULL, NULL, c.id
                    FROM challenges c WHERE c.group_id = :group AND c.provider = :provider
                      AND (c.kind = 'GROUP' OR EXISTS (
                          SELECT 1 FROM challenge_participants cp WHERE cp.challenge_id = c.id AND cp.account_id = :actor))
                    UNION ALL
                    SELECT c.id, 'CHALLENGE_RESULT', c.finalized_at, c.title, NULL, NULL, NULL, c.id
                    FROM challenges c WHERE c.group_id = :group AND c.provider = :provider AND c.finalized_at IS NOT NULL
                      AND (c.kind = 'GROUP' OR EXISTS (
                          SELECT 1 FROM challenge_participants cp WHERE cp.challenge_id = c.id AND cp.account_id = :actor))
                ) activity ORDER BY event_at DESC, id DESC, kind LIMIT 40
                """).param("group", group).param("actor", actor).param("provider", provider)
                .query((row, index) -> new FeedItem(row.getString("kind"), GroupService.time(row, "event_at"),
                        row.getString("label"), row.getString("display_name"), row.getString("handle"),
                        row.getObject("xp", Integer.class), row.getString("challenge_id"))).list();
    }

    List<Notice> inbox(long actor, long before) {
        return jdbc.sql("""
                SELECT n.id, n.kind, n.label, n.group_id, g.name AS group_name, n.challenge_id,
                       c.title AS challenge_title, n.created_at, n.read_at
                """ + VISIBLE_NOTICES + " AND n.id < :before ORDER BY n.id DESC LIMIT 50")
                .param("actor", actor).param("before", before).query((row, index) -> new Notice(row.getString("id"),
                        row.getString("kind"), row.getString("label"), row.getString("group_id"), row.getString("group_name"),
                        row.getString("challenge_id"), row.getString("challenge_title"),
                        GroupService.time(row, "created_at"), GroupService.time(row, "read_at"))).list();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void read(long actor, long id) {
        var location = jdbc.sql("SELECT n.group_id " + VISIBLE_NOTICES + " AND n.id = :id")
                .param("actor", actor).param("id", id).query((row, index) ->
                        new Location(row.getObject("group_id", Long.class))).optional().orElseThrow(GroupActivity::unavailable);
        if (location.group() != null) groups.requireMember(location.group(), actor, false);
        jdbc.sql("UPDATE notifications SET read_at = COALESCE(read_at, ?) WHERE id = ? AND recipient_id = ?")
                .params(Utc.sql(Utc.now(clock)), id, actor).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void committedSolve(long solve, long account, Instant now) {
        jdbc.sql("""
                INSERT INTO notifications (recipient_id, kind, source_key, group_id, label, created_at)
                SELECT DISTINCT s.account_id, 'STANDINGS_CHANGE', CONCAT(s.id, ':', m.group_id), m.group_id,
                       CONCAT('+', x.amount, ' base XP', CASE WHEN x.provider = 'FIXTURE' THEN ' in demo standings' ELSE '' END), ?
                FROM solves s JOIN memberships m ON m.account_id = s.account_id
                JOIN study_groups g ON g.id = m.group_id AND g.closed_at IS NULL
                JOIN xp_entries x ON x.solve_id = s.id AND x.reason = 'BASE_SOLVE'
                WHERE s.id = ? AND s.account_id = ?
                  AND s.occurred_at > m.joined_at AND (m.left_at IS NULL OR s.occurred_at < m.left_at)
                  AND EXISTS (SELECT 1 FROM memberships current_member
                              WHERE current_member.group_id = m.group_id AND current_member.active_account_id = s.account_id)
                ON DUPLICATE KEY UPDATE id = notifications.id
                """).params(Utc.sql(now), solve, account).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void achievement(long account, String provider, String code, Instant now) {
        String label = "FIRST_SOLVE".equals(code) ? "First solve" : "Three-day streak";
        insert(account, "ACHIEVEMENT", provider + ":" + code, null, null,
                "FIXTURE".equals(provider) ? label + " (demo)" : label, now);
    }

    void challengeNotice(long account, String kind, long group, long challenge, Instant now) {
        insert(account, kind, Long.toString(challenge), group, challenge, null, now);
    }

    private void insert(long account, String kind, String key, Long group, Long challenge, String label, Instant now) {
        jdbc.sql("""
                INSERT INTO notifications (recipient_id, kind, source_key, group_id, challenge_id, label, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE id = notifications.id
                """).params(account, kind, key, group, challenge, label, Utc.sql(now)).update();
    }

    private static Failure unavailable() {
        return new Failure(HttpStatus.NOT_FOUND, "notification_not_found", "This notification is unavailable.");
    }

    private record Location(Long group) {}
    record FeedItem(String kind, Instant at, String label, String displayName, String handle, Integer xp, String challengeId) {}
    record Notice(String id, String kind, String label, String groupId, String groupName, String challengeId,
                  String challengeTitle, Instant createdAt, Instant readAt) {}
}
