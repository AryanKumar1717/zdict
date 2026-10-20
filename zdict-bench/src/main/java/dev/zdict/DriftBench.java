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
 * Simulates schema drift by streaming payloads from folder A (60%) followed by folder B (40%),
 * comparing four strategies:
 * 1. gzip (level 6)
 * 2. static zstd+dict (trained once on shape A and never updated)
 * 3. fixed-schedule zstd+dict (retrained every 200 payloads on the last 200, without drift detection)
 * 4. adaptive zdict (re-implements the zdict drift rule: rolling mean &lt; baseline * 0.85
 *    over a window of 200 payloads; when drift is detected, the buffer is reset and retraining
 *    uses ONLY at least 200 fresh payloads seen after detection).
 *
 * NOTE: The drift detection rule re-implements the exact same logic as demo-app's
 * dev.zdict.demo.RatioTracker and AutoTrainerService because zdict-bench is an independent
 * benchmark module without a runtime dependency on demo-app.
 */
public class DriftBench {

    public static final int WINDOW_SIZE = 100;
    public static final int DRIFT_EVAL_WINDOW = 200;
    public static final double DRIFT_THRESHOLD = 0.85; // rolling mean < baseline * 0.85
    public static final int DICT_SIZE = 16 * 1024;

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
        public long adaptiveBytes;
        public double gzipRatio;
        public double staticRatio;
        public double fixedRatio;
        public double adaptiveRatio;
    }

    public static class StrategyResult {
        public String name;
        public long totalBytes;
        public double overallRatio;
        public int retrainCount;
        public double postSwitch100Ratio;
        public String description;

        public StrategyResult() {}

        public StrategyResult(String name, String description) {
            this.name = name;
            this.description = description;
        }
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

        public StrategyResult gzip = new StrategyResult("gzip (level 6)", "Standard gzip compression");
        public StrategyResult staticDict = new StrategyResult("Static zstd+dict", "Trained once on Shape A, never updated");
        public StrategyResult fixedSchedule = new StrategyResult("Fixed-schedule zstd+dict", "Retrained every 200 payloads on last 200 without drift detection");
        public StrategyResult adaptive = new StrategyResult("Adaptive zdict", "Self-learning: detects drift, resets buffer, retrains on 200 fresh post-drift samples");

        public List<Integer> driftRetrainPayloadIndices = new ArrayList<>();
        public List<Integer> fixedRetrainPayloadIndices = new ArrayList<>();
        public List<WindowStats> windows = new ArrayList<>();

        // Backward compatibility convenience fields
        public long totalOriginalBytes;
        public long totalGzipBytes;
        public long totalStaticBytes;
        public long totalFixedBytes;
        public long totalAdaptiveBytes;
        public double overallGzipRatio;
        public double overallStaticRatio;
        public double overallFixedRatio;
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
            // Default: generate synthetic data in target/synthetic-bench
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

        // Build combined stream: 60% from A, then B
        int totalTarget = payloadsA.size() + payloadsB.size();
        int countA = (int) Math.round(totalTarget * 0.60);
        countA = Math.min(countA, payloadsA.size());
        int countB = Math.min(totalTarget - countA, payloadsB.size());

        List<byte[]> stream = new ArrayList<>(countA + countB);
        List<String> shapes = new ArrayList<>(countA + countB);

        for (int i = 0; i < countA; i++) {
            stream.add(payloadsA.get(i % payloadsA.size()));
            shapes.add("Shape A");
        }
        for (int i = 0; i < countB; i++) {
            stream.add(payloadsB.get(i % payloadsB.size()));
            shapes.add("Shape B");
        }

        int totalPayloads = stream.size();
        boolean isSynthetic = detectSynthetic(payloadsA, payloadsB);

        DriftResult result = new DriftResult();
        result.date = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC));
        result.javaVersion = System.getProperty("java.version");
        result.isSynthetic = isSynthetic;
        result.totalPayloads = totalPayloads;
        result.countA = countA;
        result.countB = countB;
        result.shapeSwitchPoint = countA;

        System.out.printf("Running DriftBench%s: %d total payloads (%d Shape A, %d Shape B). Switch point at #%d.%n",
                isSynthetic ? " [SYNTHETIC]" : "", totalPayloads, countA, countB, countA);

        // 1. Initial training on the first min(200, countA) payloads of Shape A
        int initialTrainCount = Math.min(DRIFT_EVAL_WINDOW, countA);
        List<byte[]> initialTrainSamples = stream.subList(0, initialTrainCount);
        byte[] initialDictBytes = trainDict(initialTrainSamples);

        // Baseline ratio on initial samples
        double initialBaseline;
        try (ZstdDictCompress initialComp = new ZstdDictCompress(initialDictBytes, 3)) {
            long origInit = 0;
            long compInit = 0;
            for (byte[] s : initialTrainSamples) {
                origInit += s.length;
                compInit += Zstd.compress(s, initialComp).length;
            }
            initialBaseline = compInit > 0 ? (double) origInit / compInit : 1.0;
        }

        // Strategy 2: Static compressor (never updated)
        ZstdDictCompress staticCompressor = new ZstdDictCompress(initialDictBytes, 3);

        // Strategy 3: Fixed-schedule compressor (retrained every 200 payloads on the last 200)
        ZstdDictCompress fixedCompressor = new ZstdDictCompress(initialDictBytes, 3);
        List<byte[]> fixedRecent = new ArrayList<>(DRIFT_EVAL_WINDOW);

        // Strategy 4: Adaptive compressor (drift detection + buffer reset + 200 post-drift payloads)
        ZstdDictCompress adaptiveCompressor = new ZstdDictCompress(initialDictBytes, 3);
        double activeBaseline = initialBaseline;
        List<Double> rollingWindow = new ArrayList<>(DRIFT_EVAL_WINDOW);
        boolean isDrifting = false;
        List<byte[]> postDriftBuffer = new ArrayList<>();

        // Post-switch 100 payloads accumulator
        int postSwitchStart = countA;
        int postSwitchEnd = Math.min(countA + 100, totalPayloads);
        long postSwitchOrigBytes = 0;
        long postSwitchGzipBytes = 0;
        long postSwitchStaticBytes = 0;
        long postSwitchFixedBytes = 0;
        long postSwitchAdaptiveBytes = 0;

        // Window accumulator
        long currentWindowOrig = 0;
        long currentWindowGzip = 0;
        long currentWindowStatic = 0;
        long currentWindowFixed = 0;
        long currentWindowAdaptive = 0;
        int windowIdx = 0;

        for (int i = 0; i < totalPayloads; i++) {
            byte[] payload = stream.get(i);
            int origLen = payload.length;
            result.totalOriginalBytes += origLen;
            currentWindowOrig += origLen;

            // Strategy 1: gzip (level 6)
            byte[] gz = ReplayBench.compressGzip(payload, 6);
            result.totalGzipBytes += gz.length;
            currentWindowGzip += gz.length;

            // Strategy 2: static zstd+dict
            byte[] stComp = Zstd.compress(payload, staticCompressor);
            result.totalStaticBytes += stComp.length;
            currentWindowStatic += stComp.length;

            // Strategy 3: fixed-schedule zstd+dict
            byte[] fxComp = Zstd.compress(payload, fixedCompressor);
            result.totalFixedBytes += fxComp.length;
            currentWindowFixed += fxComp.length;
            fixedRecent.add(payload);
            if (fixedRecent.size() > DRIFT_EVAL_WINDOW) {
                fixedRecent.remove(0);
            }
            if ((i + 1) % DRIFT_EVAL_WINDOW == 0 && (i + 1) < totalPayloads) {
                // Fixed schedule retrain on last 200 payloads
                byte[] fxDictBytes = trainDict(fixedRecent);
                fixedCompressor.close();
                fixedCompressor = new ZstdDictCompress(fxDictBytes, 3);
                result.fixedSchedule.retrainCount++;
                result.fixedRetrainPayloadIndices.add(i + 1);
            }

            // Strategy 4: adaptive zstd+dict
            byte[] adComp = Zstd.compress(payload, adaptiveCompressor);
            result.totalAdaptiveBytes += adComp.length;
            currentWindowAdaptive += adComp.length;

            double ratio = (double) origLen / adComp.length;

            if (!isDrifting) {
                if (rollingWindow.size() >= DRIFT_EVAL_WINDOW) {
                    rollingWindow.remove(0);
                }
                rollingWindow.add(ratio);

                if (rollingWindow.size() >= DRIFT_EVAL_WINDOW) {
                    double rollingMean = rollingWindow.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                    if (rollingMean < activeBaseline * DRIFT_THRESHOLD) {
                        isDrifting = true;
                        result.driftDetectedAt = i + 1; // 1-based payload index
                        postDriftBuffer.clear(); // RESET buffer at detection
                        if (result.shapeSwitchPoint > 0 && (i + 1) >= result.shapeSwitchPoint) {
                            result.payloadsElapsedToDetection = (i + 1) - result.shapeSwitchPoint;
                        }
                        System.out.printf("Drift detected at payload #%d (rolling mean %.2fx < threshold %.2fx). Reset buffer to accumulate %d fresh payloads.%n",
                                i + 1, rollingMean, activeBaseline * DRIFT_THRESHOLD, DRIFT_EVAL_WINDOW);
                    }
                }
            } else {
                // Collect ONLY payloads seen after drift detection
                postDriftBuffer.add(payload);
                if (postDriftBuffer.size() >= DRIFT_EVAL_WINDOW) {
                    // Retrain on ONLY the 200 post-drift payloads
                    byte[] newDictBytes = trainDict(postDriftBuffer);
                    adaptiveCompressor.close();
                    adaptiveCompressor = new ZstdDictCompress(newDictBytes, 3);

                    // Re-measure baseline
                    long rOrig = 0;
                    long rComp = 0;
                    for (byte[] s : postDriftBuffer) {
                        rOrig += s.length;
                        rComp += Zstd.compress(s, adaptiveCompressor).length;
                    }
                    activeBaseline = rComp > 0 ? (double) rOrig / rComp : 1.0;

                    isDrifting = false;
                    rollingWindow.clear();
                    postDriftBuffer.clear();

                    result.driftRetrainCount++;
                    result.adaptive.retrainCount++;
                    result.driftRetrainPayloadIndices.add(i + 1);
                    System.out.printf("Switch point: Deployed retrained adaptive dictionary at payload #%d using %d post-drift samples (new baseline: %.2fx)%n",
                            i + 1, DRIFT_EVAL_WINDOW, activeBaseline);
                }
            }

            // Accumulate post-switch 100 payloads
            if (i >= postSwitchStart && i < postSwitchEnd) {
                postSwitchOrigBytes += origLen;
                postSwitchGzipBytes += gz.length;
                postSwitchStaticBytes += stComp.length;
                postSwitchFixedBytes += fxComp.length;
                postSwitchAdaptiveBytes += adComp.length;
            }

            // Window boundary (every 100 payloads or end of stream)
            if ((i + 1) % WINDOW_SIZE == 0 || (i + 1) == totalPayloads) {
                WindowStats ws = new WindowStats();
                ws.windowIndex = windowIdx++;
                ws.startPayload = (i / WINDOW_SIZE) * WINDOW_SIZE;
                ws.endPayload = i + 1;
                ws.activeShape = (ws.startPayload < countA) ? ((ws.endPayload <= countA) ? "Shape A" : "A -> B Transition") : "Shape B";
                ws.originalBytes = currentWindowOrig;
                ws.gzipBytes = currentWindowGzip;
                ws.staticBytes = currentWindowStatic;
                ws.fixedBytes = currentWindowFixed;
                ws.adaptiveBytes = currentWindowAdaptive;

                ws.gzipRatio = currentWindowGzip > 0 ? (double) currentWindowOrig / currentWindowGzip : 0.0;
                ws.staticRatio = currentWindowStatic > 0 ? (double) currentWindowOrig / currentWindowStatic : 0.0;
                ws.fixedRatio = currentWindowFixed > 0 ? (double) currentWindowOrig / currentWindowFixed : 0.0;
                ws.adaptiveRatio = currentWindowAdaptive > 0 ? (double) currentWindowOrig / currentWindowAdaptive : 0.0;

                result.windows.add(ws);

                currentWindowOrig = 0;
                currentWindowGzip = 0;
                currentWindowStatic = 0;
                currentWindowFixed = 0;
                currentWindowAdaptive = 0;
            }
        }

        staticCompressor.close();
        fixedCompressor.close();
        adaptiveCompressor.close();

        // Populate strategy results
        result.overallGzipRatio = result.totalGzipBytes > 0 ? (double) result.totalOriginalBytes / result.totalGzipBytes : 0.0;
        result.overallStaticRatio = result.totalStaticBytes > 0 ? (double) result.totalOriginalBytes / result.totalStaticBytes : 0.0;
        result.overallFixedRatio = result.totalFixedBytes > 0 ? (double) result.totalOriginalBytes / result.totalFixedBytes : 0.0;
        result.overallAdaptiveRatio = result.totalAdaptiveBytes > 0 ? (double) result.totalOriginalBytes / result.totalAdaptiveBytes : 0.0;

        result.adaptiveSavingsVsStaticPercent = result.totalStaticBytes > 0 ?
                (1.0 - (double) result.totalAdaptiveBytes / result.totalStaticBytes) * 100.0 : 0.0;

        result.gzip.totalBytes = result.totalGzipBytes;
        result.gzip.overallRatio = result.overallGzipRatio;
        result.gzip.retrainCount = 0;
        result.gzip.postSwitch100Ratio = postSwitchGzipBytes > 0 ? (double) postSwitchOrigBytes / postSwitchGzipBytes : 0.0;

        result.staticDict.totalBytes = result.totalStaticBytes;
        result.staticDict.overallRatio = result.overallStaticRatio;
        result.staticDict.retrainCount = 0;
        result.staticDict.postSwitch100Ratio = postSwitchStaticBytes > 0 ? (double) postSwitchOrigBytes / postSwitchStaticBytes : 0.0;

        result.fixedSchedule.totalBytes = result.totalFixedBytes;
        result.fixedSchedule.overallRatio = result.overallFixedRatio;
        result.fixedSchedule.postSwitch100Ratio = postSwitchFixedBytes > 0 ? (double) postSwitchOrigBytes / postSwitchFixedBytes : 0.0;

        result.adaptive.totalBytes = result.totalAdaptiveBytes;
        result.adaptive.overallRatio = result.overallAdaptiveRatio;
        result.adaptive.postSwitch100Ratio = postSwitchAdaptiveBytes > 0 ? (double) postSwitchOrigBytes / postSwitchAdaptiveBytes : 0.0;

        // Print elapsed notice
        if (result.payloadsElapsedToDetection >= 0) {
            System.out.printf("Payloads elapsed between real shape switch (#%d) and detection (#%d): %d payloads%n",
                    result.shapeSwitchPoint, result.driftDetectedAt, result.payloadsElapsedToDetection);
        }

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
    // SVG Line Chart Generation (Hand-crafted, self-contained XML)
    // =========================================================================

    public static String generateDriftSvg(DriftResult result) {
        int width = 920;
        int height = 480;
        int chartLeft = 70;
        int chartRight = width - 40;
        int chartTop = 90;
        int chartBottom = 400;
        int chartWidth = chartRight - chartLeft;
        int chartHeight = chartBottom - chartTop;

        double maxRatio = 1.0;
        for (WindowStats ws : result.windows) {
            maxRatio = Math.max(maxRatio, ws.gzipRatio);
            maxRatio = Math.max(maxRatio, ws.staticRatio);
            maxRatio = Math.max(maxRatio, ws.fixedRatio);
            maxRatio = Math.max(maxRatio, ws.adaptiveRatio);
        }
        maxRatio = Math.ceil(maxRatio * 1.15); // headroom

        StringBuilder svg = new StringBuilder();
        svg.append(String.format("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\">\n",
                width, height, width, height));
        svg.append("  <rect width=\"100%\" height=\"100%\" fill=\"#ffffff\" rx=\"8\"/>\n");
        svg.append("  <rect width=\"100%\" height=\"100%\" fill=\"none\" stroke=\"#e2e8f0\" stroke-width=\"1\" rx=\"8\"/>\n");

        // Title and Subtitle
        String synthTag = result.isSynthetic ? "[SYNTHETIC] " : "";
        svg.append(String.format("  <text x=\"30\" y=\"32\" font-family=\"system-ui, -apple-system, sans-serif\" font-size=\"18\" font-weight=\"bold\" fill=\"#1a202c\">%sAPI Schema Drift: Comparison of 4 Strategies</text>\n", synthTag));

        String subText = String.format("Shape switch: #%d | Drift detected: #%d (%d elapsed) | Retrain buffer: 200 fresh post-drift | Threshold: &lt; 0.85 * baseline",
                result.shapeSwitchPoint, result.driftDetectedAt, result.payloadsElapsedToDetection >= 0 ? result.payloadsElapsedToDetection : 0);
        svg.append(String.format("  <text x=\"30\" y=\"54\" font-family=\"system-ui, -apple-system, sans-serif\" font-size=\"12\" fill=\"#718096\">%s</text>\n", subText));

        // Legend (4 strategies)
        int legY = 72;
        drawLegendItem(svg, 30, legY, "#718096", "gzip (lvl 6)");
        drawLegendItem(svg, 150, legY, "#e53e3e", "static zstd+dict");
        drawLegendItem(svg, 300, legY, "#3182ce", "fixed-sched (every 200)");
        drawLegendItem(svg, 480, legY, "#38a169", "adaptive zdict (drift)");

        // Y-axis gridlines
        int gridSteps = 5;
        for (int i = 0; i <= gridSteps; i++) {
            double val = (maxRatio / gridSteps) * i;
            int y = chartBottom - (int) ((val / maxRatio) * chartHeight);
            svg.append(String.format("  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#edf2f7\" stroke-width=\"1\"/>\n",
                    chartLeft, y, chartRight, y));
            svg.append(String.format("  <text x=\"%d\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"11\" fill=\"#a0aec0\" text-anchor=\"end\">%.1fx</text>\n",
                    chartLeft - 10, y + 4, val));
        }

        // Schema transition vertical line (Shape switch point)
        double transitionPct = (double) result.countA / result.totalPayloads;
        int transX = chartLeft + (int) (transitionPct * chartWidth);
        svg.append(String.format("  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#e53e3e\" stroke-width=\"2\" stroke-dasharray=\"4,4\"/>\n",
                transX, chartTop, transX, chartBottom));
        svg.append(String.format("  <text x=\"%d\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"11\" font-weight=\"600\" fill=\"#e53e3e\" text-anchor=\"middle\">Shape Switch (#%d)</text>\n",
                transX, chartTop - 6, result.shapeSwitchPoint));

        // Coordinates for the 4 polylines
        List<int[]> gzipPts = new ArrayList<>();
        List<int[]> staticPts = new ArrayList<>();
        List<int[]> fixedPts = new ArrayList<>();
        List<int[]> adaptivePts = new ArrayList<>();

        int numWindows = result.windows.size();
        for (int i = 0; i < numWindows; i++) {
            WindowStats ws = result.windows.get(i);
            int x = chartLeft + (int) (((double) i / (numWindows - 1)) * chartWidth);

            int yGz = chartBottom - (int) ((ws.gzipRatio / maxRatio) * chartHeight);
            int ySt = chartBottom - (int) ((ws.staticRatio / maxRatio) * chartHeight);
            int yFx = chartBottom - (int) ((ws.fixedRatio / maxRatio) * chartHeight);
            int yAd = chartBottom - (int) ((ws.adaptiveRatio / maxRatio) * chartHeight);

            gzipPts.add(new int[]{x, yGz});
            staticPts.add(new int[]{x, ySt});
            fixedPts.add(new int[]{x, yFx});
            adaptivePts.add(new int[]{x, yAd});

            // X-axis tick & label
            svg.append(String.format("  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#cbd5e0\" stroke-width=\"1\"/>\n",
                    x, chartBottom, x, chartBottom + 5));
            svg.append(String.format("  <text x=\"%d\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"11\" fill=\"#718096\" text-anchor=\"middle\">%d</text>\n",
                    x, chartBottom + 20, ws.endPayload));
        }

        // Draw polylines
        drawPolyline(svg, gzipPts, "#718096", 2);
        drawPolyline(svg, staticPts, "#e53e3e", 2.2);
        drawPolyline(svg, fixedPts, "#3182ce", 2.2);
        drawPolyline(svg, adaptivePts, "#38a169", 2.5);

        // Draw data points
        for (int[] pt : gzipPts) {
            svg.append(String.format("  <circle cx=\"%d\" cy=\"%d\" r=\"3.5\" fill=\"#718096\"/>\n", pt[0], pt[1]));
        }
        for (int[] pt : staticPts) {
            svg.append(String.format("  <rect x=\"%d\" y=\"%d\" width=\"6\" height=\"6\" fill=\"#e53e3e\"/>\n", pt[0] - 3, pt[1] - 3));
        }
        for (int[] pt : fixedPts) {
            svg.append(String.format("  <circle cx=\"%d\" cy=\"%d\" r=\"3.5\" fill=\"#3182ce\"/>\n", pt[0], pt[1]));
        }
        for (int[] pt : adaptivePts) {
            svg.append(String.format("  <circle cx=\"%d\" cy=\"%d\" r=\"4.5\" fill=\"#38a169\" stroke=\"#ffffff\" stroke-width=\"1.5\"/>\n", pt[0], pt[1]));
        }

        // X-axis title
        svg.append(String.format("  <text x=\"%d\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"12\" fill=\"#718096\" text-anchor=\"middle\">Payload Sequence (%d per window)</text>\n",
                chartLeft + chartWidth / 2, chartBottom + 40, WINDOW_SIZE));

        svg.append("</svg>\n");
        return svg.toString();
    }

    private static void drawLegendItem(StringBuilder svg, int x, int y, String color, String text) {
        svg.append(String.format("  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"%s\" stroke-width=\"2.5\"/>\n",
                x, y + 6, x + 16, y + 6, color));
        svg.append(String.format("  <circle cx=\"%d\" cy=\"%d\" r=\"3.5\" fill=\"%s\"/>\n", x + 8, y + 6, color));
        svg.append(String.format("  <text x=\"%d\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"11\" fill=\"#4a5568\">%s</text>\n",
                x + 22, y + 10, text));
    }

    private static void drawPolyline(StringBuilder svg, List<int[]> pts, String color, double width) {
        if (pts.isEmpty()) return;
        StringBuilder pointsStr = new StringBuilder();
        for (int[] p : pts) {
            pointsStr.append(p[0]).append(",").append(p[1]).append(" ");
        }
        svg.append(String.format("  <polyline fill=\"none\" stroke=\"%s\" stroke-width=\"%.1f\" stroke-linecap=\"round\" stroke-linejoin=\"round\" points=\"%s\"/>\n",
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
            sb.append("> **Dataset**: **SYNTHETIC** (payloads generated with schema variation, marked `_dataset: \"SYNTHETIC\"`).\n\n");
        }

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
        sb.append("- **Dictionary Switch Points**: ").append(result.driftRetrainPayloadIndices).append(" (trained on ONLY ").append(DRIFT_EVAL_WINDOW).append(" fresh post-drift payloads)\n");
        sb.append("- **Evaluation Window**: **").append(DRIFT_EVAL_WINDOW).append(" payloads**\n");
        sb.append("- **Drift Detection Threshold**: `rolling mean < baseline * ").append(DRIFT_THRESHOLD).append("` (15% drop)\n");
        sb.append("- **Reporting Window**: **").append(WINDOW_SIZE).append(" payloads**\n\n");

        sb.append("## Strategy Comparison\n\n");
        sb.append("| Strategy | Total Compressed Size | Mean Ratio | Retrain Count | Post-Switch 100 Ratio | Description |\n");
        sb.append("|---|---|---|---|---|---|\n");
        sb.append(String.format("| **%s** | %,d B | **%.2fx** | %d | %.2fx | %s |\n",
                result.gzip.name, result.gzip.totalBytes, result.gzip.overallRatio, result.gzip.retrainCount, result.gzip.postSwitch100Ratio, result.gzip.description));
        sb.append(String.format("| **%s** | %,d B | **%.2fx** | %d | %.2fx | %s |\n",
                result.staticDict.name, result.staticDict.totalBytes, result.staticDict.overallRatio, result.staticDict.retrainCount, result.staticDict.postSwitch100Ratio, result.staticDict.description));
        sb.append(String.format("| **%s** | %,d B | **%.2fx** | %d | %.2fx | %s |\n",
                result.fixedSchedule.name, result.fixedSchedule.totalBytes, result.fixedSchedule.overallRatio, result.fixedSchedule.retrainCount, result.fixedSchedule.postSwitch100Ratio, result.fixedSchedule.description));
        sb.append(String.format("| **%s** | %,d B | **%.2fx** | %d | **%.2fx** | %s |\n",
                result.adaptive.name, result.adaptive.totalBytes, result.adaptive.overallRatio, result.adaptive.retrainCount, result.adaptive.postSwitch100Ratio, result.adaptive.description));
        sb.append("\n");

        sb.append(String.format("> **Adaptive Efficiency**: Adaptive zdict saved **%,d bytes (%.1f%% reduction)** compared to static dictionary compression.\n\n",
                result.totalStaticBytes - result.totalAdaptiveBytes, result.adaptiveSavingsVsStaticPercent));

        sb.append("## Window-by-Window Breakdown\n\n");
        sb.append("| Window | Payloads | Dominant Schema | gzip Ratio | Static Ratio | Fixed Ratio | Adaptive Ratio | Note |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");

        for (WindowStats ws : result.windows) {
            String note = "-";
            if (ws.activeShape.contains("Transition")) {
                note = "Schema drift begins";
            } else if ("Shape B".equals(ws.activeShape)) {
                if (ws.adaptiveRatio > ws.staticRatio * 1.5) {
                    note = "**Ratio recovered after retrain**";
                } else {
                    note = "Drift detected; accumulating fresh post-drift samples";
                }
            }
            sb.append(String.format("| #%d | %d - %d | %s | %.2fx | %.2fx | %.2fx | **%.2fx** | %s |\n",
                    ws.windowIndex + 1, ws.startPayload, ws.endPayload, ws.activeShape,
                    ws.gzipRatio, ws.staticRatio, ws.fixedRatio, ws.adaptiveRatio, note));
        }
        sb.append("\n");

        return sb.toString();
    }

    // =========================================================================
    // Synthetic Data Generator (labeled SYNTHETIC)
    // =========================================================================

    public static void generateSynthetic(Path dirA, Path dirB, int countA, int countB) throws IOException {
        Files.createDirectories(dirA);
        Files.createDirectories(dirB);

        Random rA = new Random(42);
        for (int i = 1; i <= countA; i++) {
            String json = String.format(
                    "{\"_dataset\":\"SYNTHETIC\",\"id\":%d,\"name\":\"Product-%d\",\"category\":\"%s\",\"price\":%.2f,\"inStock\":%b,\"rating\":%.1f,\"tags\":[\"%s\",\"%s\"],\"createdAt\":\"2026-03-%02d\",\"description\":\"High performance consumer merchandise item designed for reliability with standard warranty protection.\"}",
                    i, i, (i % 2 == 0 ? "electronics" : "appliances"), (i * 19.99) % 500 + 10.0, (i % 3 != 0), 3.5 + (i % 15) * 0.1,
                    (i % 2 == 0 ? "new" : "popular"), "sale", (i % 28) + 1
            );
            Files.writeString(dirA.resolve(String.format("%06d.json", i)), json, StandardCharsets.UTF_8);
        }

        Random rB = new Random(84);
        for (int i = 1; i <= countB; i++) {
            String json = String.format(
                    "{\"_dataset\":\"SYNTHETIC\",\"item_id\":%d,\"title\":\"Product-%d\",\"department\":\"%s\",\"pricing\":{\"amount\":%.2f,\"currency\":\"USD\",\"discount_eligible\":%b},\"inventory\":{\"available\":%b,\"fulfillment_hub\":\"WH-ZONE-%d\"},\"customer_score\":%.1f,\"labels\":[\"%s\",\"%s\"],\"registered_date\":\"2026-04-%02d\",\"specs\":{\"model_series\":\"SERIES-X\",\"extended_warranty\":true}}",
                    i, i, (i % 2 == 0 ? "electronics" : "appliances"), (i * 19.99) % 500 + 10.0, (i % 4 == 0), (i % 3 != 0), (i % 5) + 1,
                    3.5 + (i % 15) * 0.1, (i % 2 == 0 ? "new" : "popular"), "sale", (i % 28) + 1
            );
            Files.writeString(dirB.resolve(String.format("%06d.json", i)), json, StandardCharsets.UTF_8);
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
        System.out.printf("Shape Switch Point:         Payload #%d (Shape A: %d, Shape B: %d)%n",
                r.shapeSwitchPoint, r.countA, r.countB);
        if (r.payloadsElapsedToDetection >= 0) {
            System.out.printf("Drift Detection Point:      Payload #%d (%d payloads elapsed after shape switch)%n",
                    r.driftDetectedAt, r.payloadsElapsedToDetection);
        } else {
            System.out.println("Drift Detection Point:      Not detected");
        }
        System.out.printf("Dictionary Switch Points:   %s (trained on ONLY %d fresh post-drift samples)%n",
                r.driftRetrainPayloadIndices, r.driftEvalWindow);
        System.out.printf("Evaluation Window:          %d payloads | Threshold: < %.2f * baseline%n",
                r.driftEvalWindow, r.driftThreshold);
        System.out.printf("Reporting Window:           %d payloads%n%n", r.reportingWindowSize);

        System.out.println("Per-Strategy Comparison:");
        System.out.printf("  %-25s %12s %11s %10s %22s%n", "Strategy", "Total Bytes", "Mean Ratio", "Retrains", "Post-Switch 100 Ratio");
        System.out.println("  -------------------------------------------------------------------------------------");
        printStrategyRow(r.gzip);
        printStrategyRow(r.staticDict);
        printStrategyRow(r.fixedSchedule);
        printStrategyRow(r.adaptive);
        System.out.println("=====================================================================================\n");
    }

    private static void printStrategyRow(StrategyResult s) {
        System.out.printf("  %-25s %,10d B %10.2fx %10d %21.2fx%n",
                s.name, s.totalBytes, s.overallRatio, s.retrainCount, s.postSwitch100Ratio);
    }
}
