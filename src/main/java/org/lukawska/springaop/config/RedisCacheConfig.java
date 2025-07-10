package org.lukawska.springaop.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableCaching
public class RedisCacheConfig {

    /**
     * Configuration for RedisTemplate.
     * We use GenericJackson2JsonRedisSerializer to map the object to JSON format,
     * and then it is serialized to bytecode for Redis storage.
     *
     * @param connectionFactory The Redis connection factory provided by Spring Boot based on settings in
     *                          {@code application.yml}.
     * @return Bean for RedisTemplate
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        GenericJackson2JsonRedisSerializer jsonRedisSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonRedisSerializer);
        template.setHashValueSerializer(jsonRedisSerializer);

        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        template.afterPropertiesSet();

        return template;
    }

    /**
     * Configuration for Cache Manager using RedisCacheConfiguration.defaultCacheConfig() for common scenarios.
     * We are setting the default TTL as a fallback and explicitly ensure that all cache keys are
     * serialized as readable strings using StringRedisSerializer.
     * Then we use GenericJackson2JsonRedisSerializer to make sure values (Java Objects) are serialized
     * into JSON format (instead of default JDK serialization), and we disable caching the null values.
     *
     * @param connectionFactory The Redis connection factory provided by Spring Boot based on settings in
     *                          {@code application.yml}.
     * @return RedisCacheManager using the connection factory and our default configuration.
     */

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultCacheConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
            .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultCacheConfig)
            .build();
    }
}
