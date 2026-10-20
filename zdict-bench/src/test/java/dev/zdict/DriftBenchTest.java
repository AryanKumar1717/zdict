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

        // Generate synthetic samples for canonical 60/40 run
        DriftBench.generateSynthetic(dirA, dirB, 600, 400);

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
        assertEquals(1000, result.totalPayloads);
        assertEquals(600, result.countA);
        assertEquals(400, result.countB);
        assertEquals(600, result.shapeSwitchPoint);

        // Verify elapsed payloads to detection
        assertTrue(result.driftDetectedAt > 600);
        assertTrue(result.payloadsElapsedToDetection > 0);

        // Verify all 6 strategies exist (including Fixed-schedule 100/100)
        assertNotNull(result.gzip);
        assertNotNull(result.staticDict);
        assertNotNull(result.fixedSchedule);
        assertNotNull(result.fixedSchedule100);
        assertNotNull(result.adaptive200);
        assertNotNull(result.adaptive100);

        assertTrue(result.fixedSchedule100.retrainCount >= 9, "Fixed-100 should retrain every 100 payloads");

        // Verify Requirement 2: Recovery Horizon is NEVER 0 for retraining strategies
        assertTrue(result.fixedSchedule.timeToRecovery > 0, "Fixed-200 recovery horizon must be > 0 (never 0)");
        assertTrue(result.fixedSchedule100.timeToRecovery > 0, "Fixed-100 recovery horizon must be > 0 (never 0)");
        assertTrue(result.adaptive200.timeToRecovery > 0, "Adaptive-200 recovery horizon must be > 0 (never 0)");
        assertTrue(result.adaptive100.timeToRecovery > 0, "Adaptive-100 recovery horizon must be > 0 (never 0)");
        assertEquals(200, result.fixedSchedule.timeToRecovery, "Fixed-200 should deploy post-switch dict at #800 (200 elapsed)");
        assertEquals(100, result.fixedSchedule100.timeToRecovery, "Fixed-100 should deploy post-switch dict at #700 (100 elapsed)");
        assertEquals(-1, result.gzip.timeToRecovery);
        assertEquals(-1, result.staticDict.timeToRecovery);

        // Verify Adaptive buffer=100 vs buffer=200 comparison
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
        boolean hasRealVariance = false;
        for (DriftBench.SwitchPointStrategyStats stat : result.matrixResult.pointStrategyStats) {
            assertTrue(stat.totalBytes.mean > 0);
            assertTrue(stat.totalBytes.min <= stat.totalBytes.max);
            assertTrue(stat.overallRatio.mean > 1.0);
            assertNotNull(stat.retrainCount);
            assertNotNull(stat.postSwitch100Ratio);
            assertNotNull(stat.trainingTimeMs);
            if (stat.totalBytes.min < stat.totalBytes.max) {
                hasRealVariance = true;
            }
        }
        assertTrue(hasRealVariance, "Seeds must produce real variance across generated payloads");

        // Verify Requirement 3 (Gradual Drift): Gradual-drift stream evaluated
        assertNotNull(result.gradualResult, "Gradual drift result must exist");
        assertEquals(6, result.gradualResult.strategies.size());
        boolean adaptiveDetectedGradual = false;
        for (DriftBench.StrategyResult s : result.gradualResult.strategies) {
            if (s.name.contains("Adaptive")) {
                assertTrue(s.driftDetectedAt > result.gradualResult.transitionStart,
                        "Adaptive should detect gradual drift after transition start");
                assertTrue(s.driftDetectedAt <= result.gradualResult.transitionEnd,
                        "Adaptive should detect gradual drift before or at transition end");
                adaptiveDetectedGradual = true;
            }
        }
        assertTrue(adaptiveDetectedGradual, "Adaptive strategies must detect gradual drift");

        // Verify Missed-drift cases visible for 0-retrain runs
        assertFalse(result.matrixResult.zeroRetrainCases.isEmpty(),
                "Should capture missed-drift cases where adaptive strategies had 0 retrains");
        for (DriftBench.ZeroRetrainCase zc : result.matrixResult.zeroRetrainCases) {
            assertEquals(800, zc.switchPoint, "Switch point should be 800 for truncated runs");
            assertTrue(zc.strategyName.contains("Adaptive"));
            assertTrue(zc.payloadsLeftAfterDetection > 0, "Payloads left after detection must be recorded");
            assertTrue(zc.payloadsLeftAfterDetection < zc.bufferNeeded, "Must reflect insufficient payloads for buffer");
            assertNotNull(zc.explanation);
        }

        // Verify Clean No-Drift Stream Verification (numbers only)
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

        // Verify Noisy-stationary stream verification
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
        assertTrue(mdText.contains("Gradual-Drift Stream Verification"));
        assertTrue(mdText.contains("Noisy-Stationary Stream"));
        assertTrue(mdText.contains("Fixed-schedule (100/100)"));
        assertTrue(mdText.contains("Adaptive Variant Tradeoff: Buffer=100 vs Buffer=200"));
        assertTrue(mdText.contains("Training Time (ms)"));
        assertFalse(mdText.contains("Efficiency Verdict"), "Efficiency Verdict column must be removed");
        assertFalse(mdText.contains("Resilience Verdict"), "Resilience Verdict column must be removed");
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
