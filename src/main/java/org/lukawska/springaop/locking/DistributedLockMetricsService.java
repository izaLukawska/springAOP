package org.lukawska.springaop.locking;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class DistributedLockMetricsService {

    private final MeterRegistry meterRegistry;

    public void incrementLockAcquisitionSuccess(String lockName, String methodName) {
        meterRegistry.counter("distributed.lock.acquisition.success",
                "lock.name", lockName,
                "method.name", methodName)
            .increment();
    }

    public void incrementLockAcquisitionFailure(String lockName, String methodName, String reason) {
        meterRegistry.counter("distributed.lock.acquisition.failure",
                "lock.name", lockName,
                "method.name", methodName,
                "reason", reason)
            .increment();
    }

    public void incrementLockSkipped(String lockName, String methodName) {
        meterRegistry.counter("distributed.lock.skipped",
                "lock.name", lockName,
                "method.name", methodName)
            .increment();
    }

    public void recordLockWaitTime(String lockName, String methodName, long durationNanos) {
        meterRegistry.timer("distributed.lock.wait.time",
                "lock.name", lockName,
                "method.name", methodName)
            .record(durationNanos, TimeUnit.NANOSECONDS);
    }
}
