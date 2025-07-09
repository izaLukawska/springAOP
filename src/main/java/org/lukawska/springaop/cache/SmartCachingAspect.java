package org.lukawska.springaop.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class SmartCachingAspect {

    private final CacheManager cacheManager;

    private final RedisTemplate<String, Object> redisTemplate;

    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    private final Map<String, Map<String, Object>> localWeakRefCache = new WeakHashMap<>();

    /**
     * This is the core Around advice for caching operations. It intercepts method calls annotated with
     * {@link SmartCache} to provide caching functionality.
     * Method implements a two-level caching strategy prioritizing a local in-memory cache (using weak references)
     * before checking Redis for performance optimization.
     * Steps:
     * 1. Generates a unique cache key based on method signature, arguments, and optional SpEL expression.
     * 2. Attempts to retrieve the cached value from the local in-memory cache (a {@link WeakHashMap}).
     * 3. For Local Cache HIT, then the value is returned immediately
     * (bypassing Redis and the original method execution).
     * 4. For Local Cache MISS, then checks the Redis Cache and retrieves the appropriate cache instance from
     * {@link CacheManager} and attempts to retrieve the cached value from Redis using the generated cache key.
     * 5. For Redis Cache HIT it checks if the cached entry in Redis is still valid using
     * {@link #isCacheValid(String)}.
     * If the value is valid, it is retrieved from Redis and if {@code smartCache.useWeakReference()} is true it's
     * stored in the local cache for future rapid access, and then returned. Otherwise, it's evicted from Redis.
     * 6. For Redis Cache MISS (not found locally, or Redis entry was expired/ not found) then
     * The original intercepted method is executed via {@link ProceedingJoinPoint#proceed()}. If the method returns a
     * non-null result, this result is cached in both Redis (with TTL set by
     * {@link #setCacheTTL(String, long)}).
     * If {@code smartCache.useWeakReference()} is true, the result is also cached in the local in-memory cache.
     * 7. Returning the result (either from cache or method execution).
     *
     * @param pjp        The {@link ProceedingJoinPoint} representing the intercepted method execution.
     *                   Allows proceeding with the original method or returning a cached value.
     * @param smartCache The {@link SmartCache} annotation instance found on the intercepted method,
     *                   providing caching configuration details (cache name, key, TTL).
     * @return The result of the cached operation, either from the cache or from the original method execution.
     * @throws Throwable If the original method execution throws an exception.
     */
    @Around("@annotation(smartCache)")
    public Object handleCaching(ProceedingJoinPoint pjp, SmartCache smartCache) throws Throwable {
        String cacheKey = generateCacheKey(pjp, smartCache);
        String cacheName = smartCache.cacheName();

        log.info("Cache operation for key: {} on cache: {}", cacheKey, cacheName);

        Map<String, Object> namedLocalCache = localWeakRefCache.computeIfAbsent(cacheName, k -> new WeakHashMap<>());
        Object localResult = namedLocalCache.get(cacheKey);

        if (localResult != null) {
            log.info("Local Cache HIT for key: {} in cache: {}", cacheKey, cacheName);
            return localResult;
        }

        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            log.error("Cache does not exist: {}", cacheName);
            throw new IllegalStateException("Cache doesn't exist: " + cacheName);
        }

        Cache.ValueWrapper wrapper = cache.get(cacheKey);

        if (wrapper != null) {
            if (isCacheValid(cacheKey)) {
                Object redisResult = wrapper.get();
                log.info("Cache HIT valid for key: {} in cache: {}", cacheKey, cacheName);
                namedLocalCache.put(cacheKey, redisResult);
                return redisResult;
            } else {
                log.info("Cache HIT expired for key:'{} in cache: {}", cacheKey, cacheName);
                cache.evict(cacheKey);
            }
        }

        log.info("Cache MISS for key: {} in cache: {}. Executing method.", cacheKey, cacheName);
        Object result = pjp.proceed();

        if (result != null) {
            cache.put(cacheKey, result);
            setCacheTTL(cacheKey, smartCache.ttlSeconds());
            if (smartCache.useWeakReference()) {
                namedLocalCache.put(cacheKey, result);
                log.info("Redis and Local WeakRef cache for key: {} in cache: {}", cacheKey, cacheName);
            } else {
                log.info("Redis only cache for key: {} in cache: {}", cacheKey, cacheName);
            }
        } else {
            log.info("Method returned null for key: {}. Not caching null result.", cacheKey);
        }

        return result;
    }

    /**
     * Method used to evict cache entries after a method annotated with {@code @InvalidateCache} is executed.
     * It identifies cache keys in Redis that match a specified pattern and removes them.
     * It also attempts to invalidate matching keys from the local in-memory cache.
     * Additionally, it triggers invalidation for other caches specified in the {@code dependsOn} attribute.
     *
     * @param invalidateCache The {@link InvalidateCache} annotation instance,
     *                        providing the {@code keyPattern} for eviction and optionally {@code dependsOn} caches.
     */
    @After("@annotation(invalidateCache)")
    public void evictCache(InvalidateCache invalidateCache) {
        String pattern = invalidateCache.keyPattern();
        log.info("Attempting to evict cache keys matching pattern: '{}'.", pattern);

        Set<String> keysToEvict = redisTemplate.keys(pattern);

        if (!keysToEvict.isEmpty()) {
            Long deletedCount = redisTemplate.delete(keysToEvict);
            log.info("Evicted {} keys from Redis matching pattern: {}", deletedCount, pattern);

            for(String key : keysToEvict){
                String cacheName = getCacheNameFromKey(key);
                if (cacheName != null) {
                    invalidateLocalCache(cacheName, key);
                } else {
                    log.warn("Could not derive cache name from key {} ", key);
                }
            }
        } else {
            log.info("No keys found to evict for pattern: '{}'.", pattern);
        }

    }

    /**
     * Invalidates a specific key in the local in-memory WeakHashMap cache for a given cache name.
     * This ensures consistency between Redis and the local cache when an entry is evicted.
     *
     * @param cacheName The name of the cache (e.g., "users") from which to evict the key.
     * @param cacheKey  The full Redis key (e.g., "users:12345") to evict from the local cache.
     */
    private void invalidateLocalCache(String cacheName, String cacheKey) {
        Map<String, Object> namedLocalCache = localWeakRefCache.get(cacheName);
        if (namedLocalCache != null) {
            Object removed = namedLocalCache.remove(cacheKey);
            if (removed != null) {
                log.debug("Evicted key '{}' from local cache '{}'.", cacheKey, cacheName);
            }
        }
    }

    /**
     * Helper method for targeting local cache invalidation.
     * Extracts the cache name from a given Redis cache key, assuming the format "cacheName:hashedKey".
     *
     * @param cacheKey The full Redis key (e.g., "users:12345").
     * @return The extracted cache name (e.g., "users"), or null if the format is unexpected.
     */
    private String getCacheNameFromKey(String cacheKey) {
        int colonIndex = cacheKey.indexOf(":");
        return colonIndex > 0 ? cacheKey.substring(0, colonIndex) : null;
    }

    /**
     * Generates a unique cache key for a method invocation, incorporating the cache name and an optional SpEL
     * expression.
     * This key is used to store and retrieve values from both Redis and the local in-memory cache.
     * Steps:
     * 1. Building a base key string from the method's short signature and its arguments.
     * 2. If a SpEL {@code keyExpression} is provided in {@link SmartCache}, it attempts to evaluate it.
     * If evaluation is successful, the result of the SpEL expression becomes the base key string.
     * In case of a SpEL evaluation error, it falls back to the default key generated in step 1.
     * 3. The final base key string is then hashed using MD5 for consistency.
     * 4. The resulting MD5 hash is prefixed with the {@code cacheName} (e.g., "users:hashedKey") to ensure
     * keys are uniquely identifiable per cache and to facilitate targeted invalidation.
     *
     * @param pjp        The {@link ProceedingJoinPoint} representing the intercepted method execution,
     *                   providing access to method signature and arguments.
     * @param smartCache The {@link SmartCache} annotation instance, which supplies the
     *                   {@code cacheName} and an optional {@code key} (SpEL expression).
     * @return A unique, hashed cache key prefixed with the cache's name (e.g., "cacheName: md5hash").
     */
    private String generateCacheKey(ProceedingJoinPoint pjp, SmartCache smartCache) {
        log.debug("Generating smart cache key for method: '{}'.", pjp.getSignature().toShortString());

        String keyString = generateCacheKey(pjp);

        String keyExpression = smartCache.key();

        if (!keyExpression.isEmpty()) {
            try {
                keyString = evaluateSpEL_Expression(keyExpression, pjp);
                log.debug("Generated key using SpEL expression {}: {}.", keyExpression, keyString);
            } catch (Exception e) {
                log.error("Failed to evaluate SpEL expression {} for method '{}. Error: {}",
                    keyExpression, pjp.getSignature().toShortString(), e.getMessage());
                keyString = generateCacheKey(pjp);
            }
        }

        String hashedKey = DigestUtils.md5DigestAsHex(keyString.getBytes());
        return smartCache.cacheName() + ":" + hashedKey;
    }

    /**
     * Default unique cache key generator based on the method's signature and its arguments.
     * Converts the resulting concatenated string into an MD5 hash for compact representation.
     *
     * @param pjp The {@link ProceedingJoinPoint} instance representing the intercepted method invocation.
     *            Used to extract method signature and arguments for key generation.
     * @return A {@link String} representing the generated cache key in MD5 hash format.
     */
    private String generateCacheKey(ProceedingJoinPoint pjp) {
        log.info("Generating smart cache key for method: '{}'.", pjp.getSignature().toShortString());

        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(pjp.getSignature().toShortString());

        Object[] args = pjp.getArgs();
        for(Object arg : args){
            if (arg != null) {
                keyBuilder.append(":").append(arg);
            }
        }

        return DigestUtils.md5DigestAsHex(keyBuilder.toString().getBytes());
    }

    /**
     * Evaluates a Spring Expression Language (SpEL) expression within the context of a method's
     * parameters and returns the result as a string.
     *
     * @param expression The SpEL expression to be evaluated. It can reference the method's parameters by name.
     * @param pjp        The {@link ProceedingJoinPoint} instance representing the method being intercepted.
     *                   Used to retrieve method parameter names and values.
     * @return The result of the evaluated SpEL expression as a string.
     */
    private String evaluateSpEL_Expression(String expression, ProceedingJoinPoint pjp) {
        ExpressionParser parser = new SpelExpressionParser();
        EvaluationContext context = new StandardEvaluationContext();

        MethodSignature methodSignature = (MethodSignature) pjp.getSignature();
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(methodSignature.getMethod());
        Object[] args = pjp.getArgs();

        if (parameterNames != null) {
            for(int i = 0; i < parameterNames.length; i++){
                context.setVariable(parameterNames[i], args[i]);
            }
        }

        return parser.parseExpression(expression).getValue(context, String.class);
    }

    /**
     * Sets a time-to-live (TTL) for the specified cache key. If the TTL value is greater than 0,
     * the cache key is configured to expire after the given duration in seconds. If the TTL value is
     * 0 or less, no expiration is set, and an informational log message is generated.
     *
     * @param cacheKey   The Redis cache key for which the TTL is to be set.
     * @param ttlSeconds The time-to-live duration in seconds. Must be greater than 0 to set expiration.
     */
    private void setCacheTTL(String cacheKey, long ttlSeconds) {
        if (ttlSeconds > 0) {
            redisTemplate.expire(cacheKey, ttlSeconds, TimeUnit.SECONDS);
            log.info("Set TTL for cache key: {} to {} seconds.", cacheKey, ttlSeconds);
        } else {
            log.info("TTL for cache key: {} is 0 or less; expiration not set.", cacheKey);
        }
    }

    /**
     * Checks whether a given cache key is valid based on its time-to-live (TTL) in Redis.
     * Logs information or warnings about the cache key's state.
     *
     * @param cacheKey The Redis cache key to be checked for validity.
     * @return {@code true} if the cache key is valid (exists with a positive TTL or no expiration);
     * {@code false} if the cache key does not exist or has expired.
     */
    private boolean isCacheValid(String cacheKey) {
        long ttl = redisTemplate.getExpire(cacheKey);

        if (ttl == -2L) {
            log.info("Cache key: {}'does not exist or has expired in Redis.", cacheKey);
            return false;
        }

        if (ttl == -1L) {
            log.info("Cache key exists with no expiration  set: {}", cacheKey);
            return true;
        }

        if (ttl > 0) {
            log.info("Cache key: {} is valid with seconds remaining: {}", cacheKey, ttl);
            return true;
        }

        log.warn("Unexpected TTL value for cache key: '{}': {}", cacheKey, ttl);

        return false;
    }
}
