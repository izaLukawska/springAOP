package org.lukawska.springaop.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j

public class SmartCacheAspect {

    private final RedisTemplate<String, Object> redisTemplate;

    private final ExpressionParser expressionParser = new SpelExpressionParser();

    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    private final ConcurrentHashMap<String, WeakReference<Object>> weakRefLocalCache;

    private final CacheInvalidator cacheInvalidator;

    private final CacheMetricsService cacheMetricsService;

    /**
     * Handles caching logic for methods annotated with the {@link SmartCache} annotation.
     * Determines if a value is available in the local or Redis cache, or executes the original method
     * and inserts the resulting value into the caches.
     *
     * @param pjp        The {@link ProceedingJoinPoint} representing the method being executed.
     * @param smartCache The {@link SmartCache} annotation providing cache configuration such as cache name, key, and
     *                  TTL.
     * @return The cached value if it exists, otherwise the result of the method execution.
     * @throws Throwable If the original method invocation fails or throws an exception.
     */
    @Around("@annotation(smartCache)")
    public Object handleCaching(ProceedingJoinPoint pjp, SmartCache smartCache) throws Throwable {
        String cacheName = smartCache.cacheName();
        String cacheKey = generateSmartCacheKey(pjp, smartCache.key(), cacheName);
        String methodName = pjp.getSignature().toShortString();

        Object cachedValue = tryGetFromLocalCache(cacheKey, smartCache);
        if (cachedValue != null) {
            return cachedValue;
        }

        cachedValue = tryGetFromRedis(cacheKey, smartCache);
        if (cachedValue != null) {

            return cachedValue;
        }

        log.info("Cache MISS for key: {} ", cacheKey);
        cacheMetricsService.incrementCacheCounter("misses", cacheName,
            null, "Number of cache misses"); //

        long startTime = System.nanoTime();
        Object result = pjp.proceed();
        long durationNanos = System.nanoTime() - startTime;
        cacheMetricsService.recordMethodDuration(cacheName, methodName,
            "Duration of the method execution when cache is missed", durationNanos);

        if (result != null) {
            storeResultInCaches(cacheKey, result, smartCache);
        }

        return result;
    }

    /**
     * Handles the eviction of cache entries based on the provided {@link InvalidateCache} annotation settings.
     * This method identifies the cache names, key patterns, and dependent caches to invalidate
     * and dispatches them for invalidation either synchronously or asynchronously based on the configuration.
     * Logs details about the invalidation actions and skips the process when no relevant eviction patterns are found.
     *
     * @param invalidateCache The {@link InvalidateCache} annotation containing details such as cache names,
     *                        key patterns to invalidate, dependent caches, and whether the eviction should run
     *                        asynchronously.
     */
    @After("@annotation(invalidateCache)")
    public void evictCache(InvalidateCache invalidateCache) {
        Set<String> patternsToEvict = new java.util.HashSet<>();
        boolean isAsync = invalidateCache.async();

        handleCacheNameInvalidation(invalidateCache, patternsToEvict);
        handleKeyPatternInvalidation(invalidateCache, patternsToEvict);
        handleDependentCacheInvalidation(invalidateCache, isAsync);

        if (patternsToEvict.isEmpty() && invalidateCache.dependsOn().length == 0) {
            log.info("Skipping eviction due to no cache name or pattern found.");
            return;
        }

        if (!patternsToEvict.isEmpty()) {
            dispatchPatternInvalidation(patternsToEvict, isAsync);
        }
    }

    /**
     * Attempts to retrieve a value from the local WeakReference cache.
     *
     * @param cacheKey   The key for the cache entry.
     * @param smartCache The @SmartCache annotation, to check if a local cache is enabled.
     * @return The cached object if found, otherwise null.
     */
    private Object tryGetFromLocalCache(String cacheKey, SmartCache smartCache) {
        if (smartCache.useWeakReference()) {
            Object cachedValue = getFromWeakRefLocalCache(cacheKey);
            if (cachedValue != null) {
                log.info("Cache HIT (local - WeakReference) for key: {}'", cacheKey);
                cacheMetricsService.incrementCacheCounter("hits", smartCache.cacheName(),
                    "local", "Number of cache hits in local cache");
                return cachedValue;
            }
        }

        return null;
    }


    /**
     * Attempts to retrieve a value from Redis cache. If found, potentially stores it in a local cache.
     *
     * @param cacheKey   The key for the cache entry.
     * @param smartCache The @SmartCache annotation, to check if a local cache is enabled.
     * @return The cached object if found, otherwise null.
     */
    private Object tryGetFromRedis(String cacheKey, SmartCache smartCache) {
        Object cachedValue = redisTemplate.opsForValue().get(cacheKey);
        if (cachedValue != null) {
            log.info("Cache HIT (Redis) for key: {}", cacheKey);
            cacheMetricsService.incrementCacheCounter("hits", smartCache.cacheName(),
                "redis", "Number of cache hits in Redis cache");
            if (smartCache.useWeakReference()) {
                putInWeakRefLocalCache(cacheKey, cachedValue, smartCache.cacheName());
            }
            return cachedValue;
        }
        return null;
    }

    /**
     * Stores the result in Redis cache and potentially in local cache.
     *
     * @param cacheKey   The key for the cache entry.
     * @param result     The result to cache.
     * @param smartCache The @SmartCache annotation, for TTL and local cache settings.
     */
    private void storeResultInCaches(String cacheKey, Object result, SmartCache smartCache) {
        redisTemplate.opsForValue().set(cacheKey, result, smartCache.ttlSeconds(), TimeUnit.SECONDS);
        log.info("Cached result in Redis for key: {}', with TTL: {} s", cacheKey, smartCache.ttlSeconds());
        cacheMetricsService.incrementCacheCounter("puts", smartCache.cacheName(),
            "redis", "Number of items put into Redis cache");

        if (smartCache.useWeakReference()) {
            putInWeakRefLocalCache(cacheKey, result, smartCache.cacheName());
        }
    }

    /**
     * Retrieves a value from the WeakReference local cache, handling garbage collection.
     *
     * @param key The cache key.
     * @return The cached object, or null if not found or garbage collected.
     */
    private Object getFromWeakRefLocalCache(String key) {
        WeakReference<Object> ref = weakRefLocalCache.get(key);
        if (ref != null) {
            Object value = ref.get();
            if (value == null) {
                weakRefLocalCache.remove(key);
                log.debug("Local WeakReference cache entry for key: '{}' was garbage collected.", key);
            }
            return value;
        }
        return null;
    }

    /**
     * Puts a value into the WeakReference local cache and increments the put counter.
     *
     * @param key        The cache key.
     * @param value      The value to cache.
     * @param cacheName  The name of the cache for metrics tagging.
     */
    private void putInWeakRefLocalCache(String key, Object value, String cacheName) {
        weakRefLocalCache.put(key, new WeakReference<>(value));
        log.info("Cached result in local WeakReference cache for key: {}'", key);
        cacheMetricsService.incrementCacheCounter("puts", cacheName,
            "local", "Number of items put into local cache");
    }

    /**
     * Handles cache invalidation for specific cache names provided via the {@link InvalidateCache} annotation.
     * Updates eviction metrics, constructs eviction patterns, and removes local cache entries that match the
     * specified cache names.
     *
     * @param invalidateCache The {@link InvalidateCache} annotation containing the cache names to invalidate.
     * @param patternsToEvict A set where invalidation patterns will be added for further processing.
     */
    private void handleCacheNameInvalidation(InvalidateCache invalidateCache, Set<String> patternsToEvict) {
        if (invalidateCache.cacheNames().length > 0) {
            String cacheNamesStr = String.join(",", invalidateCache.cacheNames());
            cacheMetricsService.incrementEvictionByNameCounter(cacheNamesStr,
                "Number of cache evictions triggered by specific cache names.");
            Arrays.stream(invalidateCache.cacheNames())
                .forEach(cacheName -> {
                    patternsToEvict.add(cacheName + ":*");
                    weakRefLocalCache.keySet().removeIf(key -> key.startsWith(cacheName + ":"));
                    log.info("Local WeakReference cache keys matching '{}*' invalidated for cache name.", cacheName);
                });
        }
    }

    /**
     * Handles invalidation of cache entries based on a specified key pattern.
     * This method increments the eviction metrics, determines affected cache entries
     * using the key pattern, and removes them from the local WeakReference cache.
     * It also logs details of the invalidation process.
     *
     * @param invalidateCache The {@link InvalidateCache} annotation providing the key pattern
     *                        for cache invalidation and other related details.
     * @param patternsToEvict A set to which the key patterns targeted for eviction
     *                        will be added.
     */
    private void handleKeyPatternInvalidation(InvalidateCache invalidateCache, Set<String> patternsToEvict) {
        if (!invalidateCache.keyPattern().isEmpty()) {
            String cacheTag = inferCacheNameFromPattern(invalidateCache.keyPattern());
            cacheMetricsService.incrementEvictionByPatternCounter(cacheTag,
                "Number of cache evictions triggered by key patterns.");
            patternsToEvict.add(invalidateCache.keyPattern());

            String pattern = invalidateCache.keyPattern();
            weakRefLocalCache.keySet().removeIf(key -> {
                if (pattern.endsWith("*")) {
                    return key.startsWith(pattern.substring(0, pattern.length() - 1));
                }
                return key.equals(pattern);
            });

            log.info("Local WeakReference cache keys matching pattern '{}' invalidated.", pattern);
        }
    }

    /**
     * Handles invalidation of dependent caches specified in the {@link InvalidateCache} annotation.
     * This method processes each dependent cache name, increments the eviction metrics, removes
     * keys from the local WeakReference cache that are associated with the dependent caches and logs
     * information about the invalidation. Furthermore, it dispatches the invalidation of dependent
     * caches either asynchronously or synchronously based on the given configuration.
     *
     * @param invalidateCache The {@link InvalidateCache} annotation containing the list of dependent
     *                        caches to invalidate.
     * @param isAsync         Indicates whether the invalidation process should be executed asynchronously.
     */
    private void handleDependentCacheInvalidation(InvalidateCache invalidateCache, boolean isAsync) {
        if (invalidateCache.dependsOn().length > 0) {
            Arrays.stream(invalidateCache.dependsOn()).forEach(dependentCacheName -> {
                cacheMetricsService.incrementEvictionByDependencyCounter(dependentCacheName,
                    "Number of cache eviction triggered by dependencies.");
                weakRefLocalCache.keySet().removeIf(key -> key.startsWith(dependentCacheName + ":"));
                log.info("Local WeakReference cache keys dependent on '{}' invalidated.", dependentCacheName);
            });

            if (isAsync) {
                cacheInvalidator.invalidateDependentCachesAsync(invalidateCache.dependsOn());
            } else {
                cacheInvalidator.invalidateDependentCachesSync(invalidateCache.dependsOn());
            }
        }
    }

    /**
     * Dispatches cache invalidation for the specified patterns.
     * The invalidation can be executed either asynchronously or synchronously
     * based on the provided configuration.
     *
     * @param patternsToEvict A set of key patterns to be invalidated.
     * @param isAsync         Indicates whether the invalidation process
     *                        should run asynchronously (true) or synchronously (false).
     */
    private void dispatchPatternInvalidation(Set<String> patternsToEvict, boolean isAsync) {
        if (isAsync) {
            cacheInvalidator.invalidatePatternsAsync(patternsToEvict);
        } else {
            cacheInvalidator.invalidatePatternsSync(patternsToEvict);
        }
    }

    private String inferCacheNameFromPattern(String keyPattern) {
        if (keyPattern.contains(":")) {
            return keyPattern.substring(0, keyPattern.indexOf(":"));
        }
        return "unknown_or_multiple";
    }

    /**
     * Generates a unique cache key by combining the method name, method arguments, and optionally
     * a SpEL (Spring Expression Language) expression. The generated key is MD5-hashed and prefixed
     * with the specified cache name.
     *
     * @param pjp            The {@link ProceedingJoinPoint} representing the method being intercepted.
     *                        Used to extract method details and arguments.
     * @param spelExpression A SpEL expression that can be used to compute or customize the cache key.
     *                        If empty, the default key generation logic is applied.
     * @param cacheName      The name of the cache. This is used as a prefix for the final cache key.
     * @return A unique string representing the cache key, created by concatenating the cache name and
     *         an MD5 hash of the computed key suffix.
     */
    private String generateSmartCacheKey(ProceedingJoinPoint pjp, String spelExpression, String cacheName) {
        MethodSignature methodSignature = (MethodSignature) pjp.getSignature();
        Method method = methodSignature.getMethod();
        Object[] args = pjp.getArgs();

        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(method.getName());
        if (args != null && args.length > 0) {
            keyBuilder.append(":");
            keyBuilder.append(
                Arrays.stream(args)
                    .map(arg -> arg != null ? arg.toString() : "null")
                    .collect(Collectors.joining("-"))
            );
        }
        String keySuffix = keyBuilder.toString();

        if (!spelExpression.isEmpty()) {
            try {
                keySuffix = evaluateSpelExpression(pjp, spelExpression);
            } catch (Exception e) {
                log.error("Using default key due to failed SpEL evaluation: '{}' for method '{}'. Error: {}",
                    spelExpression, method.getName(), e.getMessage());
            }
        }

        return cacheName + ":" + DigestUtils.md5DigestAsHex(keySuffix.getBytes());
    }

    /**
     * Evaluates a Spring Expression Language (SpEL) expression in the context of the provided method signature
     * and arguments and returns the resulting value as a string.
     *
     * @param pjp The {@link ProceedingJoinPoint} representing the current method execution, used to extract
     *            method details and arguments.
     * @param spelExpression The SpEL expression to evaluate, which may reference method parameters.
     * @return The result of the evaluated SpEL expression as a string.
     */
    private String evaluateSpelExpression(ProceedingJoinPoint pjp, String spelExpression) {
        MethodSignature methodSignature = (MethodSignature) pjp.getSignature();
        Method method = methodSignature.getMethod();
        Object[] args = pjp.getArgs();

        EvaluationContext context = new StandardEvaluationContext();
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);

        if (parameterNames != null) {
            for(int i = 0; i < parameterNames.length; i++){
                context.setVariable(parameterNames[i], args[i]);
            }
        }

        return expressionParser.parseExpression(spelExpression).getValue(context, String.class);
    }
}
