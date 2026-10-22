package dev.zdict.demo;

import dev.zdict.core.*;
import dev.zdict.spring.ZdictProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that on canary promotion:
 * 1. The new ACTIVE dictionary's baseline is set to the canary's measured live mean ratio (not the holdout ratio).
 * 2. The PROMOTED event reason in EventLog displays both liveMean and holdoutRatio.
 * 3. When holdout ratio is significantly higher than live ratio, subsequent steady traffic
 *    at the live ratio does NOT trigger a false-alarm DRIFT_DETECTED event.
 */
public class CanaryLiveBaselinePromotionTest {

    @TempDir
    Path tempDir;

    private ZdictProperties props;
    private SampleStore sampleStore;
    private DictionaryRegistry registry;
    private DictionaryTrainerService trainerService;
    private RatioTracker ratioTracker;
    private EventLog eventLog;
    private MutableClock clock;
    private AutoTrainerService autoTrainer;

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
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-06-01T12:00:00Z"));

        props = new ZdictProperties();
        props.setDataDir(tempDir.toString());
        props.getAutoLearning().setEnabled(true);
        props.getTrain().setMinSamples(10);
        props.getTrain().setDictSize(1024);
        props.getTrain().setCooldown(Duration.ofMinutes(5));
        props.getDrift().setMinResponses(20);
        props.getDrift().setThreshold(0.15); // 15% drop triggers drift
        props.getCanary().setMinResponses(20);
        props.getCanary().setPercent(100);

        eventLog = new EventLog(clock);
        sampleStore = new SampleStore(tempDir, 1000, 1.0, new PayloadScrubber(), new Random(42));
        registry = new DictionaryRegistry(tempDir, null, clock);
        trainerService = new DictionaryTrainerService(sampleStore, registry, props, eventLog);
        ratioTracker = new RatioTracker(registry, props, clock);
        autoTrainer = new AutoTrainerService(props, sampleStore, registry, trainerService, ratioTracker, clock, eventLog);
    }

    @Test
    void testCanaryPromotionSetsLiveBaselineAndPreventsFalseDriftWhenHoldoutIsHigher() throws IOException {
        String group = "/api/test-service";

        // 1. Establish an initial ACTIVE dictionary with baseline = 2.0x
        byte[] activeBytes = "initial_active_dict_bytes_version_1".getBytes(StandardCharsets.UTF_8);
        DictionaryVersion initialActive = registry.register(group, activeBytes, 50, 0.50, 2.0);
        String initialActiveId = initialActive.getId();

        // Simulate initial active traffic: 25 responses at 2.0x ratio
        for (int i = 0; i < 25; i++) {
            ratioTracker.recordSuccess(group, initialActiveId, 2.0);
        }
        assertThat(ratioTracker.getRollingMean(group, initialActiveId)).isEqualTo(2.0);
        assertThat(ratioTracker.isDrifting(group)).isFalse();

        // 2. Register a candidate CANARY dictionary with a HIGH holdout baseline (8.0x)
        // Suppose during offline training on holdout data, it compressed extremely well (8.0x baseline),
        // but live real-world traffic is more varied and compresses to 4.0x.
        byte[] canaryBytes = "canary_dict_bytes_version_2_candidate".getBytes(StandardCharsets.UTF_8);
        double holdoutBaseline = 8.0;
        double holdoutRatio = 1.0 / holdoutBaseline; // 0.125
        DictionaryVersion canary = registry.registerCanary(group, canaryBytes, 50, holdoutRatio, holdoutBaseline);
        String canaryId = canary.getId();

        assertThat(canary.getMetadata().getBaseline()).isEqualTo(8.0);
        assertThat(registry.getCanary(group)).isNotNull();
        assertThat(registry.getActive(group).getId()).isEqualTo(initialActiveId);

        // 3. Canary traffic: 25 requests arrive in live evaluation, compressing to 4.0x ratio
        // Note: live ratio (4.0x) is MUCH LOWER than holdout ratio (8.0x) (50% drop!),
        // BUT canary (4.0x) strongly beats active (2.0x) by 100% (> 3% threshold).
        double liveRatio = 4.0;
        for (int i = 0; i < 25; i++) {
            ratioTracker.recordSuccess(group, canaryId, liveRatio);
        }

        double canaryLiveMean = ratioTracker.getRollingMean(group, canaryId);
        double activeMean = ratioTracker.getRollingMean(group, initialActiveId);
        assertThat(canaryLiveMean).isEqualTo(4.0);
        assertThat(activeMean).isEqualTo(2.0);
        assertThat(holdoutBaseline).isGreaterThan(canaryLiveMean); // 8.0 > 4.0

        // 4. AutoTrainer evaluates the canary -> Canary is PROMOTED
        autoTrainer.checkAndTrain();

        // Verify canary is promoted to ACTIVE
        assertThat(registry.getCanary(group)).isNull();
        DictionaryVersion newActive = registry.getActive(group);
        assertThat(newActive).isNotNull();
        assertThat(newActive.getId()).isEqualTo(canaryId);

        // REQUIREMENT 1: Baseline must be set to the canary's measured LIVE mean ratio (4.0), NOT the holdout ratio (8.0)
        double newActiveBaseline = newActive.getMetadata().getBaseline();
        assertThat(newActiveBaseline).isEqualTo(canaryLiveMean); // 4.0
        assertThat(newActiveBaseline).isNotEqualTo(holdoutBaseline); // NOT 8.0

        // REQUIREMENT 2: Event details in EventLog show BOTH values (liveMean and holdoutRatio)
        List<ZdictEvent> promoEvents = eventLog.getRecentEvents().stream()
                .filter(e -> group.equals(e.getGroup()) && "PROMOTED".equals(e.getType()))
                .toList();
        assertThat(promoEvents).hasSize(1);
        String promoReason = promoEvents.get(0).getReason();
        assertThat(promoReason)
                .contains("liveMean=4.0000")
                .contains("holdoutRatio=8.0000");

        // 5. REQUIREMENT 3: Steady traffic continues at 4.0x live ratio -> verify NO drift fires
        // Send 30 more steady responses on the new active dictionary at 4.0x
        for (int i = 0; i < 30; i++) {
            ratioTracker.recordSuccess(group, canaryId, liveRatio);
        }

        // Verification 1: If baseline had erroneously remained at 8.0x, drift WOULD HAVE FIRED
        // because 4.0 < 8.0 * (1 - 0.15) = 6.8
        assertThat(ratioTracker.isDrifting(group, canaryId, holdoutBaseline)).isTrue();

        // Verification 2: Because baseline was set to liveMean (4.0x), isDrifting is FALSE!
        assertThat(ratioTracker.isDrifting(group)).isFalse();

        // Verification 3: Running auto-trainer check does NOT trigger DRIFT_DETECTED
        autoTrainer.checkAndTrain();

        List<ZdictEvent> driftEvents = eventLog.getRecentEvents().stream()
                .filter(e -> group.equals(e.getGroup()) && "DRIFT_DETECTED".equals(e.getType()))
                .toList();
        assertThat(driftEvents).isEmpty();

        // Verify dictionary stays ACTIVE and no unexpected events were fired
        assertThat(registry.getActive(group).getId()).isEqualTo(canaryId);
        List<String> eventTypes = eventLog.getRecentEvents().stream()
                .filter(e -> group.equals(e.getGroup()))
                .map(ZdictEvent::getType)
                .toList();
        assertThat(eventTypes).containsExactly("PROMOTED");
    }
}
