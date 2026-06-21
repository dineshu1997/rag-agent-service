package com.dinesh.rag.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Async executor used by {@code @Async} methods (notably
 * {@code FileIngestionService.ingest}).
 *
 * <p>Sized small on purpose: Ollama serves one request at a time per model,
 * so spraying many parallel embedders just queues at the Ollama side and
 * wastes thread headroom. Two workers lets a parse-heavy job overlap with
 * an embed-heavy one without thrashing.</p>
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "ingestionExecutor")
    public TaskExecutor ingestionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ingest-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
