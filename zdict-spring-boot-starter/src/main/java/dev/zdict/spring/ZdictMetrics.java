package dev.zdict.spring;

import dev.zdict.core.ZdictCircuitBreaker;
import dev.zdict.core.ZdictMetricsListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer metrics implementation for zdict, implementing {@link ZdictMetricsListener}.
 */
public class ZdictMetrics implements ZdictMetricsListener {

    private final MeterRegistry registry;
    private final Timer compressTimer;
    private final ConcurrentHashMap<String, double[]> rollingRatioGauges = new ConcurrentHashMap<>();

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

    @Override
    public void updateRollingRatio(String group, double ratio) {
        String normalized = normalizePath(group);
        double[] holder = rollingRatioGauges.computeIfAbsent(normalized, g -> {
            double[] arr = new double[1];
            Gauge.builder("zdict.ratio.rolling", arr, a -> a[0])
                    .description("Rolling compression ratio for the endpoint group")
                    .tag("group", g)
                    .register(registry);
            return arr;
        });
        holder[0] = ratio;
    }

    @Override
    public void recordEvent(String type, String group) {
        Counter.builder("zdict.events")
                .description("Lifecycle events for zdict")
                .tag("type", (type != null && !type.isBlank()) ? type : "unknown")
                .tag("group", normalizePath(group))
                .register(registry)
                .increment();
    }

    public MeterRegistry getRegistry() {
        return registry;
    }

    private String normalizePath(String path) {
        return (path != null && !path.isBlank()) ? path : "unknown";
    }
}
