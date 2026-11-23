package com.leetgrind.integration;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

import com.leetgrind.platform.ApiErrors.Failure;
import com.leetgrind.platform.Utc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LinkService {
    private final JdbcClient jdbc;
    private final ActivityProvider provider;
    private final Clock clock;
    private final boolean fixturesEnabled;
    private final boolean previewEnabled;

    LinkService(JdbcClient jdbc, ActivityProvider provider, Clock clock,
                @Value("${leetgrind.integration.fixtures-enabled}") boolean fixturesEnabled,
                @Value("${leetgrind.integration.profile-preview-enabled}") boolean previewEnabled) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.clock = clock;
        this.fixturesEnabled = fixturesEnabled;
        this.previewEnabled = previewEnabled;
    }

    @Transactional(readOnly = true)
    public Status status(long accountId) {
        return readStatus(accountId);
    }

    @Transactional(readOnly = true)
    public HistoryCoverage historyCoverage(long accountId, String provider) {
        if ("LEETCODE".equals(provider)) return new HistoryCoverage("UNAVAILABLE", null, null, false);
        return jdbc.sql("""
                SELECT l.state, l.coverage_uncertain, l.last_success_at AS link_success_at, j.failure_code,
                       MIN(l.activated_at) OVER () AS tracked_since,
                       MAX(l.last_success_at) OVER () AS last_success_at
                FROM leetcode_links l LEFT JOIN sync_jobs j ON j.link_id = l.id AND j.link_version = l.version
                WHERE l.account_id = ? AND l.provider = ?
                ORDER BY l.id DESC LIMIT 1
                """).params(accountId, provider).query((row, index) -> {
            Instant lastSuccess = time(row, "last_success_at");
            String state;
            if ("DISCONNECTED".equals(row.getString("state"))) state = "DISCONNECTED";
            else if (row.getString("failure_code") != null) state = "PARTIAL";
            else if (time(row, "link_success_at") == null) state = "AWAITING_SYNC";
            else if (row.getBoolean("coverage_uncertain")) state = "PARTIAL";
            else state = "TRACKED_ONLY";
            return new HistoryCoverage(state, time(row, "tracked_since"), lastSuccess, false);
        }).optional().orElse(new HistoryCoverage("NO_LINK", null, null, false));
    }

    @Transactional
    public Status link(long accountId, String submittedHandle) {
        return saveHandle(accountId, submittedHandle, true);
    }

    @Transactional
    public void savePublicHandle(long accountId, String submittedHandle) {
        saveHandle(accountId, submittedHandle, false);
    }

    private Status saveHandle(long accountId, String submittedHandle, boolean allowDemo) {
        String handle = profileHandle(submittedHandle);
        lockAccount(accountId);
        Link previous = activeLink(accountId).orElse(null);
        if (previous != null && previous.handle().equals(handle) && previous.isVerified()) {
            return readStatus(accountId);
        }

        Instant now = Utc.now(clock);
        String proof = allowDemo && fixturesEnabled ? provider.fixtureIdentity(accountId, handle).orElse(null) : null;
        if (previous != null && !previous.handle().equals(handle)) {
            disconnect(previous, now);
            previous = null;
        }
        long id = previous == null ? insertPending(accountId, handle) : previous.id();
        long version = previous == null ? 1 : previous.version();
        if (proof != null) {
            verifyFixture(accountId, id, version, proof, now);
        }
        return readStatus(accountId);
    }

    static String profileHandle(String input) {
        String handle = input.strip().toLowerCase(Locale.ROOT);
        if (handle.contains("://")) {
            try {
                URI url = URI.create(handle);
                if (("https".equals(url.getScheme()) || "http".equals(url.getScheme()))
                        && ("leetcode.com".equals(url.getHost()) || "www.leetcode.com".equals(url.getHost()))
                        && url.getUserInfo() == null && url.getPort() == -1 && url.getQuery() == null && url.getFragment() == null
                        && url.getRawPath().matches("/u/[a-z0-9_-]{1,64}/?")) {
                    handle = url.getRawPath().substring(3).replaceFirst("/$", "");
                }
            } catch (IllegalArgumentException invalid) {
                // Reject malformed links with the same validation response as malformed handles.
            }
        }
        if (!handle.matches("[a-z0-9_-]{1,64}")) {
            throw new Failure(HttpStatus.BAD_REQUEST, "invalid_handle", "Enter a valid LeetCode handle or profile URL.");
        }
        return handle;
    }

    @Transactional
    public void disconnect(long accountId) {
        lockAccount(accountId);
        activeLink(accountId).ifPresent(link -> disconnect(link, Utc.now(clock)));
    }

    @Transactional
    public Status requestSync(long accountId) {
        lockAccount(accountId);
        Link link = activeLink(accountId).filter(Link::isVerified).orElseThrow(() ->
                new Failure(HttpStatus.CONFLICT, "link_unverified", "Verify a linked profile before requesting sync."));
        if (!fixturesEnabled || !FixtureActivityProvider.PROVIDER.equals(link.provider())) {
            throw new Failure(HttpStatus.CONFLICT, "sync_unavailable", "Synchronization is unavailable for this profile.");
        }
        if (jdbc.sql("SELECT paused FROM sync_control WHERE id = 1").query(Boolean.class).single()) {
            throw new Failure(HttpStatus.CONFLICT, "sync_paused", "Synchronization is paused for maintenance.");
        }
        Instant now = Utc.now(clock);
        jdbc.sql("""
                UPDATE sync_jobs SET state = 'QUEUED', next_run_at = :now
                WHERE link_id = :id AND link_version = :version AND state = 'IDLE'
                  AND (manual_available_at IS NULL OR manual_available_at <= :now)
                """).param("now", Utc.sql(now)).param("id", link.id()).param("version", link.version()).update();
        Status status = readStatus(accountId);
        if ("PAUSED".equals(status.syncState())) {
            throw new Failure(HttpStatus.CONFLICT, "sync_paused", "Synchronization is paused for this profile.");
        }
        if ("IDLE".equals(status.syncState()) && status.manualSyncAvailableAt() != null
                && status.manualSyncAvailableAt().isAfter(now)) {
            throw new Failure(HttpStatus.TOO_MANY_REQUESTS, "sync_cooldown", "Wait before requesting another sync.");
        }
        return status;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void eraseActivity(long accountId) {
        jdbc.sql("""
                DELETE j FROM sync_jobs j JOIN leetcode_links l ON j.link_id = l.id WHERE l.account_id = ?
                """).param(accountId).update();
        jdbc.sql("DELETE FROM submissions WHERE account_id = ?").param(accountId).update();
        jdbc.sql("DELETE FROM leetcode_links WHERE account_id = ?").param(accountId).update();
        jdbc.sql("DELETE FROM provider_identities WHERE account_id = ?").param(accountId).update();
    }

    private Status readStatus(long accountId) {
        return jdbc.sql("""
                SELECT l.handle, l.state AS link_state, l.provider, l.activated_at, l.coverage_uncertain, l.last_success_at,
                       j.state AS sync_state, j.last_attempt_at, j.failure_code, j.next_run_at, j.manual_available_at, c.paused
                FROM leetcode_links l LEFT JOIN sync_jobs j
                   ON j.link_id = l.id AND j.link_version = l.version
                CROSS JOIN sync_control c
                WHERE l.account_id = ? ORDER BY l.id DESC LIMIT 1
                """).param(accountId).query((row, index) -> {
            String syncState = row.getString("sync_state");
            String linkState = row.getString("link_state");
            boolean demo = FixtureActivityProvider.PROVIDER.equals(row.getString("provider"));
            boolean paused = row.getBoolean("paused");
            return new Status(linkState, row.getString("handle"), demo, fixturesEnabled, previewEnabled,
                    fixturesEnabled && demo && "VERIFIED".equals(linkState) && !paused && !"PAUSED".equals(syncState),
                    paused, syncState, row.getString("failure_code"), row.getBoolean("coverage_uncertain") || row.getString("failure_code") != null,
                    time(row, "activated_at"), time(row, "last_attempt_at"), time(row, "last_success_at"),
                    "PAUSED".equals(syncState) || "RUNNING".equals(syncState) ? null : time(row, "next_run_at"),
                    time(row, "manual_available_at"));
        }).optional().orElse(new Status("NONE", null, false, fixturesEnabled, previewEnabled, false, false,
                null, null, true, null, null, null, null, null));
    }

    private void lockAccount(long accountId) {
        jdbc.sql("SELECT id FROM accounts WHERE id = ? AND active = TRUE FOR UPDATE")
                .param(accountId).query(Long.class).optional().orElseThrow(() ->
                        new Failure(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in to continue."));
    }

    private Optional<Link> activeLink(long accountId) {
        return jdbc.sql("SELECT id, handle, version, state, provider FROM leetcode_links WHERE active_account_id = ? FOR UPDATE")
                .param(accountId).query((row, index) -> new Link(row.getLong("id"), row.getString("handle"),
                        row.getLong("version"), row.getString("state"), row.getString("provider"))).optional();
    }

    private long insertPending(long accountId, String handle) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO leetcode_links (account_id, handle, state) VALUES (?, ?, 'PENDING')")
                .params(accountId, handle).update(key, "id");
        return key.getKey().longValue();
    }

    private void disconnect(Link current, Instant now) {
        jdbc.sql("""
                UPDATE leetcode_links SET state = 'DISCONNECTED', disconnected_at = ?, version = version + 1
                WHERE id = ?
                """).params(Utc.sql(now), current.id()).update();
        jdbc.sql("""
                UPDATE sync_jobs SET state = 'CANCELLED', lease_token = NULL, lease_expires_at = NULL
                WHERE link_id = ? AND link_version = ?
                """).params(current.id(), current.version()).update();
    }

    private void verifyFixture(long accountId, long linkId, long version, String profileId, Instant now) {
        String fixture = FixtureActivityProvider.PROVIDER;
        jdbc.sql("""
                INSERT INTO provider_identities (provider, provider_account_id, account_id) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE account_id = provider_identities.account_id
                """).params(fixture, profileId, accountId).update();
        long owner = jdbc.sql("SELECT account_id FROM provider_identities WHERE provider = ? AND provider_account_id = ? FOR UPDATE")
                .params(fixture, profileId).query(Long.class).single();
        if (owner != accountId) {
            throw new Failure(HttpStatus.CONFLICT, "provider_conflict", "That provider profile cannot be linked.");
        }
        jdbc.sql("""
                UPDATE leetcode_links SET state = 'VERIFIED', provider = ?, provider_account_id = ?,
                    verified_at = ?, activated_at = ? WHERE id = ?
                """).params(fixture, profileId, Utc.sql(now), Utc.sql(now), linkId).update();
        jdbc.sql("""
                INSERT INTO sync_jobs (link_id, link_version, state, next_run_at) VALUES (?, ?, 'QUEUED', ?)
                """).params(linkId, version, Utc.sql(now)).update();
    }

    static Instant time(ResultSet row, String field) throws SQLException {
        return Utc.instant(row.getObject(field, LocalDateTime.class));
    }

    private record Link(long id, String handle, long version, String state, String provider) {
        boolean isVerified() {
            return "VERIFIED".equals(state);
        }
    }

    record Status(String state, String handle, boolean fixture, boolean demoAvailable, boolean previewEnabled, boolean syncAvailable,
                  boolean providerPaused, String syncState, String failureCode, boolean coverageUncertain, Instant activatedAt,
                  Instant lastAttemptAt, Instant lastSuccessfulSyncAt, Instant nextSyncAt, Instant manualSyncAvailableAt) {}

    public record HistoryCoverage(String state, Instant trackedSince, Instant lastSuccessfulSyncAt,
                                  boolean historicalImportAvailable) {}
}
