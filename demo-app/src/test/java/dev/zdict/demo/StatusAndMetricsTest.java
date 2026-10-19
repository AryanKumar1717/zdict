package dev.zdict.demo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class StatusAndMetricsTest {

    @TempDir
    Path tempDir;

    private SimpleMeterRegistry meterRegistry;
    private ZdictMetrics metrics;
    private EventLog eventLog;
    private DictionaryRegistry registry;
    private RatioTracker ratioTracker;
    private SampleStore sampleStore;
    private AutoTrainerService autoTrainer;
    private ZdictProperties props;
    private ZdictTrainController controller;
    private Clock clock;

    @BeforeEach
    void setUp() throws IOException {
        clock = Clock.fixed(Instant.parse("2026-05-01T10:00:00Z"), ZoneId.of("UTC"));
        meterRegistry = new SimpleMeterRegistry();
        metrics = new ZdictMetrics(meterRegistry, new ZdictCircuitBreaker());
        eventLog = new EventLog(clock, metrics);

        props = new ZdictProperties();
        props.setEnabled(true);
        props.setDataDir(tempDir.toString());
        props.getDrift().setMinResponses(20);
        props.getDrift().setThreshold(0.15);

        registry = new DictionaryRegistry(tempDir, null, clock);
        ratioTracker = new RatioTracker(registry, props, clock, metrics);
        sampleStore = new SampleStore(tempDir, 1000, 1.0, new PayloadScrubber(), new Random(42));
        DictionaryTrainerService trainer = new DictionaryTrainerService(sampleStore, registry, props, eventLog);
        autoTrainer = new AutoTrainerService(props, sampleStore, registry, trainer, ratioTracker, clock, eventLog);

        controller = new ZdictTrainController(trainer, registry, ratioTracker, sampleStore, autoTrainer, eventLog, props);
    }

    private byte[] makeSample(int size, char c) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (c + (i % 26));
        }
        return b;
    }

    @Test
    void testEventLogCircularBufferCapacity50() {
        for (int i = 0; i < 55; i++) {
            eventLog.record("TRAINED", "/api/group" + (i % 5), "dict_" + i, "reason " + i);
        }

        List<ZdictEvent> events = eventLog.getRecentEvents();
        assertEquals(50, events.size(), "Event log should keep at most 50 events in memory");
        // Oldest 5 (0..4) were dropped; first item should be reason 5
        assertEquals("reason 5", events.get(0).getReason());
        assertEquals("reason 54", events.get(49).getReason());

        ZdictEvent lastForGroup0 = eventLog.getLastEvent("/api/group0");
        assertNotNull(lastForGroup0);
        assertEquals("reason 50", lastForGroup0.getReason());
    }

    @Test
    void testMetricsCountersAndRollingGauge() {
        eventLog.record("TRAINED", "/api/items", "dict_a", "initial training");
        eventLog.record("PROMOTED", "/api/items", "dict_b", "canary beat active");

        Counter trainedCounter = meterRegistry.find("zdict.events")
                .tag("type", "TRAINED")
                .tag("group", "/api/items")
                .counter();
        assertNotNull(trainedCounter);
        assertEquals(1.0, trainedCounter.count());

        Counter promotedCounter = meterRegistry.find("zdict.events")
                .tag("type", "PROMOTED")
                .tag("group", "/api/items")
                .counter();
        assertNotNull(promotedCounter);
        assertEquals(1.0, promotedCounter.count());

        // Update rolling ratio via tracker
        ratioTracker.recordSuccess("/api/items", "dict_b", 4.25);

        Gauge ratioGauge = meterRegistry.find("zdict.ratio.rolling")
                .tag("group", "/api/items")
                .gauge();
        assertNotNull(ratioGauge);
        assertEquals(4.25, ratioGauge.value(), 0.001);
    }

    @Test
    void testActuatorGroupsEndpointReportsAllStatuses() throws IOException {
        // Group 1: NO_DICTIONARY
        sampleStore.offer("/api/nodict", "{\"item\":\"sample\"}".repeat(10).getBytes());

        // Group 2: HEALTHY
        byte[] dictH = makeSample(2048, 'h');
        DictionaryVersion vH = registry.register("/api/healthy", dictH, 500, 0.25, 4.0);
        for (int i = 0; i < 25; i++) {
            ratioTracker.recordSuccess("/api/healthy", vH.getId(), 4.0);
        }
        eventLog.record("TRAINED", "/api/healthy", vH.getId(), "initial healthy active");

        // Group 3: DRIFTING
        byte[] dictD = makeSample(2048, 'd');
        DictionaryVersion vD = registry.register("/api/drifting", dictD, 500, 0.25, 4.0);
        for (int i = 0; i < 25; i++) {
            ratioTracker.recordSuccess("/api/drifting", vD.getId(), 1.5); // degraded
        }
        eventLog.record("DRIFT_DETECTED", "/api/drifting", vD.getId(), "ratio dropped below threshold");

        // Group 4: CANARY
        byte[] dictC1 = makeSample(2048, 'c');
        byte[] dictC2 = makeSample(2048, 'x');
        registry.register("/api/canary", dictC1, 500, 0.25, 4.0);
        DictionaryVersion vCanary = registry.registerCanary("/api/canary", dictC2, 500, 0.20, 5.0);
        eventLog.record("CANARY_STARTED", "/api/canary", vCanary.getId(), "candidate entered canary rollout");

        ResponseEntity<List<GroupStatus>> response = controller.getGroups();
        assertEquals(200, response.getStatusCode().value());
        List<GroupStatus> statuses = response.getBody();
        assertNotNull(statuses);

        GroupStatus noDictStatus = statuses.stream().filter(s -> s.getGroup().contains("nodict")).findFirst().orElseThrow();
        assertEquals("NO_DICTIONARY", noDictStatus.getStatus());
        assertNull(noDictStatus.getActiveId());

        GroupStatus healthyStatus = statuses.stream().filter(s -> s.getGroup().contains("healthy")).findFirst().orElseThrow();
        assertEquals("HEALTHY", healthyStatus.getStatus());
        assertEquals(vH.getId(), healthyStatus.getActiveId());
        assertEquals(4.0, healthyStatus.getBaselineRatio(), 0.001);
        assertEquals(4.0, healthyStatus.getRollingRatio(), 0.001);
        assertNotNull(healthyStatus.getLastEvent());
        assertEquals("TRAINED", healthyStatus.getLastEvent().getType());

        GroupStatus driftingStatus = statuses.stream().filter(s -> s.getGroup().contains("drifting")).findFirst().orElseThrow();
        assertEquals("DRIFTING", driftingStatus.getStatus());
        assertEquals(vD.getId(), driftingStatus.getActiveId());
        assertNotNull(driftingStatus.getLastEvent());
        assertEquals("DRIFT_DETECTED", driftingStatus.getLastEvent().getType());

        GroupStatus canaryStatus = statuses.stream().filter(s -> s.getGroup().contains("canary")).findFirst().orElseThrow();
        assertEquals("CANARY", canaryStatus.getStatus());
        assertNotNull(canaryStatus.getActiveId());
        assertEquals(vCanary.getId(), canaryStatus.getCanaryId());
        assertNotNull(canaryStatus.getLastEvent());
        assertEquals("CANARY_STARTED", canaryStatus.getLastEvent().getType());
    }
}
