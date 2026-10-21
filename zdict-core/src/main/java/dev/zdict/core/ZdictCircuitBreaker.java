package dev.zdict.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/**
 * Circuit breaker for zstd compression.
 *
 * <p>Trips if >5% of the last 200 attempts failed OR avg compress time > 5 ms.
 * When tripped, compression is disabled for 60 seconds (or configured duration).
 */
public class ZdictCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ZdictCircuitBreaker.class);

    private static final int WINDOW_SIZE = 200;
    private static final double FAILURE_RATE_THRESHOLD = 0.05; // 5%
    private static final double AVG_TIME_MS_THRESHOLD = 5.0; // 5 ms
    private static final long DEFAULT_OPEN_DURATION_MS = 60_000; // 60 seconds

    private Clock clock;
    private long openDurationMs;

    private final boolean[] successHistory = new boolean[WINDOW_SIZE];
    private final long[] durationHistory = new long[WINDOW_SIZE];
    private int count = 0;
    private int head = 0;

    private boolean open = false;
    private long openUntil = 0;

    public ZdictCircuitBreaker() {
        this(Clock.systemUTC(), DEFAULT_OPEN_DURATION_MS);
    }

    public ZdictCircuitBreaker(Clock clock, long openDurationMs) {
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.openDurationMs = openDurationMs;
    }

    public synchronized boolean isOpen() {
        if (!open) {
            return false;
        }
        long now = clock.millis();
        if (now >= openUntil) {
            open = false;
            // Reset history upon recovery
            count = 0;
            head = 0;
            log.info("zdict circuit breaker recovered: CLOSED");
            return false;
        }
        return true;
    }

    public synchronized void recordSuccess(long durationNanos) {
        record(true, durationNanos);
    }

    public synchronized void recordFailure(long durationNanos) {
        record(false, durationNanos);
    }

    private void record(boolean success, long durationNanos) {
        successHistory[head] = success;
        durationHistory[head] = durationNanos;
        head = (head + 1) % WINDOW_SIZE;
        if (count < WINDOW_SIZE) {
            count++;
        }

        evaluate();
    }

    private static final int MIN_SAMPLES = 20;

    private void evaluate() {
        if (count < MIN_SAMPLES) {
            return;
        }
        int failures = 0;
        long totalDurationNanos = 0;
        for (int i = 0; i < count; i++) {
            if (!successHistory[i]) {
                failures++;
            }
            totalDurationNanos += durationHistory[i];
        }

        double failureRate = (double) failures / count;
        double avgDurationMs = ((double) totalDurationNanos / count) / 1_000_000.0;

        if (failureRate > FAILURE_RATE_THRESHOLD || avgDurationMs > AVG_TIME_MS_THRESHOLD) {
            open = true;
            openUntil = clock.millis() + openDurationMs;
            log.warn("zdict circuit breaker tripped: OPEN for {} ms (failures={}/{}, failRate={}% avgTime={} ms)",
                    openDurationMs, failures, count,
                    String.format("%.1f", failureRate * 100.0),
                    String.format("%.2f", avgDurationMs));
        }
    }

    public synchronized void reset() {
        open = false;
        openUntil = 0;
        count = 0;
        head = 0;
    }

    public synchronized void setClock(Clock clock) {
        this.clock = clock;
    }

    public synchronized void setOpenDurationMs(long openDurationMs) {
        this.openDurationMs = openDurationMs;
    }
}
