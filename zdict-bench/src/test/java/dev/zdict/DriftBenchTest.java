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

        // Verify all 6 strategies exist (including Fixed-schedule 100/100)
        assertNotNull(result.gzip);
        assertNotNull(result.staticDict);
        assertNotNull(result.fixedSchedule);
        assertNotNull(result.fixedSchedule100);
        assertNotNull(result.adaptive200);
        assertNotNull(result.adaptive100);

        assertTrue(result.fixedSchedule100.retrainCount >= 5, "Fixed-100 should retrain every 100 payloads");

        // Verify Adaptive buffer=100 vs buffer=200 comparison
        assertTrue(result.adaptive100.timeToRecovery > 0);
        assertTrue(result.adaptive200.timeToRecovery > 0);
        assertTrue(result.adaptive100.timeToRecovery < result.adaptive200.timeToRecovery,
                "Adaptive-100 must recover faster than Adaptive-200. 100=" +
                        result.adaptive100.timeToRecovery + ", 200=" + result.adaptive200.timeToRecovery);

        // Verify Training time reported per strategy (ms)
        assertEquals(0, result.gzip.trainingTimeMs);
        assertTrue(result.staticDict.trainingTimeMs >= 0);
        assertTrue(result.fixedSchedule.trainingTimeMs >= 0);
        assertTrue(result.fixedSchedule100.trainingTimeMs >= 0);
        assertTrue(result.adaptive200.trainingTimeMs >= 0);
        assertTrue(result.adaptive100.trainingTimeMs >= 0);

        // Verify Requirement 1: Sensitivity matrix has one row per (strategy, switch point) across 5 seeds
        assertNotNull(result.matrixResult);
        assertEquals(24, result.matrixResult.pointStrategyStats.size(),
                "Should have 24 rows (4 switch points x 6 strategies)");
        for (DriftBench.SwitchPointStrategyStats stat : result.matrixResult.pointStrategyStats) {
            assertTrue(stat.totalBytes.mean > 0);
            assertTrue(stat.totalBytes.min <= stat.totalBytes.max);
            assertTrue(stat.overallRatio.mean > 1.0);
            assertNotNull(stat.retrainCount);
            assertNotNull(stat.postSwitch100Ratio);
            assertNotNull(stat.trainingTimeMs);
        }

        // Verify Requirement 3: Missed-drift cases visible for 0-retrain runs
        assertFalse(result.matrixResult.zeroRetrainCases.isEmpty(),
                "Should capture missed-drift cases where adaptive strategies had 0 retrains");
        for (DriftBench.ZeroRetrainCase zc : result.matrixResult.zeroRetrainCases) {
            assertEquals(800, zc.switchPoint, "Switch point should be 800 for truncated runs");
            assertTrue(zc.strategyName.contains("Adaptive"));
            assertTrue(zc.payloadsLeftAfterDetection > 0, "Payloads left after detection must be recorded");
            assertTrue(zc.payloadsLeftAfterDetection < zc.bufferNeeded, "Must reflect insufficient payloads for buffer");
            assertNotNull(zc.explanation);
        }

        // Verify Clean No-Drift Stream Verification
        assertNotNull(result.noDriftResult);
        assertEquals(6, result.noDriftResult.strategies.size());
        for (DriftBench.StrategyResult s : result.noDriftResult.strategies) {
            if (s.name.contains("Fixed-schedule (200/200)")) {
                assertEquals(4, s.retrainCount, "Fixed-schedule 200 should retrain 4 times on 1000 payloads");
            } else if (s.name.contains("Fixed-schedule (100/100)")) {
                assertEquals(9, s.retrainCount, "Fixed-schedule 100 should retrain 9 times on 1000 payloads");
            } else if (s.name.contains("Adaptive")) {
                assertEquals(0, s.retrainCount, "Adaptive strategies should trigger 0 retrains on clean stream");
            } else if (s.name.contains("Static") || s.name.contains("gzip")) {
                assertEquals(0, s.retrainCount);
            }
        }

        // Verify Requirement 4: Noisy-stationary stream verification
        assertNotNull(result.noisyResult, "Noisy stationary result must exist");
        assertEquals(6, result.noisyResult.strategies.size(), "Should evaluate all 6 strategies on noisy stream");
        for (DriftBench.StrategyResult s : result.noisyResult.strategies) {
            if (s.name.contains("Adaptive")) {
                assertEquals(0, s.falseAlarmCount, "Adaptive strategies should maintain 0 false alarms on noisy stationary stream");
                assertEquals(0, s.retrainCount, "Adaptive strategies should maintain 0 retrains on noisy stationary stream");
            } else if (s.name.contains("Fixed-schedule (200/200)")) {
                assertEquals(4, s.retrainCount);
            } else if (s.name.contains("Fixed-schedule (100/100)")) {
                assertEquals(9, s.retrainCount);
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
        assertTrue(svgContent.contains("fixed (100/100)"));
        assertTrue(svgContent.contains("adaptive (buf=100)"));
        assertTrue(svgContent.contains("adaptive (buf=200)"));

        // Verify markdown output and Item 5 header statements
        Path mdPath = outputDir.resolve("drift.md");
        assertTrue(Files.isRegularFile(mdPath));
        String mdText = Files.readString(mdPath);
        assertTrue(mdText.contains("SYNTHETIC"));
        assertTrue(mdText.contains("total-bytes based"));
        assertTrue(mdText.contains("Sensitivity Matrix: One Row Per (Strategy, Switch Point) Across 5 Seeds"));
        assertTrue(mdText.contains("Missed-Drift & Horizon Truncation Analysis (Adaptive Runs with 0 Retrains)"));
        assertTrue(mdText.contains("Noisy-Stationary Stream"));
        assertTrue(mdText.contains("Fixed-schedule (100/100)"));
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
