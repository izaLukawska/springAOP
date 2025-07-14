package org.lukawska.springaop.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class CacheMetricsService {

    private final MeterRegistry meterRegistry;

    /**
     * Increments a counter for cache operations (e.g., hits, misses, puts) by 1.
     * Metric Name: cache.<nameSuffix>_total
     * Tags: cache=<cacheName>, type=<type> (optional)
     *
     * @param nameSuffix  The suffix for the metric name (e.g., "hits", "misses", "puts").
     * @param cacheName   The name of the cache (used as a "cache" tag).
     * @param type        The type of cache (used as a "type" tag, e.g., "local", "redis"). Can be null or empty.
     * @param description A description for the metric.
     */
    public void incrementCacheCounter(String nameSuffix, String cacheName, String type, String description) {
        Counter.Builder builder = Counter.builder("cache." + nameSuffix + "_total")
            .tag("cache", cacheName)
            .description(description);

        if (type != null && !type.isEmpty()) {
            builder.tag("type", type);
        }
        builder.register(meterRegistry).increment(); // Direct increment by 1
    }

    /**
     * Records the duration of a method execution related to cache.
     * Metric Name: cache.method.duration
     * Tags: cache=<cacheName>, method=<methodName>
     *
     * @param cacheName   The name of the cache associated with the method (used as "cache" tag).
     * @param methodName  The name of the method being timed (used as "method" tag).
     * @param description A description for the metric.
     * @param durationNanos Duration in nanoseconds.
     */
    public void recordMethodDuration(String cacheName, String methodName, String description, long durationNanos) {
        Timer.builder("cache.method.duration")
            .tag("cache", cacheName)
            .tag("method", methodName)
            .description(description)
            .register(meterRegistry)
            .record(durationNanos, TimeUnit.NANOSECONDS); // Direct time recording
    }

    /**
     * Increments a counter for cache evictions by specific cache names.
     * Metric Name: cache.evictions.by.name_total
     * Tags: cache=<cacheName>
     *
     * @param cacheName   The name of the cache from which elements are being evicted.
     * @param description A description for the metric.
     */
    public void incrementEvictionByNameCounter(String cacheName, String description) {
        Counter.builder("cache.evictions.by.name_total")
            .tag("cache", cacheName)
            .description(description)
            .register(meterRegistry)
            .increment(); // Direct increment by 1
    }

    /**
     * Increments a counter for cache evictions by key patterns.
     * Metric Name: cache.evictions.by.pattern_total
     * Tags: cache=<cacheName> (if a single cache name can be inferred, or "multiple" for broad patterns)
     *
     * @param cacheName   A representative cache name for the pattern (or "all" if the pattern is too broad).
     * @param description A description for the metric.
     */
    public void incrementEvictionByPatternCounter(String cacheName, String description) {
        Counter.builder("cache.evictions.by.pattern_total")
            .tag("cache", cacheName)
            .description(description)
            .register(meterRegistry)
            .increment(); // Direct increment by 1
    }

    /**
     * Increments a counter for cache evictions triggered by dependencies.
     * Metric Name: cache.evictions.by.dependency_total
     * Tags: dependsOn=<dependentCacheName>
     *
     * @param dependentCacheName The name of the cache that triggered the invalidation.
     * @param description        A description for the metric.
     */
    public void incrementEvictionByDependencyCounter(String dependentCacheName, String description) {
        Counter.builder("cache.evictions.by.dependency_total")
            .tag("dependsOn", dependentCacheName)
            .description(description)
            .register(meterRegistry)
            .increment(); // Direct increment by 1
    }
}
