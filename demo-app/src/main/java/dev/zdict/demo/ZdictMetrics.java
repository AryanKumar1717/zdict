package dev.zdict.demo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer metrics for zstd dictionary compression.
 */
@Component
public class ZdictMetrics {

    private final MeterRegistry registry;
    private final Timer compressTimer;

    public ZdictMetrics(MeterRegistry registry, ZdictCircuitBreaker circuitBreaker) {
        this.registry = registry;
        this.compressTimer = Timer.builder("zdict.compress.time")
                .description("Time taken to compress responses with zstd")
                .register(registry);

        Gauge.builder("zdict.circuit.open", circuitBreaker, cb -> cb.isOpen() ? 1.0 : 0.0)
                .description("Circuit breaker open state (1 = open, 0 = closed)")
                .register(registry);
    }

    public void recordBytesOriginal(String path, long bytes) {
        Counter.builder("zdict.bytes.original")
                .tag("path", normalizePath(path))
                .register(registry)
                .increment(bytes);
    }

    public void recordBytesCompressed(String path, long bytes) {
        Counter.builder("zdict.bytes.compressed")
                .tag("path", normalizePath(path))
                .register(registry)
                .increment(bytes);
    }

    public void recordResponse(String result, String reason) {
        Counter.builder("zdict.responses")
                .tag("result", result)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void recordCompressTime(long durationNanos) {
        compressTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public MeterRegistry getRegistry() {
        return registry;
    }

    private String normalizePath(String path) {
        return (path != null && !path.isBlank()) ? path : "unknown";
    }
}
