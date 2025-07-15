package org.lukawska.springaop.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RKeys;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class CacheInvalidator {

    private final RedissonClient redissonClient;

    /**
     * Asynchronously invalidates cache entries for specified patterns.
     * This method leverages Spring's @Async to execute the invalidation
     * process in a separate thread, ensuring it doesn't block the calling thread.
     * It deletes matching keys from Redis. Redisson's L1 cache invalidation
     * mechanism will automatically update local caches on all connected clients.
     *
     * @param patternsToEvict A Set of glob-style patterns representing keys to be evicted from Redis.
     */
    @Async
    public void invalidatePatternsAsync(Set<String> patternsToEvict) {
        patternsToEvict.forEach(pattern -> {
            log.info("[ASYNC] Attempting to evict keys matching the pattern: {}", pattern);

            RKeys keys = redissonClient.getKeys();
            long deletedCount = keys.deleteByPattern(pattern);

            log.info("[ASYNC] Deleted {} keys from Redis for pattern: {}", deletedCount, pattern);
        });
    }

    /**
     * Asynchronously invalidates cache entries for specified dependent caches.
     * This method uses @Async to run in a separate thread, preventing blocking.
     * It clears the entire RMapCache for each dependent cache.
     * Redisson's L1 cache invalidation mechanism will automatically update local caches on all connected clients.
     *
     * @param dependentCacheNames An array of String names for caches that need to be invalidated
     *                            due to their dependency on a primary cache operation.
     */
    @Async
    public void invalidateDependentCachesAsync(String[] dependentCacheNames) {
        if (dependentCacheNames == null || dependentCacheNames.length == 0) {
            log.debug("[ASYNC] Skipping due to no dependent cache found.");
            return;
        }
        log.info("[ASYNC] Starting invalidation for dependent caches: {}",
            String.join(", ", dependentCacheNames));

        for(String dependentCacheName : dependentCacheNames){
            log.info("[ASYNC] Processing dependent cache {}.", dependentCacheName);
            RMapCache<String, Object> dependentRMapCache = redissonClient.getMapCache(dependentCacheName);
            dependentRMapCache.clear();
            log.info("[ASYNC] Redisson dependent cache '{}' cleared.", dependentCacheName);
        }
    }

    public void invalidatePatternsSync(Set<String> patternsToEvict) {
        patternsToEvict.forEach(pattern -> {
            log.info("[SYNC] Attempting to evict keys matching the pattern: {}", pattern);
            RKeys keys = redissonClient.getKeys();
            long deletedCount = keys.deleteByPattern(pattern);
            log.info("[SYNC] Deleted {} keys from Redis for pattern: {}", deletedCount, pattern);
        });
    }

    public void invalidateDependentCachesSync(String[] dependentCacheNames) {
        if (dependentCacheNames == null || dependentCacheNames.length == 0) {
            log.debug("[SYNC] Skipping due to no dependent cache found.");
            return;
        }
        log.info("[SYNC] Starting invalidation for dependent caches: {}",
            String.join(", ", dependentCacheNames));

        for(String dependentCacheName : dependentCacheNames){
            log.info("[SYNC] Processing dependent cache {}.", dependentCacheName);
            RMapCache<String, Object> dependentRMapCache = redissonClient.getMapCache(dependentCacheName);
            dependentRMapCache.clear();
            log.info("[SYNC] Redisson dependent cache '{}' cleared.", dependentCacheName);
        }
    }
}
