package com.leetgrind.groups;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.leetgrind.identity.ActiveAccountGuard;
import com.leetgrind.platform.ApiErrors.Failure;
import com.leetgrind.platform.Utc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupService {
    private final JdbcClient jdbc;
    private final ActiveAccountGuard accounts;
    private final Clock clock;

    GroupService(JdbcClient jdbc, ActiveAccountGuard accounts, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.clock = clock;
    }

    List<Summary> list(long accountId) {
        return jdbc.sql("""
                SELECT g.id, g.owner_id, g.name, g.description, g.visibility
                FROM memberships m JOIN study_groups g ON g.id = m.group_id
                WHERE m.active_account_id = ? AND g.closed_at IS NULL
                ORDER BY g.id DESC
                """).param(accountId).query((row, index) -> summary(readGroup(row), accountId)).list();
    }

    @Transactional
    public Summary create(long accountId, String name, String description, String visibility) {
        accounts.lock(accountId);
        Instant now = Utc.now(clock);
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO study_groups (owner_id, name, description, created_at, visibility) VALUES (?, ?, ?, ?, ?)")
                .params(accountId, name.strip(), description.strip(), Utc.sql(now), visibility).update(key, "id");
        long groupId = key.getKey().longValue();
        join(groupId, accountId, now);
        return new Summary(Long.toString(groupId), name.strip(), description.strip(), "OWNER", visibility);
    }

    List<PublicGroup> discover(long actor, long before) {
        return jdbc.sql("""
                SELECT g.id, g.name, g.description FROM study_groups g
                WHERE g.visibility = 'PUBLIC' AND g.closed_at IS NULL AND g.id < ?
                  AND NOT EXISTS (SELECT 1 FROM memberships m WHERE m.group_id = g.id AND m.active_account_id = ?)
                ORDER BY g.id DESC LIMIT 50
                """).params(before, actor).query((row, index) -> new PublicGroup(Long.toString(row.getLong("id")),
                row.getString("name"), row.getString("description"))).list();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Summary joinPublic(long groupId, long actor) {
        accounts.lock(actor);
        Group group = lockOpen(groupId, true).filter(item -> "PUBLIC".equals(item.visibility()))
                .orElseThrow(GroupService::unavailable);
        if (!isMember(groupId, actor)) join(groupId, actor, Utc.now(clock));
        return summary(group, actor);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail detail(long groupId, long actor) {
        Group group = requireMember(groupId, actor, false);
        List<Member> members = jdbc.sql("""
                SELECT a.id, a.handle, a.display_name
                FROM memberships m JOIN accounts a ON a.id = m.active_account_id
                WHERE m.group_id = ? ORDER BY a.id
                """).param(groupId).query((row, index) -> {
            long account = row.getLong("id");
            return new Member(Long.toString(account), row.getString("handle"), row.getString("display_name"),
                    role(group, account));
        }).list();
        return new Detail(summary(group, actor), members);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Summary update(long groupId, long actor, String name, String description) {
        Group group = requireOwner(groupId, actor, true);
        String nextName = name == null ? group.name() : name.strip();
        String nextDescription = description == null ? group.description() : description.strip();
        if (nextName.isBlank()) {
            throw new Failure(HttpStatus.BAD_REQUEST, "invalid_request", "Enter a group name.");
        }
        jdbc.sql("UPDATE study_groups SET name = ?, description = ? WHERE id = ?")
                .params(nextName, nextDescription, group.id()).update();
        return new Summary(Long.toString(group.id()), nextName, nextDescription, "OWNER", group.visibility());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void remove(long groupId, long actor, long target) {
        Group group = requireMember(groupId, actor, true);
        if (actor != target && group.ownerId() != actor) {
            throw ownerRequired();
        }
        if (target == group.ownerId()) {
            throw new Failure(HttpStatus.CONFLICT, "owner_must_transfer", "Transfer ownership or close the group first.");
        }
        jdbc.sql("""
                UPDATE memberships SET left_at = GREATEST(joined_at, ?)
                WHERE group_id = ? AND active_account_id = ?
                """).params(Utc.sql(Utc.now(clock)), groupId, target).update();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void transfer(long groupId, long actor, long newOwner) {
        if (jdbc.sql("SELECT id FROM accounts WHERE id = ? AND active = TRUE FOR UPDATE")
                .param(newOwner).query(Long.class).optional().isEmpty()) {
            // Group details remain private even for an invalid transfer target.
            requireOwner(groupId, actor, true);
            throw new Failure(HttpStatus.CONFLICT, "invalid_new_owner", "Choose a current group member.");
        }
        requireOwner(groupId, actor, true);
        boolean eligible = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM memberships m JOIN accounts a ON a.id = m.active_account_id
                    WHERE m.group_id = ? AND m.active_account_id = ? AND a.active = TRUE
                )
                """).params(groupId, newOwner).query(Boolean.class).single();
        if (!eligible) {
            throw new Failure(HttpStatus.CONFLICT, "invalid_new_owner", "Choose a current group member.");
        }
        jdbc.sql("UPDATE study_groups SET owner_id = ? WHERE id = ?")
                .params(newOwner, groupId).update();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void close(long groupId, long actor) {
        requireOwner(groupId, actor, true);
        LocalDateTime now = Utc.sql(Utc.now(clock));
        jdbc.sql("UPDATE group_invites SET revoked_at = ? WHERE group_id = ? AND revoked_at IS NULL")
                .params(now, groupId).update();
        jdbc.sql("UPDATE memberships SET left_at = GREATEST(joined_at, ?) WHERE group_id = ? AND left_at IS NULL")
                .params(now, groupId).update();
        jdbc.sql("UPDATE study_groups SET closed_at = ? WHERE id = ?")
                .params(now, groupId).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void eraseMemberships(long accountId) {
        // The caller holds the account lock. New invites and ownership transfers take that lock first.
        List<Long> groupIds = jdbc.sql("""
                SELECT group_id FROM memberships WHERE account_id = ?
                UNION SELECT id FROM study_groups WHERE owner_id = ?
                ORDER BY 1
                """).params(accountId, accountId).query(Long.class).list();
        for (long id : groupIds) {
            if (lockOpen(id, true).filter(group -> group.ownerId() == accountId).isPresent()) {
                throw new Failure(HttpStatus.CONFLICT, "owned_groups", "Transfer or close your groups before deleting your account.");
            }
        }
        jdbc.sql("DELETE FROM memberships WHERE account_id = ?").param(accountId).update();
    }

    // Group-row locks serialize membership and invite changes and guard private reads against concurrent removal.
    Optional<Group> lockOpen(long groupId, boolean write) {
        String lock = write ? "FOR UPDATE" : "FOR SHARE";
        return jdbc.sql("SELECT id, owner_id, name, description, visibility FROM study_groups WHERE id = ? AND closed_at IS NULL " + lock)
                .param(groupId).query((row, index) -> readGroup(row)).optional();
    }

    Group requireMember(long groupId, long accountId, boolean write) {
        return lockOpen(groupId, write).filter(group -> isMember(groupId, accountId))
                .orElseThrow(GroupService::unavailable);
    }

    Group requireOwner(long groupId, long accountId, boolean write) {
        Group group = requireMember(groupId, accountId, write);
        if (group.ownerId() != accountId) {
            throw ownerRequired();
        }
        return group;
    }

    boolean isMember(long groupId, long accountId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM memberships WHERE group_id = ? AND active_account_id = ?)")
                .params(groupId, accountId).query(Boolean.class).single();
    }

    void join(long groupId, long accountId, Instant now) {
        jdbc.sql("""
                INSERT INTO memberships (group_id, account_id, joined_at)
                SELECT ?, ?, GREATEST(?, COALESCE(MAX(left_at), ?))
                FROM memberships WHERE group_id = ? AND account_id = ?
                """).params(groupId, accountId, Utc.sql(now), Utc.sql(now), groupId, accountId).update();
    }

    static Summary summary(Group group, long actor) {
        return new Summary(Long.toString(group.id()), group.name(), group.description(), role(group, actor), group.visibility());
    }

    static Instant time(ResultSet row, String field) throws SQLException {
        return Utc.instant(row.getObject(field, LocalDateTime.class));
    }

    private static Failure ownerRequired() {
        return new Failure(HttpStatus.FORBIDDEN, "group_owner_required", "Only the group owner can do that.");
    }

    private static Failure unavailable() {
        return new Failure(HttpStatus.NOT_FOUND, "group_not_found", "This group is unavailable.");
    }

    private static String role(Group group, long accountId) {
        return group.ownerId() == accountId ? "OWNER" : "MEMBER";
    }

    private static Group readGroup(ResultSet row) throws SQLException {
        return new Group(row.getLong("id"), row.getLong("owner_id"), row.getString("name"), row.getString("description"),
                row.getString("visibility"));
    }

    record Group(long id, long ownerId, String name, String description, String visibility) {}
    record Summary(String id, String name, String description, String role, String visibility) {}
    record PublicGroup(String id, String name, String description) {}
    record Detail(Summary group, List<Member> members) {}
    record Member(String id, String handle, String displayName, String role) {}
}
