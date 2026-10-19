package dev.zdict.demo;

import dev.zdict.client.ZdictClient;
import dev.zdict.client.ZdictResponse;
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
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({EndToEndDriftCanaryTest.TestConfig.class, EndToEndDriftCanaryTest.DynamicShapeController.class})
class EndToEndDriftCanaryTest {

    private static final Logger log = LoggerFactory.getLogger(EndToEndDriftCanaryTest.class);

    @TempDir
    static Path tempDir;

    @LocalServerPort
    private int port;

    @Autowired
    private DynamicShapeController controller;

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

    static class TestMutableClock extends Clock {
        private volatile Instant current;
        private final ZoneId zone = ZoneId.of("UTC");

        TestMutableClock(Instant start) {
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

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public TestMutableClock clock() {
            return new TestMutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        }
    }

    @RestController
    static class DynamicShapeController {
        private volatile String shape = "A";
        private final AtomicInteger counterA = new AtomicInteger(100);
        private final AtomicInteger counterCanary = new AtomicInteger(500);

        public void setShape(String shape) {
            this.shape = shape;
        }

        public String getShape() {
            return this.shape;
        }

        @GetMapping(value = "/api/dynamic/data", produces = MediaType.APPLICATION_JSON_VALUE)
        public String getData() {
            int idx = counterA.incrementAndGet();
            if ("A".equals(shape)) {
                return String.format(
                        "{\"type\":\"PROFILE_A\",\"id\":%d,\"user\":{\"username\":\"User-%d\",\"domain_handle\":\"user_%d_infra\",\"department\":\"Enterprise Engineering & Cloud Operations\",\"title\":\"Senior Principal Infrastructure Engineer\"},\"details\":\"Detailed metadata description for shape A payload with repeated common tokens for dictionary compression and optimization.\",\"permissions\":[\"READ\",\"WRITE\",\"EXECUTE\",\"ADMIN\"],\"status\":\"ACTIVE\"}",
                        idx, idx, idx
                );
            } else {
                return String.format(
                        "{\"kind\":\"METRIC_B\",\"sequence\":%d,\"telemetry\":{\"sensor\":\"Sensor-%d\",\"facility\":\"Facility-Zone-%d\",\"reading_type\":\"VIBRATION_FREQUENCY_HERTZ\",\"reading_value\":%d.45},\"attributes\":\"High frequency telemetry stream packet containing distinct terminology and payload patterns for industrial monitoring.\",\"flags\":[\"CALIBRATED\",\"ONLINE\",\"STREAMING\"],\"state\":\"OPERATIONAL\"}",
                        idx, idx % 10, idx % 5, idx * 13
                );
            }
        }

        @GetMapping(value = "/api/dynamic/canary-eval", produces = MediaType.APPLICATION_JSON_VALUE)
        public String getCanaryEvalData() {
            int idx = counterCanary.incrementAndGet();
            return String.format(
                    "{\"service\":\"CANARY_SERVICE\",\"id\":%d,\"config\":{\"region\":\"us-east-1\",\"cluster\":\"prod-cluster-alpha\",\"tier\":\"HIGH_AVAILABILITY\"},\"metrics\":{\"latency_ms\":%d.2,\"throughput_rps\":12500,\"error_rate\":0.0001},\"description\":\"Service metrics and telemetry information for evaluation of canary dictionary performance against baseline active dictionary.\",\"status\":\"OK\"}",
                    idx, idx % 50
            );
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry propRegistry) {
        propRegistry.add("zdict.enabled", () -> "true");
        propRegistry.add("zdict.data-dir", () -> tempDir.toString());
        propRegistry.add("zdict.seed-dictionary-path", () -> "");
        propRegistry.add("zdict.dictionary-path", () -> "");
        propRegistry.add("zdict.paths", () -> "/api/dynamic");
        propRegistry.add("zdict.sample.rate", () -> "1.0");
        propRegistry.add("zdict.train.min-samples", () -> "30");
        propRegistry.add("zdict.train.dict-size", () -> "2048");
        propRegistry.add("zdict.train.cooldown", () -> "5m");
        propRegistry.add("zdict.drift.min-responses", () -> "20");
        propRegistry.add("zdict.drift.threshold", () -> "0.15");
        propRegistry.add("zdict.canary.percent", () -> "100");
        propRegistry.add("zdict.canary.min-responses", () -> "20");
        propRegistry.add("zdict.canary.max-duration", () -> "30m");
        propRegistry.add("zdict.auto-learning.enabled", () -> "true");
        propRegistry.add("zdict.auto-learning.interval", () -> "3600000");
        propRegistry.add("management.endpoints.web.exposure.include", () -> "*");
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
    void testEndToEndDriftAndCanaryPromotionFlow() throws Exception {
        String group = "/api/dynamic/data";
        String url = "http://localhost:" + port + group;

        // -------------------------------------------------------------
        // Step 1: Traffic in Shape A (initially uncompressed)
        // -------------------------------------------------------------
        controller.setShape("A");

        for (int i = 0; i < 35; i++) {
            ZdictResponse resp = client.get(url);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.compressed()).isFalse();
            assertThat(resp.body()).contains("\"PROFILE_A\"");
        }

        assertThat(sampleStore.getSampleCount(group)).isGreaterThanOrEqualTo(30);

        // Auto-train initial ACTIVE dictionary
        autoTrainer.checkAndTrain();

        DictionaryVersion activeA = registry.getActive(group);
        assertThat(activeA).isNotNull();
        assertThat(activeA.isCanary()).isFalse();
        String activeAId = activeA.getId();
        double baselineA = activeA.getMetadata().getBaseline();
        assertThat(baselineA).isGreaterThan(1.5);

        // -------------------------------------------------------------
        // Step 2: Establish good ratio with ACTIVE dictionary on Shape A
        // -------------------------------------------------------------
        // First request receives X-Dict-Available and caches activeA
        ZdictResponse warmupResp = client.get(url);
        assertThat(warmupResp.statusCode()).isEqualTo(200);
        assertThat(client.hasDictionary(activeAId)).isTrue();
        assertThat(warmupResp.body()).contains("\"PROFILE_A\"");

        // Send 25 requests compressed with activeA
        for (int i = 0; i < 25; i++) {
            ZdictResponse resp = client.get(url);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.compressed()).isTrue();
            assertThat(resp.httpResponse().headers().firstValue("X-Dict-Id")).hasValue(activeAId);
            assertThat(resp.body()).contains("\"PROFILE_A\"");
        }

        double rollingA = ratioTracker.getRollingMean(group, activeAId);
        log.info("activeAId={}, baselineA={}, rollingA={}, count={}", activeAId, baselineA, rollingA, ratioTracker.getResponseCount(group, activeAId));
        assertThat(rollingA).isGreaterThan(2.0);
        assertThat(ratioTracker.isDrifting(group)).isFalse();

        // -------------------------------------------------------------
        // Step 3: Switch to Shape B -> ratio drops -> DRIFT_DETECTED
        // -------------------------------------------------------------
        controller.setShape("B");

        // Send 30 requests of Shape B through the client
        // Requests use activeA dictionary (trained on Shape A), so ratio drops
        for (int i = 0; i < 30; i++) {
            ZdictResponse resp = client.get(url);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.compressed()).isTrue();
            // Crucial requirement: responses ALWAYS decode correctly in client during whole flow
            assertThat(resp.body()).contains("\"METRIC_B\"");
        }

        double droppedRatio = ratioTracker.getRollingMean(group, activeAId);
        log.info("Ratio dropped from baseline {} to {}", baselineA, droppedRatio);
        assertThat(ratioTracker.isDrifting(group)).isTrue();

        // Trigger auto-trainer: detects drift, clears reservoir once, emits DRIFT_DETECTED
        autoTrainer.checkAndTrain();

        // Sample store cleared for this group
        assertThat(sampleStore.getSampleCount(group)).isEqualTo(0);

        // -------------------------------------------------------------
        // Step 4: Fresh Shape B samples collected -> retrain -> CANARY_STARTED
        // -------------------------------------------------------------
        // Send 35 new Shape B requests to collect fresh samples
        for (int i = 0; i < 35; i++) {
            ZdictResponse resp = client.get(url);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.body()).contains("\"METRIC_B\"");
        }

        assertThat(sampleStore.getSampleCount(group)).isGreaterThanOrEqualTo(30);

        // Advance controllable clock to satisfy training cooldown (5m configured)
        clock.advance(Duration.ofMinutes(10));

        // Auto-trainer retrains on Shape B: beats activeA by > 5% on holdout, registers CANARY
        autoTrainer.checkAndTrain();

        DictionaryVersion canaryB = registry.getCanary(group);
        assertThat(canaryB).isNotNull();
        assertThat(canaryB.isCanary()).isTrue();
        String canaryBId = canaryB.getId();
        assertThat(canaryBId).isNotEqualTo(activeAId);

        // Active remains activeA while canary is tested
        assertThat(registry.getActive(group).getId()).isEqualTo(activeAId);

        // -------------------------------------------------------------
        // Step 5: Canary traffic -> PROMOTED -> rolling ratio recovers
        // -------------------------------------------------------------
        // Client receives announcement of canaryB
        ZdictResponse announceResp = client.get(url);
        assertThat(announceResp.statusCode()).isEqualTo(200);
        assertThat(client.hasDictionary(canaryBId)).isTrue();
        assertThat(announceResp.body()).contains("\"METRIC_B\"");

        // Send 25 requests encoded with canaryB
        for (int i = 0; i < 25; i++) {
            ZdictResponse resp = client.get(url);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.compressed()).isTrue();
            assertThat(resp.httpResponse().headers().firstValue("X-Dict-Id")).hasValue(canaryBId);
            assertThat(resp.body()).contains("\"METRIC_B\"");
        }

        double canaryRollingRatio = ratioTracker.getRollingMean(group, canaryBId);
        assertThat(canaryRollingRatio).isGreaterThan(2.5);

        // Evaluate canary: canaryMean >= activeMean * 1.03 and failRate <= activeFailRate
        autoTrainer.checkAndTrain();

        // Canary is PROMOTED to ACTIVE!
        assertThat(registry.getCanary(group)).isNull();
        DictionaryVersion newActive = registry.getActive(group);
        assertThat(newActive).isNotNull();
        assertThat(newActive.getId()).isEqualTo(canaryBId);

        // Verify retired version
        List<DictionaryVersion> history = registry.getVersions(group);
        assertThat(history.stream().anyMatch(v -> v.getId().equals(activeAId) && v.getMetadata().isRetired())).isTrue();

        // Subsequent requests are compressed with new ACTIVE (canaryB) and decode correctly
        for (int i = 0; i < 10; i++) {
            ZdictResponse resp = client.get(url);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.compressed()).isTrue();
            assertThat(resp.httpResponse().headers().firstValue("X-Dict-Id")).hasValue(canaryBId);
            assertThat(resp.body()).contains("\"METRIC_B\"");
        }

        // -------------------------------------------------------------
        // Step 6: Assert event order
        // -------------------------------------------------------------
        List<String> eventTypes = eventLog.getRecentEvents().stream()
                .filter(e -> group.equals(e.getGroup()))
                .map(ZdictEvent::getType)
                .toList();

        assertThat(eventTypes).containsExactly("TRAINED", "DRIFT_DETECTED", "CANARY_STARTED", "PROMOTED");
    }

    @Test
    void testCanaryRollbackWhenWorseAndActiveUnchanged() throws Exception {
        String group = "/api/dynamic/canary-eval";
        String url = "http://localhost:" + port + group;

        // 1. Initial traffic and training for ACTIVE dictionary
        for (int i = 0; i < 35; i++) {
            ZdictResponse resp = client.get(url);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.body()).contains("\"CANARY_SERVICE\"");
        }

        // Advance clock to allow training
        clock.advance(Duration.ofMinutes(10));
        autoTrainer.checkAndTrain();

        DictionaryVersion originalActive = registry.getActive(group);
        assertThat(originalActive).isNotNull();
        String originalActiveId = originalActive.getId();

        // Warm up client with originalActive
        ZdictResponse warmup = client.get(url);
        assertThat(client.hasDictionary(originalActiveId)).isTrue();

        for (int i = 0; i < 25; i++) {
            ZdictResponse resp = client.get(url);
            assertThat(resp.compressed()).isTrue();
            assertThat(resp.httpResponse().headers().firstValue("X-Dict-Id")).hasValue(originalActiveId);
            assertThat(resp.body()).contains("\"CANARY_SERVICE\"");
        }

        double activeMean = ratioTracker.getRollingMean(group, originalActiveId);
        assertThat(activeMean).isGreaterThan(2.0);

        // 2. Register a deliberately worse canary dictionary (random/dummy bytes with poor compression)
        byte[] worseBytes = new byte[2048];
        for (int i = 0; i < worseBytes.length; i++) {
            worseBytes[i] = (byte) (i % 17);
        }
        DictionaryVersion worseCanary = registry.registerCanary(group, worseBytes, 50, 0.95, 1.05);
        String worseCanaryId = worseCanary.getId();

        // Client discovers worse canary and fetches it
        ZdictResponse announce = client.get(url);
        assertThat(client.hasDictionary(worseCanaryId)).isTrue();
        assertThat(announce.body()).contains("\"CANARY_SERVICE\"");

        // Send 25 requests using worse canary
        for (int i = 0; i < 25; i++) {
            ZdictResponse resp = client.get(url);
            assertThat(resp.compressed()).isTrue();
            assertThat(resp.httpResponse().headers().firstValue("X-Dict-Id")).hasValue(worseCanaryId);
            // Responses still decode correctly in the client
            assertThat(resp.body()).contains("\"CANARY_SERVICE\"");
        }

        double canaryMean = ratioTracker.getRollingMean(group, worseCanaryId);
        log.info("Active mean = {}, Worse canary mean = {}", activeMean, canaryMean);
        assertThat(canaryMean).isLessThan(activeMean);

        // 3. Evaluate canary: should fail 3% improvement threshold and rollback
        autoTrainer.checkAndTrain();

        // Assert canary was rolled back and retired
        assertThat(registry.getCanary(group)).isNull();

        // Assert ACTIVE is unchanged!
        DictionaryVersion currentActive = registry.getActive(group);
        assertThat(currentActive).isNotNull();
        assertThat(currentActive.getId()).isEqualTo(originalActiveId);

        // Verify ROLLED_BACK event recorded
        List<ZdictEvent> rollbackEvents = eventLog.getRecentEvents().stream()
                .filter(e -> group.equals(e.getGroup()) && "ROLLED_BACK".equals(e.getType()))
                .toList();
        assertThat(rollbackEvents).hasSize(1);
        assertThat(rollbackEvents.get(0).getReason()).contains("did not beat active");

        // Subsequent requests continue serving active dictionary safely
        for (int i = 0; i < 5; i++) {
            ZdictResponse resp = client.get(url);
            assertThat(resp.compressed()).isTrue();
            assertThat(resp.httpResponse().headers().firstValue("X-Dict-Id")).hasValue(originalActiveId);
            assertThat(resp.body()).contains("\"CANARY_SERVICE\"");
        }
    }
}
