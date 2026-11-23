package com.leetgrind.platform;

import java.time.Duration;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class OperationsMetrics {
    private final MeterRegistry meters;
    private final Timer providerFetch;
    private final Timer queueWait;

    OperationsMetrics(MeterRegistry meters) {
        this.meters = meters;
        providerFetch = meters.timer("leetgrind.sync.provider.fetch.duration");
        queueWait = meters.timer("leetgrind.sync.queue.wait.duration");
        meters.counter("leetgrind.sync.expired.leases");
        meters.counter("leetgrind.xp.deduplications");
    }

    public void syncOutcome(String result) {
        afterCommit(() -> meters.counter("leetgrind.sync.outcomes", "result", result).increment());
    }

    public void expiredLease() {
        afterCommit(() -> meters.counter("leetgrind.sync.expired.leases").increment());
    }

    public void deduplicatedXp() {
        afterCommit(() -> meters.counter("leetgrind.xp.deduplications").increment());
    }

    public <T> T providerFetch(Supplier<T> request) {
        return providerFetch.record(request);
    }

    public void syncQueueWait(Duration wait) {
        afterCommit(() -> queueWait.record(wait));
    }

    public <T> T leaderboardQuery(Supplier<T> query) {
        return meters.timer("leetgrind.leaderboard.query.duration").record(query);
    }

    private static void afterCommit(Runnable increment) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            increment.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                increment.run();
            }
        });
    }
}
