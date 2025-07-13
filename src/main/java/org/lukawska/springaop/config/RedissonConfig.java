package org.lukawska.springaop.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    /**
     * Creates a RedissonClient bean configured to connect to a local Redis server on port 6379.
     * <p></p>
     * Redisson is a Redis client for Java that provides distributed locks, caches,
     * synchronization primitives, and more.
     * <p>
     * The {@code destroyMethod = "shutdown"} ensures that Spring will call the {@code shutdown()}
     * method on application shutdown to properly release resources and connections.
     *
     * @return RedissonClient the main interface for interacting with Redis using Redisson.
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
            .setAddress("redis://localhost:6379");
        return Redisson.create(config);
    }
}
