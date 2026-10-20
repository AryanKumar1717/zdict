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

        // Generate 300 samples for A, 300 for B (so B has enough to accumulate 200 post-drift samples)
        DriftBench.generateSynthetic(dirA, dirB, 300, 300);

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
        assertTrue(result.isSynthetic, "Result should be labeled SYNTHETIC");
        assertEquals(600, result.totalPayloads);
        assertEquals(300, result.countA);
        assertEquals(300, result.countB);
        assertEquals(300, result.shapeSwitchPoint);

        // Verify drift detection and elapsed payload tracking
        assertTrue(result.driftDetectedAt > 300, "Drift should be detected after shape switch at #300");
        assertTrue(result.payloadsElapsedToDetection > 0, "Should track positive elapsed payloads to detection");

        // Verify adaptive retrain happened after accumulating 200 post-drift payloads
        assertTrue(result.driftRetrainCount >= 1, "Expected at least 1 drift retrain, but was: " + result.driftRetrainCount);
        assertTrue(result.driftRetrainPayloadIndices.get(0) >= result.driftDetectedAt + 200,
                "Retrain switch point should be at least 200 payloads after drift detection");

        // Verify 4 strategies reported
        assertNotNull(result.gzip);
        assertNotNull(result.staticDict);
        assertNotNull(result.fixedSchedule);
        assertNotNull(result.adaptive);

        assertTrue(result.fixedSchedule.retrainCount >= 2, "Fixed schedule should retrain every 200 payloads");
        assertTrue(result.gzip.postSwitch100Ratio > 0.0);
        assertTrue(result.staticDict.postSwitch100Ratio > 0.0);
        assertTrue(result.fixedSchedule.postSwitch100Ratio > 0.0);
        assertTrue(result.adaptive.postSwitch100Ratio > 0.0);

        // Verify drift.svg is well-formed XML
        Path svgPath = outputDir.resolve("drift.svg");
        assertTrue(Files.isRegularFile(svgPath));
        String svgContent = Files.readString(svgPath);
        assertValidXml(svgContent);
        assertTrue(svgContent.contains("fill=\"#ffffff\""));
        assertTrue(svgContent.contains("Shape Switch"));
        assertTrue(svgContent.contains("SYNTHETIC"));

        // Verify drift.md exists and contains expected sections and headers
        Path mdPath = outputDir.resolve("drift.md");
        assertTrue(Files.isRegularFile(mdPath));
        String mdText = Files.readString(mdPath);
        assertTrue(mdText.contains("SYNTHETIC"));
        assertTrue(mdText.contains("Real Shape Switch Point"));
        assertTrue(mdText.contains("Drift Detected At"));
        assertTrue(mdText.contains("Dictionary Switch Points"));
        assertTrue(mdText.contains("Evaluation Window"));
        assertTrue(mdText.contains("Fixed-schedule zstd+dict"));
        assertTrue(mdText.contains("Adaptive zdict"));
        assertTrue(mdText.contains("Post-Switch 100 Ratio"));
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
