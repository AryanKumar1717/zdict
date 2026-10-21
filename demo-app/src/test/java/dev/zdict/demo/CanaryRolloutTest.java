package dev.zdict.demo;

import dev.zdict.core.*;
import dev.zdict.spring.ZdictProperties;
import dev.zdict.spring.ZstdDictFilter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CanaryRolloutTest {

    @TempDir
    Path tempDir;

    private ZdictProperties props;
    private DictionaryRegistry registry;
    private RatioTracker ratioTracker;
    private AutoTrainerService autoTrainer;
    private ZstdDictFilter filter;
    private MutableClock clock;

    static class MutableClock extends Clock {
        private Instant current;
        private final ZoneId zone = ZoneId.of("UTC");

        MutableClock(Instant start) {
            this.current = start;
        }

        void advance(Duration d) {
            current = current.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        clock = new MutableClock(Instant.parse("2026-04-01T12:00:00Z"));

        props = new ZdictProperties();
        props.setEnabled(true);
        props.setDataDir(tempDir.toString());
        props.setPaths(List.of("/api/items"));
        props.getCanary().setPercent(10); // 10% for test statistical stability
        props.getCanary().setMinResponses(300);
        props.getCanary().setMaxDuration(Duration.ofMinutes(30));

        registry = new DictionaryRegistry(tempDir, null, clock);
        ratioTracker = new RatioTracker(registry, props, clock);
        SampleStore sampleStore = new SampleStore(tempDir, 1000, 1.0, new PayloadScrubber(), new Random(42));
        DictionaryTrainerService trainer = new DictionaryTrainerService(sampleStore, registry, props);
        autoTrainer = new AutoTrainerService(props, sampleStore, registry, trainer, ratioTracker, clock);

        filter = new ZstdDictFilter(props, new ZdictCircuitBreaker(), null, null, registry, ratioTracker);
    }

    private byte[] makeSample(int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ('a' + (i % 26));
        }
        return b;
    }

    @Test
    void testCanaryShareCloseToConfiguredPercent() throws Exception {
        String group = "/api/items";
        byte[] dict1 = makeSample(2048);
        byte[] dict2 = makeSample(2048);
        dict2[0] = 'Z'; // differentiate

        DictionaryVersion active = registry.register(group, dict1, 500, 0.30, 3.33);
        DictionaryVersion canary = registry.registerCanary(group, dict2, 500, 0.25, 4.0);

        filter.setRandom(new Random(12345));

        int totalRequests = 1000;
        int canaryCount = 0;
        int activeCount = 0;

        for (int i = 0; i < totalRequests; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/items");
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, group);
            req.addHeader("X-Dict-Ids", active.getId() + ", " + canary.getId());
            MockHttpServletResponse res = new MockHttpServletResponse();

            filter.doFilter(req, res, (q, r) -> {
                r.setContentType("application/json");
                r.getWriter().write("{\"item\":\"sample json response for compression testing\"}".repeat(10));
            });

            String usedDictId = res.getHeader("X-Dict-Id");
            if (canary.getId().equals(usedDictId)) {
                canaryCount++;
            } else if (active.getId().equals(usedDictId)) {
                activeCount++;
            }
        }

        assertEquals(totalRequests, canaryCount + activeCount);
        // Expect ~10% (100). With 1000 requests and seed 12345, check tolerance [80, 120]
        double canaryPct = (canaryCount * 100.0) / totalRequests;
        assertTrue(canaryPct >= 7.5 && canaryPct <= 12.5,
                "Canary percentage was " + canaryPct + "%, expected ~10%");
    }

    @Test
    void testClientsWithoutCanaryIdNeverGetCanaryEncoding() throws Exception {
        String group = "/api/items";
        byte[] dict1 = makeSample(2048);
        byte[] dict2 = makeSample(2048);
        dict2[0] = 'Y';

        DictionaryVersion active = registry.register(group, dict1, 500, 0.30, 3.33);
        DictionaryVersion canary = registry.registerCanary(group, dict2, 500, 0.25, 4.0);

        filter.setRandom(new Random(54321));

        int totalRequests = 300;
        int canaryAdvertisedCount = 0;

        for (int i = 0; i < totalRequests; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/items");
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, group);
            // Client ONLY advertises active ID
            req.addHeader("X-Dict-Ids", active.getId());
            MockHttpServletResponse res = new MockHttpServletResponse();

            filter.doFilter(req, res, (q, r) -> {
                r.setContentType("application/json");
                r.getWriter().write("{\"message\":\"payload to test canary lack advertising\"}".repeat(10));
            });

            String usedDictId = res.getHeader("X-Dict-Id");
            assertNotEquals(canary.getId(), usedDictId,
                    "Client without canary id must never receive canary encoding!");
            assertEquals(active.getId(), usedDictId);

            String available = res.getHeader("X-Dict-Available");
            if (canary.getId().equals(available)) {
                canaryAdvertisedCount++;
            }
        }

        assertTrue(canaryAdvertisedCount > 0,
                "Clients lacking canary id should receive X-Dict-Available with canary id roughly 10% of time");
    }

    @Test
    void testPromoteOnBetter() throws IOException {
        String group = "/api/items";
        byte[] dict1 = makeSample(2048);
        byte[] dict2 = makeSample(2048);
        dict2[0] = 'B';

        DictionaryVersion active = registry.register(group, dict1, 500, 0.33, 3.0);
        DictionaryVersion canary = registry.registerCanary(group, dict2, 500, 0.25, 4.0);

        String activeId = active.getId();
        String canaryId = canary.getId();

        // Feed ACTIVE ratio: mean 3.0
        for (int i = 0; i < 300; i++) {
            ratioTracker.recordSuccess(group, activeId, 3.0);
        }

        // Feed CANARY ratio: mean 3.20 (3.20 >= 3.0 * 1.03 = 3.09) with 0 failures
        for (int i = 0; i < 300; i++) {
            ratioTracker.recordSuccess(group, canaryId, 3.20);
        }

        autoTrainer.evaluateCanary(group);

        // Canary promoted!
        assertNull(registry.getCanary(group), "Canary should be promoted and cleared from canary slot");
        DictionaryVersion newActive = registry.getActive(group);
        assertNotNull(newActive);
        assertEquals(canaryId, newActive.getId(), "Canary should now be the ACTIVE dictionary");

        DictionaryVersion oldVersion = registry.getById(activeId);
        assertNotNull(oldVersion);
        assertEquals("RETIRED", oldVersion.getState(), "Old active should be RETIRED");
    }

    @Test
    void testRollbackOnWorse() throws IOException {
        String group = "/api/items";
        byte[] dict1 = makeSample(2048);
        byte[] dict2 = makeSample(2048);
        dict2[0] = 'W';

        DictionaryVersion active = registry.register(group, dict1, 500, 0.33, 3.0);
        DictionaryVersion canary = registry.registerCanary(group, dict2, 500, 0.30, 3.33);

        String activeId = active.getId();
        String canaryId = canary.getId();

        // Feed active: 3.0
        for (int i = 0; i < 300; i++) {
            ratioTracker.recordSuccess(group, activeId, 3.0);
        }

        // Feed canary: 3.02 (< 3.0 * 1.03 = 3.09)
        for (int i = 0; i < 300; i++) {
            ratioTracker.recordSuccess(group, canaryId, 3.02);
        }

        autoTrainer.evaluateCanary(group);

        assertNull(registry.getCanary(group), "Canary slot should be cleared after rollback");
        assertEquals(activeId, registry.getActive(group).getId(), "Original active remains ACTIVE");

        DictionaryVersion rolledBack = registry.getById(canaryId);
        assertNotNull(rolledBack);
        assertEquals("RETIRED", rolledBack.getState());
        assertNotNull(rolledBack.getMetadata().getRejectionReason());
        assertTrue(rolledBack.getMetadata().getRejectionReason().contains("did not beat active"));
    }

    @Test
    void testRollbackOnHigherFailures() throws IOException {
        String group = "/api/items";
        byte[] dict1 = makeSample(2048);
        byte[] dict2 = makeSample(2048);
        dict2[0] = 'F';

        DictionaryVersion active = registry.register(group, dict1, 500, 0.33, 3.0);
        DictionaryVersion canary = registry.registerCanary(group, dict2, 500, 0.20, 5.0);

        String activeId = active.getId();
        String canaryId = canary.getId();

        // Active: ratio 3.0, 0 failures
        for (int i = 0; i < 300; i++) {
            ratioTracker.recordSuccess(group, activeId, 3.0);
        }

        // Canary: ratio 4.0 (higher than 3.09), but has 5 failures!
        for (int i = 0; i < 300; i++) {
            ratioTracker.recordSuccess(group, canaryId, 4.0);
        }
        for (int i = 0; i < 5; i++) {
            ratioTracker.recordFailure(group, canaryId);
        }

        autoTrainer.evaluateCanary(group);

        assertNull(registry.getCanary(group));
        assertEquals(activeId, registry.getActive(group).getId());

        DictionaryVersion rolledBack = registry.getById(canaryId);
        assertNotNull(rolledBack);
        assertEquals("RETIRED", rolledBack.getState());
        assertTrue(rolledBack.getMetadata().getRejectionReason().contains("failure rate"));
    }

    @Test
    void testRollbackOnTimeout() throws IOException {
        String group = "/api/items";
        byte[] dict1 = makeSample(2048);
        byte[] dict2 = makeSample(2048);
        dict2[0] = 'T';

        DictionaryVersion active = registry.register(group, dict1, 500, 0.33, 3.0);
        DictionaryVersion canary = registry.registerCanary(group, dict2, 500, 0.25, 4.0);

        String canaryId = canary.getId();

        // Feed only 50 responses (< 300 required)
        for (int i = 0; i < 50; i++) {
            ratioTracker.recordSuccess(group, canaryId, 3.5);
        }

        // Advance clock by 31 minutes (> 30 min maxDuration)
        clock.advance(Duration.ofMinutes(31));

        autoTrainer.evaluateCanary(group);

        assertNull(registry.getCanary(group));
        DictionaryVersion rolledBack = registry.getById(canaryId);
        assertNotNull(rolledBack);
        assertEquals("RETIRED", rolledBack.getState());
        assertEquals("insufficient traffic", rolledBack.getMetadata().getRejectionReason());
    }

    @Test
    void testNeverMoreThanOneCanaryPerGroup() throws IOException {
        String group = "/api/items";
        byte[] dict1 = makeSample(2048);
        byte[] dict2 = makeSample(2048);
        byte[] dict3 = makeSample(2048);
        dict2[0] = '1';
        dict3[0] = '2';

        registry.register(group, dict1, 500, 0.33, 3.0);
        DictionaryVersion canary1 = registry.registerCanary(group, dict2, 500, 0.25, 4.0);
        assertEquals(canary1.getId(), registry.getCanary(group).getId());

        // Register a second canary
        DictionaryVersion canary2 = registry.registerCanary(group, dict3, 500, 0.22, 4.5);
        assertEquals(canary2.getId(), registry.getCanary(group).getId());

        // Canary 1 should be superseded/retired
        DictionaryVersion oldCanary = registry.getById(canary1.getId());
        assertEquals("RETIRED", oldCanary.getState());
        assertEquals("superseded by new canary candidate", oldCanary.getMetadata().getRejectionReason());

        // Verify total versions in group is <= 3
        List<DictionaryVersion> versions = registry.getVersions(group);
        assertTrue(versions.size() <= 3, "Retention limit of 3 versions per group must be preserved");
    }
}
