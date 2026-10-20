package dev.zdict;

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

class DriftBenchTest {

    @TempDir
    Path tempDir;

    @Test
    void testSyntheticGenerationAndDriftScenario() throws Exception {
        Path dirA = tempDir.resolve("synthetic-A");
        Path dirB = tempDir.resolve("synthetic-B");

        // Generate 300 samples for A, 200 for B
        DriftBench.generateSynthetic(dirA, dirB, 300, 200);

        // Verify SYNTHETIC labeling
        Path sampleA = dirA.resolve("000001.json");
        Path sampleB = dirB.resolve("000001.json");
        assertTrue(Files.isRegularFile(sampleA));
        assertTrue(Files.isRegularFile(sampleB));
        assertTrue(Files.readString(sampleA).contains("\"SYNTHETIC\""));
        assertTrue(Files.readString(sampleB).contains("\"SYNTHETIC\""));

        Path outputDir = tempDir.resolve("drift-out");
        DriftBench.DriftResult result = DriftBench.run(dirA, dirB, outputDir);

        assertNotNull(result);
        assertEquals(500, result.totalPayloads);
        assertEquals(300, result.countA);
        assertEquals(200, result.countB);

        // Verify drift was detected and retrain happened
        assertTrue(result.driftRetrainCount >= 1, "Expected at least 1 drift retrain, but was: " + result.driftRetrainCount);
        assertTrue(result.totalAdaptiveBytes < result.totalStaticBytes, "Adaptive should compress better overall than static after drift");

        // Verify drift.svg is well-formed XML
        Path svgPath = outputDir.resolve("drift.svg");
        assertTrue(Files.isRegularFile(svgPath));
        String svgContent = Files.readString(svgPath);
        assertValidXml(svgContent);
        assertTrue(svgContent.contains("fill=\"#ffffff\""));
        assertTrue(svgContent.contains("Schema Drift"));

        // Verify drift.md exists and contains expected sections
        Path mdPath = outputDir.resolve("drift.md");
        assertTrue(Files.isRegularFile(mdPath));
        String mdText = Files.readString(mdPath);
        assertTrue(mdText.contains("# zdict Drift Scenario Benchmark"));
        assertTrue(mdText.contains("Adaptive zdict"));
        assertTrue(mdText.contains("Static zstd+dict"));
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
