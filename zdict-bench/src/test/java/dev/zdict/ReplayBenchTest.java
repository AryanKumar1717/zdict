package dev.zdict;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReplayBenchTest {

    @TempDir
    Path tempDir;

    @Test
    void testReplayBenchWithSmallDataset() throws Exception {
        Path samplesDir = tempDir.resolve("samples");
        Path groupDir = samplesDir.resolve("api_items_{id}");
        Files.createDirectories(groupDir);

        // Generate 30 payloads with common JSON tokens
        for (int i = 1; i <= 30; i++) {
            String json = String.format(
                    "{\"item_id\":%d,\"sku\":\"SKU-%05d\",\"title\":\"High Quality Product Name %d\",\"category\":\"Consumer Electronics\",\"details\":{\"warehouse\":\"CENTRAL_HUB\",\"stock\":%d,\"lead_time_days\":2},\"tags\":[\"PRIME\",\"NEW\",\"FEATURED\"],\"active\":true}",
                    i, i, i, (i * 7) % 50
            );
            Path p = groupDir.resolve(String.format("%06d.json", i));
            Files.writeString(p, json);
        }

        Path outputDir = tempDir.resolve("bench-out");
        ReplayBench.SuiteResult suite = ReplayBench.run(samplesDir, outputDir, 20);

        assertNotNull(suite);
        assertEquals(1, suite.groups.size());
        ReplayBench.GroupResult gr = suite.groups.get(0);
        assertEquals("api_items_{id}", gr.group);
        assertEquals(30, gr.totalPayloads);
        assertEquals(20, gr.trainPayloads);
        assertEquals(10, gr.testPayloads);

        // Verify results.json exists and parses
        Path jsonPath = outputDir.resolve("results.json");
        assertTrue(Files.isRegularFile(jsonPath));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonNode = mapper.readTree(jsonPath.toFile());
        assertEquals("ALL_GROUPS_TOTAL", jsonNode.path("overall").path("group").asText());
        assertEquals(1, jsonNode.path("groups").size());

        // Verify results.md exists and contains expected sections
        Path mdPath = outputDir.resolve("results.md");
        assertTrue(Files.isRegularFile(mdPath));
        String mdText = Files.readString(mdPath);
        assertTrue(mdText.contains("# zdict Replay Benchmark Results"));
        assertTrue(mdText.contains("api_items_{id}"));
        assertTrue(mdText.contains("zstd+dict"));
        assertTrue(mdText.toLowerCase().contains("simple loop"));

        // Verify ratio.svg is well-formed XML
        Path ratioSvgPath = outputDir.resolve("ratio.svg");
        assertTrue(Files.isRegularFile(ratioSvgPath));
        String ratioSvg = Files.readString(ratioSvgPath);
        assertValidXml(ratioSvg);
        assertTrue(ratioSvg.contains("<svg"));
        assertTrue(ratioSvg.contains("fill=\"#ffffff\""));

        // Verify latency.svg is well-formed XML
        Path latencySvgPath = outputDir.resolve("latency.svg");
        assertTrue(Files.isRegularFile(latencySvgPath));
        String latencySvg = Files.readString(latencySvgPath);
        assertValidXml(latencySvg);
        assertTrue(latencySvg.contains("<svg"));
        assertTrue(latencySvg.contains("fill=\"#ffffff\""));
    }

    private void assertValidXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            assertNotNull(doc.getDocumentElement());
        } catch (Exception e) {
            fail("SVG is not well-formed XML: " + e.getMessage());
        }
    }
}
