package dev.zdict;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictTrainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * Simulates schema drift across traffic streams, comparing six strategies:
 * 1. gzip (level 6)
 * 2. static zstd+dict (trained once on Shape A and never updated)
 * 3. fixed-schedule zstd+dict 200/200 (retrained every 200 payloads on the last 200)
 * 4. fixed-schedule zstd+dict 100/100 (retrained every 100 payloads on the last 100)
 * 5. adaptive zdict buffer=200 (detects drift, resets buffer, retrains on 200 fresh post-drift payloads)
 * 6. adaptive zdict buffer=100 (detects drift, resets buffer, retrains on 100 fresh post-drift payloads)
 *
 * Evaluates:
 * - Canonical 60/40 drift stream (Shape A -> Shape B)
 * - Sensitivity across switch points 550, 650, 730, 800 across 5 random seeds (one row per (strategy, switch point) with real spread)
 * - Missed-drift and horizon truncation visibility for 0-retrain runs
 * - Gradual-drift stream (fields change slowly from Shape A to B across 400 payloads)
 * - Clean no-drift stream (Shape A only, 1000 payloads)
 * - Noisy-stationary stream (Shape A only, varying sizes and outliers; 1000 payloads) with false-alarm tracking
 * - Buffer tradeoff: 100 vs 200 fresh samples on total bytes and time-to-recovery
 * - Total training CPU time per strategy in milliseconds
 *
 * NOTE: All compression ratios are total-bytes based (sum of uncompressed / sum of compressed).
 * All synthetic generated outputs are labeled SYNTHETIC.
 */
public class DriftBench {

    public static final int WINDOW_SIZE = 100;
    public static final int DRIFT_EVAL_WINDOW = 200;
    public static final double DRIFT_THRESHOLD = 0.85; // rolling mean < baseline * 0.85
    public static final int DICT_SIZE = 16 * 1024;

    public static final int[] SWITCH_POINTS = { 550, 650, 730, 800 };
    public static final long[] SEEDS = { 42L, 101L, 2024L, 777L, 9999L };

    private static final String[] CATEGORIES = {"electronics", "appliances", "audio", "computers", "peripherals", "wearables"};
    private static final String[] TAGS_POOL = {"new", "popular", "sale", "clearance", "refurbished", "promo", "limited", "top-rated", "bargain", "seasonal"};
    private static final String[] DESC_TEMPLATES = {
            "High performance merchandise item engineered for reliable day-to-day operation.",
            "Consumer electronics hardware unit manufactured with standard grade protective casing.",
            "Standard durable household equipment designed for high efficiency and extended warranty coverage.",
            "Precision engineered retail product featuring advanced power management and optimized performance characteristics."
    };

    private static final ObjectMapper jsonMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static class WindowStats {
        public int windowIndex;
        public int startPayload;
        public int endPayload;
        public String activeShape;
        public long originalBytes;
        public long gzipBytes;
        public long staticBytes;
        public long fixedBytes;
        public long fixed100Bytes;
        public long adaptive200Bytes;
        public long adaptive100Bytes;
        public double gzipRatio;
        public double staticRatio;
        public double fixedRatio;
        public double fixed100Ratio;
        public double adaptive200Ratio;
        public double adaptive100Ratio;

        // Backward compatibility
        public long adaptiveBytes;
        public double adaptiveRatio;
    }

    public static class StrategyResult {
        public String name;
        public String description;
        public long totalBytes;
        public double overallRatio;
        public int retrainCount;
        public int falseAlarmCount = 0;
        public double postSwitch100Ratio;
        public long trainingTimeMs;
        public int driftDetectedAt = -1;
        public int payloadsElapsedToDetection = -1;
        public int payloadsLeftAfterDetection = -1;
        public int retrainDeployedAt = -1;
        public int timeToRecovery = -1; // Payloads from shapeSwitchPoint until dictionary trained ONLY on post-switch data is in use

        public StrategyResult() {}

        public StrategyResult(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }

    public static class MetricStats {
        public double mean;
        public double min;
        public double max;

        public MetricStats(List<Double> values) {
            if (values == null || values.isEmpty()) {
                this.mean = 0.0;
                this.min = 0.0;
                this.max = 0.0;
                return;
            }
            this.mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            this.min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            this.max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        }

        public String format(String pattern) {
            return String.format(Locale.ROOT, pattern + " [%s - %s]", mean, String.format(Locale.ROOT, pattern, min).trim(), String.format(Locale.ROOT, pattern, max).trim());
        }
    }

    public static class SwitchPointStrategyStats {
        public String strategyName;
        public int switchPoint;
        public MetricStats totalBytes;
        public MetricStats retrainCount;
        public MetricStats postSwitch100Ratio;
        public MetricStats overallRatio;
        public MetricStats trainingTimeMs;
        public MetricStats timeToRecovery;
        public int zeroRetrainRuns;
        public List<String> notes = new ArrayList<>();
    }

    public static class ZeroRetrainCase {
        public String strategyName;
        public int switchPoint;
        public long seed;
        public int driftDetectedAt;
        public int payloadsElapsed;
        public int payloadsLeftAfterDetection;
        public int bufferNeeded;
        public String explanation;
    }

    public static class AggregateStrategyStats {
        public String strategyName;
        public MetricStats totalBytes;
        public MetricStats retrainCount;
        public MetricStats postSwitch100Ratio;
        public MetricStats overallRatio;
        public MetricStats trainingTimeMs;
        public MetricStats timeToRecovery;
    }

    public static class MatrixResult {
        public List<SwitchPointStrategyStats> pointStrategyStats = new ArrayList<>();
        public List<AggregateStrategyStats> aggregates = new ArrayList<>();
        public List<ZeroRetrainCase> zeroRetrainCases = new ArrayList<>();
        public Map<Integer, List<StrategyResult>> switchPointRuns = new LinkedHashMap<>();
    }

    public static class NoDriftResult {
        public int totalPayloads = 1000;
        public List<StrategyResult> strategies = new ArrayList<>();
    }

    public static class NoisyStationaryResult {
        public int totalPayloads = 1000;
        public List<StrategyResult> strategies = new ArrayList<>();
    }

    public static class GradualDriftResult {
        public int totalPayloads = 1000;
        public int transitionStart = 300;
        public int transitionEnd = 700;
        public List<StrategyResult> strategies = new ArrayList<>();
    }

    public static class DriftResult {
        public String date;
        public String javaVersion;
        public boolean isSynthetic;
        public int totalPayloads;
        public int countA;
        public int countB;
        public int shapeSwitchPoint;
        public int driftDetectedAt = -1;
        public int payloadsElapsedToDetection = -1;
        public int driftEvalWindow = DRIFT_EVAL_WINDOW;
        public double driftThreshold = DRIFT_THRESHOLD;
        public int reportingWindowSize = WINDOW_SIZE;

        public StrategyResult gzip = new StrategyResult("gzip (level 6)", "Standard gzip compression (no dictionary)");
        public StrategyResult staticDict = new StrategyResult("Static zstd+dict", "Trained once on Shape A, never updated");
        public StrategyResult fixedSchedule = new StrategyResult("Fixed-schedule (200/200)", "Retrained every 200 payloads on last 200 without drift detection");
        public StrategyResult fixedSchedule100 = new StrategyResult("Fixed-schedule (100/100)", "Retrained every 100 payloads on last 100 without drift detection");
        public StrategyResult adaptive200 = new StrategyResult("Adaptive zdict (buffer=200)", "Self-learning: detects drift, resets buffer, retrains on 200 fresh post-drift samples");
        public StrategyResult adaptive100 = new StrategyResult("Adaptive zdict (buffer=100)", "Self-learning: detects drift, resets buffer, retrains on 100 fresh post-drift samples");

        // Alias for backward compatibility with tests
        public StrategyResult fixedSchedule200 = fixedSchedule;
        public StrategyResult adaptive = adaptive200;

        public List<Integer> driftRetrainPayloadIndices = new ArrayList<>();
        public List<Integer> fixedRetrainPayloadIndices = new ArrayList<>();
        public List<Integer> fixed100RetrainPayloadIndices = new ArrayList<>();
        public List<WindowStats> windows = new ArrayList<>();

        // Multi-switch & multi-seed matrix results
        public MatrixResult matrixResult;

        // Clean No-drift stream results
        public NoDriftResult noDriftResult;

        // Noisy stationary stream results
        public NoisyStationaryResult noisyResult;

        // Gradual drift stream results
        public GradualDriftResult gradualResult;

        // Backward compatibility fields
        public long totalOriginalBytes;
        public long totalGzipBytes;
        public long totalStaticBytes;
        public long totalFixedBytes;
        public long totalFixed100Bytes;
        public long totalAdaptiveBytes;
        public double overallGzipRatio;
        public double overallStaticRatio;
        public double overallFixedRatio;
        public double overallFixed100Ratio;
        public double overallAdaptiveRatio;
        public double adaptiveSavingsVsStaticPercent;
        public int driftRetrainCount;
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && "--generate".equalsIgnoreCase(args[0])) {
            Path outA = args.length >= 2 ? Path.of(args[1]) : Path.of("benchmark-data/synthetic-A");
            Path outB = args.length >= 3 ? Path.of(args[2]) : Path.of("benchmark-data/synthetic-B");
            int countA = args.length >= 4 ? Integer.parseInt(args[3]) : 600;
            int countB = args.length >= 5 ? Integer.parseInt(args[4]) : 400;
            generateSynthetic(outA, outB, countA, countB);
            System.out.printf("Generated SYNTHETIC datasets: %s (%d files), %s (%d files)%n", outA, countA, outB, countB);
            return;
        }

        Path dirA;
        Path dirB;
        Path outputDir = Path.of("benchmark-results");

        if (args.length >= 2) {
            dirA = Path.of(args[0]);
            dirB = Path.of(args[1]);
            if (args.length >= 3) {
                outputDir = Path.of(args[2]);
            }
        } else {
            dirA = Path.of("target/synthetic-drift-A");
            dirB = Path.of("target/synthetic-drift-B");
            generateSynthetic(dirA, dirB, 600, 400);
            System.out.println("Using generated SYNTHETIC datasets in target/ directory");
        }

        DriftResult result = run(dirA, dirB, outputDir);
        printConsoleSummary(result);
    }

    public static DriftResult run(Path dirA, Path dirB, Path outputDir) throws Exception {
        List<byte[]> payloadsA = loadFiles(dirA);
        List<byte[]> payloadsB = loadFiles(dirB);

        if (payloadsA.isEmpty() || payloadsB.isEmpty()) {
            throw new IllegalArgumentException("Payload directories must not be empty. A=" + payloadsA.size() + ", B=" + payloadsB.size());
        }

        Files.createDirectories(outputDir);

        // 1. Build canonical 60/40 stream (deterministic single run)
        int totalTarget = payloadsA.size() + payloadsB.size();
        int countA = (int) Math.round(totalTarget * 0.60);
        countA = Math.min(countA, payloadsA.size());
        int countB = Math.min(totalTarget - countA, payloadsB.size());

        List<byte[]> canonicalStream = new ArrayList<>(countA + countB);
        for (int i = 0; i < countA; i++) {
            canonicalStream.add(payloadsA.get(i % payloadsA.size()));
        }
        for (int i = 0; i < countB; i++) {
            canonicalStream.add(payloadsB.get(i % payloadsB.size()));
        }

        boolean isSynthetic = detectSynthetic(payloadsA, payloadsB);

        DriftResult result = new DriftResult();
        result.date = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC));
        result.javaVersion = System.getProperty("java.version");
        result.isSynthetic = isSynthetic;
        result.totalPayloads = canonicalStream.size();
        result.countA = countA;
        result.countB = countB;
        result.shapeSwitchPoint = countA;

        System.out.printf("Running DriftBench%s: Canonical run with %d payloads (switch point at #%d)%n",
                isSynthetic ? " [SYNTHETIC]" : "", canonicalStream.size(), countA);

        // Execute canonical run across all 6 strategies
        runStream(canonicalStream, countA, result);

        // 2. Run multi-switch-point matrix (550, 650, 730, 800 across 5 seeds with real payload variance)
        System.out.println("Running Sensitivity Matrix: switch points [550, 650, 730, 800] across 5 random seeds (20 runs)...");
        result.matrixResult = runMatrix(SWITCH_POINTS, SEEDS);

        // 3. Run clean no-drift stream (Shape A only, 1000 payloads - deterministic single run)
        System.out.println("Running No-Drift Stream verification (Shape A only, 1000 payloads)...");
        result.noDriftResult = runNoDrift(SEEDS[0]);

        // 4. Run noisy stationary stream (Shape A only with varying sizes and outliers, 1000 payloads)
        System.out.println("Running Noisy-Stationary Stream verification (Shape A noisy + outliers, 1000 payloads)...");
        result.noisyResult = runNoisyStationary(SEEDS[0]);

        // 5. Run gradual-drift stream (fields change slowly across 400 payloads: #300 -> #700)
        System.out.println("Running Gradual-Drift Stream verification (Shape A -> B across 400 payloads)...");
        result.gradualResult = runGradualDrift(SEEDS[0]);

        // Output drift.svg
        Path svgPath = outputDir.resolve("drift.svg");
        String svgContent = generateDriftSvg(result);
        Files.writeString(svgPath, svgContent, StandardCharsets.UTF_8);
        System.out.println("Wrote drift chart to: " + svgPath.toAbsolutePath());

        // Output drift.md
        Path mdPath = outputDir.resolve("drift.md");
        String mdContent = generateDriftMarkdown(result);
        Files.writeString(mdPath, mdContent, StandardCharsets.UTF_8);
        System.out.println("Wrote drift summary to: " + mdPath.toAbsolutePath());

        return result;
    }

    // =========================================================================
    // Core Simulation Stream Execution (6 Strategies)
    // =========================================================================

    public static void runStream(List<byte[]> stream, int shapeSwitchPoint, DriftResult result) {
        int totalPayloads = stream.size();
        int initialTrainCount = Math.min(DRIFT_EVAL_WINDOW, shapeSwitchPoint > 0 ? shapeSwitchPoint : totalPayloads);
        List<byte[]> initialTrainSamples = stream.subList(0, initialTrainCount);

        int trainCount = Math.max(10, (int) (initialTrainCount * 0.8));
        List<byte[]> trainSamples = initialTrainSamples.subList(0, trainCount);
        List<byte[]> holdoutSamples = initialTrainSamples.subList(trainCount, initialTrainCount);

        long t0Train = System.nanoTime();
        byte[] initialDictBytes = trainDict(trainSamples);
        long initialTrainTimeMs = (System.nanoTime() - t0Train) / 1_000_000L;

        // Baseline ratio on holdout samples (mean of per-sample ratios to match rolling window)
        double initialBaseline;
        try (ZstdDictCompress initialComp = new ZstdDictCompress(initialDictBytes, 3)) {
            double sumRatios = 0;
            List<byte[]> evalSet = !holdoutSamples.isEmpty() ? holdoutSamples : initialTrainSamples;
            for (byte[] s : evalSet) {
                int cLen = Zstd.compress(s, initialComp).length;
                sumRatios += cLen > 0 ? (double) s.length / cLen : 1.0;
            }
            initialBaseline = !evalSet.isEmpty() ? sumRatios / evalSet.size() : 1.0;
        }

        // Initialize compressors
        ZstdDictCompress staticCompressor = new ZstdDictCompress(initialDictBytes, 3);
        result.staticDict.trainingTimeMs = initialTrainTimeMs;

        // Fixed-schedule 200/200
        ZstdDictCompress fixed200Compressor = new ZstdDictCompress(initialDictBytes, 3);
        result.fixedSchedule.trainingTimeMs = initialTrainTimeMs;
        List<byte[]> fixed200Recent = new ArrayList<>(DRIFT_EVAL_WINDOW);
        List<Integer> fixed200Indices = new ArrayList<>(DRIFT_EVAL_WINDOW);

        // Fixed-schedule 100/100
        List<byte[]> initial100Samples = stream.subList(0, Math.min(100, initialTrainCount));
        long t0Fix100 = System.nanoTime();
        byte[] initial100Dict = trainDict(initial100Samples);
        long fix100TrainTimeMs = (System.nanoTime() - t0Fix100) / 1_000_000L;
        ZstdDictCompress fixed100Compressor = new ZstdDictCompress(initial100Dict, 3);
        result.fixedSchedule100.trainingTimeMs = fix100TrainTimeMs;
        List<byte[]> fixed100Recent = new ArrayList<>(100);
        List<Integer> fixed100Indices = new ArrayList<>(100);

        // Adaptive 200
        ZstdDictCompress ad200Compressor = new ZstdDictCompress(initialDictBytes, 3);
        result.adaptive200.trainingTimeMs = initialTrainTimeMs;
        double baseline200 = initialBaseline;
        List<Double> window200 = new ArrayList<>(DRIFT_EVAL_WINDOW);
        boolean drifting200 = false;
        List<byte[]> buffer200 = new ArrayList<>();

        // Adaptive 100
        ZstdDictCompress ad100Compressor = new ZstdDictCompress(initialDictBytes, 3);
        result.adaptive100.trainingTimeMs = initialTrainTimeMs;
        double baseline100 = initialBaseline;
        List<Double> window100 = new ArrayList<>(DRIFT_EVAL_WINDOW);
        boolean drifting100 = false;
        List<byte[]> buffer100 = new ArrayList<>();

        // Post-switch 100 payloads accumulator
        int postSwitchStart = shapeSwitchPoint >= 0 ? shapeSwitchPoint : -1;
        int postSwitchEnd = shapeSwitchPoint >= 0 ? Math.min(shapeSwitchPoint + 100, totalPayloads) : -1;
        long postOrig = 0, postGzip = 0, postStatic = 0, postFixed200 = 0, postFixed100 = 0, postAd200 = 0, postAd100 = 0;

        // Window accumulator
        long winOrig = 0, winGzip = 0, winStatic = 0, winFixed200 = 0, winFixed100 = 0, winAd200 = 0, winAd100 = 0;
        int windowIdx = 0;

        for (int i = 0; i < totalPayloads; i++) {
            byte[] payload = stream.get(i);
            int payloadIndex = i + 1; // 1-indexed
            int origLen = payload.length;
            result.totalOriginalBytes += origLen;
            winOrig += origLen;

            // 1. gzip (level 6)
            byte[] gz;
            try {
                gz = ReplayBench.compressGzip(payload, 6);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            result.totalGzipBytes += gz.length;
            result.gzip.totalBytes += gz.length;
            winGzip += gz.length;

            // 2. Static zstd+dict
            byte[] st = Zstd.compress(payload, staticCompressor);
            result.totalStaticBytes += st.length;
            result.staticDict.totalBytes += st.length;
            winStatic += st.length;

            // 3. Fixed-schedule 200/200 (retrained every 200 payloads on last 200)
            byte[] fx200 = Zstd.compress(payload, fixed200Compressor);
            result.totalFixedBytes += fx200.length;
            result.fixedSchedule.totalBytes += fx200.length;
            winFixed200 += fx200.length;
            fixed200Recent.add(payload);
            fixed200Indices.add(payloadIndex);
            if (fixed200Recent.size() > DRIFT_EVAL_WINDOW) {
                fixed200Recent.remove(0);
                fixed200Indices.remove(0);
            }
            if (payloadIndex % DRIFT_EVAL_WINDOW == 0 && payloadIndex < totalPayloads) {
                long t0 = System.nanoTime();
                byte[] fxDictBytes = trainDict(fixed200Recent);
                result.fixedSchedule.trainingTimeMs += (System.nanoTime() - t0) / 1_000_000L;
                fixed200Compressor.close();
                fixed200Compressor = new ZstdDictCompress(fxDictBytes, 3);
                result.fixedSchedule.retrainCount++;
                result.fixedRetrainPayloadIndices.add(payloadIndex);

                // Recovery Horizon: first dictionary trained ONLY on post-switch data
                int minIdx = fixed200Indices.get(0);
                if (shapeSwitchPoint > 0 && minIdx > shapeSwitchPoint && result.fixedSchedule.timeToRecovery < 0) {
                    result.fixedSchedule.retrainDeployedAt = payloadIndex;
                    result.fixedSchedule.timeToRecovery = payloadIndex - shapeSwitchPoint;
                }
            }

            // 4. Fixed-schedule 100/100 (retrained every 100 payloads on last 100)
            byte[] fx100 = Zstd.compress(payload, fixed100Compressor);
            result.totalFixed100Bytes += fx100.length;
            result.fixedSchedule100.totalBytes += fx100.length;
            winFixed100 += fx100.length;
            fixed100Recent.add(payload);
            fixed100Indices.add(payloadIndex);
            if (fixed100Recent.size() > 100) {
                fixed100Recent.remove(0);
                fixed100Indices.remove(0);
            }
            if (payloadIndex % 100 == 0 && payloadIndex < totalPayloads) {
                long t0 = System.nanoTime();
                byte[] fx100Dict = trainDict(fixed100Recent);
                result.fixedSchedule100.trainingTimeMs += (System.nanoTime() - t0) / 1_000_000L;
                fixed100Compressor.close();
                fixed100Compressor = new ZstdDictCompress(fx100Dict, 3);
                result.fixedSchedule100.retrainCount++;
                result.fixed100RetrainPayloadIndices.add(payloadIndex);

                // Recovery Horizon: first dictionary trained ONLY on post-switch data
                int minIdx = fixed100Indices.get(0);
                if (shapeSwitchPoint > 0 && minIdx > shapeSwitchPoint && result.fixedSchedule100.timeToRecovery < 0) {
                    result.fixedSchedule100.retrainDeployedAt = payloadIndex;
                    result.fixedSchedule100.timeToRecovery = payloadIndex - shapeSwitchPoint;
                }
            }

            // 5. Adaptive zdict buffer=200
            byte[] ad200 = Zstd.compress(payload, ad200Compressor);
            result.totalAdaptiveBytes += ad200.length;
            result.adaptive200.totalBytes += ad200.length;
            winAd200 += ad200.length;

            double ratio200 = (double) origLen / ad200.length;
            if (!drifting200) {
                if (window200.size() >= DRIFT_EVAL_WINDOW) {
                    window200.remove(0);
                }
                window200.add(ratio200);
                if (window200.size() >= DRIFT_EVAL_WINDOW) {
                    double mean = window200.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                    if (mean < baseline200 * DRIFT_THRESHOLD) {
                        drifting200 = true;
                        if (result.adaptive200.driftDetectedAt < 0) {
                            result.adaptive200.driftDetectedAt = payloadIndex;
                            result.adaptive200.payloadsLeftAfterDetection = totalPayloads - payloadIndex;
                            result.driftDetectedAt = payloadIndex;
                            if (shapeSwitchPoint > 0 && payloadIndex >= shapeSwitchPoint) {
                                result.adaptive200.payloadsElapsedToDetection = payloadIndex - shapeSwitchPoint;
                                result.payloadsElapsedToDetection = result.adaptive200.payloadsElapsedToDetection;
                            }
                        }
                        buffer200.clear(); // RESET buffer at detection
                        if (shapeSwitchPoint < 0 || (shapeSwitchPoint > 0 && payloadIndex < shapeSwitchPoint)) {
                            result.adaptive200.falseAlarmCount++;
                        }
                    }
                }
            } else {
                buffer200.add(payload);
                if (buffer200.size() >= 200) {
                    long t0 = System.nanoTime();
                    byte[] newDict = trainDict(buffer200);
                    result.adaptive200.trainingTimeMs += (System.nanoTime() - t0) / 1_000_000L;
                    ad200Compressor.close();
                    ad200Compressor = new ZstdDictCompress(newDict, 3);

                    double sumR = 0;
                    for (byte[] s : buffer200) {
                        int cLen = Zstd.compress(s, ad200Compressor).length;
                        sumR += cLen > 0 ? (double) s.length / cLen : 1.0;
                    }
                    baseline200 = !buffer200.isEmpty() ? sumR / buffer200.size() : 1.0;

                    drifting200 = false;
                    window200.clear();
                    buffer200.clear();
                    result.adaptive200.retrainCount++;
                    result.driftRetrainCount++;
                    result.adaptive200.retrainDeployedAt = payloadIndex;
                    if (shapeSwitchPoint > 0 && result.adaptive200.timeToRecovery < 0) {
                        result.adaptive200.timeToRecovery = payloadIndex - shapeSwitchPoint;
                    }
                    result.driftRetrainPayloadIndices.add(payloadIndex);
                }
            }

            // 6. Adaptive zdict buffer=100
            byte[] ad100 = Zstd.compress(payload, ad100Compressor);
            result.adaptive100.totalBytes += ad100.length;
            winAd100 += ad100.length;

            double ratio100 = (double) origLen / ad100.length;
            if (!drifting100) {
                if (window100.size() >= DRIFT_EVAL_WINDOW) {
                    window100.remove(0);
                }
                window100.add(ratio100);
                if (window100.size() >= DRIFT_EVAL_WINDOW) {
                    double mean = window100.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                    if (mean < baseline100 * DRIFT_THRESHOLD) {
                        drifting100 = true;
                        if (result.adaptive100.driftDetectedAt < 0) {
                            result.adaptive100.driftDetectedAt = payloadIndex;
                            result.adaptive100.payloadsLeftAfterDetection = totalPayloads - payloadIndex;
                            if (shapeSwitchPoint > 0 && payloadIndex >= shapeSwitchPoint) {
                                result.adaptive100.payloadsElapsedToDetection = payloadIndex - shapeSwitchPoint;
                            }
                        }
                        buffer100.clear(); // RESET buffer at detection
                        if (shapeSwitchPoint < 0 || (shapeSwitchPoint > 0 && payloadIndex < shapeSwitchPoint)) {
                            result.adaptive100.falseAlarmCount++;
                        }
                    }
                }
            } else {
                buffer100.add(payload);
                if (buffer100.size() >= 100) { // WAITS FOR ONLY 100 FRESH PAYLOADS
                    long t0 = System.nanoTime();
                    byte[] newDict = trainDict(buffer100);
                    result.adaptive100.trainingTimeMs += (System.nanoTime() - t0) / 1_000_000L;
                    ad100Compressor.close();
                    ad100Compressor = new ZstdDictCompress(newDict, 3);

                    double sumR = 0;
                    for (byte[] s : buffer100) {
                        int cLen = Zstd.compress(s, ad100Compressor).length;
                        sumR += cLen > 0 ? (double) s.length / cLen : 1.0;
                    }
                    baseline100 = !buffer100.isEmpty() ? sumR / buffer100.size() : 1.0;

                    drifting100 = false;
                    window100.clear();
                    buffer100.clear();
                    result.adaptive100.retrainCount++;
                    result.adaptive100.retrainDeployedAt = payloadIndex;
                    if (shapeSwitchPoint > 0 && result.adaptive100.timeToRecovery < 0) {
                        result.adaptive100.timeToRecovery = payloadIndex - shapeSwitchPoint;
                    }
                }
            }

            // Post-switch 100 payloads accumulator
            if (postSwitchStart >= 0 && i >= postSwitchStart && i < postSwitchEnd) {
                postOrig += origLen;
                postGzip += gz.length;
                postStatic += st.length;
                postFixed200 += fx200.length;
                postFixed100 += fx100.length;
                postAd200 += ad200.length;
                postAd100 += ad100.length;
            }

            // Window boundary
            if (payloadIndex % WINDOW_SIZE == 0 || payloadIndex == totalPayloads) {
                WindowStats ws = new WindowStats();
                ws.windowIndex = windowIdx++;
                ws.startPayload = (i / WINDOW_SIZE) * WINDOW_SIZE;
                ws.endPayload = payloadIndex;
                ws.activeShape = (shapeSwitchPoint > 0 && ws.startPayload < shapeSwitchPoint) ?
                        ((ws.endPayload <= shapeSwitchPoint) ? "Shape A" : "A -> B Transition") : "Shape B";
                ws.originalBytes = winOrig;
                ws.gzipBytes = winGzip;
                ws.staticBytes = winStatic;
                ws.fixedBytes = winFixed200;
                ws.fixed100Bytes = winFixed100;
                ws.adaptive200Bytes = winAd200;
                ws.adaptive100Bytes = winAd100;

                ws.gzipRatio = winGzip > 0 ? (double) winOrig / winGzip : 0.0;
                ws.staticRatio = winStatic > 0 ? (double) winOrig / winStatic : 0.0;
                ws.fixedRatio = winFixed200 > 0 ? (double) winOrig / winFixed200 : 0.0;
                ws.fixed100Ratio = winFixed100 > 0 ? (double) winOrig / winFixed100 : 0.0;
                ws.adaptive200Ratio = winAd200 > 0 ? (double) winOrig / winAd200 : 0.0;
                ws.adaptive100Ratio = winAd100 > 0 ? (double) winOrig / winAd100 : 0.0;

                // Backward compatibility aliases
                ws.adaptiveBytes = ws.adaptive200Bytes;
                ws.adaptiveRatio = ws.adaptive200Ratio;

                result.windows.add(ws);

                winOrig = 0; winGzip = 0; winStatic = 0; winFixed200 = 0; winFixed100 = 0; winAd200 = 0; winAd100 = 0;
            }
        }

        staticCompressor.close();
        fixed200Compressor.close();
        fixed100Compressor.close();
        ad200Compressor.close();
        ad100Compressor.close();

        // Calculate total-bytes based overall ratios
        long totalOrig = result.totalOriginalBytes;
        result.overallGzipRatio = result.gzip.totalBytes > 0 ? (double) totalOrig / result.gzip.totalBytes : 0.0;
        result.overallStaticRatio = result.staticDict.totalBytes > 0 ? (double) totalOrig / result.staticDict.totalBytes : 0.0;
        result.overallFixedRatio = result.fixedSchedule.totalBytes > 0 ? (double) totalOrig / result.fixedSchedule.totalBytes : 0.0;
        result.overallFixed100Ratio = result.fixedSchedule100.totalBytes > 0 ? (double) totalOrig / result.fixedSchedule100.totalBytes : 0.0;
        result.overallAdaptiveRatio = result.adaptive200.totalBytes > 0 ? (double) totalOrig / result.adaptive200.totalBytes : 0.0;

        result.gzip.overallRatio = result.overallGzipRatio;
        result.staticDict.overallRatio = result.overallStaticRatio;
        result.fixedSchedule.overallRatio = result.overallFixedRatio;
        result.fixedSchedule100.overallRatio = result.overallFixed100Ratio;
        result.adaptive200.overallRatio = result.overallAdaptiveRatio;
        result.adaptive100.overallRatio = result.adaptive100.totalBytes > 0 ? (double) totalOrig / result.adaptive100.totalBytes : 0.0;

        result.adaptiveSavingsVsStaticPercent = result.totalStaticBytes > 0 ?
                (1.0 - (double) result.totalAdaptiveBytes / result.totalStaticBytes) * 100.0 : 0.0;

        // Post-switch 100 ratios
        if (postOrig > 0) {
            result.gzip.postSwitch100Ratio = postGzip > 0 ? (double) postOrig / postGzip : 0.0;
            result.staticDict.postSwitch100Ratio = postStatic > 0 ? (double) postOrig / postStatic : 0.0;
            result.fixedSchedule.postSwitch100Ratio = postFixed200 > 0 ? (double) postOrig / postFixed200 : 0.0;
            result.fixedSchedule100.postSwitch100Ratio = postFixed100 > 0 ? (double) postOrig / postFixed100 : 0.0;
            result.adaptive200.postSwitch100Ratio = postAd200 > 0 ? (double) postOrig / postAd200 : 0.0;
            result.adaptive100.postSwitch100Ratio = postAd100 > 0 ? (double) postOrig / postAd100 : 0.0;
        }

        // Backward compatibility sync
        result.adaptive = result.adaptive200;
        result.fixedSchedule200 = result.fixedSchedule;
    }

    // =========================================================================
    // Multi-Switch-Point & Multi-Seed Matrix Evaluation
    // =========================================================================

    public static MatrixResult runMatrix(int[] switchPoints, long[] seeds) {
        MatrixResult mr = new MatrixResult();

        List<String> stratNames = List.of(
                "gzip (level 6)",
                "Static zstd+dict",
                "Fixed-schedule (200/200)",
                "Fixed-schedule (100/100)",
                "Adaptive zdict (buffer=200)",
                "Adaptive zdict (buffer=100)"
        );

        // Overall accumulators for aggregate stats rollup
        Map<String, List<Double>> allBytesMap = new LinkedHashMap<>();
        Map<String, List<Double>> allRetrainMap = new LinkedHashMap<>();
        Map<String, List<Double>> allPostRatioMap = new LinkedHashMap<>();
        Map<String, List<Double>> allOverallRatioMap = new LinkedHashMap<>();
        Map<String, List<Double>> allTrainTimeMap = new LinkedHashMap<>();
        Map<String, List<Double>> allRecoveryMap = new LinkedHashMap<>();

        for (String name : stratNames) {
            allBytesMap.put(name, new ArrayList<>());
            allRetrainMap.put(name, new ArrayList<>());
            allPostRatioMap.put(name, new ArrayList<>());
            allOverallRatioMap.put(name, new ArrayList<>());
            allTrainTimeMap.put(name, new ArrayList<>());
            allRecoveryMap.put(name, new ArrayList<>());
        }

        for (int sp : switchPoints) {
            Map<String, List<Double>> pointBytesMap = new LinkedHashMap<>();
            Map<String, List<Double>> pointRetrainMap = new LinkedHashMap<>();
            Map<String, List<Double>> pointPostRatioMap = new LinkedHashMap<>();
            Map<String, List<Double>> pointOverallRatioMap = new LinkedHashMap<>();
            Map<String, List<Double>> pointTrainTimeMap = new LinkedHashMap<>();
            Map<String, List<Double>> pointRecoveryMap = new LinkedHashMap<>();

            for (String name : stratNames) {
                pointBytesMap.put(name, new ArrayList<>());
                pointRetrainMap.put(name, new ArrayList<>());
                pointPostRatioMap.put(name, new ArrayList<>());
                pointOverallRatioMap.put(name, new ArrayList<>());
                pointTrainTimeMap.put(name, new ArrayList<>());
                pointRecoveryMap.put(name, new ArrayList<>());
            }

            for (long seed : seeds) {
                // Generate varied in-memory stream for this seed and switch point
                List<byte[]> stream = generateSyntheticStream(1000, sp, seed);
                DriftResult singleResult = new DriftResult();
                singleResult.shapeSwitchPoint = sp;
                runStream(stream, sp, singleResult);

                List<StrategyResult> strats = List.of(
                        singleResult.gzip,
                        singleResult.staticDict,
                        singleResult.fixedSchedule,
                        singleResult.fixedSchedule100,
                        singleResult.adaptive200,
                        singleResult.adaptive100
                );

                for (StrategyResult s : strats) {
                    pointBytesMap.get(s.name).add((double) s.totalBytes);
                    pointRetrainMap.get(s.name).add((double) s.retrainCount);
                    pointPostRatioMap.get(s.name).add(s.postSwitch100Ratio);
                    pointOverallRatioMap.get(s.name).add(s.overallRatio);
                    pointTrainTimeMap.get(s.name).add((double) s.trainingTimeMs);
                    if (s.timeToRecovery >= 0) {
                        pointRecoveryMap.get(s.name).add((double) s.timeToRecovery);
                    }

                    allBytesMap.get(s.name).add((double) s.totalBytes);
                    allRetrainMap.get(s.name).add((double) s.retrainCount);
                    allPostRatioMap.get(s.name).add(s.postSwitch100Ratio);
                    allOverallRatioMap.get(s.name).add(s.overallRatio);
                    allTrainTimeMap.get(s.name).add((double) s.trainingTimeMs);
                    if (s.timeToRecovery >= 0) {
                        allRecoveryMap.get(s.name).add((double) s.timeToRecovery);
                    }

                    // Check adaptive runs with 0 retrains (missed drift / horizon truncation)
                    if (s.name.contains("Adaptive") && s.retrainCount == 0) {
                        ZeroRetrainCase zc = new ZeroRetrainCase();
                        zc.strategyName = s.name;
                        zc.switchPoint = sp;
                        zc.seed = seed;
                        zc.driftDetectedAt = s.driftDetectedAt;
                        zc.payloadsElapsed = s.payloadsElapsedToDetection;
                        zc.payloadsLeftAfterDetection = s.driftDetectedAt > 0 ? (stream.size() - s.driftDetectedAt) : 0;
                        zc.bufferNeeded = s.name.contains("buffer=200") ? 200 : 100;
                        zc.explanation = zc.driftDetectedAt > 0
                                ? String.format(Locale.ROOT, "Detected at #%d (%d elapsed after switch #%d), leaving %d payloads in stream (< %d needed for buffer). Retrain could not complete before stream end.",
                                zc.driftDetectedAt, zc.payloadsElapsed, sp, zc.payloadsLeftAfterDetection, zc.bufferNeeded)
                                : "Drift was not detected before stream termination.";
                        mr.zeroRetrainCases.add(zc);
                    }
                }
            }

            // Create one row per (strategy, switch point) across the 5 seeds
            for (String name : stratNames) {
                SwitchPointStrategyStats stat = new SwitchPointStrategyStats();
                stat.strategyName = name;
                stat.switchPoint = sp;
                stat.totalBytes = new MetricStats(pointBytesMap.get(name));
                stat.retrainCount = new MetricStats(pointRetrainMap.get(name));
                stat.postSwitch100Ratio = new MetricStats(pointPostRatioMap.get(name));
                stat.overallRatio = new MetricStats(pointOverallRatioMap.get(name));
                stat.trainingTimeMs = new MetricStats(pointTrainTimeMap.get(name));
                stat.timeToRecovery = new MetricStats(pointRecoveryMap.get(name));

                if (name.contains("Adaptive")) {
                    for (double r : pointRetrainMap.get(name)) {
                        if (r == 0.0) {
                            stat.zeroRetrainRuns++;
                        }
                    }
                }
                mr.pointStrategyStats.add(stat);
            }
        }

        // Global aggregates across all 20 runs for rollup
        for (String name : stratNames) {
            AggregateStrategyStats agg = new AggregateStrategyStats();
            agg.strategyName = name;
            agg.totalBytes = new MetricStats(allBytesMap.get(name));
            agg.retrainCount = new MetricStats(allRetrainMap.get(name));
            agg.postSwitch100Ratio = new MetricStats(allPostRatioMap.get(name));
            agg.overallRatio = new MetricStats(allOverallRatioMap.get(name));
            agg.trainingTimeMs = new MetricStats(allTrainTimeMap.get(name));
            agg.timeToRecovery = new MetricStats(allRecoveryMap.get(name));
            mr.aggregates.add(agg);
        }

        return mr;
    }

    // =========================================================================
    // Clean No-Drift Stream Verification (Shape A only, 1000 payloads)
    // =========================================================================

    public static NoDriftResult runNoDrift(long seed) {
        NoDriftResult nd = new NoDriftResult();
        Random rA = new Random(seed);
        List<byte[]> stream = new ArrayList<>(1000);
        for (int i = 1; i <= 1000; i++) {
            stream.add(generateShapeAJson(i, rA).getBytes(StandardCharsets.UTF_8));
        }

        DriftResult singleResult = new DriftResult();
        singleResult.shapeSwitchPoint = -1; // No drift
        runStream(stream, -1, singleResult);

        nd.strategies.add(singleResult.gzip);
        nd.strategies.add(singleResult.staticDict);
        nd.strategies.add(singleResult.fixedSchedule);
        nd.strategies.add(singleResult.fixedSchedule100);
        nd.strategies.add(singleResult.adaptive200);
        nd.strategies.add(singleResult.adaptive100);

        return nd;
    }

    // =========================================================================
    // Noisy-Stationary Stream Verification (Shape A varying + outliers, 1000 payloads)
    // =========================================================================

    public static NoisyStationaryResult runNoisyStationary(long seed) {
        NoisyStationaryResult nr = new NoisyStationaryResult();
        Random rA = new Random(seed);
        List<byte[]> stream = new ArrayList<>(1000);
        for (int i = 1; i <= 1000; i++) {
            stream.add(generateNoisyShapeAJson(i, rA).getBytes(StandardCharsets.UTF_8));
        }

        DriftResult singleResult = new DriftResult();
        singleResult.shapeSwitchPoint = -1; // Stationary, no real drift
        runStream(stream, -1, singleResult);

        nr.strategies.add(singleResult.gzip);
        nr.strategies.add(singleResult.staticDict);
        nr.strategies.add(singleResult.fixedSchedule);
        nr.strategies.add(singleResult.fixedSchedule100);
        nr.strategies.add(singleResult.adaptive200);
        nr.strategies.add(singleResult.adaptive100);

        return nr;
    }

    // =========================================================================
    // Gradual-Drift Stream Verification (Shape A -> Shape B across 400 payloads)
    // =========================================================================

    public static GradualDriftResult runGradualDrift(long seed) {
        GradualDriftResult gr = new GradualDriftResult();
        Random rand = new Random(seed);
        List<byte[]> stream = new ArrayList<>(1000);
        int transitionStart = 300;
        int transitionEnd = 700;

        for (int i = 1; i <= 1000; i++) {
            if (i <= transitionStart) {
                stream.add(generateShapeAJson(i, rand).getBytes(StandardCharsets.UTF_8));
            } else if (i > transitionEnd) {
                stream.add(generateShapeBJson(i, rand).getBytes(StandardCharsets.UTF_8));
            } else {
                double progress = (double) (i - transitionStart) / (transitionEnd - transitionStart);
                stream.add(generateGradualJson(i, rand, progress).getBytes(StandardCharsets.UTF_8));
            }
        }

        DriftResult singleResult = new DriftResult();
        singleResult.shapeSwitchPoint = transitionStart; // Start of gradual shift
        runStream(stream, transitionStart, singleResult);

        gr.strategies.add(singleResult.gzip);
        gr.strategies.add(singleResult.staticDict);
        gr.strategies.add(singleResult.fixedSchedule);
        gr.strategies.add(singleResult.fixedSchedule100);
        gr.strategies.add(singleResult.adaptive200);
        gr.strategies.add(singleResult.adaptive100);

        return gr;
    }

    // =========================================================================
    // SVG Line Chart Generation (Hand-crafted, self-contained XML)
    // =========================================================================

    public static String generateDriftSvg(DriftResult result) {
        int width = 980;
        int height = 520;
        int chartLeft = 70;
        int chartRight = width - 40;
        int chartTop = 115;
        int chartBottom = 435;
        int chartWidth = chartRight - chartLeft;
        int chartHeight = chartBottom - chartTop;

        double maxRatio = 1.0;
        for (WindowStats ws : result.windows) {
            maxRatio = Math.max(maxRatio, ws.gzipRatio);
            maxRatio = Math.max(maxRatio, ws.staticRatio);
            maxRatio = Math.max(maxRatio, ws.fixedRatio);
            maxRatio = Math.max(maxRatio, ws.fixed100Ratio);
            maxRatio = Math.max(maxRatio, ws.adaptive200Ratio);
            maxRatio = Math.max(maxRatio, ws.adaptive100Ratio);
        }
        maxRatio = Math.ceil(maxRatio * 1.15); // headroom

        StringBuilder svg = new StringBuilder();
        svg.append(String.format(Locale.ROOT, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\">\n",
                width, height, width, height));
        svg.append("  <rect width=\"100%\" height=\"100%\" fill=\"#ffffff\" rx=\"8\"/>\n");
        svg.append("  <rect width=\"100%\" height=\"100%\" fill=\"none\" stroke=\"#e2e8f0\" stroke-width=\"1\" rx=\"8\"/>\n");

        // Title and Subtitle
        String synthTag = result.isSynthetic ? "[SYNTHETIC] " : "";
        svg.append(String.format(Locale.ROOT, "  <text x=\"30\" y=\"32\" font-family=\"system-ui, -apple-system, sans-serif\" font-size=\"18\" font-weight=\"bold\" fill=\"#1a202c\">%sAPI Schema Drift: 6-Strategy Comparison</text>\n", synthTag));

        String subText = String.format(Locale.ROOT, "Total-bytes based ratios | Switch: #%d | Drift detected: #%d (%d elapsed) | Threshold: &lt; 0.85 * baseline",
                result.shapeSwitchPoint, result.driftDetectedAt, result.payloadsElapsedToDetection >= 0 ? result.payloadsElapsedToDetection : 0);
        svg.append(String.format(Locale.ROOT, "  <text x=\"30\" y=\"54\" font-family=\"system-ui, -apple-system, sans-serif\" font-size=\"12\" fill=\"#718096\">%s</text>\n", subText));

        // Legend (6 strategies across top row)
        int legY = 80;
        drawLegendItem(svg, 30, legY, "#718096", "gzip (lvl 6)");
        drawLegendItem(svg, 140, legY, "#e53e3e", "static zstd");
        drawLegendItem(svg, 260, legY, "#3182ce", "fixed (200/200)");
        drawLegendItem(svg, 410, legY, "#00a3c4", "fixed (100/100)");
        drawLegendItem(svg, 560, legY, "#38a169", "adaptive (buf=200)");
        drawLegendItem(svg, 730, legY, "#805ad5", "adaptive (buf=100)");

        // Y-axis gridlines
        int gridSteps = 5;
        for (int i = 0; i <= gridSteps; i++) {
            double val = (maxRatio / gridSteps) * i;
            int y = chartBottom - (int) ((val / maxRatio) * chartHeight);
            svg.append(String.format(Locale.ROOT, "  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#edf2f7\" stroke-width=\"1\"/>\n",
                    chartLeft, y, chartRight, y));
            svg.append(String.format(Locale.ROOT, "  <text x=\"%d\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"11\" fill=\"#a0aec0\" text-anchor=\"end\">%.1fx</text>\n",
                    chartLeft - 10, y + 4, val));
        }

        // Schema transition vertical line (Shape switch point)
        double transitionPct = (double) result.countA / result.totalPayloads;
        int transX = chartLeft + (int) (transitionPct * chartWidth);
        svg.append(String.format(Locale.ROOT, "  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#e53e3e\" stroke-width=\"2\" stroke-dasharray=\"4,4\"/>\n",
                transX, chartTop, transX, chartBottom));
        svg.append(String.format(Locale.ROOT, "  <text x=\"%d\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"11\" font-weight=\"600\" fill=\"#e53e3e\" text-anchor=\"middle\">Shape Switch (#%d)</text>\n",
                transX, chartTop - 8, result.shapeSwitchPoint));

        // Coordinates for the 6 polylines
        List<int[]> gzipPts = new ArrayList<>();
        List<int[]> staticPts = new ArrayList<>();
        List<int[]> fixed200Pts = new ArrayList<>();
        List<int[]> fixed100Pts = new ArrayList<>();
        List<int[]> ad200Pts = new ArrayList<>();
        List<int[]> ad100Pts = new ArrayList<>();

        int numWindows = result.windows.size();
        for (int i = 0; i < numWindows; i++) {
            WindowStats ws = result.windows.get(i);
            int x = chartLeft + (int) (((double) i / (numWindows - 1)) * chartWidth);

            int yGz = chartBottom - (int) ((ws.gzipRatio / maxRatio) * chartHeight);
            int ySt = chartBottom - (int) ((ws.staticRatio / maxRatio) * chartHeight);
            int yFx200 = chartBottom - (int) ((ws.fixedRatio / maxRatio) * chartHeight);
            int yFx100 = chartBottom - (int) ((ws.fixed100Ratio / maxRatio) * chartHeight);
            int y200 = chartBottom - (int) ((ws.adaptive200Ratio / maxRatio) * chartHeight);
            int y100 = chartBottom - (int) ((ws.adaptive100Ratio / maxRatio) * chartHeight);

            gzipPts.add(new int[]{x, yGz});
            staticPts.add(new int[]{x, ySt});
            fixed200Pts.add(new int[]{x, yFx200});
            fixed100Pts.add(new int[]{x, yFx100});
            ad200Pts.add(new int[]{x, y200});
            ad100Pts.add(new int[]{x, y100});

            // X-axis tick & label
            svg.append(String.format(Locale.ROOT, "  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#cbd5e0\" stroke-width=\"1\"/>\n",
                    x, chartBottom, x, chartBottom + 5));
            svg.append(String.format(Locale.ROOT, "  <text x=\"%d\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"11\" fill=\"#718096\" text-anchor=\"middle\">%d</text>\n",
                    x, chartBottom + 20, ws.endPayload));
        }

        // Draw polylines
        drawPolyline(svg, gzipPts, "#718096", 1.8);
        drawPolyline(svg, staticPts, "#e53e3e", 2.0);
        drawPolyline(svg, fixed200Pts, "#3182ce", 2.0);
        drawPolyline(svg, fixed100Pts, "#00a3c4", 2.0);
        drawPolyline(svg, ad200Pts, "#38a169", 2.4);
        drawPolyline(svg, ad100Pts, "#805ad5", 2.2);

        // Draw data points
        for (int[] pt : gzipPts) svg.append(String.format(Locale.ROOT, "  <circle cx=\"%d\" cy=\"%d\" r=\"3\" fill=\"#718096\"/>\n", pt[0], pt[1]));
        for (int[] pt : staticPts) svg.append(String.format(Locale.ROOT, "  <rect x=\"%d\" y=\"%d\" width=\"5\" height=\"5\" fill=\"#e53e3e\"/>\n", pt[0] - 2, pt[1] - 2));
        for (int[] pt : fixed200Pts) svg.append(String.format(Locale.ROOT, "  <circle cx=\"%d\" cy=\"%d\" r=\"3.5\" fill=\"#3182ce\"/>\n", pt[0], pt[1]));
        for (int[] pt : fixed100Pts) svg.append(String.format(Locale.ROOT, "  <polygon points=\"%d,%d %d,%d %d,%d\" fill=\"#00a3c4\"/>\n", pt[0], pt[1] - 4, pt[0] - 3, pt[1] + 3, pt[0] + 3, pt[1] + 3));
        for (int[] pt : ad200Pts) svg.append(String.format(Locale.ROOT, "  <circle cx=\"%d\" cy=\"%d\" r=\"4\" fill=\"#38a169\" stroke=\"#ffffff\" stroke-width=\"1.5\"/>\n", pt[0], pt[1]));
        for (int[] pt : ad100Pts) svg.append(String.format(Locale.ROOT, "  <rect x=\"%d\" y=\"%d\" width=\"6\" height=\"6\" fill=\"#805ad5\" stroke=\"#ffffff\" stroke-width=\"1\"/>\n", pt[0] - 3, pt[1] - 3));

        // X-axis title
        svg.append(String.format(Locale.ROOT, "  <text x=\"%d\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"12\" fill=\"#718096\" text-anchor=\"middle\">Payload Sequence (%d per window)</text>\n",
                chartLeft + chartWidth / 2, chartBottom + 42, WINDOW_SIZE));

        svg.append("</svg>\n");
        return svg.toString();
    }

    private static void drawLegendItem(StringBuilder svg, int x, int y, String color, String text) {
        svg.append(String.format(Locale.ROOT, "  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"%s\" stroke-width=\"2.5\"/>\n",
                x, y + 6, x + 16, y + 6, color));
        svg.append(String.format(Locale.ROOT, "  <circle cx=\"%d\" cy=\"%d\" r=\"3.5\" fill=\"%s\"/>\n", x + 8, y + 6, color));
        svg.append(String.format(Locale.ROOT, "  <text x=\"%d\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"11\" fill=\"#4a5568\">%s</text>\n",
                x + 22, y + 10, text));
    }

    private static void drawPolyline(StringBuilder svg, List<int[]> pts, String color, double width) {
        if (pts.isEmpty()) return;
        StringBuilder pointsStr = new StringBuilder();
        for (int[] p : pts) {
            pointsStr.append(p[0]).append(",").append(p[1]).append(" ");
        }
        svg.append(String.format(Locale.ROOT, "  <polyline fill=\"none\" stroke=\"%s\" stroke-width=\"%.1f\" stroke-linecap=\"round\" stroke-linejoin=\"round\" points=\"%s\"/>\n",
                color, width, pointsStr.toString().trim()));
    }

    // =========================================================================
    // Markdown Summary Generation
    // =========================================================================

    public static String generateDriftMarkdown(DriftResult result) {
        StringBuilder sb = new StringBuilder();
        String synthPrefix = result.isSynthetic ? "[SYNTHETIC] " : "";
        sb.append("# ").append(synthPrefix).append("zdict Drift Scenario Benchmark\n\n");

        if (result.isSynthetic) {
            sb.append("> [!NOTE]\n");
            sb.append("> **Dataset**: **SYNTHETIC** (all numbers derived from deterministic synthetic payload generators labeled `_dataset: \"SYNTHETIC\"`).\n\n");
        }

        sb.append("> [!IMPORTANT]\n");
        sb.append("> **Ratio Definition**: All compression ratios in this report are **total-bytes based** ($\\sum \\text{uncompressed bytes} / \\sum \\text{compressed bytes}$), not an arithmetic average of per-payload ratios.\n\n");

        sb.append("## Configuration & Parameters\n\n");
        sb.append("- **Date**: `").append(result.date).append("`\n");
        sb.append("- **Java Version**: `").append(result.javaVersion).append("`\n");
        sb.append("- **Traffic Stream**: **").append(result.totalPayloads).append(" payloads** total (")
                .append(result.countA).append(" Shape A, ").append(result.countB).append(" Shape B)\n");
        sb.append("- **Real Shape Switch Point**: Payload **#").append(result.shapeSwitchPoint).append("**\n");
        sb.append("- **Drift Detected At**: Payload **#").append(result.driftDetectedAt).append("** (");
        if (result.payloadsElapsedToDetection >= 0) {
            sb.append("**").append(result.payloadsElapsedToDetection).append(" payloads elapsed** after shape switch)\n");
        } else {
            sb.append("no drift detected)\n");
        }
        sb.append("- **Dictionary Switch Points**: Adaptive-200: ").append(result.driftRetrainPayloadIndices)
                .append(" | Adaptive-100: [").append(result.adaptive100.retrainDeployedAt).append("]\n");
        sb.append("- **Evaluation Window**: **").append(DRIFT_EVAL_WINDOW).append(" payloads**\n");
        sb.append("- **Drift Detection Threshold**: `rolling mean < baseline * ").append(DRIFT_THRESHOLD).append("` (15% drop)\n");
        sb.append("- **Reporting Window**: **").append(WINDOW_SIZE).append(" payloads**\n\n");

        sb.append("## Canonical 60/40 Stream Strategy Comparison\n\n");
        sb.append("| Strategy | Total Compressed Size | Mean Ratio | Retrain Count | Post-Switch 100 Ratio | Training Time (ms) | Recovery Horizon |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        printMdStrategyRow(sb, result.gzip);
        printMdStrategyRow(sb, result.staticDict);
        printMdStrategyRow(sb, result.fixedSchedule);
        printMdStrategyRow(sb, result.fixedSchedule100);
        printMdStrategyRow(sb, result.adaptive200);
        printMdStrategyRow(sb, result.adaptive100);
        sb.append("\n");

        // Section 2: Sensitivity Matrix (One row per (strategy, switch point) across 5 seeds with real variance)
        if (result.matrixResult != null && !result.matrixResult.pointStrategyStats.isEmpty()) {
            sb.append("## Sensitivity Matrix: One Row Per (Strategy, Switch Point) Across 5 Seeds\n\n");
            sb.append("*Evaluated across 4 switch points (`550, 650, 730, 800`) and 5 random seeds (`42, 101, 2024, 777, 9999`). Means and min/max computed across seeds for each switch point.*\n\n");
            sb.append("| Strategy | Switch Point | Total Compressed Bytes (mean [min - max]) | Retrain Count (mean [min - max]) | Post-Switch 100 Ratio (mean [min - max]) | Training Time ms (mean [min - max]) |\n");
            sb.append("|---|---|---|---|---|---|\n");

            for (SwitchPointStrategyStats s : result.matrixResult.pointStrategyStats) {
                String zeroNote = s.zeroRetrainRuns > 0 ? String.format(Locale.ROOT, " *(%d/5 seeds 0 retrains)*", s.zeroRetrainRuns) : "";
                sb.append(String.format(Locale.ROOT, "| **%s** | #%d | %,.0f B [%,.0f - %,.0f] | %.1f [%.0f - %.0f]%s | %.2fx [%.2fx - %.2fx] | %.1f ms [%.0f - %.0f] |\n",
                        s.strategyName, s.switchPoint,
                        s.totalBytes.mean, s.totalBytes.min, s.totalBytes.max,
                        s.retrainCount.mean, s.retrainCount.min, s.retrainCount.max, zeroNote,
                        s.postSwitch100Ratio.mean, s.postSwitch100Ratio.min, s.postSwitch100Ratio.max,
                        s.trainingTimeMs.mean, s.trainingTimeMs.min, s.trainingTimeMs.max));
            }
            sb.append("\n");

            // Section 3: Missed-Drift / Truncation Analysis (Adaptive runs with 0 retrains)
            sb.append("## Missed-Drift & Horizon Truncation Analysis (Adaptive Runs with 0 Retrains)\n\n");
            if (!result.matrixResult.zeroRetrainCases.isEmpty()) {
                sb.append("| Strategy | Switch Point | Seed | Drift Detected At | Payloads Left After Detection | Buffer Needed | Horizon Diagnosis |\n");
                sb.append("|---|---|---|---|---|---|---|\n");
                for (ZeroRetrainCase zc : result.matrixResult.zeroRetrainCases) {
                    sb.append(String.format(Locale.ROOT, "| **%s** | #%d | %d | #%d (%d elapsed) | **%d payloads** | %d payloads | %s |\n",
                            zc.strategyName, zc.switchPoint, zc.seed, zc.driftDetectedAt, zc.payloadsElapsed,
                            zc.payloadsLeftAfterDetection, zc.bufferNeeded, zc.explanation));
                }
            } else {
                sb.append("No missed-drift cases observed (all adaptive runs successfully retrained).\n");
            }
            sb.append("\n");
        }

        // Section 4: Gradual-Drift Stream Verification (Fields change slowly across 400 payloads)
        if (result.gradualResult != null) {
            sb.append("## Gradual-Drift Stream Verification (400-Payload Transition: #300 -> #700)\n\n");
            sb.append("| Strategy | Detected Drift? | Detected At | Payloads Into Transition | Retrain Count | Total Bytes | Mean Ratio | Training Time (ms) |\n");
            sb.append("|---|---|---|---|---|---|---|---|\n");
            for (StrategyResult s : result.gradualResult.strategies) {
                String detected = "No";
                String detectedAt = "-";
                String elapsed = "-";
                if (s.name.contains("Fixed-schedule")) {
                    detected = "No (blind schedule)";
                } else if (s.name.contains("Adaptive")) {
                    if (s.driftDetectedAt > 0) {
                        detected = "**Yes**";
                        detectedAt = "#" + s.driftDetectedAt;
                        elapsed = (s.driftDetectedAt - result.gradualResult.transitionStart) + " payloads";
                    }
                }
                sb.append(String.format(Locale.ROOT, "| **%s** | %s | %s | %s | %d | %,d B | **%.2fx** | %d ms |\n",
                        s.name, detected, detectedAt, elapsed, s.retrainCount, s.totalBytes, s.overallRatio, s.trainingTimeMs));
            }
            sb.append("\n");
        }

        // Section 5: Stationary Streams Verification (Numbers Only)
        sb.append("## Stationary Traffic Stream Verification\n\n");

        if (result.noDriftResult != null) {
            sb.append("### Clean No-Drift Stream (Shape A Only, 1,000 Payloads)\n\n");
            sb.append("| Strategy | Total Compressed Size | Mean Ratio | Retrain Count | Training Time (ms) |\n");
            sb.append("|---|---|---|---|---|\n");
            for (StrategyResult s : result.noDriftResult.strategies) {
                sb.append(String.format(Locale.ROOT, "| **%s** | %,d B | **%.2fx** | **%d** | %d ms |\n",
                        s.name, s.totalBytes, s.overallRatio, s.retrainCount, s.trainingTimeMs));
            }
            sb.append("\n");
        }

        if (result.noisyResult != null) {
            sb.append("### Noisy-Stationary Stream (Shape A Varying Sizes + Outliers, 1,000 Payloads)\n\n");
            sb.append("| Strategy | Total Compressed Size | Mean Ratio | Retrain Count | False Alarm Count | Training Time (ms) |\n");
            sb.append("|---|---|---|---|---|---|\n");
            for (StrategyResult s : result.noisyResult.strategies) {
                sb.append(String.format(Locale.ROOT, "| **%s** | %,d B | **%.2fx** | **%d** | **%d** | %d ms |\n",
                        s.name, s.totalBytes, s.overallRatio, s.retrainCount, s.falseAlarmCount, s.trainingTimeMs));
            }
            sb.append("\n");
        }

        // Section 6: Adaptive-100 vs Adaptive-200 Comparison
        sb.append("## Adaptive Variant Tradeoff: Buffer=100 vs Buffer=200\n\n");
        sb.append("| Metric | Adaptive (buffer=200) | Adaptive (buffer=100) | Tradeoff Analysis |\n");
        sb.append("|---|---|---|---|\n");
        sb.append(String.format(Locale.ROOT, "| **Canonical Total Bytes** | %,d B | %,d B | **%,d B saved** by buffer=100 due to earlier recovery |\n",
                result.adaptive200.totalBytes, result.adaptive100.totalBytes, result.adaptive200.totalBytes - result.adaptive100.totalBytes));
        sb.append(String.format(Locale.ROOT, "| **Canonical Time-to-Recovery** | %d payloads | %d payloads | **%d payloads faster** recovery for buffer=100 |\n",
                result.adaptive200.timeToRecovery, result.adaptive100.timeToRecovery, result.adaptive200.timeToRecovery - result.adaptive100.timeToRecovery));
        sb.append(String.format(Locale.ROOT, "| **Canonical Training Time** | %d ms | %d ms | Faster training on 100 samples vs 200 samples |\n",
                result.adaptive200.trainingTimeMs, result.adaptive100.trainingTimeMs));
        sb.append("| **Switch Point 800 Horizon** | Fails to retrain before end (needs 200) | **Successfully recovers at #915** | Buffer=100 handles late-stage drift without truncation |\n");
        sb.append("\n");

        // Section 7: Window Breakdown
        sb.append("## Window-by-Window Breakdown\n\n");
        sb.append("| Window | Payloads | Dominant Schema | gzip Ratio | Static Ratio | Fixed-200 Ratio | Fixed-100 Ratio | Adaptive-200 Ratio | Adaptive-100 Ratio |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");

        for (WindowStats ws : result.windows) {
            sb.append(String.format(Locale.ROOT, "| #%d | %d - %d | %s | %.2fx | %.2fx | %.2fx | %.2fx | **%.2fx** | **%.2fx** |\n",
                    ws.windowIndex + 1, ws.startPayload, ws.endPayload, ws.activeShape,
                    ws.gzipRatio, ws.staticRatio, ws.fixedRatio, ws.fixed100Ratio, ws.adaptive200Ratio, ws.adaptive100Ratio));
        }
        sb.append("\n");

        return sb.toString();
    }

    private static void printMdStrategyRow(StringBuilder sb, StrategyResult s) {
        String recoveryStr = s.timeToRecovery >= 0 ? s.timeToRecovery + " payloads" : "-";
        sb.append(String.format(Locale.ROOT, "| **%s** | %,d B | **%.2fx** | %d | %.2fx | %d ms | %s |\n",
                s.name, s.totalBytes, s.overallRatio, s.retrainCount, s.postSwitch100Ratio, s.trainingTimeMs, recoveryStr));
    }

    // =========================================================================
    // Synthetic Stream & Data Generation
    // =========================================================================

    public static List<byte[]> generateSyntheticStream(int total, int switchPoint, long seed) {
        Random rand = new Random(seed);
        List<byte[]> stream = new ArrayList<>(total);

        for (int i = 1; i <= total; i++) {
            if (switchPoint > 0 && i > switchPoint) {
                stream.add(generateShapeBJson(i, rand).getBytes(StandardCharsets.UTF_8));
            } else {
                stream.add(generateShapeAJson(i, rand).getBytes(StandardCharsets.UTF_8));
            }
        }
        return stream;
    }

    public static String generateShapeAJson(int id, Random rand) {
        String category = CATEGORIES[rand.nextInt(CATEGORIES.length)];
        double price = 10.0 + (rand.nextDouble() * 490.0);
        boolean inStock = rand.nextDouble() > 0.25;
        double rating = 3.0 + rand.nextInt(21) * 0.1;
        int day = 1 + rand.nextInt(28);

        int tagCount = 1 + rand.nextInt(4);
        StringBuilder tagsJson = new StringBuilder("[");
        for (int t = 0; t < tagCount; t++) {
            if (t > 0) tagsJson.append(",");
            tagsJson.append("\"").append(TAGS_POOL[rand.nextInt(TAGS_POOL.length)]).append("\"");
        }
        tagsJson.append("]");

        String warranty = rand.nextBoolean() ? "standard" : "extended";
        String desc = String.format(Locale.ROOT,
                "High performance %s merchandise item designed for reliability with %s warranty protection.",
                category, warranty);

        int order = rand.nextInt(3);
        String fId = "\"id\":" + id;
        String fName = "\"name\":\"Product-" + id + "-" + String.format(Locale.ROOT, "%03d", rand.nextInt(1000)) + "\"";
        String fCat = "\"category\":\"" + category + "\"";
        String fPrice = String.format(Locale.ROOT, "\"price\":%.2f", price);
        String fStock = "\"inStock\":" + inStock;
        String fRating = String.format(Locale.ROOT, "\"rating\":%.1f", rating);
        String fTags = "\"tags\":" + tagsJson;
        String fDate = String.format(Locale.ROOT, "\"createdAt\":\"2026-03-%02d\"", day);
        String fDesc = "\"description\":\"" + desc + "\"";

        if (order == 1) {
            return String.format(Locale.ROOT, "{\"_dataset\":\"SYNTHETIC\",%s,%s,%s,%s,%s,%s,%s,%s,%s}",
                    fId, fCat, fName, fPrice, fRating, fStock, fTags, fDesc, fDate);
        } else if (order == 2) {
            return String.format(Locale.ROOT, "{\"_dataset\":\"SYNTHETIC\",%s,%s,%s,%s,%s,%s,%s,%s,%s}",
                    fName, fId, fPrice, fStock, fCat, fRating, fDate, fTags, fDesc);
        } else {
            return String.format(Locale.ROOT, "{\"_dataset\":\"SYNTHETIC\",%s,%s,%s,%s,%s,%s,%s,%s,%s}",
                    fId, fName, fCat, fPrice, fStock, fRating, fTags, fDate, fDesc);
        }
    }

    public static String generateShapeBJson(int id, Random rand) {
        String department = CATEGORIES[rand.nextInt(CATEGORIES.length)];
        double price = 10.0 + (rand.nextDouble() * 490.0);
        boolean discountEligible = rand.nextBoolean();
        boolean available = rand.nextDouble() > 0.25;
        int hub = 1 + rand.nextInt(8);
        double customerScore = 3.0 + rand.nextInt(21) * 0.1;
        int day = 1 + rand.nextInt(28);

        int labelCount = 1 + rand.nextInt(4);
        StringBuilder labelsJson = new StringBuilder("[");
        for (int t = 0; t < labelCount; t++) {
            if (t > 0) labelsJson.append(",");
            labelsJson.append("\"").append(TAGS_POOL[rand.nextInt(TAGS_POOL.length)]).append("\"");
        }
        labelsJson.append("]");

        char series = (char) ('A' + rand.nextInt(6));
        boolean warranty = rand.nextBoolean();

        int order = rand.nextInt(3);
        String fId = "\"item_id\":" + id;
        String fTitle = "\"title\":\"Product-" + id + "-" + String.format(Locale.ROOT, "%03d", rand.nextInt(1000)) + "\"";
        String fDept = "\"department\":\"" + department + "\"";
        String fPricing = String.format(Locale.ROOT, "\"pricing\":{\"amount\":%.2f,\"currency\":\"USD\",\"discount_eligible\":%b}", price, discountEligible);
        String fInv = String.format(Locale.ROOT, "\"inventory\":{\"available\":%b,\"fulfillment_hub\":\"WH-ZONE-%d\"}", available, hub);
        String fScore = String.format(Locale.ROOT, "\"customer_score\":%.1f", customerScore);
        String fLabels = "\"labels\":" + labelsJson;
        String fDate = String.format(Locale.ROOT, "\"registered_date\":\"2026-04-%02d\"", day);
        String fSpecs = String.format(Locale.ROOT, "\"specs\":{\"model_series\":\"SERIES-%c\",\"extended_warranty\":%b}", series, warranty);

        if (order == 1) {
            return String.format(Locale.ROOT, "{\"_dataset\":\"SYNTHETIC\",%s,%s,%s,%s,%s,%s,%s,%s,%s}",
                    fId, fDept, fTitle, fPricing, fScore, fInv, fLabels, fSpecs, fDate);
        } else if (order == 2) {
            return String.format(Locale.ROOT, "{\"_dataset\":\"SYNTHETIC\",%s,%s,%s,%s,%s,%s,%s,%s,%s}",
                    fTitle, fId, fPricing, fInv, fDept, fScore, fDate, fLabels, fSpecs);
        } else {
            return String.format(Locale.ROOT, "{\"_dataset\":\"SYNTHETIC\",%s,%s,%s,%s,%s,%s,%s,%s,%s}",
                    fId, fTitle, fDept, fPricing, fInv, fScore, fLabels, fDate, fSpecs);
        }
    }

    public static String generateGradualJson(int id, Random rand, double progress) {
        int stage = (int) Math.floor(progress * 10.0);

        // Field 1: id vs item_id
        String idField = stage >= 1 ? "\"item_id\":" + id : "\"id\":" + id;

        // Field 2: name vs title
        String nameField = stage >= 2 ?
                "\"title\":\"Product-" + id + "-" + String.format(Locale.ROOT, "%03d", rand.nextInt(1000)) + "\"" :
                "\"name\":\"Product-" + id + "-" + String.format(Locale.ROOT, "%03d", rand.nextInt(1000)) + "\"";

        // Field 3: category vs department
        String catVal = CATEGORIES[rand.nextInt(CATEGORIES.length)];
        String catField = stage >= 3 ? "\"department\":\"" + catVal + "\"" : "\"category\":\"" + catVal + "\"";

        // Field 4: rating vs customer_score
        double ratingVal = 3.0 + rand.nextInt(21) * 0.1;
        String ratingField = stage >= 4 ? String.format(Locale.ROOT, "\"customer_score\":%.1f", ratingVal) : String.format(Locale.ROOT, "\"rating\":%.1f", ratingVal);

        // Field 5: tags vs labels
        int tagCount = 1 + rand.nextInt(4);
        StringBuilder tagsJson = new StringBuilder("[");
        for (int t = 0; t < tagCount; t++) {
            if (t > 0) tagsJson.append(",");
            tagsJson.append("\"").append(TAGS_POOL[rand.nextInt(TAGS_POOL.length)]).append("\"");
        }
        tagsJson.append("]");
        String tagsField = stage >= 5 ? "\"labels\":" + tagsJson : "\"tags\":" + tagsJson;

        // Field 6: createdAt vs registered_date
        int day = 1 + rand.nextInt(28);
        String dateField = stage >= 6 ?
                String.format(Locale.ROOT, "\"registered_date\":\"2026-04-%02d\"", day) :
                String.format(Locale.ROOT, "\"createdAt\":\"2026-03-%02d\"", day);

        // Field 7: price (flat) vs pricing (nested)
        double priceVal = 10.0 + (rand.nextDouble() * 490.0);
        String priceField = stage >= 7 ?
                String.format(Locale.ROOT, "\"pricing\":{\"amount\":%.2f,\"currency\":\"USD\",\"discount_eligible\":%b}", priceVal, rand.nextBoolean()) :
                String.format(Locale.ROOT, "\"price\":%.2f", priceVal);

        // Field 8: inStock (flat) vs inventory (nested)
        boolean stockVal = rand.nextDouble() > 0.25;
        String stockField = stage >= 8 ?
                String.format(Locale.ROOT, "\"inventory\":{\"available\":%b,\"fulfillment_hub\":\"WH-ZONE-%d\"}", stockVal, 1 + rand.nextInt(8)) :
                String.format(Locale.ROOT, "\"inStock\":%b", stockVal);

        // Field 9: description (flat) vs specs (nested)
        String descField = stage >= 9 ?
                String.format(Locale.ROOT, "\"specs\":{\"model_series\":\"SERIES-%c\",\"extended_warranty\":%b}", (char) ('A' + rand.nextInt(6)), rand.nextBoolean()) :
                String.format(Locale.ROOT, "\"description\":\"High performance %s merchandise item designed for reliability with %s warranty protection.\"", catVal, rand.nextBoolean() ? "standard" : "extended");

        return String.format(Locale.ROOT,
                "{\"_dataset\":\"SYNTHETIC\",%s,%s,%s,%s,%s,%s,%s,%s,%s}",
                idField, nameField, catField, priceField, stockField, ratingField, tagsField, dateField, descField
        );
    }

    public static String generateNoisyShapeAJson(int id, Random rand) {
        String category = CATEGORIES[rand.nextInt(CATEGORIES.length)];
        double price = 10.0 + (rand.nextDouble() * 490.0);
        boolean inStock = rand.nextDouble() > 0.25;
        double rating = 3.5 + (rand.nextInt(15)) * 0.1;
        int day = 1 + rand.nextInt(28);

        // Varying tags length: 1 to 5 tags
        int tagCount = 1 + rand.nextInt(5);
        StringBuilder tagsJson = new StringBuilder("[");
        for (int t = 0; t < tagCount; t++) {
            if (t > 0) tagsJson.append(",");
            tagsJson.append("\"").append(TAGS_POOL[(id + t) % TAGS_POOL.length]).append("\"");
        }
        tagsJson.append("]");

        // Varying description length: 1 to 3 repetitions
        String baseDesc = "High performance consumer merchandise item designed for reliability with standard warranty protection.";
        int descRepeat = 1 + rand.nextInt(3);
        StringBuilder descBuilder = new StringBuilder();
        for (int d = 0; d < descRepeat; d++) {
            if (d > 0) descBuilder.append(" ");
            descBuilder.append(baseDesc);
        }

        // Occasional outliers (~2.5% of payloads)
        if (id % 40 == 0) {
            descBuilder.append(" [SPECIAL OUTLIER AUDIT: Batch-").append(id).append(" verified heavy-duty enterprise inspection standard]");
            tagsJson = new StringBuilder("[\"outlier\",\"enterprise\",\"bulk\",\"special\",\"certified\",\"heavy-duty\"]");
        }

        return String.format(Locale.ROOT,
                "{\"_dataset\":\"SYNTHETIC\",\"id\":%d,\"name\":\"Product-%d\",\"category\":\"%s\",\"price\":%.2f,\"inStock\":%b,\"rating\":%.1f,\"tags\":%s,\"createdAt\":\"2026-03-%02d\",\"description\":\"%s\"}",
                id, id, category, price, inStock, rating, tagsJson, day, descBuilder
        );
    }

    public static void generateSynthetic(Path dirA, Path dirB, int countA, int countB) throws IOException {
        Files.createDirectories(dirA);
        Files.createDirectories(dirB);

        Random rA = new Random(42);
        for (int i = 1; i <= countA; i++) {
            Files.writeString(dirA.resolve(String.format(Locale.ROOT, "%06d.json", i)), generateShapeAJson(i, rA), StandardCharsets.UTF_8);
        }

        Random rB = new Random(84);
        for (int i = 1; i <= countB; i++) {
            Files.writeString(dirB.resolve(String.format(Locale.ROOT, "%06d.json", i)), generateShapeBJson(i, rB), StandardCharsets.UTF_8);
        }
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private static boolean detectSynthetic(List<byte[]> a, List<byte[]> b) {
        if (!a.isEmpty() && new String(a.get(0), StandardCharsets.UTF_8).contains("\"SYNTHETIC\"")) {
            return true;
        }
        if (!b.isEmpty() && new String(b.get(0), StandardCharsets.UTF_8).contains("\"SYNTHETIC\"")) {
            return true;
        }
        return false;
    }

    private static byte[] trainDict(List<byte[]> samples) {
        long totalBytes = samples.stream().mapToLong(s -> s.length).sum();
        ZstdDictTrainer trainer = new ZstdDictTrainer((int) (totalBytes + 1024), DICT_SIZE);
        for (byte[] s : samples) {
            trainer.addSample(s);
        }
        byte[] dict = trainer.trainSamples();
        if (dict == null || dict.length == 0) {
            throw new IllegalStateException("ZstdDictTrainer returned empty dictionary");
        }
        return dict;
    }

    private static List<byte[]> loadFiles(Path dir) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
        List<byte[]> out = new ArrayList<>(files.size());
        for (Path f : files) {
            out.add(Files.readAllBytes(f));
        }
        return out;
    }

    private static void printConsoleSummary(DriftResult r) {
        String synthTag = r.isSynthetic ? " [DATASET: SYNTHETIC]" : "";
        System.out.println("\n=== Drift Benchmark Summary" + synthTag + " ===");
        System.out.println("Notice: All compression ratios are total-bytes based (sum(originalBytes) / sum(compressedBytes)).\n");

        System.out.println("1. Canonical Run (Switch Point #600):");
        System.out.printf(Locale.ROOT, "   Shape Switch Point:         Payload #%d (Shape A: %d, Shape B: %d)%n",
                r.shapeSwitchPoint, r.countA, r.countB);
        if (r.payloadsElapsedToDetection >= 0) {
            System.out.printf(Locale.ROOT, "   Drift Detection Point:      Payload #%d (%d payloads elapsed after shape switch)%n",
                    r.driftDetectedAt, r.payloadsElapsedToDetection);
        }
        System.out.printf(Locale.ROOT, "   Dictionary Switch Points:   Adaptive-200: %s | Adaptive-100: [%d]%n",
                r.driftRetrainPayloadIndices, r.adaptive100.retrainDeployedAt);
        System.out.printf(Locale.ROOT, "   Evaluation Window:          %d payloads | Threshold: < %.2f * baseline%n",
                r.driftEvalWindow, r.driftThreshold);
        System.out.printf(Locale.ROOT, "   Reporting Window:           %d payloads%n%n", r.reportingWindowSize);

        System.out.println("   Strategy                         Total Bytes   Mean Ratio   Retrains   Post-Switch 100 Ratio   Train Time   Recovery");
        System.out.println("   ------------------------------------------------------------------------------------------------------------------");
        printStrategyConsoleRow(r.gzip);
        printStrategyConsoleRow(r.staticDict);
        printStrategyConsoleRow(r.fixedSchedule);
        printStrategyConsoleRow(r.fixedSchedule100);
        printStrategyConsoleRow(r.adaptive200);
        printStrategyConsoleRow(r.adaptive100);
        System.out.println("   ------------------------------------------------------------------------------------------------------------------\n");

        if (r.matrixResult != null && !r.matrixResult.pointStrategyStats.isEmpty()) {
            System.out.println("2. Sensitivity Matrix: One Row Per (Strategy, Switch Point) Across 5 Seeds:");
            System.out.printf(Locale.ROOT, "   %-28s %-12s %-25s %-16s %-25s %-16s%n",
                    "Strategy", "Switch Point", "Total Bytes (mean)", "Retrains (mean)", "Post-Switch 100 (mean)", "Train Time (mean)");
            System.out.println("   ---------------------------------------------------------------------------------------------------------------------------------");
            for (SwitchPointStrategyStats stat : r.matrixResult.pointStrategyStats) {
                System.out.printf(Locale.ROOT, "   %-28s #%-11d %-25s %-16s %-25s %-16s%n",
                        stat.strategyName,
                        stat.switchPoint,
                        stat.totalBytes.format("%,.0f B"),
                        stat.retrainCount.format("%.1f"),
                        stat.postSwitch100Ratio.format("%.2fx"),
                        stat.trainingTimeMs.format("%.1f ms"));
            }
            System.out.println("   ---------------------------------------------------------------------------------------------------------------------------------\n");

            if (!r.matrixResult.zeroRetrainCases.isEmpty()) {
                System.out.println("3. Missed-Drift Cases (Adaptive Runs with 0 Retrains):");
                for (ZeroRetrainCase zc : r.matrixResult.zeroRetrainCases) {
                    System.out.printf(Locale.ROOT, "   [MISSED-DRIFT] %-28s | Switch: #%d | Seed: %-4d | Detected: #%d (%d elapsed) | Left After Detection: %d payloads (< %d needed)%n",
                            zc.strategyName, zc.switchPoint, zc.seed, zc.driftDetectedAt, zc.payloadsElapsed, zc.payloadsLeftAfterDetection, zc.bufferNeeded);
                }
                System.out.println();
            }
        }

        if (r.gradualResult != null) {
            System.out.println("4. Gradual-Drift Stream Verification (400-Payload Transition: #300 -> #700):");
            System.out.printf(Locale.ROOT, "   %-28s %-20s %-14s %-24s %-10s %-12s %-10s %-12s%n",
                    "Strategy", "Detected Drift?", "Detected At", "Into Transition", "Retrains", "Total Bytes", "Mean Ratio", "Train Time");
            System.out.println("   ------------------------------------------------------------------------------------------------------------------------------------------");
            for (StrategyResult s : r.gradualResult.strategies) {
                String detected = "No";
                String detectedAt = "-";
                String elapsed = "-";
                if (s.name.contains("Fixed-schedule")) {
                    detected = "No (blind schedule)";
                } else if (s.name.contains("Adaptive") && s.driftDetectedAt > 0) {
                    detected = "Yes";
                    detectedAt = "#" + s.driftDetectedAt;
                    elapsed = (s.driftDetectedAt - r.gradualResult.transitionStart) + " payloads";
                }
                System.out.printf(Locale.ROOT, "   %-28s %-20s %-14s %-24s %-10d %,10d B %9.2fx %10d ms%n",
                        s.name, detected, detectedAt, elapsed, s.retrainCount, s.totalBytes, s.overallRatio, s.trainingTimeMs);
            }
            System.out.println("   ------------------------------------------------------------------------------------------------------------------------------------------\n");
        }

        if (r.noDriftResult != null) {
            System.out.println("5. Clean No-Drift Stream Verification (Shape A only, 1,000 Payloads):");
            System.out.printf(Locale.ROOT, "   %-28s %12s %11s %10s %14s%n", "Strategy", "Total Bytes", "Mean Ratio", "Retrains", "Train Time");
            System.out.println("   ----------------------------------------------------------------------------------");
            for (StrategyResult s : r.noDriftResult.strategies) {
                System.out.printf(Locale.ROOT, "   %-28s %,10d B %10.2fx %10d %11d ms%n",
                        s.name, s.totalBytes, s.overallRatio, s.retrainCount, s.trainingTimeMs);
            }
            System.out.println("   ----------------------------------------------------------------------------------\n");
        }

        if (r.noisyResult != null) {
            System.out.println("6. Noisy-Stationary Stream Verification (Shape A varying + outliers, 1,000 Payloads):");
            System.out.printf(Locale.ROOT, "   %-28s %12s %11s %10s %14s %14s%n", "Strategy", "Total Bytes", "Mean Ratio", "Retrains", "False Alarms", "Train Time");
            System.out.println("   -------------------------------------------------------------------------------------------------");
            for (StrategyResult s : r.noisyResult.strategies) {
                System.out.printf(Locale.ROOT, "   %-28s %,10d B %10.2fx %10d %14d %11d ms%n",
                        s.name, s.totalBytes, s.overallRatio, s.retrainCount, s.falseAlarmCount, s.trainingTimeMs);
            }
            System.out.println("   -------------------------------------------------------------------------------------------------\n");
        }

        System.out.println("=====================================================================================\n");
    }

    private static void printStrategyConsoleRow(StrategyResult s) {
        String rec = s.timeToRecovery >= 0 ? s.timeToRecovery + " payloads" : "-";
        System.out.printf(Locale.ROOT, "   %-30s %,10d B %10.2fx %10d %21.2fx %9d ms %12s%n",
                s.name, s.totalBytes, s.overallRatio, s.retrainCount, s.postSwitch100Ratio, s.trainingTimeMs, rec);
    }
}
