package dev.zdict.demo;

import dev.zdict.client.ZdictClient;
import dev.zdict.client.ZdictResponse;
import dev.zdict.core.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test demonstrating the full lifecycle on real-world API data:
 * 1. Sampling starts on real "forecast" JSON payloads
 * 2. First dictionary is trained and promoted to ACTIVE, storing baseline ratio
 * 3. Client establishes compression with ACTIVE dictionary
 * 4. Stream switches on the same endpoint to "geocoding" JSON payloads
 * 5. Ratio drops and DRIFT_DETECTED triggers reservoir reset
 * 6. Fresh geocoding samples accumulate -> retrain -> CANARY_STARTED
 * 7. Canary traffic runs -> PROMOTED or ROLLED_BACK
 * 8. Prints event log with timestamps and rolling ratios before and after
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({RealDataDriftCanaryIntegrationTest.TestClockConfig.class, RealDataDriftCanaryIntegrationTest.RealDataReplayController.class})
public class RealDataDriftCanaryIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RealDataDriftCanaryIntegrationTest.class);

    @TempDir
    static Path tempDir;

    @LocalServerPort
    private int port;

    @Autowired
    private RealDataReplayController controller;

    @Autowired
    private TestMutableClock clock;

    @Autowired
    private AutoTrainerService autoTrainer;

    @Autowired
    private DictionaryRegistry registry;

    @Autowired
    private SampleStore sampleStore;

    @Autowired
    private RatioTracker ratioTracker;

    @Autowired
    private EventLog eventLog;

    private ZdictClient client;

    public static class TestMutableClock extends Clock {
        private volatile Instant current;
        private final ZoneId zone = ZoneId.of("UTC");

        public TestMutableClock(Instant start) {
            this.current = start;
        }

        public void advance(Duration d) {
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

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        public TestMutableClock clock() {
            return new TestMutableClock(Instant.parse("2026-06-01T12:00:00Z"));
        }
    }

    @RestController
    static class RealDataReplayController {
        private final List<String> forecastPayloads = new ArrayList<>();
        private final List<String> geocodingPayloads = new ArrayList<>();
        private final AtomicInteger forecastIdx = new AtomicInteger(0);
        private final AtomicInteger geocodingIdx = new AtomicInteger(0);
        private volatile String currentGroup = "forecast";

        void loadPayloads(Path realDataDir) throws IOException {
            forecastPayloads.clear();
            geocodingPayloads.clear();

            Path forecastDir = realDataDir.resolve("forecast");
            if (Files.exists(forecastDir)) {
                try (Stream<Path> stream = Files.list(forecastDir)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                            .sorted(Comparator.comparing(Path::getFileName))
                            .forEach(p -> {
                                try {
                                    String c = Files.readString(p, StandardCharsets.UTF_8);
                                    if (c.length() >= 200) {
                                        forecastPayloads.add(c);
                                    }
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                }
            }

            Path geocodingDir = realDataDir.resolve("geocoding");
            if (Files.exists(geocodingDir)) {
                try (Stream<Path> stream = Files.list(geocodingDir)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                            .sorted(Comparator.comparing(Path::getFileName))
                            .forEach(p -> {
                                try {
                                    String c = Files.readString(p, StandardCharsets.UTF_8);
                                    if (c.length() >= 200) {
                                        geocodingPayloads.add(c);
                                    }
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                }
            }
        }

        void setGroup(String group) {
            this.currentGroup = group;
        }

        @GetMapping(value = "/api/real-replay", produces = MediaType.APPLICATION_JSON_VALUE)
        public String getPayload() {
            if ("forecast".equals(currentGroup) && !forecastPayloads.isEmpty()) {
                int idx = forecastIdx.getAndIncrement() % forecastPayloads.size();
                return forecastPayloads.get(idx);
            } else if (!geocodingPayloads.isEmpty()) {
                int idx = geocodingIdx.getAndIncrement() % geocodingPayloads.size();
                return geocodingPayloads.get(idx);
            }
            return "{\"status\":\"ok\"}";
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry propRegistry) {
        propRegistry.add("zdict.enabled", () -> "true");
        propRegistry.add("zdict.data-dir", () -> tempDir.toString());
        propRegistry.add("zdict.seed-dictionary-path", () -> "");
        propRegistry.add("zdict.dictionary-path", () -> "");
        propRegistry.add("zdict.paths", () -> "/api/real-replay");
        propRegistry.add("zdict.sample.rate", () -> "1.0");
        propRegistry.add("zdict.train.min-samples", () -> "200");
        propRegistry.add("zdict.train.dict-size", () -> "16384");
        propRegistry.add("zdict.train.cooldown", () -> "1m");
        propRegistry.add("zdict.drift.min-responses", () -> "20");
        propRegistry.add("zdict.drift.threshold", () -> "0.15");
        propRegistry.add("zdict.canary.percent", () -> "100");
        propRegistry.add("zdict.canary.min-responses", () -> "20");
        propRegistry.add("zdict.canary.max-duration", () -> "30m");
        propRegistry.add("zdict.auto-learning.enabled", () -> "true");
        propRegistry.add("zdict.auto-learning.interval", () -> "3600000"); // deterministic test invocation
        propRegistry.add("management.endpoints.web.exposure.include", () -> "*");
    }

    private static Path resolveRealDataDir() {
        Path dir = Path.of("benchmark-data/real");
        if (!Files.exists(dir)) {
            dir = Path.of("../benchmark-data/real");
        }
        return dir;
    }

    @BeforeEach
    void setUp() {
        client = new ZdictClient();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void testRealDataLifecycleForecastToGeocodingDriftAndCanary() throws Exception {
        Path realDataDir = resolveRealDataDir();
        Path forecastDir = realDataDir.resolve("forecast");
        Path geocodingDir = realDataDir.resolve("geocoding");

        if (!Files.exists(forecastDir) || !Files.exists(geocodingDir)) {
            System.out.println("Skipping testRealDataLifecycleForecastToGeocodingDriftAndCanary: benchmark-data/real missing.");
            return;
        }

        controller.loadPayloads(realDataDir);
        String group = "/api/real-replay";
        String url = "http://localhost:" + port + group;

        System.out.println("===============================================================================");
        System.out.println("      Real Data Lifecycle Integration Test: forecast -> geocoding drift       ");
        System.out.println("===============================================================================");

        // -------------------------------------------------------------
        // Step 1: Sampling starts with forecast payloads (uncompressed)
        // -------------------------------------------------------------
        controller.setGroup("forecast");
        System.out.println("\n[Phase 1] Sending initial traffic (300 warmup requests) for 'forecast' payloads...");
        for (int i = 0; i < 300; i++) {
            ZdictResponse resp = client.get(url);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.compressed()).isFalse();
            assertThat(resp.body()).contains("hourly");
        }

        int initialSamples = sampleStore.getSampleCount(group);
        System.out.printf("  -> Collected %,d samples in SampleStore for '%s'%n", initialSamples, group);
        assertThat(initialSamples).isGreaterThanOrEqualTo(200);

        // -------------------------------------------------------------
        // Step 2: First dictionary trained and promoted to ACTIVE
        // -------------------------------------------------------------
        System.out.println("\n[Phase 2] Triggering auto-trainer for initial dictionary...");
        autoTrainer.checkAndTrain();

        DictionaryVersion active1 = registry.getActive(group);
        assertThat(active1).isNotNull();
        String active1Id = active1.getId();
        double baseline1 = active1.getMetadata().getBaseline();

        System.out.printf("  -> First dictionary trained & ACTIVE: id=%s, dictSize=%,d B%n",
                active1Id, active1.getDictBytes().length);
        System.out.printf("  -> Measured baseline stored: %.4fx%n", baseline1);
        assertThat(baseline1).isGreaterThan(3.0);

        // -------------------------------------------------------------
        // Step 3: Establish steady-state compression on forecast payloads
        // -------------------------------------------------------------
        System.out.println("\n[Phase 3] Replaying forecast traffic through client with active dictionary...");
        // Discover active1 via X-Dict-Available
        ZdictResponse warmup = client.get(url);
        assertThat(warmup.statusCode()).isEqualTo(200);
        assertThat(client.hasDictionary(active1Id)).isTrue();

        for (int i = 0; i < 30; i++) {
            ZdictResponse resp = client.get(url);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.compressed()).isTrue();
            assertThat(resp.httpResponse().headers().firstValue("X-Dict-Id")).hasValue(active1Id);
        }

        double rollingBefore = ratioTracker.getRollingMean(group, active1Id);
        System.out.printf("  -> Rolling ratio BEFORE drift (forecast on forecast dict): %.4fx (baseline: %.4fx)%n",
                rollingBefore, baseline1);
        assertThat(rollingBefore).isGreaterThan(4.0);
        assertThat(ratioTracker.isDrifting(group)).isFalse();

        // -------------------------------------------------------------
        // Step 4: Switch stream to "geocoding" payloads on SAME endpoint
        // -------------------------------------------------------------
        System.out.println("\n[Phase 4] Switching endpoint stream to 'geocoding' payloads (forcing shape change)...");
        controller.setGroup("geocoding");

        // Send 40 geocoding requests encoded with active1 (forecast dictionary)
        for (int i = 0; i < 40; i++) {
            ZdictResponse resp = client.get(url);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.compressed()).isTrue();
            assertThat(resp.body()).contains("results");
        }

        double rollingAfter = ratioTracker.getRollingMean(group, active1Id);
        System.out.printf("  -> Rolling ratio AFTER drift (geocoding on forecast dict): %.4fx (drop: %.2f%%)%n",
                rollingAfter, ((baseline1 - rollingAfter) / baseline1) * 100);
        assertThat(rollingAfter).isLessThan(baseline1 * (1.0 - 0.15));
        assertThat(ratioTracker.isDrifting(group)).isTrue();

        // -------------------------------------------------------------
        // Step 5: DRIFT_DETECTED -> fresh samples
        // -------------------------------------------------------------
        System.out.println("\n[Phase 5] Auto-trainer detecting drift and resetting reservoir...");
        autoTrainer.checkAndTrain();

        assertThat(sampleStore.getSampleCount(group)).isEqualTo(0);
        System.out.println("  -> Drift confirmed: SampleStore reservoir cleared for fresh post-drift sampling.");

        // Send fresh geocoding requests to populate clean post-drift reservoir
        System.out.println("  -> Collecting fresh 'geocoding' samples...");
        for (int i = 0; i < 210; i++) {
            ZdictResponse resp = client.get(url);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.body()).contains("results");
        }
        assertThat(sampleStore.getSampleCount(group)).isGreaterThanOrEqualTo(200);
        System.out.printf("  -> Collected %,d fresh geocoding samples.%n", sampleStore.getSampleCount(group));

        // -------------------------------------------------------------
        // Step 6: Advance clock past cooldown -> Retrain -> CANARY_STARTED
        // -------------------------------------------------------------
        System.out.println("\n[Phase 6] Advancing controllable Clock past cooldown and retraining...");
        clock.advance(Duration.ofMinutes(2));

        autoTrainer.checkAndTrain();

        DictionaryVersion canary = registry.getCanary(group);
        assertThat(canary).isNotNull();
        String canaryId = canary.getId();
        assertThat(canaryId).isNotEqualTo(active1Id);
        System.out.printf("  -> CANARY_STARTED: id=%s (active remains %s during evaluation)%n",
                canaryId, active1Id);

        // -------------------------------------------------------------
        // Step 7: Canary traffic -> PROMOTED or ROLLED_BACK
        // -------------------------------------------------------------
        System.out.println("\n[Phase 7] Routing traffic through canary dictionary...");
        // Client receives announcement of canaryId
        ZdictResponse canaryAnnounce = client.get(url);
        assertThat(canaryAnnounce.statusCode()).isEqualTo(200);
        assertThat(client.hasDictionary(canaryId)).isTrue();

        // Client advertises both dictionaries; with canary.percent=100 it receives canary compression
        for (int i = 0; i < 25; i++) {
            ZdictResponse resp = client.get(url);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.compressed()).isTrue();
            assertThat(resp.httpResponse().headers().firstValue("X-Dict-Id")).hasValue(canaryId);
            assertThat(resp.body()).contains("results");
        }

        double canaryRolling = ratioTracker.getRollingMean(group, canaryId);
        System.out.printf("  -> Canary rolling ratio on geocoding: %.4fx%n", canaryRolling);

        // Evaluate canary
        autoTrainer.checkAndTrain();

        DictionaryVersion postCanaryActive = registry.getActive(group);
        DictionaryVersion postCanary = registry.getCanary(group);
        String finalDecision;

        if (postCanary == null && postCanaryActive.getId().equals(canaryId)) {
            finalDecision = "PROMOTED";
            System.out.printf("  -> Canary %s PROMOTED to ACTIVE! New baseline: %.4fx%n",
                    canaryId, postCanaryActive.getMetadata().getBaseline());
        } else {
            finalDecision = "ROLLED_BACK";
            System.out.printf("  -> Canary %s was ROLLED_BACK. Active remains %s%n",
                    canaryId, postCanaryActive.getId());
        }

        assertThat(finalDecision).isIn("PROMOTED", "ROLLED_BACK");

        // -------------------------------------------------------------
        // Step 8: Print Event Log & Summary
        // -------------------------------------------------------------
        System.out.println("\n===============================================================================");
        System.out.println("                           ZDICT LIFECYCLE EVENT LOG                           ");
        System.out.println("===============================================================================");
        System.out.printf("%-24s | %-16s | %-10s | %s%n", "Timestamp", "Event Type", "Dict ID", "Details / Reason");
        System.out.println("-------------------------+------------------+------------+-------------------------");

        List<ZdictEvent> recentEvents = eventLog.getRecentEvents();
        for (ZdictEvent ev : recentEvents) {
            System.out.printf("%-24s | %-16s | %-10s | %s%n",
                    ev.getTimestamp(), ev.getType(), ev.getDictionaryId() != null ? ev.getDictionaryId() : "-", ev.getReason());
        }

        System.out.println("-------------------------------------------------------------------------------");
        System.out.printf("Summary Metrics:%n");
        System.out.printf("  - Initial Forecast Baseline Ratio: %.4fx%n", baseline1);
        System.out.printf("  - Rolling Ratio Before Drift:     %.4fx%n", rollingBefore);
        System.out.printf("  - Rolling Ratio After Drift:      %.4fx%n", rollingAfter);
        System.out.printf("  - Canary Rolling Ratio:           %.4fx%n", canaryRolling);
        System.out.printf("  - Final Lifecycle Outcome:        %s%n", finalDecision);
        System.out.println("===============================================================================\n");

        List<String> eventTypes = recentEvents.stream()
                .filter(e -> group.equals(e.getGroup()))
                .map(ZdictEvent::getType)
                .toList();

        assertThat(eventTypes).contains("TRAINED", "DRIFT_DETECTED", "CANARY_STARTED", finalDecision);
    }
}
