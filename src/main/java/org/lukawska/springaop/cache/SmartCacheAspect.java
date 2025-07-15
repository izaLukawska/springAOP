package org.lukawska.springaop.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.LocalCachedMapOptions;
import org.redisson.api.options.LocalCachedMapOptions.ReconnectionStrategy;
import org.redisson.api.options.LocalCachedMapOptions.SyncStrategy;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j

public class SmartCacheAspect {

    private final RedissonClient redissonClient;

    private final CacheMetricsService cacheMetricsService;

    private final CacheInvalidator cacheInvalidator;

    private final ExpressionParser expressionParser = new SpelExpressionParser();

    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * Handles caching logic for methods annotated with the {@link SmartCache} annotation.
     * Determines if a value is available in the Redisson or executes the original method
     * and inserts the resulting value into the cache.
     *
     * @param pjp        The {@link ProceedingJoinPoint} representing the method being executed.
     * @param smartCache The {@link SmartCache} annotation providing cache configuration such as cache name, key, and
     *                   TTL.
     * @return The cached value if it exists, otherwise the result of the method execution.
     * @throws Throwable If the original method invocation fails or throws an exception.
     */
    @Around("@annotation(smartCache)")
    public Object handleCaching(ProceedingJoinPoint pjp, SmartCache smartCache) throws Throwable {
        String cacheName = smartCache.cacheName();
        String cacheKey = generateSmartCacheKey(pjp, smartCache.key(), cacheName);
        String methodName = pjp.getSignature().toShortString();

        RMapCache<String, Object> rMapCache = getOrCreateRMapCache(cacheName, smartCache.useWeakReference());

        // Próba pobrania wartości z cache'a Redisson (automatycznie obsługuje L1 cache, jeśli skonfigurowany)
        Object cachedValue = rMapCache.get(cacheKey);

        if (cachedValue != null) {
            log.info("Cache HIT (Redisson) for key: {}", cacheKey);
            // Metryki dla trafień w cache (Redisson obsługuje lokalny L1 cache, jeśli jest włączony)
            cacheMetricsService.incrementCacheCounter("hits", cacheName,
                smartCache.useWeakReference() ? "redisson_l1" : "redisson", "Cache HIT count (Redisson)");
            return cachedValue;
        }

        log.info("Cache MISS for key: {} ", cacheKey);
        cacheMetricsService.incrementCacheCounter("misses", cacheName,
            null, "Cache MISS count");

        long startTime = System.nanoTime();
        Object result = pjp.proceed();
        long durationNanos = System.nanoTime() - startTime;
        cacheMetricsService.recordMethodDuration(cacheName, methodName,
            "Duration of the method execution when cache is missed", durationNanos);

        if (result != null) {
            rMapCache.put(cacheKey, result, smartCache.ttlSeconds(), TimeUnit.SECONDS);
            log.info("Cached result in Redisson for key: {}, with TTL: {}s", cacheKey, smartCache.ttlSeconds());
            cacheMetricsService.incrementCacheCounter("puts", cacheName,
                smartCache.useWeakReference() ? "redisson_l1" : "redisson", "Cache PUT count (Redisson)");
        }

        return result;
    }

    /**
     * Helper method to get or create an RMapCache instance, potentially with L1 local caching.
     * We configure L1 for a specific cache and synchronize changes from Redis to L1, clears L1 upon connection loss.
     *
     * @param cacheName  The name of the cache.
     * @param useL1Cache Whether to enable L1 local caching for this map.
     * @return An RMapCache instance (or RLocalCachedMap if L1 cache is enabled).
     */
    @SuppressWarnings("unchecked")
    private RMapCache<String, Object> getOrCreateRMapCache(String cacheName, boolean useL1Cache) {
        if (useL1Cache) {
            LocalCachedMapOptions<String, Object> options = LocalCachedMapOptions.<String, Object>name(cacheName)
                .syncStrategy(SyncStrategy.UPDATE)
                .reconnectionStrategy(ReconnectionStrategy.CLEAR);
            log.debug("Creating RLocalCachedMap for cacheName: {} with L1 cache enabled.", cacheName);
            return (RMapCache<String, Object>) redissonClient.getLocalCachedMap(options);
        } else {
            log.debug("Creating RMapCache for cacheName: {} without L1 cache.", cacheName);
            return redissonClient.getMapCache(cacheName);
        }
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
        Set<String> patternsToEvict = new HashSet<>();
        boolean isAsync = invalidateCache.async();

        handleCacheNameInvalidation(invalidateCache);
        handleKeyPatternInvalidation(invalidateCache, patternsToEvict);
        handleDependentCacheInvalidation(invalidateCache, isAsync);

        if (patternsToEvict.isEmpty() && invalidateCache.dependsOn().length == 0) {
            log.info("Skipping eviction (no cache name or pattern found).");
            return;
        }

        if (!patternsToEvict.isEmpty()) {
            dispatchPatternInvalidation(patternsToEvict, isAsync);
        }
    }

    /**
     * Handles cache invalidation for specific cache names provided via the {@link InvalidateCache} annotation.
     * Clears the entire RMapCache for each specified cache name and sends L1 invalidation signal.
     *
     * @param invalidateCache The {@link InvalidateCache} annotation containing the cache names to invalidate.
     */
    private void handleCacheNameInvalidation(InvalidateCache invalidateCache) {
        if (invalidateCache.cacheNames().length > 0) {
            String cacheNamesStr = String.join(",", invalidateCache.cacheNames());
            cacheMetricsService.incrementEvictionByNameCounter(cacheNamesStr,
                "Number of cache evictions triggered by specific cache names.");
            Arrays.stream(invalidateCache.cacheNames())
                .forEach(cacheName -> {
                    RMapCache<String, Object> rMapCache = redissonClient.getMapCache(cacheName);
                    rMapCache.clear();
                    log.info("Redisson cache '{}' cleared.", cacheName);
                });
        }
    }

    /**
     * Handles invalidation of cache entries based on a specified key pattern.
     * This method increments the eviction metrics and adds the pattern to a set for
     * asynchronous processing by CacheInvalidator.
     *
     * @param invalidateCache The {@link InvalidateCache} annotation providing the key pattern
     *                        for cache invalidation and other related details.
     * @param patternsToEvict A set to which the key patterns targeted for eviction
     *                        will be added.
     */
    private void handleKeyPatternInvalidation(InvalidateCache invalidateCache, Set<String> patternsToEvict) {
        String keyPattern = invalidateCache.keyPattern();
        if (!keyPattern.isEmpty()) {
            String cacheTag;
            if (keyPattern.contains(":")) {
                cacheTag = keyPattern.substring(0, keyPattern.indexOf(":"));
            } else {
                cacheTag = "unknown_or_multiple";
            }

            cacheMetricsService.incrementEvictionByPatternCounter(cacheTag,
                "Number of cache evictions triggered by key patterns.");

            patternsToEvict.add(invalidateCache.keyPattern());

            log.debug("Key pattern '{}' added for asynchronous invalidation.", invalidateCache.keyPattern());
        }
    }

    /**
     * Handles invalidation of dependent caches specified in the {@link InvalidateCache} annotation.
     * This method processes each dependent cache name, increments the eviction metrics, and
     * dispatches the invalidation of dependent caches either asynchronously or synchronously.
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

                if (isAsync) {
                    cacheInvalidator.invalidateDependentCachesAsync(invalidateCache.dependsOn());
                } else {
                    cacheInvalidator.invalidateDependentCachesSync(invalidateCache.dependsOn());
                }
            });
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

    /**
     * Generates a unique cache key by combining the method name, method arguments, and optionally
     * a SpEL (Spring Expression Language) expression. The generated key is MD5-hashed and prefixed
     * with the specified cache name.
     *
     * @param pjp            The {@link ProceedingJoinPoint} representing the method being intercepted.
     *                       Used to extract method details and arguments.
     * @param spELExpression A SpEL expression that can be used to compute or customize the cache key.
     *                       If empty, the default key generation logic is applied.
     * @param cacheName      The name of the cache. This is used as a prefix for the final cache key.
     * @return A unique string representing the cache key, created by concatenating the cache name and
     * an MD5 hash of the computed key suffix.
     */
    private String generateSmartCacheKey(ProceedingJoinPoint pjp, String spELExpression, String cacheName) {
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

        if (!spELExpression.isEmpty()) {
            try {
                keySuffix = evaluateSpELExpression(pjp, spELExpression);
            } catch (Exception e) {
                log.error("Using default key (SpEL evaluation fail): {} for method {}. Error: {}",
                    spELExpression, method.getName(), e.getMessage());
            }
        }

        return cacheName + ":" + DigestUtils.md5DigestAsHex(keySuffix.getBytes());
    }

    /**
     * Evaluates a Spring Expression Language (SpEL) expression in the context of the provided method signature
     * and arguments and returns the resulting value as a string.
     *
     * @param pjp            The {@link ProceedingJoinPoint} representing the current method execution, used to extract
     *                       method details and arguments.
     * @param spELExpression The SpEL expression to evaluate, which may reference method parameters.
     * @return The result of the evaluated SpEL expression as a string.
     */
    private String evaluateSpELExpression(ProceedingJoinPoint pjp, String spELExpression) {
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

        return expressionParser.parseExpression(spELExpression).getValue(context, String.class);
    }
}
