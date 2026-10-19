package dev.zdict.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class DictionaryTrainerServiceTest {

    @TempDir
    Path tempDir;

    private SampleStore sampleStore;
    private DictionaryRegistry registry;
    private ZdictProperties props;
    private DictionaryTrainerService trainerService;

    @BeforeEach
    void setUp() {
        props = new ZdictProperties();
        props.setDataDir(tempDir.toString());
        props.getTrain().setMinSamples(50); // set lower for fast test execution
        props.getTrain().setDictSize(4 * 1024); // 4 KB for test

        sampleStore = new SampleStore(tempDir, 1000, 1.0, new PayloadScrubber(), new Random(42));
        registry = new DictionaryRegistry(tempDir);
        trainerService = new DictionaryTrainerService(sampleStore, registry, props);
    }

    private void generateSamples(String group, int count) {
        for (int i = 0; i < count; i++) {
            String json = String.format(
                    "{\"id\":%d,\"name\":\"Item %d\",\"description\":\"High quality manufactured product for enterprise customer %d\",\"category\":\"electronics\",\"price\":%.2f,\"active\":true,\"inventory\":{\"warehouse\":\"WH-%d\",\"count\":%d}}",
                    i, i, i % 10, (i * 12.34) % 1000, i % 5, i * 7
            );
            sampleStore.offer(group, json.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void belowMinSamplesIsRefused() throws Exception {
        String group = "/api/sparse";
        generateSamples(group, 20); // only 20 samples, min is 50

        TrainResult result = trainerService.train(group);
        assertThat(result.isPromoted()).isFalse();
        assertThat(result.getDecision()).isEqualTo("REFUSED");
        assertThat(result.getSampleCount()).isEqualTo(20);
        assertThat(result.getReason()).contains("below minimum");

        assertThat(registry.getActive(group)).isNull();
    }

    @Test
    void trainsOnGeneratedSamplesAndPromotesFirstActive() throws Exception {
        String group = "/api/products";
        generateSamples(group, 80);

        TrainResult result = trainerService.train(group);

        assertThat(result.isPromoted()).isTrue();
        assertThat(result.getDecision()).isEqualTo("PROMOTED_FIRST_ACTIVE");
        assertThat(result.getDictionaryId()).isNotNull();
        assertThat(result.getNewHoldoutRatio()).isGreaterThan(0.0).isLessThan(1.0);
        assertThat(result.getPlainHoldoutRatio()).isGreaterThan(0.0).isLessThan(1.0);
        assertThat(result.getActiveHoldoutRatio()).isNull();

        DictionaryVersion active = registry.getActive(group);
        assertThat(active).isNotNull();
        assertThat(active.getId()).isEqualTo(result.getDictionaryId());
    }

    @Test
    void promotionRuleWorksBothWays() throws Exception {
        String group = "/api/items";
        generateSamples(group, 80);

        // 1. Initial training promotes first active dictionary
        TrainResult firstTrain = trainerService.train(group);
        assertThat(firstTrain.isPromoted()).isTrue();
        String initialId = firstTrain.getDictionaryId();
        assertThat(registry.getActive(group).getId()).isEqualTo(initialId);

        // 2. Training again on identical samples: candidate does not beat active by 5%, so discarded
        TrainResult secondTrain = trainerService.train(group);
        assertThat(secondTrain.isPromoted()).isFalse();
        assertThat(secondTrain.getDecision()).isEqualTo("DISCARDED_INSUFFICIENT_IMPROVEMENT");
        assertThat(secondTrain.getActiveHoldoutRatio()).isNotNull();
        // Active dictionary remains initialId
        assertThat(registry.getActive(group).getId()).isEqualTo(initialId);

        // 3. Now register a poor/dummy active dictionary to test the other branch (promotion when beating active by >= 5%)
        byte[] poorDict = "poor dummy dict".getBytes(StandardCharsets.UTF_8);
        registry.register(group, poorDict, 50, 0.99);
        assertThat(registry.getActive(group).getId()).isNotEqualTo(initialId);

        // Retraining on the rich samples should beat the poor dictionary significantly and register as CANARY
        TrainResult thirdTrain = trainerService.train(group);
        assertThat(thirdTrain.getDecision()).isEqualTo("CANARY_STARTED");
        assertThat(registry.getCanary(group).getId()).isEqualTo(thirdTrain.getDictionaryId());
    }
}
