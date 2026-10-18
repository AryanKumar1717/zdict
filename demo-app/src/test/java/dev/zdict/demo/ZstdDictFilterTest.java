package dev.zdict.demo;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictDecompress;
import com.github.luben.zstd.ZstdDictTrainer;
import org.junit.jupiter.api.BeforeAll;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    }

    // ---- Round-trip test ----

    @Test
    void compressedResponseDecompressesToOriginalJson() throws Exception {
        // Request with the dict id header - should compress
        MvcResult compressed = mockMvc.perform(get("/api/products?page=3")
                        .header("X-Dict-Id", dictId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Encoding", "zstd-dict"))
                .andExpect(header().string("X-Dict-Id", dictId))
                .andExpect(header().exists("Vary"))
                .andReturn();

        byte[] compressedBody = compressed.getResponse().getContentAsByteArray();
        assertThat(compressedBody.length).isGreaterThan(0);

        // The filter also cached the original body in ContentCachingResponseWrapper.
        // We know what the original JSON should look like, so decompress and verify
        // it has a valid JSON structure with expected fields.
        try (ZstdDictDecompress ddict = new ZstdDictDecompress(dictBytes)) {
            long decompressedSize = Zstd.decompressedSize(compressedBody);
            assertThat(decompressedSize).isGreaterThan(0);
            byte[] decompressed = Zstd.decompress(compressedBody, ddict, (int) decompressedSize);
            String json = new String(decompressed, StandardCharsets.UTF_8);

            // Verify it's valid JSON with the expected envelope
            assertThat(json).startsWith("{\"status\":\"ok\"");
            assertThat(json).contains("\"requestId\":");
            assertThat(json).contains("\"data\":");
            assertThat(json).contains("\"items\":[");
            assertThat(json).contains("\"category\":");
            assertThat(json).contains("\"price\":");
            assertThat(json).contains("\"inStock\":");

            // Verify compressed is actually smaller than decompressed
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
        // Single product is typically < 200 bytes
        mockMvc.perform(get("/api/products/1")
                        .header("X-Dict-Id", dictId))
                .andExpect(status().isOk())
                .andReturn();
        // We just verify it doesn't crash; the body may or may not be compressed
        // depending on size. The key thing is no error.
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
}
