package dev.zdict.demo;

import dev.zdict.core.*;
import dev.zdict.spring.ZdictProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AutoTrainerServiceTest {

    @TempDir
    Path tempDir;

    private ZdictProperties props;
    private SampleStore sampleStore;
    private DictionaryRegistry registry;
    private DictionaryTrainerService trainerService;
    private RatioTracker ratioTracker;
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
        clock = new MutableClock(Instant.parse("2026-03-01T10:00:00Z"));

        props = new ZdictProperties();
        props.setDataDir(tempDir.toString());
        props.getAutoLearning().setEnabled(true);
        props.getTrain().setMinSamples(10);
        props.getTrain().setDictSize(1024);
        props.getTrain().setCooldown(Duration.ofMinutes(15));
        props.getDrift().setMinResponses(20);
        props.getDrift().setThreshold(0.15);

        sampleStore = new SampleStore(tempDir, 1000, 1.0, new PayloadScrubber(), new Random(42));
        registry = new DictionaryRegistry(tempDir, null, clock);
        trainerService = new DictionaryTrainerService(sampleStore, registry, props);
        ratioTracker = new RatioTracker(registry, props, clock);

        autoTrainer = new AutoTrainerService(props, sampleStore, registry, trainerService, ratioTracker, clock);
    }

    private void seedSamples(String group, int count, String prefix) {
        for (int i = 0; i < count; i++) {
            byte[] payload = String.format("{\"%s_id\": %d, \"name\": \"Product %d\", \"desc\": \"A very repetitive text description for dictionary training\"}",
                    prefix, i, i).getBytes();
            sampleStore.offer(group, payload);
        }
    }

    @Test
    void testDisabledFlagDoesNothing() {
        props.getAutoLearning().setEnabled(false);
        String group = "/api/products";

        seedSamples(group, 15, "init");
        assertEquals(15, sampleStore.getSampleCount(group));

        autoTrainer.checkAndTrain();

        assertNull(registry.getActive(group), "No dictionary should be trained when auto-learning is disabled");
    }

    @Test
    void testNoActiveDictionaryTrainsAndPromotes() {
        String group = "/api/products";
        assertNull(registry.getActive(group));

        seedSamples(group, 15, "init");
        assertEquals(15, sampleStore.getSampleCount(group));

        autoTrainer.checkAndTrain();

        DictionaryVersion active = registry.getActive(group);
        assertNotNull(active, "Dictionary should be trained and promoted to ACTIVE when none existed");
        assertEquals("ACTIVE", active.getState());
        assertNotNull(autoTrainer.getLastTrainTime(group));
    }

    @Test
    void testDriftTriggersReservoirResetThenRetrain() throws IOException {
        String group = "/api/products";

        // Initial training
        seedSamples(group, 15, "shapeA");
        autoTrainer.checkAndTrain();
        DictionaryVersion initialActive = registry.getActive(group);
        assertNotNull(initialActive);
        String initialId = initialActive.getId();

        // Simulate steady traffic with good ratio
        double baseline = initialActive.getMetadata().getBaseline();
        for (int i = 0; i < 25; i++) {
            ratioTracker.recordSuccess(group, initialId, baseline);
        }
        assertFalse(ratioTracker.isDrifting(group));

        // Now ratio drops drastically (shape change)
        for (int i = 0; i < 25; i++) {
            ratioTracker.recordSuccess(group, initialId, 1.0); // severe drop
        }
        assertTrue(ratioTracker.isDrifting(group), "Drift should be detected after ratio drop");

        // Add 5 old samples to reservoir (15 existing + 5 = 20)
        seedSamples(group, 5, "old_shape");
        assertEquals(20, sampleStore.getSampleCount(group));

        // Run autoTrainer while in cooldown:
        // Cooldown prevents immediate training, BUT reservoir should be reset once for drift!
        autoTrainer.checkAndTrain();
        assertEquals(0, sampleStore.getSampleCount(group), "Sample reservoir should be cleared once on drift");
        assertTrue(autoTrainer.isDriftCleared(group));
        assertEquals(initialId, registry.getActive(group).getId(), "Old dictionary continues serving meanwhile");

        // Subsequent run with < minSamples should NOT clear the newly accumulating samples
        seedSamples(group, 5, "new_shape");
        assertEquals(5, sampleStore.getSampleCount(group));
        autoTrainer.checkAndTrain();
        assertEquals(5, sampleStore.getSampleCount(group), "Reservoir should not be wiped again while accumulating");

        // Add more new-shape samples to reach minSamples
        seedSamples(group, 10, "new_shape");
        assertTrue(sampleStore.getSampleCount(group) >= props.getTrain().getMinSamples());

        // Advance clock past cooldown (15 minutes)
        clock.advance(Duration.ofMinutes(16));

        // Now run autoTrainer - it should retrain!
        autoTrainer.checkAndTrain();

        assertNotNull(autoTrainer.getLastTrainTime(group));
    }

    @Test
    void testCooldownIsRespected() {
        String group = "/api/products";

        seedSamples(group, 15, "shapeA");
        autoTrainer.checkAndTrain();
        Instant firstTrain = autoTrainer.getLastTrainTime(group);
        assertNotNull(firstTrain);

        // Advance clock 5 minutes (< 15 min cooldown)
        clock.advance(Duration.ofMinutes(5));

        // Force drift
        DictionaryVersion active = registry.getActive(group);
        for (int i = 0; i < 25; i++) {
            ratioTracker.recordSuccess(group, active.getId(), 0.5);
        }
        assertTrue(ratioTracker.isDrifting(group));

        // Accumulate enough samples
        seedSamples(group, 15, "shapeB");
        autoTrainer.checkAndTrain();

        // Still in cooldown: lastTrainTime unchanged
        assertEquals(firstTrain, autoTrainer.getLastTrainTime(group),
                "Should not retrain before cooldown expires");

        // Advance past 15 min cooldown
        clock.advance(Duration.ofMinutes(11)); // total 16 mins
        seedSamples(group, 15, "shapeB");
        autoTrainer.checkAndTrain();

        Instant secondTrain = autoTrainer.getLastTrainTime(group);
        assertNotNull(secondTrain);
        assertTrue(secondTrain.isAfter(firstTrain), "Retrained after cooldown passed");
    }
}
