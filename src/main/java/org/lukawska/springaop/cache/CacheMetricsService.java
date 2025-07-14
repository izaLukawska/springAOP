package org.lukawska.springaop.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CacheMetricsService {

    private final MeterRegistry meterRegistry;

    /**
     * Retrieves or creates a Counter for cache operations.
     * Metric Name: cache.<nameSuffix>_total
     * Tags: cache=<cacheName>, type=<type> (optional)
     *
     * @param nameSuffix  The suffix for the metric name (e.g., "hits", "misses", "puts").
     * @param cacheName   The name of the cache (used as a "cache" tag).
     * @param type        The type of cache (used as a "type" tag, e.g., "local", "redis"). Can be null or empty.
     * @param description A description for the metric.
     * @return An instance of Counter.
     */
    public Counter getCacheCounter(String nameSuffix, String cacheName, String type, String description) {
        Counter.Builder builder = Counter.builder("cache." + nameSuffix)
            .tag("cache", cacheName)
            .description(description);

        if (type != null && !type.isEmpty()) {
            builder.tag("type", type);
        }
        return builder.register(meterRegistry);
    }

    /**
     * Retrieves or creates a Timer for measuring method execution duration.
     * Metric Name: cache.method.duration_seconds
     * Tags: cache=<cacheName>, method=<methodName>
     *
     * @param cacheName   The name of the cache associated with the method (used as "cache" tag).
     * @param methodName  The name of the method being timed (used as "method" tag).
     * @param description A description for the metric.
     * @return An instance of Timer.
     */
    public Timer getMethodTimer(String cacheName, String methodName, String description) {
        return Timer.builder("cache.method.duration")
            .tag("cache", cacheName)
            .tag("method", methodName)
            .description(description)
            .register(meterRegistry);
    }

    /**
     * Retrieves or creates a Counter for cache evictions by specific cache names.
     * Metric Name: cache.evictions.by.name_total
     * Tags: cache=<cacheName>
     *
     * @param cacheName   The name of the cache being evicted.
     * @param description A description for the metric.
     * @return An instance of Counter.
     */
    public Counter getEvictionByNameCounter(String cacheName, String description) {
        return Counter.builder("cache.evictions.by.name")
            .tag("cache", cacheName)
            .description(description)
            .register(meterRegistry);
    }

    /**
     * Retrieves or creates a Counter for cache evictions by key patterns.
     * Metric Name: cache.evictions.by.pattern_total
     * Tags: cache=<cacheName> (if a single cache name can be inferred, or "multiple" for broad patterns)
     *
     * @param cacheName   A representative cache name for the pattern (or "all" if the pattern is too broad).
     * @param description A description for the metric.
     * @return An instance of Counter.
     */
    public Counter getEvictionByPatternCounter(String cacheName, String description) {
        return Counter.builder("cache.evictions.by.pattern")
            .tag("cache", cacheName)
            .description(description)
            .register(meterRegistry);
    }

    /**
     * Retrieves or creates a Counter for cache evictions triggered by dependencies.
     * Metric Name: cache.evictions.by.dependency_total
     * Tags: dependsOn=<dependentCacheName>
     *
     * @param dependentCacheName The name of the cache that triggered the invalidation.
     * @param description        A description for the metric.
     * @return An instance of Counter.
     */
    public Counter getEvictionByDependencyCounter(String dependentCacheName, String description) {
        return Counter.builder("cache.evictions.by.dependency")
            .tag("dependsOn", dependentCacheName)
            .description(description)
            .register(meterRegistry);
    }
}
