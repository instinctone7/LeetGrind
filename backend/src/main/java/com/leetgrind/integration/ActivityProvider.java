package com.leetgrind.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.leetgrind.catalog.ProblemCatalog.Problem;

interface ActivityProvider {
    Batch fetch(Request request);

    Optional<String> fixtureIdentity(long accountId, String handle);

    enum Outcome { ACCEPTED, REJECTED }
    enum Result {
        COMPLETE, PARTIAL, COVERAGE_UNKNOWN, THROTTLED, TRANSIENT_ERROR, ACCESS_DENIED, SCHEMA_ERROR,
        IDENTITY_MISMATCH
    }

    record Request(String provider, String profileId, Instant activatedAt, Instant from, Instant through) {}
    record Submission(String id, Problem problem, Instant occurredAt, Outcome outcome) {}
    record Batch(String profileId, Result result, List<Submission> submissions, Duration retryAfter) {
        static Batch failure(Result result) {
            return new Batch(null, result, List.of(), null);
        }
    }
}
