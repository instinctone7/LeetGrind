package com.leetgrind.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
class WorkerConfiguration {
    @Bean(defaultCandidate = false, name = "workerExecutor")
    ThreadPoolTaskExecutor workerExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("leetgrind-worker-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
