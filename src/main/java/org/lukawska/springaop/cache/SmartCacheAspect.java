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


    @Around("@annotation(smartCache)")
    public Object handleCaching(ProceedingJoinPoint pjp, SmartCache smartCache) throws Throwable {
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

        Object cachedValue;

        if (smartCache.useWeakReference()) {
            cachedValue = getFromWeakRefLocalCache(cacheKey);
            if (cachedValue != null) {
                log.info("Cache HIT (local - WeakReference) for key: {}'", cacheKey);
                localCacheHitCounter.increment();
                return cachedValue;
            }
        }

        cachedValue = redisTemplate.opsForValue().get(cacheKey);
        if (cachedValue != null) {
            log.info("Cache HIT (Redis) for key: {}", cacheKey);
            redisCacheHitCounter.increment();

            if (smartCache.useWeakReference()) {
                putIntoWeakRefLocalCache(cacheKey, cachedValue, localCachePutCounter);
            }
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

            if (smartCache.useWeakReference()) {
                putIntoWeakRefLocalCache(cacheKey, result, localCachePutCounter);
            }
        }

        return result;
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
     * @param putCounter The Micrometer counter for local cache puts.
     */
    private void putIntoWeakRefLocalCache(String key, Object value, Counter putCounter) {
        weakRefLocalCache.put(key, new WeakReference<>(value));
        log.info("Cached result in local WeakReference cache for key: {}'", key);
        putCounter.increment();
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
        boolean isAsync = invalidateCache.async();

        if (invalidateCache.cacheNames().length > 0) {
            String cacheNamesStr = String.join(",", invalidateCache.cacheNames());
            cacheMetricsService.getEvictionByNameCounter(cacheNamesStr,
                    "Number of cache evictions triggered by specific cache names.")
                .increment();
            Arrays.stream(invalidateCache.cacheNames())
                .forEach(cacheName -> {
                    patternsToEvict.add(cacheName + ":*");
                    weakRefLocalCache.keySet().removeIf(key -> key.startsWith(cacheName + ":"));
                    log.info("Local WeakReference cache keys matching '{}*' invalidated.", cacheName);
                });
        }

        if (!invalidateCache.keyPattern().isEmpty()) {
            String cacheTag = inferCacheNameFromPattern(invalidateCache.keyPattern());
            cacheMetricsService.getEvictionByPatternCounter(cacheTag,
                    "Number of cache evictions triggered by key patterns.")
                .increment();
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

        if (invalidateCache.dependsOn().length > 0) {
            Arrays.stream(invalidateCache.dependsOn()).forEach(dependentCacheName -> {
                cacheMetricsService.getEvictionByDependencyCounter(dependentCacheName,
                        "Number of cache eviction triggered by dependencies.")
                    .increment();
                weakRefLocalCache.keySet().removeIf(key -> key.startsWith(dependentCacheName + ":"));
                log.info("Local WeakReference cache keys dependent on '{}' invalidated.", dependentCacheName);
            });

            if (isAsync) {
                cacheInvalidator.invalidateDependentCachesAsync(invalidateCache.dependsOn());
            } else {
                cacheInvalidator.invalidateDependentCachesSync(invalidateCache.dependsOn());
            }
        }

        if (patternsToEvict.isEmpty() && invalidateCache.dependsOn().length == 0) {
            log.info("Skipping due to no cache name or pattern found.");
            return;
        }

        if (!patternsToEvict.isEmpty()) {
            if (isAsync) {
                cacheInvalidator.invalidatePatternsAsync(patternsToEvict);
            } else {
                cacheInvalidator.invalidatePatternsSync(patternsToEvict);
            }
        }
    }

    private String inferCacheNameFromPattern(String keyPattern) {
        if (keyPattern.contains(":")) {
            return keyPattern.substring(0, keyPattern.indexOf(":"));
        }
        return "unknown_or_multiple";
    }

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
