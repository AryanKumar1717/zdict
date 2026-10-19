package dev.zdict.demo;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import com.github.luben.zstd.ZstdDictTrainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.MediaType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for ZstdDictFilter covering: round-trip, passthrough, and failure scenarios.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ZstdDictFilterTest {

    @TempDir
    static Path tempDir;

    static String dictId;
    static byte[] dictBytes;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ZstdDictFilter filter;

    @BeforeEach
    void resetState() {
        filter.setEnabled(true);
        filter.getCircuitBreaker().reset();
    }

    /**
     * Train a small dictionary from synthetic product JSON, write it to a temp file.
     */
    @BeforeAll
    static void trainDictionary() throws Exception {
        ProductGenerator gen = new ProductGenerator(99);
        ZstdDictTrainer trainer = new ZstdDictTrainer(256 * 1024, 16 * 1024);
        for (int page = 1; page <= 50; page++) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"status\":\"ok\",\"requestId\":\"test\",\"data\":{\"items\":[");
            var products = gen.generatePage(page);
            for (int i = 0; i < products.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("{\"id\":").append(products.get(i).id())
                  .append(",\"name\":\"").append(products.get(i).name()).append("\"")
                  .append(",\"category\":\"").append(products.get(i).category()).append("\"")
                  .append(",\"price\":").append(products.get(i).price())
                  .append(",\"inStock\":").append(products.get(i).inStock())
                  .append(",\"rating\":").append(products.get(i).rating())
                  .append(",\"tags\":[\"new\"]")
                  .append(",\"createdAt\":\"").append(products.get(i).createdAt()).append("\"")
                  .append("}");
            }
            sb.append("],\"page\":").append(page).append(",\"total\":").append(products.size() * 10).append("}}");
            trainer.addSample(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        dictBytes = trainer.trainSamples();
        dictId = ZstdDictFilter.computeDictId(dictBytes);
        Files.write(tempDir.resolve("test.dict"), dictBytes);
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("zdict.enabled", () -> "true");
        registry.add("zdict.dictionary-path", () -> tempDir.resolve("test.dict").toString());
        registry.add("zdict.paths", () -> "/api/products");
        registry.add("management.endpoints.web.exposure.include", () -> "*");
    }

    // ---- Round-trip test ----

    @Test
    void compressedResponseDecompressesToOriginalJson() throws Exception {
        // First, get the uncompressed baseline response (no X-Dict-Id header)
        MvcResult uncompressed = mockMvc.perform(get("/api/products?page=3"))
                .andExpect(status().isOk())
                .andReturn();
        String originalJson = uncompressed.getResponse().getContentAsString();

        // Now request with the dict id header - should compress
        MvcResult compressed = mockMvc.perform(get("/api/products?page=3")
                        .header("X-Dict-Id", dictId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Encoding", "zstd-dict"))
                .andExpect(header().string("X-Dict-Id", dictId))
                .andExpect(header().exists("Vary"))
                .andReturn();

        byte[] compressedBody = compressed.getResponse().getContentAsByteArray();
        assertThat(compressedBody.length).isGreaterThan(0);

        // Decompress and verify it matches the original JSON byte-for-byte
        try (ZstdDictDecompress ddict = new ZstdDictDecompress(dictBytes)) {
            long decompressedSize = Zstd.decompressedSize(compressedBody);
            assertThat(decompressedSize).isGreaterThan(0);
            byte[] decompressed = Zstd.decompress(compressedBody, ddict, (int) decompressedSize);
            String json = new String(decompressed, StandardCharsets.UTF_8);

            assertThat(json).isEqualTo(originalJson);
            assertThat(compressedBody.length).isLessThan(decompressed.length);
        }
    }

    // ---- Passthrough tests ----

    @Test
    void passThroughWhenNoXDictIdHeader() throws Exception {
        mockMvc.perform(get("/api/products?page=1"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Encoding"))
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void passThroughWhenWrongDictId() throws Exception {
        mockMvc.perform(get("/api/products?page=1")
                        .header("X-Dict-Id", "00000000"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Encoding"))
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void passThroughWhenPathNotInAllowlist() throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .header("X-Dict-Id", dictId))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Encoding"));
    }

    @Test
    void passThroughWhenBodyTooSmall() throws Exception {
        // Body is valid JSON but under 200 bytes
        MvcResult result = mockMvc.perform(get("/api/products/tiny")
                        .header("X-Dict-Id", dictId))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Encoding"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"status\":\"ok\"}");
    }

    @Test
    void passThroughWhenNonJson() throws Exception {
        // Non-JSON content type (text/plain) should not be compressed
        MvcResult result = mockMvc.perform(get("/api/products/text")
                        .header("X-Dict-Id", dictId))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Encoding"))
                .andReturn();

        assertThat(result.getResponse().getContentType()).contains("text/plain");
    }

    // ---- Fail-open compression error test ----

    @Test
    void compressionFailureFailsOpenToOriginalBody() throws Exception {
        // Temporarily set a closed compressor to force an exception in Zstd.compress
        ZstdDictCompress badCdict = new ZstdDictCompress(new byte[]{0}, 3);
        badCdict.close();
        filter.setCdictForTesting(badCdict);

        try {
            MvcResult result = mockMvc.perform(get("/api/products?page=3")
                            .header("X-Dict-Id", dictId))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Content-Encoding"))
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            assertThat(body).contains("\"status\":\"ok\"");
            assertThat(body).contains("\"items\":[");
        } finally {
            // Restore a valid compressor
            filter.setCdictForTesting(new ZstdDictCompress(dictBytes, 3));
        }
    }

    // ---- Dictionary endpoint tests ----

    @Test
    void dictionaryEndpointReturnsBytes() throws Exception {
        MvcResult result = mockMvc.perform(get("/zdict/dictionary/" + dictId))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(dictBytes);
    }

    @Test
    void dictionaryEndpointReturns404ForWrongId() throws Exception {
        mockMvc.perform(get("/zdict/dictionary/deadbeef"))
                .andExpect(status().isNotFound());
    }

    // ---- Safety Layer Tests ----

    @Test
    void passThroughWhenAuthorizationHeaderPresent() throws Exception {
        mockMvc.perform(get("/api/products?page=1")
                        .header("X-Dict-Id", dictId)
                        .header("Authorization", "Bearer test-token-12345"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Encoding"))
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void passThroughWhenCookieHeaderPresent() throws Exception {
        mockMvc.perform(get("/api/products?page=1")
                        .header("X-Dict-Id", dictId)
                        .header("Cookie", "sessionId=test-cookie-val"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Encoding"))
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void passThroughWhenResponseHasSetCookie() throws Exception {
        mockMvc.perform(get("/api/products/set-cookie")
                        .header("X-Dict-Id", dictId))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Encoding"))
                .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    void passThroughWhenResponseHasPrivateCacheControl() throws Exception {
        mockMvc.perform(get("/api/products/private-cache")
                        .header("X-Dict-Id", dictId))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Encoding"));
    }

    @Test
    void passThroughWhenQueryParamReflectedInBody() throws Exception {
        mockMvc.perform(get("/api/products/reflect?search=reflectMe123")
                        .header("X-Dict-Id", dictId))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Encoding"))
                .andExpect(jsonPath("$.search").value("reflectMe123"));
    }

    // ---- Kill Switch & Actuator Endpoint Tests ----

    @Test
    void passThroughWhenKillSwitchOff() throws Exception {
        filter.setEnabled(false);
        try {
            mockMvc.perform(get("/api/products?page=1")
                            .header("X-Dict-Id", dictId))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Content-Encoding"));
        } finally {
            filter.setEnabled(true);
        }
    }

    @Test
    void actuatorEndpointReadsAndTogglesKillSwitch() throws Exception {
        // Read state
        mockMvc.perform(get("/actuator/zdict"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        // Disable via POST
        mockMvc.perform(post("/actuator/zdict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        assertThat(filter.isEnabled()).isFalse();

        // Re-enable
        mockMvc.perform(post("/actuator/zdict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        assertThat(filter.isEnabled()).isTrue();
    }

    // ---- Circuit Breaker Tests ----

    @Test
    void circuitBreakerOpensOnFailuresAndLaterRecovers() throws Exception {
        ZdictCircuitBreaker cb = filter.getCircuitBreaker();
        cb.reset();

        AtomicLong currentTimeMs = new AtomicLong(100_000);
        Clock testClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }
            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }
            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(currentTimeMs.get());
            }
        };
        cb.setClock(testClock);
        cb.setOpenDurationMs(5_000);

        // Record 12 failures (6% failure rate of 200)
        for (int i = 0; i < 188; i++) {
            cb.recordSuccess(100_000);
        }
        for (int i = 0; i < 12; i++) {
            cb.recordFailure(100_000);
        }

        // Circuit breaker should now be OPEN
        assertThat(cb.isOpen()).isTrue();

        // Requests should pass through uncompressed while circuit breaker is OPEN
        mockMvc.perform(get("/api/products?page=1")
                        .header("X-Dict-Id", dictId))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Encoding"));

        // Advance time past the 5 second open window
        currentTimeMs.addAndGet(6_000);

        // Circuit breaker should recover (CLOSED)
        assertThat(cb.isOpen()).isFalse();

        // Compression should work again
        mockMvc.perform(get("/api/products?page=1")
                        .header("X-Dict-Id", dictId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Encoding", "zstd-dict"));

        // Reset to system clock
        cb.setClock(Clock.systemUTC());
        cb.setOpenDurationMs(60_000);
        cb.reset();
    }

    // ---- Metrics Tests ----

    @Test
    void metricsCountersIncreaseAfterCompressedResponse() throws Exception {
        MeterRegistry registry = filter.getMetrics().getRegistry();

        double originalBytesBefore = registry.find("zdict.bytes.original").tag("path", "/api/products").counter() != null
                ? registry.find("zdict.bytes.original").tag("path", "/api/products").counter().count() : 0.0;
        double compressedBytesBefore = registry.find("zdict.bytes.compressed").tag("path", "/api/products").counter() != null
                ? registry.find("zdict.bytes.compressed").tag("path", "/api/products").counter().count() : 0.0;
        double responsesCompressedBefore = registry.find("zdict.responses")
                .tag("result", "compressed").counter() != null
                ? registry.find("zdict.responses").tag("result", "compressed").counter().count() : 0.0;

        MvcResult result = mockMvc.perform(get("/api/products?page=1")
                        .header("X-Dict-Id", dictId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Encoding", "zstd-dict"))
                .andReturn();

        byte[] compressedBody = result.getResponse().getContentAsByteArray();

        double originalBytesAfter = registry.find("zdict.bytes.original").tag("path", "/api/products").counter().count();
        double compressedBytesAfter = registry.find("zdict.bytes.compressed").tag("path", "/api/products").counter().count();
        double responsesCompressedAfter = registry.find("zdict.responses")
                .tag("result", "compressed").counter().count();

        assertThat(originalBytesAfter).isGreaterThan(originalBytesBefore);
        assertThat(compressedBytesAfter).isEqualTo(compressedBytesBefore + compressedBody.length);
        assertThat(responsesCompressedAfter).isEqualTo(responsesCompressedBefore + 1.0);

        // Circuit open gauge should be 0.0
        assertThat(registry.find("zdict.circuit.open").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void metricsUseGroupKeyInsteadOfRawPath() throws Exception {
        MeterRegistry registry = filter.getMetrics().getRegistry();

        mockMvc.perform(get("/api/products/42")
                        .header("X-Dict-Id", dictId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Encoding", "zstd-dict"));

        var counter = registry.find("zdict.bytes.original")
                .tag("path", "/api/products/{id}")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThan(0.0);
    }
}
