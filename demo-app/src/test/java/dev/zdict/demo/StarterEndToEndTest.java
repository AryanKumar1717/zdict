package dev.zdict.demo;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictDecompress;
import dev.zdict.client.ZdictClient;
import dev.zdict.client.ZdictResponse;
import dev.zdict.core.*;
import dev.zdict.spring.ZstdDictFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test:
 * 1. Boots demo app with zdict-spring-boot-starter
 * 2. Runs traffic to sample endpoints
 * 3. Trains dictionary and verifies compression
 * 4. Checks the NOT_WORTH_IT path when dictionary fails min-gain gate
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StarterEndToEndTest {

    @TempDir
    static Path sharedTempDir;

    @LocalServerPort
    private int port;

    @Autowired
    private SampleStore sampleStore;

    @Autowired
    private DictionaryRegistry registry;

    @Autowired
    private DictionaryTrainerService trainerService;

    @DynamicPropertySource
    static void configureProps(DynamicPropertyRegistry r) {
        r.add("zdict.enabled", () -> "true");
        r.add("zdict.paths", () -> "/api/products");
        r.add("zdict.data-dir", () -> sharedTempDir.toAbsolutePath().toString());
        r.add("zdict.seed-dictionary-path", () -> "");
        r.add("zdict.dictionary-path", () -> "");
        r.add("zdict.sample.rate", () -> "1.0");
        r.add("zdict.sample.per-group-max", () -> "500");
        r.add("zdict.train.min-samples", () -> "20");
        r.add("zdict.train.min-gain", () -> "0.10");
    }

    private ZdictClient client;

    @BeforeEach
    void setUp() {
        client = new ZdictClient();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void testEndToEndTrafficTrainingAndNotWorthItPath() throws Exception {
        String group = "/api/products";
        String baseUrl = "http://localhost:" + port;

        // 1. Initial traffic: send 25 requests to /api/products?page=N
        for (int i = 1; i <= 25; i++) {
            ZdictResponse resp = client.get(baseUrl + "/api/products?page=" + i);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.compressed()).isFalse(); // no dictionary active yet
        }

        // Verify samples collected in SampleStore
        assertThat(sampleStore.getSampleCount(group)).isGreaterThanOrEqualTo(20);

        // 2. Train dictionary for /api/products
        TrainResult trainResult = trainerService.train(group);
        assertThat(trainResult.isPromoted()).isTrue();
        assertThat(trainResult.getDecision()).isEqualTo("PROMOTED_FIRST_ACTIVE");
        String activeDictId = trainResult.getDictionaryId();
        assertThat(activeDictId).isNotNull();

        DictionaryVersion activeVersion = registry.getActive(group);
        assertThat(activeVersion).isNotNull();
        assertThat(activeVersion.getId()).isEqualTo(activeDictId);

        // 3. Now send request through client:
        // Request 1: discovers X-Dict-Available and fetches dictionary
        ZdictResponse resp1 = client.get(baseUrl + "/api/products?page=1");
        assertThat(resp1.statusCode()).isEqualTo(200);

        // Request 2: sends X-Dict-Ids and receives zstd-dict compressed payload
        ZdictResponse resp2 = client.get(baseUrl + "/api/products?page=2");
        assertThat(resp2.statusCode()).isEqualTo(200);
        assertThat(resp2.compressed()).isTrue();
        assertThat(resp2.body()).contains("\"status\":\"ok\"");

        // 4. NOT_WORTH_IT path:
        // Seed another group with random, incompressible high-entropy payloads
        String noiseGroup = "/api/random-noise";
        Random rng = new Random(12345);
        for (int i = 0; i < 30; i++) {
            byte[] noise = new byte[800];
            rng.nextBytes(noise);
            // Wrap in minimal valid JSON
            String json = "{\"id\":" + i + ",\"noise\":\"" + java.util.Base64.getEncoder().encodeToString(noise) + "\"}";
            sampleStore.offer(noiseGroup, json.getBytes(StandardCharsets.UTF_8));
        }

        assertThat(sampleStore.getSampleCount(noiseGroup)).isGreaterThanOrEqualTo(20);

        // Train on noise group -> should fail the worth-it gate (< 10% gain over plain zstd / gzip)
        TrainResult noiseResult = trainerService.train(noiseGroup);
        assertThat(noiseResult.isPromoted()).isFalse();
        assertThat(noiseResult.getDecision()).isEqualTo("NOT_WORTH_IT");
        assertThat(noiseResult.getReason()).contains("failed worth-it gate");
        assertThat(trainerService.isNotWorthIt(noiseGroup)).isTrue();
    }
}
