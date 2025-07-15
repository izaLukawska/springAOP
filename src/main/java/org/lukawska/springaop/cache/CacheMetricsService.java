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

    public void incrementCacheCounter(String nameSuffix, String cacheName, String type, String description) {
        Counter.Builder builder = Counter.builder("cache." + nameSuffix)
            .tag("cache", cacheName)
            .description(description);

        if (type != null && !type.isEmpty()) {
            builder.tag("type", type);
        }
        builder.register(meterRegistry).increment();
    }

    public void recordMethodDuration(String cacheName, String methodName, String description, long durationNanos) {
        Timer.builder("cache.method.duration")
            .tag("cache", cacheName)
            .tag("method", methodName)
            .description(description)
            .register(meterRegistry)
            .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void incrementEvictionByNameCounter(String cacheName, String description) {
        Counter.builder("cache.evictions.by.name")
            .tag("cache", cacheName)
            .description(description)
            .register(meterRegistry)
            .increment();
    }

    public void incrementEvictionByPatternCounter(String cacheName, String description) {
        Counter.builder("cache.evictions.by.pattern")
            .tag("cache", cacheName)
            .description(description)
            .register(meterRegistry)
            .increment();
    }

    public void incrementEvictionByDependencyCounter(String dependentCacheName, String description) {
        Counter.builder("cache.evictions.by.dependency")
            .tag("dependsOn", dependentCacheName)
            .description(description)
            .register(meterRegistry)
            .increment();
    }
}
