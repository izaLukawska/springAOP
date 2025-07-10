package org.lukawska.springaop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class SmartCacheConfig {

    /**
     * Definiuje ConcurrentHashMap jako Spring Bean.
     * To sprawia, że Spring może automatycznie wstrzykiwać (autowire)
     * tę mapę do innych komponentów (np. SmartCachingAspect, AsyncCacheInvalidator).
     */
    @Bean
    public ConcurrentHashMap<String, WeakReference<Object>> localCache() {
        return new ConcurrentHashMap<>();
    }

    /**
     * Konfiguruje niestandardową pulę wątków dla metod asynchronicznych.
     * Metody z adnotacją @Async mogą używać tej puli, odwołując się do niej po nazwie (np. @Async
     * ("asyncInvalidatorExecutor")).
     * To pozwala na lepszą kontrolę nad liczbą wątków i zasobami.
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
