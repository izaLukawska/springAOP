package org.lukawska.springaop.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.SerializationCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    /**
     * Redis server host obtained from the `spring.data.redis.host` property in the configuration file.
     * The default value is "127.0.0.1" if the property is not set.
     */
    @Value("${spring.data.redis.host:127.0.0.1}")
    private String redisHost;

    /**
     * Redis server port obtained from the `spring.data.redis.port` property in the configuration file.
     * The default value is 6379 if the property is not set.
     */
    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    /**
     * Redis database number obtained from the `spring.data.redis.database` property in the configuration file.
     * The default value is 0 if the property is not set.
     */
    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    /**
     * Configures and returns a RedissonClient instance.
     * The Redisson client is used to interact with Redis, providing functionalities such as
     * distributed locks and distributed data structures (e.g., RMapCache).
     * This method also includes optional configurations for:
     * <p>
     * L1 Cache (Local Cache) for RMapCache:** This is highly recommended for caching
     * as it allows Redisson to maintain a local in-memory cache alongside the main Redis cache.
     * This configuration serves as a default for all RMapCache instances unless explicitly
     * overridden when a specific RMapCache is created (e.g., in {@code SmartCacheAspect}
     * when {@code useWeakReference} is enabled). A key benefit is automatic L1 cache
     * invalidation across all connected application instances via Redisson's Pub/Sub mechanism,
     * ensuring data consistency.
     * <p>
     * Lua Script Caching:** {@code config.setUseScriptCache(true)} enables caching of Lua scripts
     * on the Redis server. This is a good practice for performance, as it prevents re-transmission
     * and re-compilation of frequently executed scripts.
     * <p>
     * Other Global Settings:** You can add additional global Redisson settings here, such as
     * connection timeouts or retry policies. For instance, {@code config.setLockWatchdogTimeout(30000)}
     * sets the timeout for the distributed lock watchdog, which automatically extends the lock's
     * lease time. The default watchdog timeout is 30 seconds.
     *
     * @return Configured RedissonClient.
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.setUseScriptCache(true)
            .setCodec(new SerializationCodec())
            .useSingleServer()
            .setAddress("redis://" + redisHost + ":" + redisPort)
            .setDatabase(redisDatabase);

        return Redisson.create(config);
    }
}
