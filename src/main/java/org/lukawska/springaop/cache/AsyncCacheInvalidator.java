package org.lukawska.springaop.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class AsyncCacheInvalidator {

    private final RedisTemplate<String, Object> redisTemplate;

    private final ConcurrentHashMap<String, WeakReference<Object>> localCache;

    /**
     * Asynchronously invalidates the primary cache entries based on provided patterns.
     * This method is executed in a separate thread due to the {@code @Async} annotation,
     * ensuring that the cache invalidation does not block the calling thread.
     * The corresponding keys are removed from both Redis and the local cache.
     * Steps:
     * 1. Retrieves all keys from Redis that match the given patterns (e.g., "cacheName:*").
     * 2. Deletes these matching keys from Redis.
     * 3. Removes the same keys from the local in-memory cache to ensure consistency.
     *
     * @param patternsToEvict A set of glob-style patterns (e.g., "users:*", "specificKey")
     *                        specifying which cache entries should be evicted.
     */
    @Async
    public void invalidatePatternsAsync(Set<String> patternsToEvict) {
        patternsToEvict.forEach(pattern -> {
            log.info("[ASYNC] Attempt to evict keys matching the pattern: {}", pattern);
            Set<String> keysToEvict = redisTemplate.keys(pattern);

            if (!keysToEvict.isEmpty()) {
                Long deletedCount = redisTemplate.delete(keysToEvict);
                log.info("[ASYNC] Deleted keys from Redis: {}", deletedCount);
                keysToEvict.forEach(localCache::remove);
            } else {
                log.info("[ASYNC] Nie znaleziono kluczy do unieważnienia dla wzorca podstawowego: '{}'.", pattern);
            }
        });
    }

    /**
     * Asynchronously invalidates cache entries for specified dependent caches using
     * {@code @Async} annotation to execute the method in separate thread
     * ensuring the invalidation process does not block the calling thread.
     * It skips the invalidation if no dependent cache names are provided (i.e., the array is null or empty).
     * Steps for each dependent cache name:
     * 1. Constructs a glob-style pattern (e.g., "dependentCacheName:*") to match all keys within that cache.
     * 2. Retrieves all matching keys from Redis.
     * 3. Deletes the matching keys from Redis.
     * 4. Removes these same keys from the local in-memory cache to maintain consistency.
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
        log.info("[ASYNC] Starting invalidation for: {}", String.join(", ", dependentCacheNames));
        for(String dependentCacheName : dependentCacheNames){
            String dependentPattern = dependentCacheName + ":*";
            log.info("[ASYNC] Processing dependent cache '{}', matching pattern: '{}'.",
                dependentCacheName, dependentPattern);

            Set<String> dependentKeysToEvict = redisTemplate.keys(dependentPattern);

            if (!dependentKeysToEvict.isEmpty()) {
                Long deletedDependentCount = redisTemplate.delete(dependentKeysToEvict);
                log.info("[ASYNC] Deleted {} keys for dependent cache '{}'.",
                    deletedDependentCount, dependentCacheName);
                dependentKeysToEvict.forEach(localCache::remove);
            } else {
                log.info("[ASYNC] No keys found for dependent cache '{}' matching pattern: '{}'.",
                    dependentCacheName, dependentPattern);
            }
        }
    }
}
