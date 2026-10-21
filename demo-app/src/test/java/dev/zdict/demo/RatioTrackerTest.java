package dev.zdict.demo;

import dev.zdict.core.*;
import dev.zdict.spring.ZdictProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class RatioTrackerTest {

    private ZdictProperties props;
    private RatioTracker tracker;
    private Clock clock;

    @BeforeEach
    void setUp() {
        props = new ZdictProperties();
        props.getDrift().setThreshold(0.15);
        props.getDrift().setMinResponses(200);
        props.getDrift().setWindowSize(500);

        clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));
        tracker = new RatioTracker(null, props, clock);
    }

    @Test
    void testNoDriftAtSteadyRatio() {
        String group = "/api/products";
        String dictId = "dict123";
        double baseline = 4.0; // original / compressed

        // Record 250 responses with ratio ~4.0
        for (int i = 0; i < 250; i++) {
            tracker.recordSuccess(group, dictId, 4000, 1000);
        }

        assertEquals(250, tracker.getResponseCount(group, dictId));
        assertEquals(4.0, tracker.getRollingMean(group, dictId), 0.001);
        assertFalse(tracker.isDrifting(group, dictId, baseline),
                "Should not drift when rolling mean equals baseline");
    }

    @Test
    void testDriftDetectedAfterRatioDrop() {
        String group = "/api/products";
        String dictId = "dict123";
        double baseline = 4.0;
        // Threshold 0.15 means drift occurs when mean < 4.0 * (1 - 0.15) = 3.40

        // Record 200 responses with ratio 2.5 (orig 2500, comp 1000)
        for (int i = 0; i < 200; i++) {
            tracker.recordSuccess(group, dictId, 2500, 1000);
        }

        assertEquals(200, tracker.getResponseCount(group, dictId));
        assertEquals(2.5, tracker.getRollingMean(group, dictId), 0.001);
        assertTrue(tracker.isDrifting(group, dictId, baseline),
                "Should detect drift when ratio drops below baseline * (1 - threshold)");
    }

    @Test
    void testNoDriftWithFewerThan200Samples() {
        String group = "/api/products";
        String dictId = "dict123";
        double baseline = 4.0;

        // Record 199 responses with very degraded ratio 1.5
        for (int i = 0; i < 199; i++) {
            tracker.recordSuccess(group, dictId, 1500, 1000);
        }

        assertEquals(199, tracker.getResponseCount(group, dictId));
        assertFalse(tracker.isDrifting(group, dictId, baseline),
                "Should not trigger drift with fewer than 200 responses");

        // The 200th response crosses the threshold
        tracker.recordSuccess(group, dictId, 1500, 1000);
        assertEquals(200, tracker.getResponseCount(group, dictId));
        assertTrue(tracker.isDrifting(group, dictId, baseline),
                "Should trigger drift once min responses (200) is reached");
    }

    @Test
    void testRollingWindowCapAt500() {
        String group = "/api/products";
        String dictId = "dict123";

        // Push 500 responses of ratio 2.0
        for (int i = 0; i < 500; i++) {
            tracker.recordSuccess(group, dictId, 2000, 1000);
        }
        assertEquals(500, tracker.getResponseCount(group, dictId));
        assertEquals(2.0, tracker.getRollingMean(group, dictId), 0.001);

        // Push 500 responses of ratio 5.0 (completely evicting old values)
        for (int i = 0; i < 500; i++) {
            tracker.recordSuccess(group, dictId, 5000, 1000);
        }
        assertEquals(500, tracker.getResponseCount(group, dictId));
        assertEquals(5.0, tracker.getRollingMean(group, dictId), 0.001);
    }

    @Test
    void testFailureCountingAndRate() {
        String group = "/api/orders";
        String dictId = "dict_ord";

        for (int i = 0; i < 90; i++) {
            tracker.recordSuccess(group, dictId, 2.0);
        }
        for (int i = 0; i < 10; i++) {
            tracker.recordFailure(group, dictId);
        }

        assertEquals(90, tracker.getResponseCount(group, dictId));
        assertEquals(10, tracker.getFailureCount(group, dictId));
        assertEquals(0.10, tracker.getFailureRate(group, dictId), 0.001);
    }

    @Test
    void testDriftDetectionWithRegistry() throws IOException {
        Path tempDir = Files.createTempDirectory("zdict-ratio-test");
        DictionaryRegistry reg = new DictionaryRegistry(tempDir, null, clock);

        byte[] fakeDict = "fake-dictionary-bytes-content-here".getBytes();
        // baseline = 4.0
        DictionaryVersion v = reg.register("/api/items", fakeDict, 500, 0.25, 4.0);

        RatioTracker regTracker = new RatioTracker(reg, props, clock);

        // Under 200 responses
        for (int i = 0; i < 150; i++) {
            regTracker.recordSuccess("/api/items", v.getId(), 2.0);
        }
        assertFalse(regTracker.isDrifting("/api/items"));

        // Push to 200 with degraded ratio
        for (int i = 0; i < 50; i++) {
            regTracker.recordSuccess("/api/items", v.getId(), 2.0);
        }
        assertTrue(regTracker.isDrifting("/api/items"));
    }

    @Test
    void testThreadSafetyConcurrentUpdates() throws InterruptedException {
        String group = "/api/concurrent";
        String dictId = "concurrent_dict";

        int threads = 8;
        int operationsPerThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        tracker.recordSuccess(group, dictId, 3.0);
                        if (i % 20 == 0) {
                            tracker.recordFailure(group, dictId);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Total responses = 1600, but rolling window is capped at 500
        assertEquals(500, tracker.getResponseCount(group, dictId));
        assertEquals(3.0, tracker.getRollingMean(group, dictId), 0.001);
        assertEquals(threads * (operationsPerThread / 20), tracker.getFailureCount(group, dictId));
    }
}
