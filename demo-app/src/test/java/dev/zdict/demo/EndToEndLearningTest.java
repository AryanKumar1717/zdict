package dev.zdict.demo;

import dev.zdict.client.ZdictClient;
import dev.zdict.client.ZdictResponse;
import dev.zdict.core.*;
import dev.zdict.spring.ZdictProperties;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndLearningTest {

    @TempDir
    static Path tempDir;

    @LocalServerPort
    private int port;

    @Autowired
    private SampleStore sampleStore;

    @Autowired
    private DictionaryRegistry registry;

    private ZdictClient client;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry propRegistry) {
        propRegistry.add("zdict.enabled", () -> "true");
        propRegistry.add("zdict.data-dir", () -> tempDir.toString());
        propRegistry.add("zdict.seed-dictionary-path", () -> "");
        propRegistry.add("zdict.dictionary-path", () -> "");
        propRegistry.add("zdict.paths", () -> "/api/products");
        propRegistry.add("zdict.sample.rate", () -> "1.0");
        propRegistry.add("zdict.train.min-samples", () -> "100");
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
    void endToEndLearningPerEndpointWithDifferentDictionariesAndRestart() throws Exception {
        String baseUrl = "http://localhost:" + port;
        String listUrl = baseUrl + "/api/products?page=";
        String singleUrl = baseUrl + "/api/products/";

        // Step 1: Send ~500 requests through client (responses uncompressed initially)
        int listBaselineWireSize = 0;
        int singleBaselineWireSize = 0;

        for (int i = 1; i <= 250; i++) {
            ZdictResponse resp = client.get(listUrl + (i % 20 + 1));
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.compressed()).isFalse();
            if (i == 1) {
                listBaselineWireSize = resp.wireSize();
            }
        }

        for (int i = 1; i <= 250; i++) {
            ZdictResponse resp = client.get(singleUrl + i);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.compressed()).isFalse();
            if (i == 1) {
                singleBaselineWireSize = resp.wireSize();
            }
        }

        // Step 2: Verify samples accumulated in SampleStore
        assertThat(sampleStore.getSampleCount("/api/products")).isGreaterThanOrEqualTo(100);
        assertThat(sampleStore.getSampleCount("/api/products/{id}")).isGreaterThanOrEqualTo(100);

        // Step 3: Trigger training for both endpoint groups via Actuator POST
        HttpClient rawHttp = HttpClient.newHttpClient();

        String encListGroup = java.net.URLEncoder.encode("/api/products", java.nio.charset.StandardCharsets.UTF_8);
        String encSingleGroup = java.net.URLEncoder.encode("/api/products/{id}", java.nio.charset.StandardCharsets.UTF_8);

        HttpRequest trainListReq = HttpRequest.newBuilder(
                URI.create(baseUrl + "/actuator/zdict/train?group=" + encListGroup)
        ).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> trainListResp = rawHttp.send(trainListReq, HttpResponse.BodyHandlers.ofString());
        assertThat(trainListResp.statusCode()).isEqualTo(200);
        assertThat(trainListResp.body()).contains("\"promoted\":true");
        assertThat(trainListResp.body()).contains("PROMOTED_FIRST_ACTIVE");

        HttpRequest trainSingleReq = HttpRequest.newBuilder(
                URI.create(baseUrl + "/actuator/zdict/train?group=" + encSingleGroup)
        ).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> trainSingleResp = rawHttp.send(trainSingleReq, HttpResponse.BodyHandlers.ofString());
        assertThat(trainSingleResp.statusCode()).isEqualTo(200);
        assertThat(trainSingleResp.body()).contains("\"promoted\":true");
        assertThat(trainSingleResp.body()).contains("PROMOTED_FIRST_ACTIVE");

        // Step 4: Verify two groups get two DIFFERENT dictionaries
        DictionaryVersion listDict = registry.getActive("/api/products");
        DictionaryVersion singleDict = registry.getActive("/api/products/{id}");
        assertThat(listDict).isNotNull();
        assertThat(singleDict).isNotNull();
        assertThat(listDict.getId()).isNotEqualTo(singleDict.getId());

        // Step 5: Verify new client requests become compressed, are smaller on wire, and decode correctly
        // First request to /api/products sees X-Dict-Available and fetches listDict
        ZdictResponse listResp1 = client.get(listUrl + "1");
        assertThat(listResp1.statusCode()).isEqualTo(200);
        assertThat(client.hasDictionary(listDict.getId())).isTrue();

        // Second request sends X-Dict-Ids and receives compressed response
        ZdictResponse listResp2 = client.get(listUrl + "1");
        assertThat(listResp2.statusCode()).isEqualTo(200);
        assertThat(listResp2.compressed()).isTrue();
        assertThat(listResp2.wireSize()).isLessThan(listBaselineWireSize);
        assertThat(listResp2.body()).contains("\"items\":[");

        // First request to /api/products/{id} sees X-Dict-Available and fetches singleDict
        ZdictResponse singleResp1 = client.get(singleUrl + "42");
        assertThat(singleResp1.statusCode()).isEqualTo(200);
        assertThat(client.hasDictionary(singleDict.getId())).isTrue();

        // Client now holds BOTH dictionaries simultaneously
        assertThat(client.getDictionaryCount()).isEqualTo(2);

        // Second request sends both X-Dict-Ids and receives singleDict compressed response
        ZdictResponse singleResp2 = client.get(singleUrl + "42");
        assertThat(singleResp2.statusCode()).isEqualTo(200);
        assertThat(singleResp2.compressed()).isTrue();
        assertThat(singleResp2.wireSize()).isLessThan(singleBaselineWireSize);
        assertThat(singleResp2.body()).contains("\"id\":42");

        // Step 6: Verify a restart keeps both dictionaries
        try (DictionaryRegistry reloadedRegistry = new DictionaryRegistry(tempDir)) {
            reloadedRegistry.loadFromDisk();

            DictionaryVersion reloadedList = reloadedRegistry.getActive("/api/products");
            DictionaryVersion reloadedSingle = reloadedRegistry.getActive("/api/products/{id}");

            assertThat(reloadedList).isNotNull();
            assertThat(reloadedSingle).isNotNull();
            assertThat(reloadedList.getId()).isEqualTo(listDict.getId());
            assertThat(reloadedSingle.getId()).isEqualTo(singleDict.getId());
        }
    }
}
