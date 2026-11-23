package com.leetgrind.integration;

import java.util.concurrent.Semaphore;

import com.leetgrind.integration.ActivityProvider.Batch;
import com.leetgrind.integration.ActivityProvider.Result;
import com.leetgrind.platform.OperationsMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"leetgrind.integration.fixtures-enabled", "leetgrind.integration.scheduler-enabled"},
        havingValue = "true")
class SyncWorker {
    private static final Logger LOG = LoggerFactory.getLogger(SyncWorker.class);
    private static final int CONCURRENT_SLOTS = 2;
    private static final int MAX_JOBS_PER_SLOT = 4;
    private final SyncStore store;
    private final ActivityProvider provider;
    private final TaskExecutor executor;
    private final OperationsMetrics metrics;
    private final Semaphore slots = new Semaphore(CONCURRENT_SLOTS);

    SyncWorker(SyncStore store, ActivityProvider provider, @Qualifier("workerExecutor") TaskExecutor executor,
               OperationsMetrics metrics) {
        this.store = store;
        this.provider = provider;
        this.executor = executor;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 1_000)
    void dispatch() {
        for (int i = 0; i < CONCURRENT_SLOTS && slots.tryAcquire(); i++) {
            try {
                executor.execute(() -> {
                    try {
                        for (int processed = 0; processed < MAX_JOBS_PER_SLOT && runOne(); processed++) {
                            // Yield the shared executor after a bounded batch.
                        }
                    } finally {
                        slots.release();
                    }
                });
            } catch (TaskRejectedException shuttingDown) {
                slots.release();
                return;
            }
        }
    }

    private boolean runOne() {
        try {
            var claimed = store.claim();
            if (claimed.isEmpty()) return false;
            var lease = claimed.get();
            store.request(lease).ifPresent(request -> {
                Batch batch = fetch(request);
                try {
                    store.finish(lease, batch);
                } catch (DataAccessException persistenceFailure) {
                    LOG.warn("Retrying a failed synchronization write for job {}", lease.linkId());
                    store.finish(lease, Batch.failure(Result.TRANSIENT_ERROR));
                }
            });
            return true;
        } catch (DataAccessException persistenceUnavailable) {
            LOG.warn("Synchronization database unavailable. Outstanding work can be reclaimed after its lease expires.");
            return false;
        }
    }

    private Batch fetch(ActivityProvider.Request request) {
        try {
            return metrics.providerFetch(() -> provider.fetch(request));
        } catch (RuntimeException unavailable) {
            return Batch.failure(Result.TRANSIENT_ERROR);
        }
    }

}
