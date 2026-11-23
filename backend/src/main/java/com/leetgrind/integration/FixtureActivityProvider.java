package com.leetgrind.integration;

import java.util.List;
import java.util.Optional;

import com.leetgrind.catalog.ProblemCatalog.Difficulty;
import com.leetgrind.catalog.ProblemCatalog.Problem;
import org.springframework.stereotype.Component;

@Component
class FixtureActivityProvider implements ActivityProvider {
    static final String PROVIDER = "FIXTURE";

    @Override
    public Optional<String> fixtureIdentity(long accountId, String handle) {
        return ("fixture-" + accountId).equals(handle) ? Optional.of(handle) : Optional.empty();
    }

    @Override
    public Batch fetch(Request request) {
        if (!PROVIDER.equals(request.provider())) {
            return Batch.failure(Result.ACCESS_DENIED);
        }
        var problem = new Problem("medium-array", "fixture-array-window", "Array Window", Difficulty.MEDIUM);
        var occurredAt = request.activatedAt();
        var submission = new Submission("array-window:" + occurredAt, problem, occurredAt, Outcome.ACCEPTED);
        return new Batch(request.profileId(), Result.COMPLETE,
                occurredAt.isBefore(request.from()) || !occurredAt.isBefore(request.through())
                        ? List.of() : List.of(submission), null);
    }
}
