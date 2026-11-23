package com.leetgrind.groups;

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
@ConditionalOnProperty(name = "leetgrind.competition.worker-enabled", havingValue = "true", matchIfMissing = true)
class ChallengeWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ChallengeWorker.class);
    private final ChallengeService challenges;
    private final TaskExecutor executor;
    private final AtomicBoolean active = new AtomicBoolean();

    ChallengeWorker(ChallengeService challenges, @Qualifier("workerExecutor") TaskExecutor executor) {
        this.challenges = challenges;
        this.executor = executor;
    }

    @Scheduled(fixedDelay = 5_000)
    void dispatch() {
        if (!active.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    for (int i = 0; i < 16 && challenges.finalizeNext(); i++) {
                        // Bound each pass on the executor shared with sync and personal rewards.
                    }
                } catch (RuntimeException error) {
                    LOG.warn("Challenge finalization failed. The result remains eligible for retry.");
                } finally {
                    active.set(false);
                }
            });
        } catch (TaskRejectedException shuttingDown) {
            active.set(false);
        }
    }
}
