package com.leetgrind.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.leetgrind.integration.ActivityProvider.Batch;
import com.leetgrind.integration.ActivityProvider.Result;
import com.leetgrind.platform.OperationsMetrics;
import com.leetgrind.platform.Utc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class SyncStore {
    private static final Duration LEASE_TIME = Duration.ofSeconds(30);
    private static final Duration MANUAL_COOLDOWN = Duration.ofSeconds(30);
    private static final Duration FIXTURE_POLL = Duration.ofMinutes(15);
    private static final Duration OVERLAP = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_RECORDS = 200;

    private final JdbcClient jdbc;
    private final SubmissionIngestion ingestion;
    private final Clock clock;
    private final OperationsMetrics metrics;

    SyncStore(JdbcClient jdbc, SubmissionIngestion ingestion, Clock clock, OperationsMetrics metrics) {
        this.jdbc = jdbc;
        this.ingestion = ingestion;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<Lease> claim() {
        // Keep the shared control lock until the lease assignment commits.
        if (jdbc.sql("SELECT paused FROM sync_control WHERE id = 1 FOR SHARE").query(Boolean.class).single()) {
            return Optional.empty();
        }
        Instant now = Utc.now(clock);
        var due = jdbc.sql("""
                SELECT link_id, link_version, attempts, state,
                       CASE WHEN state = 'RUNNING' THEN lease_expires_at ELSE next_run_at END AS ready_at
                FROM sync_jobs
                WHERE (state IN ('QUEUED','IDLE','RETRY') AND next_run_at <= :now)
                   OR (state = 'RUNNING' AND lease_expires_at <= :now)
                ORDER BY next_run_at, link_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).param("now", Utc.sql(now)).query((row, index) ->
                new Due(row.getLong("link_id"), row.getLong("link_version"), row.getInt("attempts"), row.getString("state"),
                        LinkService.time(row, "ready_at"))).optional();
        if (due.isEmpty()) {
            return Optional.empty();
        }
        Due job = due.get();
        if ("RUNNING".equals(job.state())) {
            metrics.expiredLease();
        }
        if (job.attempts() >= MAX_ATTEMPTS) {
            Instant next = now.plus(FIXTURE_POLL);
            updateJob(job.linkId(), job.version(), "IDLE", next, "LEASE_EXHAUSTED", 0, next);
            return Optional.empty();
        }

        String token = UUID.randomUUID().toString();
        jdbc.sql("""
                UPDATE sync_jobs SET state = 'RUNNING', lease_token = ?, lease_expires_at = ?,
                    last_attempt_at = ?, manual_available_at = ?, attempts = attempts + 1
                WHERE link_id = ? AND link_version = ?
                """).params(token, Utc.sql(now.plus(LEASE_TIME)), Utc.sql(now), Utc.sql(now.plus(MANUAL_COOLDOWN)),
                job.linkId(), job.version()).update();
        metrics.syncQueueWait(Duration.between(job.readyAt(), now));
        return Optional.of(new Lease(job.linkId(), job.version(), token, now, job.attempts() + 1));
    }

    Optional<ActivityProvider.Request> request(Lease lease) {
        return jdbc.sql("""
                SELECT provider, provider_account_id, activated_at, checkpoint_at FROM leetcode_links
                WHERE id = ? AND version = ? AND state = 'VERIFIED'
                """).params(lease.linkId(), lease.version()).query((row, index) -> {
            Instant activated = LinkService.time(row, "activated_at");
            Instant checkpoint = LinkService.time(row, "checkpoint_at");
            Instant earliest = checkpoint == null ? activated : checkpoint.minus(OVERLAP);
            return new ActivityProvider.Request(row.getString("provider"), row.getString("provider_account_id"), activated,
                    earliest.isBefore(activated) ? activated : earliest, lease.through());
        }).optional();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean finish(Lease lease, Batch batch) {
        var account = jdbc.sql("""
                SELECT account_id FROM leetcode_links WHERE id = ? AND version = ? AND state = 'VERIFIED'
                """).params(lease.linkId(), lease.version()).query(Long.class).optional();
        if (account.isEmpty() || jdbc.sql("SELECT id FROM accounts WHERE id = ? AND active = TRUE FOR UPDATE")
                .param(account.get()).query(Long.class).optional().isEmpty()) {
            return false;
        }
        // Use the same account → link → job lock order as the owner-facing link operations.
        var link = jdbc.sql("""
                SELECT account_id, provider, provider_account_id, activated_at FROM leetcode_links
                WHERE id = ? AND version = ? AND state = 'VERIFIED' FOR UPDATE
                """).params(lease.linkId(), lease.version()).query((row, index) ->
                new SubmissionIngestion.Source(row.getLong("account_id"), lease.linkId(), row.getString("provider"),
                        row.getString("provider_account_id"), LinkService.time(row, "activated_at"),
                        lease.through())).optional();
        if (link.isEmpty() || !holdsLease(lease)) {
            return false;
        }

        Instant now = Utc.now(clock);
        if (!FixtureActivityProvider.PROVIDER.equals(link.get().provider())) {
            fail(lease, now, Result.ACCESS_DENIED, null);
            metrics.syncOutcome(Result.ACCESS_DENIED.name());
            return true;
        }
        Result result = result(batch, link.get());
        if (result == Result.COMPLETE || result == Result.PARTIAL || result == Result.COVERAGE_UNKNOWN) {
            if (!ingestion.ingest(link.get(), batch.submissions(), now)) {
                result = Result.PARTIAL;
            }
        }
        metrics.syncOutcome(result.name());
        if (result == Result.COMPLETE) {
            jdbc.sql("""
                    UPDATE leetcode_links SET last_success_at = ?, checkpoint_at = ?, coverage_uncertain = FALSE
                    WHERE id = ?
                    """).params(Utc.sql(now), Utc.sql(lease.through()), lease.linkId()).update();
            updateJob(lease.linkId(), lease.version(), "IDLE", now.plus(FIXTURE_POLL), null, 0, null);
        } else {
            fail(lease, now, result, batch == null ? null : batch.retryAfter());
        }
        return true;
    }

    private boolean holdsLease(Lease lease) {
        return jdbc.sql("""
                SELECT lease_expires_at FROM sync_jobs
                WHERE link_id = ? AND link_version = ? AND state = 'RUNNING' AND lease_token = ? FOR UPDATE
                """).params(lease.linkId(), lease.version(), lease.token())
                .query((row, index) -> LinkService.time(row, "lease_expires_at")).optional()
                .filter(expiry -> expiry.isAfter(Utc.now(clock))).isPresent();
    }

    private static Result result(Batch batch, SubmissionIngestion.Source source) {
        if (batch == null || batch.result() == null) {
            return Result.SCHEMA_ERROR;
        }
        return switch (batch.result()) {
            case COMPLETE, PARTIAL, COVERAGE_UNKNOWN -> {
                if (!source.profileId().equals(batch.profileId())) {
                    yield Result.IDENTITY_MISMATCH;
                }
                if (batch.submissions() == null || batch.submissions().size() > MAX_RECORDS) {
                    yield Result.SCHEMA_ERROR;
                }
                yield batch.result();
            }
            default -> batch.result();
        };
    }

    private void fail(Lease lease, Instant now, Result result, Duration retryAfter) {
        jdbc.sql("UPDATE leetcode_links SET coverage_uncertain = TRUE WHERE id = ?").param(lease.linkId()).update();
        if (result == Result.ACCESS_DENIED || result == Result.SCHEMA_ERROR || result == Result.IDENTITY_MISMATCH) {
            updateJob(lease.linkId(), lease.version(), "PAUSED", now, result.name(), 0, null);
            return;
        }
        boolean exhausted = lease.attempt() >= MAX_ATTEMPTS;
        Duration delay = exhausted ? FIXTURE_POLL : Duration.ofSeconds(
                (5L << (lease.attempt() - 1)) + ThreadLocalRandom.current().nextInt(1, 4));
        if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
            delay = retryAfter;
        }
        Instant next = now.plus(delay);
        updateJob(lease.linkId(), lease.version(), exhausted ? "IDLE" : "RETRY", next, result.name(),
                exhausted ? 0 : lease.attempt(), exhausted ? next : null);
    }

    private void updateJob(long id, long version, String state, Instant next, String error, int attempts,
                           Instant manuallyAvailableAt) {
        jdbc.sql("""
                UPDATE sync_jobs SET state = :state, next_run_at = :next, failure_code = :error,
                    attempts = :attempts, lease_token = NULL, lease_expires_at = NULL,
                    manual_available_at = COALESCE(:manual, manual_available_at)
                WHERE link_id = :id AND link_version = :version
                """).param("state", state).param("next", Utc.sql(next)).param("error", error).param("attempts", attempts)
                .param("manual", manuallyAvailableAt == null ? null : Utc.sql(manuallyAvailableAt))
                .param("id", id).param("version", version).update();
    }

    record Lease(long linkId, long version, String token, Instant through, int attempt) {}
    private record Due(long linkId, long version, int attempts, String state, Instant readyAt) {}
}
