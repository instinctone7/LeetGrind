package com.leetgrind.groups;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import com.leetgrind.identity.ActiveAccountGuard;
import com.leetgrind.platform.ApiErrors.Failure;
import com.leetgrind.platform.FixedWindowRateLimiter;
import com.leetgrind.platform.Utc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class GroupInvites {
    private final JdbcClient jdbc;
    private final GroupService groups;
    private final Clock clock;
    private final ActiveAccountGuard accounts;
    private final SecureRandom random = new SecureRandom();
    private final FixedWindowRateLimiter attempts;

    GroupInvites(JdbcClient jdbc, GroupService groups, ActiveAccountGuard accounts, Clock clock) {
        this.jdbc = jdbc;
        this.groups = groups;
        this.accounts = accounts;
        this.clock = clock;
        this.attempts = new FixedWindowRateLimiter(clock, 20);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Created create(long groupId, long actor, int hours, int maxUses) {
        groups.requireOwner(groupId, actor, true);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Utc.now(clock).plus(Duration.ofHours(hours));
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO group_invites (group_id, code_digest, expires_at, max_uses) VALUES (?, ?, ?, ?)")
                .params(groupId, digest(code), Utc.sql(expiresAt), maxUses).update(key, "id");
        return new Created(Long.toString(key.getKey().longValue()), code, expiresAt, maxUses);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Invite> list(long groupId, long actor) {
        groups.requireOwner(groupId, actor, false);
        return jdbc.sql("""
                SELECT id, expires_at, revoked_at, max_uses, uses
                FROM group_invites WHERE group_id = ? ORDER BY id DESC
                """).param(groupId).query((row, index) -> new Invite(Long.toString(row.getLong("id")),
                GroupService.time(row, "expires_at"), GroupService.time(row, "revoked_at"),
                row.getInt("max_uses"), row.getInt("uses"))).list();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revoke(long groupId, long actor, long inviteId) {
        groups.requireOwner(groupId, actor, true);
        jdbc.sql("UPDATE group_invites SET revoked_at = ? WHERE group_id = ? AND id = ? AND revoked_at IS NULL")
                .params(Utc.sql(Utc.now(clock)), groupId, inviteId).update();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GroupService.Summary redeem(long accountId, String submittedCode) {
        if (!attempts.allow(Long.toString(accountId))) {
            throw new Failure(HttpStatus.TOO_MANY_REQUESTS, "invite_rate_limited", "Too many attempts. Try again in a minute.");
        }
        String code = submittedCode.strip();
        if (!code.matches("[A-Za-z0-9_-]{43}")) {
            throw unavailable();
        }
        String codeDigest = digest(code);
        long groupId = jdbc.sql("SELECT group_id FROM group_invites WHERE code_digest = ?")
                .param(codeDigest).query(Long.class).optional().orElseThrow(GroupInvites::unavailable);
        accounts.lock(accountId);
        GroupService.Group group = groups.lockOpen(groupId, true).orElseThrow(GroupInvites::unavailable);
        if (groups.isMember(groupId, accountId)) {
            return GroupService.summary(group, accountId);
        }

        Instant now = Utc.now(clock);
        int changed = jdbc.sql("""
                UPDATE group_invites SET uses = uses + 1
                WHERE code_digest = ? AND group_id = ? AND revoked_at IS NULL AND expires_at > ? AND uses < max_uses
                """).params(codeDigest, groupId, Utc.sql(now)).update();
        if (changed == 0) {
            throw unavailable();
        }
        groups.join(groupId, accountId, now);
        return GroupService.summary(group, accountId);
    }

    private static String digest(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Failure unavailable() {
        return new Failure(HttpStatus.CONFLICT, "invite_unavailable", "That invitation is unavailable.");
    }

    record Created(String id, String code, Instant expiresAt, int maxUses) {
        @Override
        public String toString() { return "CreatedInvite[redacted]"; }
    }
    record Invite(String id, Instant expiresAt, Instant revokedAt, int maxUses, int uses) {}
}
