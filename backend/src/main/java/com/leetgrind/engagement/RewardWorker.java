package com.leetgrind.engagement;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "leetgrind.engagement.worker-enabled", havingValue = "true", matchIfMissing = true)
class RewardWorker {
    private static final Logger LOG = LoggerFactory.getLogger(RewardWorker.class);
    private final RewardConsumer consumer;
    private final TaskExecutor executor;
    private final AtomicBoolean active = new AtomicBoolean();

    RewardWorker(RewardConsumer consumer, @Qualifier("workerExecutor") TaskExecutor executor) {
        this.consumer = consumer;
        this.executor = executor;
    }

    @Scheduled(fixedDelay = 1_000)
    void dispatch() {
        if (!active.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    for (int i = 0; i < 32 && consumer.consumeNext(); i++) {
                        // Bound a worker's claim on the shared executor.
                    }
                } catch (RuntimeException error) {
                    LOG.warn("Practice reward delivery failed. The outbox event remains available for retry.");
                } finally {
                    active.set(false);
                }
            });
        } catch (TaskRejectedException shuttingDown) {
            active.set(false);
        }
    }
}
