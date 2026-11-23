package com.leetgrind.integration;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import com.leetgrind.catalog.ProblemCatalog;
import com.leetgrind.integration.ActivityProvider.Submission;
import com.leetgrind.platform.OperationsMetrics;
import com.leetgrind.platform.Utc;
import com.leetgrind.progress.ProgressService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class SubmissionIngestion {
    private final JdbcClient jdbc;
    private final ProblemCatalog catalog;
    private final ProgressService progress;
    private final OperationsMetrics metrics;

    SubmissionIngestion(JdbcClient jdbc, ProblemCatalog catalog, ProgressService progress, OperationsMetrics metrics) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.progress = progress;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean ingest(Source source, List<Submission> submissions, Instant recordedAt) {
        var candidates = new HashMap<String, Submission>();
        var ambiguous = new HashSet<String>();
        boolean complete = true;
        for (Submission submission : submissions) {
            if (!valid(submission, source.through())) {
                complete = false;
                if (submission != null && identifier(submission.id(), 128)) {
                    ambiguous.add(submission.id());
                }
                continue;
            }
            Submission prior = candidates.putIfAbsent(submission.id(), submission);
            if (prior != null && !sameEvidence(prior, submission)) {
                ambiguous.add(submission.id());
                complete = false;
            }
        }
        var ordered = candidates.values().stream().filter(candidate -> !ambiguous.contains(candidate.id()))
                .sorted(Comparator.comparing(Submission::occurredAt).thenComparing(Submission::id)).toList();
        for (Submission candidate : ordered) {
            // Historical imports are deferred. Preserve only evidence from the active interval.
            if (!candidate.occurredAt().isBefore(source.activatedAt())) {
                complete &= record(source, candidate, recordedAt);
            }
        }
        return complete;
    }

    private boolean record(Source source, Submission submitted, Instant recordedAt) {
        var existing = jdbc.sql("""
                SELECT p.provider_problem_id, s.outcome, s.difficulty, s.occurred_at
                FROM submissions s JOIN problems p ON p.id = s.problem_id
                WHERE s.provider = ? AND s.provider_account_id = ? AND s.provider_submission_id = ?
                """).params(source.provider(), source.profileId(), submitted.id()).query((row, index) ->
                new RecordedEvidence(row.getString("provider_problem_id"), row.getString("outcome"),
                        row.getString("difficulty"), LinkService.time(row, "occurred_at"))).optional();
        if (existing.isPresent()) {
            boolean replay = existing.get().equals(new RecordedEvidence(submitted.problem().id(), submitted.outcome().name(),
                    submitted.problem().difficulty().name(), submitted.occurredAt()));
            if (replay && submitted.outcome() == ActivityProvider.Outcome.ACCEPTED) {
                metrics.deduplicatedXp();
            }
            return replay;
        }

        long problemId = catalog.resolve(source.provider(), submitted.problem());
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO submissions (account_id, link_id, provider, provider_account_id, provider_submission_id,
                    problem_id, outcome, difficulty, occurred_at, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(source.accountId(), source.linkId(), source.provider(), source.profileId(), submitted.id(),
                problemId, submitted.outcome().name(), submitted.problem().difficulty().name(),
                Utc.sql(submitted.occurredAt()), Utc.sql(recordedAt)).update(key, "id");
        if (submitted.outcome() == ActivityProvider.Outcome.ACCEPTED) {
            progress.creditSolve(new ProgressService.Credit(source.accountId(), problemId, key.getKey().longValue(),
                    source.provider(), submitted.problem().difficulty(), submitted.occurredAt(), recordedAt));
        }
        return true;
    }

    private static boolean valid(Submission submission, Instant through) {
        if (submission == null || !identifier(submission.id(), 128) || submission.outcome() == null
                || submission.occurredAt() == null || !submission.occurredAt().isBefore(through)
                || submission.occurredAt().getNano() % 1_000 != 0) {
            return false;
        }
        var problem = submission.problem();
        return problem != null && identifier(problem.id(), 128) && identifier(problem.slug(), 128)
                && identifier(problem.title(), 160) && problem.difficulty() != null;
    }

    private static boolean sameEvidence(Submission left, Submission right) {
        return left.occurredAt().equals(right.occurredAt()) && left.outcome() == right.outcome()
                && left.problem().id().equals(right.problem().id())
                && left.problem().difficulty() == right.problem().difficulty();
    }

    private static boolean identifier(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.equals(value.strip());
    }

    record Source(long accountId, long linkId, String provider, String profileId, Instant activatedAt, Instant through) {}
    private record RecordedEvidence(String problemId, String outcome, String difficulty, Instant occurredAt) {}
}
