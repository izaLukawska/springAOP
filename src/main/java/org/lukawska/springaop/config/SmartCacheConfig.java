package org.lukawska.springaop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class SmartCacheConfig {

    /**
     * Configures a custom thread pool for asynchronous methods.
     * Methods annotated with @Async can use this pool
     * by referring to it by name (e.g., @Async("asyncInvalidatorExecutor")).
     * This allows for better control over the number of threads and resources.
     */
    @Bean(name = "asyncInvalidatorExecutor")
    public Executor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("CacheAsync-");
        executor.initialize();
        return executor;
    }
}
