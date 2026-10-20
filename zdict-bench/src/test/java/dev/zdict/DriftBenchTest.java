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
    void testSyntheticGenerationAndComprehensiveDriftBenchmarks() throws Exception {
        Path dirA = tempDir.resolve("synthetic-A");
        Path dirB = tempDir.resolve("synthetic-B");

        // Generate synthetic samples
        DriftBench.generateSynthetic(dirA, dirB, 300, 300);

        // Verify SYNTHETIC labeling in sample payloads
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

        // Verify elapsed payloads to detection
        assertTrue(result.driftDetectedAt > 300);
        assertTrue(result.payloadsElapsedToDetection > 0);

        // Verify all 5 strategies exist
        assertNotNull(result.gzip);
        assertNotNull(result.staticDict);
        assertNotNull(result.fixedSchedule);
        assertNotNull(result.adaptive200);
        assertNotNull(result.adaptive100);

        // Verify Item 3: Adaptive buffer=100 vs buffer=200 comparison
        assertTrue(result.adaptive100.timeToRecovery > 0);
        assertTrue(result.adaptive200.timeToRecovery > 0);
        assertTrue(result.adaptive100.timeToRecovery < result.adaptive200.timeToRecovery,
                "Adaptive-100 must recover faster than Adaptive-200. 100=" +
                        result.adaptive100.timeToRecovery + ", 200=" + result.adaptive200.timeToRecovery);

        // Verify Item 4: Training time reported per strategy (ms)
        assertEquals(0, result.gzip.trainingTimeMs);
        assertTrue(result.staticDict.trainingTimeMs >= 0);
        assertTrue(result.fixedSchedule.trainingTimeMs >= 0);
        assertTrue(result.adaptive200.trainingTimeMs >= 0);
        assertTrue(result.adaptive100.trainingTimeMs >= 0);

        // Verify Item 1: Multi-switch-point matrix (550, 650, 730, 800 across 5 seeds)
        assertNotNull(result.matrixResult);
        assertEquals(5, result.matrixResult.aggregates.size(), "Should aggregate all 5 strategies");
        for (DriftBench.AggregateStrategyStats agg : result.matrixResult.aggregates) {
            assertTrue(agg.totalBytes.mean > 0);
            assertTrue(agg.totalBytes.min <= agg.totalBytes.max);
            assertTrue(agg.overallRatio.mean > 1.0);
            assertNotNull(agg.retrainCount);
            assertNotNull(agg.postSwitch100Ratio);
            assertNotNull(agg.trainingTimeMs);
        }

        // Verify Item 2: No-drift stream verification
        assertNotNull(result.noDriftResult);
        assertEquals(5, result.noDriftResult.strategies.size());
        for (DriftBench.StrategyResult s : result.noDriftResult.strategies) {
            if (s.name.contains("Fixed-schedule")) {
                assertTrue(s.retrainCount >= 4, "Fixed-schedule should retrain every 200 payloads on no-drift stream");
            } else if (s.name.contains("Adaptive")) {
                assertEquals(0, s.retrainCount, "Adaptive strategies should trigger 0 retrains on no-drift stream");
            } else if (s.name.contains("Static") || s.name.contains("gzip")) {
                assertEquals(0, s.retrainCount);
            }
        }

        // Verify SVG chart output
        Path svgPath = outputDir.resolve("drift.svg");
        assertTrue(Files.isRegularFile(svgPath));
        String svgContent = Files.readString(svgPath);
        assertValidXml(svgContent);
        assertTrue(svgContent.contains("fill=\"#ffffff\""));
        assertTrue(svgContent.contains("Shape Switch"));
        assertTrue(svgContent.contains("SYNTHETIC"));
        assertTrue(svgContent.contains("adaptive (buf=100)"));
        assertTrue(svgContent.contains("adaptive (buf=200)"));

        // Verify markdown output and Item 5 header statements
        Path mdPath = outputDir.resolve("drift.md");
        assertTrue(Files.isRegularFile(mdPath));
        String mdText = Files.readString(mdPath);
        assertTrue(mdText.contains("SYNTHETIC"));
        assertTrue(mdText.contains("total-bytes based"));
        assertTrue(mdText.contains("Sensitivity Matrix Across Switch Points & Seeds"));
        assertTrue(mdText.contains("No-Drift Stream Verification"));
        assertTrue(mdText.contains("Adaptive Variant Tradeoff: Buffer=100 vs Buffer=200"));
        assertTrue(mdText.contains("Training Time (ms)"));
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
