package org.lukawska.springaop.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
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

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class SmartCachingAspect {

    private final RedisTemplate<String, Object> redisTemplate;

    private final ExpressionParser expressionParser = new SpelExpressionParser();

    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    private final ConcurrentHashMap<String, WeakReference<Object>> localCache;

    private final AsyncCacheInvalidator asyncCacheInvalidator;

    private final CacheMetricsService cacheMetricsService;

    /**
     * Main aspect handling the {@code @SmartCache} annotation.
     * It's responsible for retrieving data from the cache (local or Redis)
     * and saving it to the cache if the data wasn't found.
     *
     * @param pjp        The {@link ProceedingJoinPoint} object to continue method execution.
     * @param smartCache The {@code @SmartCache} annotation with its parameters.
     * @return The return value of the method, originating either from the cache or from the actual method execution.
     * @throws Throwable If an error occurs during method execution.
     */
    @Around("@annotation(smartCache)")
    public Object cacheMethod(ProceedingJoinPoint pjp, SmartCache smartCache) throws Throwable {
        String cacheName = smartCache.cacheName();
        String cacheKey = generateSmartCacheKey(pjp, smartCache.key(), cacheName);
        String methodName = pjp.getSignature().toShortString();

        Counter localCacheHitCounter = cacheMetricsService.getCacheCounter("hits", cacheName,
            "local", "Number of cache hits in local cache");
        Counter redisCacheHitCounter = cacheMetricsService.getCacheCounter("hits", cacheName,
            "redis", "Number of cache hits in Redis cache");
        Counter cacheMissCounter = cacheMetricsService.getCacheCounter("misses", cacheName,
            null, "Number of cache misses");
        Counter redisCachePutCounter = cacheMetricsService.getCacheCounter("puts", cacheName,
            "redis", "Number of items put into Redis cache");
        Counter localCachePutCounter = cacheMetricsService.getCacheCounter("puts", cacheName,
            "local", "Number of items put into local cache");


        Timer methodExecutionTimer = cacheMetricsService.getMethodTimer(cacheName, methodName,
            "Duration of the method execution when cache is missed");

        Object cachedValue = getFromLocalCache(cacheKey);
        if (cachedValue != null) {
            log.info("Cache HIT (local) for key: {}'", cacheKey);
            localCacheHitCounter.increment();
            return cachedValue;
        }

        cachedValue = redisTemplate.opsForValue().get(cacheKey);
        if (cachedValue != null) {
            log.info("Cache HIT (Redis) for key: {}", cacheKey);
            redisCacheHitCounter.increment();
            putInLocalCache(cacheKey, cachedValue);
            localCachePutCounter.increment();
            return cachedValue;
        }

        log.info("Cache MISS for key: {} ", cacheKey);
        cacheMissCounter.increment();


        Object result;

        result = methodExecutionTimer.recordCallable(() -> {
            try {
                return pjp.proceed();
            } catch (Throwable t) {
                if (t instanceof Exception) {
                    throw (Exception) t;
                }
                throw new RuntimeException("Original method execution failed with an unexpected Throwable", t);
            }
        });

        if (result != null) {
            redisTemplate.opsForValue().set(cacheKey, result, smartCache.ttlSeconds(), TimeUnit.SECONDS);
            log.info("Cached result in Redis for key: {}', with TTL: {} s", cacheKey, smartCache.ttlSeconds());
            redisCachePutCounter.increment();

            putInLocalCache(cacheKey, result);
            log.info("Cached result in local cache for key: {}'", cacheKey);
            localCachePutCounter.increment();
        }

        return result;
    }

    /**
     * Aspect handling the {@code @InvalidateCache.} annotation
     * used to asynchronous keys eviction in Redis and local cache.
     *
     * @param invalidateCache @InvalidateCache annotation with parameters.
     */
    @After("@annotation(invalidateCache)")
    public void evictCache(InvalidateCache invalidateCache) {
        Set<String> patternsToEvict = new java.util.HashSet<>();

        if (invalidateCache.cacheNames().length > 0) {
            String cacheNamesStr = String.join(",", invalidateCache.cacheNames());
            cacheMetricsService.getEvictionByNameCounter(cacheNamesStr,
                    "Number of cache evictions triggered by specific cache names.")
                .increment();
            Arrays.stream(invalidateCache.cacheNames())
                .forEach(cacheName -> patternsToEvict.add(cacheName + ":*"));
        }


        if (!invalidateCache.keyPattern().isEmpty()) {
            String cacheTag = inferCacheNameFromPattern(invalidateCache.keyPattern());
            cacheMetricsService.getEvictionByPatternCounter(cacheTag,
                    "Number of cache evictions triggered by key patterns.")
                .increment();
            patternsToEvict.add(invalidateCache.keyPattern());
        }

        if (invalidateCache.dependsOn().length > 0) {
            Arrays.stream(invalidateCache.dependsOn()).forEach(dependentCacheName ->
                cacheMetricsService.getEvictionByDependencyCounter(dependentCacheName,
                        "Number of cache eviction triggered by dependencies.")
                .increment());
            asyncCacheInvalidator.invalidateDependentCachesAsync(invalidateCache.dependsOn());
        }


        if (patternsToEvict.isEmpty() && invalidateCache.dependsOn().length == 0) {
            log.info("Skipping due to no cache name or pattern found.");
            return;
        }

        if (!patternsToEvict.isEmpty()) {
            asyncCacheInvalidator.invalidatePatternsAsync(patternsToEvict);
        }
    }

    /**
     * Helper to infer a cache name from a key pattern for metrics tagging.
     * Very basic inference: takes the part before the first colon.
     */
    private String inferCacheNameFromPattern(String keyPattern) {
        if (keyPattern.contains(":")) {
            return keyPattern.substring(0, keyPattern.indexOf(":"));
        }
        return "unknown_or_multiple";
    }

    /**
     * Generates the final cache key. It first creates a default key based on the method signature and arguments.
     * If a SpEL expression is provided in the @SmartCache annotation, it attempts to evaluate it
     * and use that result to override the default key's suffix.
     * The final key is always prefixed with the cache name, and then MD5 hashed for uniqueness and brevity.
     *
     * @param pjp            The ProceedingJoinPoint of the method.
     * @param spelExpression The SpEL expression from the @SmartCache annotation (can be empty).
     * @param cacheName      The name of the cache.
     * @return The final, unique cache key.
     */
    private String generateSmartCacheKey(ProceedingJoinPoint pjp, String spelExpression, String cacheName) {
        String keySuffix = generateDefaultKeySuffix(pjp);

        if (!spelExpression.isEmpty()) {
            try {
                keySuffix = evaluateSpelExpression(pjp, spelExpression);
            } catch (Exception e) {
                log.error("Using default key due to failed SpEL evaluation: '{}' for method '{}'. Error: {}",
                    spelExpression, pjp.getSignature().toShortString(), e.getMessage());
            }
        }

        return cacheName + ":" + DigestUtils.md5DigestAsHex(keySuffix.getBytes());
    }

    /**
     * Generates a default key suffix based on the method's short signature and its arguments.
     * This is used when no SpEL expression is provided or when SpEL evaluation fails.
     *
     * @param pjp The ProceedingJoinPoint of the method.
     * @return The default key suffix string.
     */
    private String generateDefaultKeySuffix(ProceedingJoinPoint pjp) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(pjp.getSignature().toShortString());

        Object[] args = pjp.getArgs();
        for(Object arg : args){
            if (arg != null) {
                keyBuilder.append(":").append(arg);
            }
        }
        return keyBuilder.toString();
    }

    /**
     * Evaluates a SpEL expression against the method's arguments and context.
     * This method is designed to be called within a try-catch block by the caller,
     * as it will throw an exception if parsing or evaluation fails.
     *
     * @param pjp            The ProceedingJoinPoint of the method.
     * @param spelExpression The SpEL expression to evaluate.
     * @return The result of the SpEL evaluation as a String.
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


    /**
     * Retrieves an object from the local cache using the specified key.
     * The local cache stores values as {@link WeakReference}, allowing them to be
     * garbage collected when they are no longer strongly referenced.
     *
     * @param key The key used to look up the cached object.
     * @return The cached object associated with the provided key, or {@code null}
     * if the key does not exist in the cache or if the reference has been cleared.
     */
    private Object getFromLocalCache(String key) {
        WeakReference<Object> ref = localCache.get(key);
        return (ref != null) ? ref.get() : null;
    }

    /**
     * Stores a key-value pair in the local cache. The value is wrapped
     * in a {@link WeakReference} to allow for garbage collection if it is
     * no longer strongly referenced elsewhere.
     *
     * @param key   The cache key used to reference the stored value.
     * @param value The object to store in the local cache.
     */
    private void putInLocalCache(String key, Object value) {
        localCache.put(key, new WeakReference<>(value));
    }
}
