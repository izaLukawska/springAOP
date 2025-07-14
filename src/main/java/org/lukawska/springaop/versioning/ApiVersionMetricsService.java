package org.lukawska.springaop.versioning;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApiVersionMetricsService {

    private final MeterRegistry meterRegistry;

    public void incrementApiVersionUsage(String version) {
        meterRegistry.counter("api_version_usage_total", "version", version)
            .increment();
    }

    public void incrementDeprecatedVersionUsage(String version) {
        meterRegistry.counter("deprecated_version_usage_total", "version", version).increment();
    }

    public void incrementInvalidVersionUsage(String invalidVersion) {
        meterRegistry.counter("invalid_version_usage_total", "version", invalidVersion).increment();
    }
}
