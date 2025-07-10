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
public class CacheInvalidator {

    private final RedisTemplate<String, Object> redisTemplate;

    private final ConcurrentHashMap<String, WeakReference<Object>> localCache;

    /**
     * Asynchronously invalidates cache entries for specified patterns.
     * This method leverages Spring's @Async to execute the invalidation
     * process in a separate thread, ensuring it doesn't block the calling thread.
     * It deletes matching keys from Redis and then removes them from the local in-memory cache.
     *
     * @param patternsToEvict A Set of glob-style patterns representing keys to be evicted from Redis.
     */
    @Async
    public void invalidatePatternsAsync(Set<String> patternsToEvict) {
        patternsToEvict.forEach(pattern -> {
            log.info("[ASYNC] Attempting to evict keys matching the pattern: {}", pattern);
            Set<String> keysToEvict = redisTemplate.keys(pattern);

            if (!keysToEvict.isEmpty()) {
                Long deletedCount = redisTemplate.delete(keysToEvict);
                log.info("[ASYNC] Deleted {} keys from Redis for pattern: {}", deletedCount, pattern);

                keysToEvict.forEach(localCache::remove);
                log.info("[ASYNC] Deleted {} keys from local WeakReference cache for pattern: {}", keysToEvict.size()
                    , pattern);
            } else {
                log.info("[ASYNC] No keys found to evict for pattern: {}", pattern);
            }
        });
    }

    /**
     * Asynchronously invalidates cache entries for specified dependent caches.
     * This method uses @Async to run in a separate thread, preventing blocking.
     * It constructs glob-style patterns for each dependent cache, retrieves matching keys,
     * deletes them from Redis, and then removes them from the local in-memory cache.
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
        log.info("[ASYNC] Starting invalidation for dependent caches: {}", String.join(", ", dependentCacheNames));
        for(String dependentCacheName : dependentCacheNames){
            String dependentPattern = dependentCacheName + ":*";
            log.info("[ASYNC] Processing dependent cache {}, matching pattern: {}.",
                dependentCacheName, dependentPattern);

            Set<String> dependentKeysToEvict = redisTemplate.keys(dependentPattern);

            if (!dependentKeysToEvict.isEmpty()) {
                Long deletedDependentCount = redisTemplate.delete(dependentKeysToEvict);
                log.info("[ASYNC] Deleted {} keys from Redis for dependent cache '{}'.",
                    deletedDependentCount, dependentCacheName);

                dependentKeysToEvict.forEach(localCache::remove);
                log.info("[ASYNC] Deleted {} keys from local WeakReference cache for dependent cache '{}'.",
                    dependentKeysToEvict.size(), dependentCacheName);
            } else {
                log.info("[ASYNC] No keys found for dependent cache '{}' matching pattern: '{}'.",
                    dependentCacheName, dependentPattern);
            }
        }
    }

    /**
     * Synchronously invalidates cache entries for specified patterns.
     * This method executes in the calling thread, ensuring the invalidation process
     * completes before the calling method returns.
     *
     * @param patternsToEvict A Set of glob-style patterns representing keys to be evicted from Redis.
     */
    public void invalidatePatternsSync(Set<String> patternsToEvict) {
        patternsToEvict.forEach(pattern -> {
            log.info("[SYNC] Attempting to evict keys matching the pattern: {}", pattern);
            Set<String> keysToEvict = redisTemplate.keys(pattern);

            if (!keysToEvict.isEmpty()) {
                Long deletedCount = redisTemplate.delete(keysToEvict);
                log.info("[SYNC] Deleted {} keys from Redis for pattern: {}", deletedCount, pattern);

                // --- Usuwanie z lokalnego cache'a WeakReference ---
                keysToEvict.forEach(localCache::remove);
                log.info("[SYNC] Deleted {} keys from local WeakReference cache for pattern: {}", keysToEvict.size(),
                    pattern);
            } else {
                log.info("[SYNC] No keys found to evict for pattern: {}", pattern);
            }
        });
    }

    /**
     * Synchronously invalidates cache entries for specified dependent caches.
     * This method executes in the calling thread, ensuring the invalidation process
     * completes before the calling method returns.
     *
     * @param dependentCacheNames An array of String names for caches that need to be invalidated
     *                            due to their dependency on a primary cache operation.
     */
    public void invalidateDependentCachesSync(String[] dependentCacheNames) {
        if (dependentCacheNames == null || dependentCacheNames.length == 0) {
            log.debug("[SYNC] Skipping due to no dependent cache found.");
            return;
        }
        log.info("[SYNC] Starting invalidation for dependent caches: {}",
            String.join(", ", dependentCacheNames));
        for(String dependentCacheName : dependentCacheNames){
            String dependentPattern = dependentCacheName + ":*";
            log.info("[SYNC] Processing dependent cache {}, matching pattern: {}.",
                dependentCacheName, dependentPattern);

            Set<String> dependentKeysToEvict = redisTemplate.keys(dependentPattern);

            if (!dependentKeysToEvict.isEmpty()) {
                Long deletedDependentCount = redisTemplate.delete(dependentKeysToEvict);
                log.info("[SYNC] Deleted {} keys from Redis for dependent cache '{}'.",
                    deletedDependentCount, dependentCacheName);

                dependentKeysToEvict.forEach(localCache::remove);
                log.info("[SYNC] Deleted {} keys from local WeakReference cache for dependent cache '{}'.",
                    dependentKeysToEvict.size(), dependentCacheName);
            } else {
                log.info("[SYNC] No keys found for dependent cache '{}' matching pattern: '{}'.",
                    dependentCacheName, dependentPattern);
            }
        }
    }
}
