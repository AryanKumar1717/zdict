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
 * comparing three strategies:
 * 1. gzip (level 6)
 * 2. static zstd+dict (trained once on shape A and never updated)
 * 3. adaptive zdict (re-implements the zdict drift rule: rolling mean &lt; baseline * 0.85
 *    over a window of 200 payloads, retrains on the most recent 200 payloads, and switches).
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
        public long adaptiveBytes;
        public double gzipRatio;
        public double staticRatio;
        public double adaptiveRatio;
    }

    public static class DriftResult {
        public String date;
        public String javaVersion;
        public int totalPayloads;
        public int countA;
        public int countB;
        public long totalOriginalBytes;
        public long totalGzipBytes;
        public long totalStaticBytes;
        public long totalAdaptiveBytes;
        public double overallGzipRatio;
        public double overallStaticRatio;
        public double overallAdaptiveRatio;
        public double adaptiveSavingsVsStaticPercent;
        public int driftRetrainCount;
        public List<Integer> driftRetrainPayloadIndices = new ArrayList<>();
        public List<WindowStats> windows = new ArrayList<>();
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
            // Default: generate or use synthetic data in target/synthetic-bench
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
        System.out.printf("Running DriftBench: %d total payloads (%d Shape A, %d Shape B)%n", totalPayloads, countA, countB);

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

        // Strategy 2: Static compressor
        ZstdDictCompress staticCompressor = new ZstdDictCompress(initialDictBytes, 3);

        // Strategy 3: Adaptive compressor
        ZstdDictCompress adaptiveCompressor = new ZstdDictCompress(initialDictBytes, 3);
        double activeBaseline = initialBaseline;
        List<Double> rollingWindow = new ArrayList<>(DRIFT_EVAL_WINDOW);
        List<byte[]> recentPayloads = new ArrayList<>(DRIFT_EVAL_WINDOW);

        DriftResult result = new DriftResult();
        result.date = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC));
        result.javaVersion = System.getProperty("java.version");
        result.totalPayloads = totalPayloads;
        result.countA = countA;
        result.countB = countB;

        // Process stream payload by payload
        long currentWindowOrig = 0;
        long currentWindowGzip = 0;
        long currentWindowStatic = 0;
        long currentWindowAdaptive = 0;
        int windowIdx = 0;

        for (int i = 0; i < totalPayloads; i++) {
            byte[] payload = stream.get(i);
            int origLen = payload.length;
            result.totalOriginalBytes += origLen;
            currentWindowOrig += origLen;

            // Strategy 1: gzip
            byte[] gz = ReplayBench.compressGzip(payload, 6);
            result.totalGzipBytes += gz.length;
            currentWindowGzip += gz.length;

            // Strategy 2: static zstd+dict
            byte[] stComp = Zstd.compress(payload, staticCompressor);
            result.totalStaticBytes += stComp.length;
            currentWindowStatic += stComp.length;

            // Strategy 3: adaptive zstd+dict
            byte[] adComp = Zstd.compress(payload, adaptiveCompressor);
            result.totalAdaptiveBytes += adComp.length;
            currentWindowAdaptive += adComp.length;

            // Track ratio and payload history for adaptive drift detection
            double ratio = (double) origLen / adComp.length;
            if (rollingWindow.size() >= DRIFT_EVAL_WINDOW) {
                rollingWindow.remove(0);
            }
            rollingWindow.add(ratio);

            if (recentPayloads.size() >= DRIFT_EVAL_WINDOW) {
                recentPayloads.remove(0);
            }
            recentPayloads.add(payload);

            // Drift detection rule:
            // When window has at least 200 samples and rolling mean < activeBaseline * 0.85
            if (rollingWindow.size() >= DRIFT_EVAL_WINDOW) {
                double rollingMean = rollingWindow.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                if (rollingMean < activeBaseline * DRIFT_THRESHOLD) {
                    // Drift detected! Retrain on recent payloads
                    byte[] newDictBytes = trainDict(recentPayloads);
                    adaptiveCompressor.close();
                    adaptiveCompressor = new ZstdDictCompress(newDictBytes, 3);

                    // Re-measure baseline on the recent payloads
                    long rOrig = 0;
                    long rComp = 0;
                    for (byte[] s : recentPayloads) {
                        rOrig += s.length;
                        rComp += Zstd.compress(s, adaptiveCompressor).length;
                    }
                    activeBaseline = rComp > 0 ? (double) rOrig / rComp : 1.0;

                    rollingWindow.clear(); // Reset window after retrain
                    result.driftRetrainCount++;
                    result.driftRetrainPayloadIndices.add(i);
                    System.out.printf("Drift detected at payload %d (rolling mean %.2fx < threshold %.2fx). Retrained new dictionary (new baseline: %.2fx)%n",
                            i, rollingMean, activeBaseline * DRIFT_THRESHOLD, activeBaseline);
                }
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
                ws.adaptiveBytes = currentWindowAdaptive;

                ws.gzipRatio = currentWindowGzip > 0 ? (double) currentWindowOrig / currentWindowGzip : 0.0;
                ws.staticRatio = currentWindowStatic > 0 ? (double) currentWindowOrig / currentWindowStatic : 0.0;
                ws.adaptiveRatio = currentWindowAdaptive > 0 ? (double) currentWindowOrig / currentWindowAdaptive : 0.0;

                result.windows.add(ws);

                currentWindowOrig = 0;
                currentWindowGzip = 0;
                currentWindowStatic = 0;
                currentWindowAdaptive = 0;
            }
        }

        staticCompressor.close();
        adaptiveCompressor.close();

        result.overallGzipRatio = result.totalGzipBytes > 0 ? (double) result.totalOriginalBytes / result.totalGzipBytes : 0.0;
        result.overallStaticRatio = result.totalStaticBytes > 0 ? (double) result.totalOriginalBytes / result.totalStaticBytes : 0.0;
        result.overallAdaptiveRatio = result.totalAdaptiveBytes > 0 ? (double) result.totalOriginalBytes / result.totalAdaptiveBytes : 0.0;
        result.adaptiveSavingsVsStaticPercent = result.totalStaticBytes > 0 ?
                (1.0 - (double) result.totalAdaptiveBytes / result.totalStaticBytes) * 100.0 : 0.0;

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
        int width = 860;
        int height = 460;
        int chartLeft = 70;
        int chartRight = width - 40;
        int chartTop = 80;
        int chartBottom = 380;
        int chartWidth = chartRight - chartLeft;
        int chartHeight = chartBottom - chartTop;

        double maxRatio = 1.0;
        for (WindowStats ws : result.windows) {
            maxRatio = Math.max(maxRatio, ws.gzipRatio);
            maxRatio = Math.max(maxRatio, ws.staticRatio);
            maxRatio = Math.max(maxRatio, ws.adaptiveRatio);
        }
        maxRatio = Math.ceil(maxRatio * 1.15); // headroom

        StringBuilder svg = new StringBuilder();
        svg.append(String.format("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\">\n",
                width, height, width, height));
        // White background for light and dark mode compatibility
        svg.append("  <rect width=\"100%\" height=\"100%\" fill=\"#ffffff\" rx=\"8\"/>\n");
        svg.append("  <rect width=\"100%\" height=\"100%\" fill=\"none\" stroke=\"#e2e8f0\" stroke-width=\"1\" rx=\"8\"/>\n");

        // Title and Subtitle
        svg.append("  <text x=\"30\" y=\"35\" font-family=\"system-ui, -apple-system, sans-serif\" font-size=\"18\" font-weight=\"bold\" fill=\"#1a202c\">API Schema Drift: Static vs Adaptive zdict</text>\n");
        svg.append("  <text x=\"30\" y=\"55\" font-family=\"system-ui, -apple-system, sans-serif\" font-size=\"12\" fill=\"#718096\">Traffic: 60% Shape A → 40% Shape B | Rolling 100-payload evaluation window</text>\n");

        // Legend
        int legX = width - 410;
        drawLegendItem(svg, legX, 30, "#718096", "gzip (lvl 6)");
        drawLegendItem(svg, legX + 105, 30, "#e53e3e", "static zstd+dict");
        drawLegendItem(svg, legX + 230, 30, "#38a169", "adaptive zdict");

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

        // Schema transition vertical line (at 60% of total payloads)
        double transitionPct = (double) result.countA / result.totalPayloads;
        int transX = chartLeft + (int) (transitionPct * chartWidth);
        svg.append(String.format("  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#cbd5e0\" stroke-width=\"2\" stroke-dasharray=\"4,4\"/>\n",
                transX, chartTop - 10, transX, chartBottom));
        svg.append(String.format("  <text x=\"%d\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"11\" font-weight=\"600\" fill=\"#e53e3e\" text-anchor=\"middle\">Schema Drift (A → B)</text>\n",
                transX, chartTop - 15));

        // Coordinates for the 3 polylines
        List<int[]> gzipPts = new ArrayList<>();
        List<int[]> staticPts = new ArrayList<>();
        List<int[]> adaptivePts = new ArrayList<>();

        int numWindows = result.windows.size();
        for (int i = 0; i < numWindows; i++) {
            WindowStats ws = result.windows.get(i);
            int x = chartLeft + (int) (((double) i / (numWindows - 1)) * chartWidth);

            int yGz = chartBottom - (int) ((ws.gzipRatio / maxRatio) * chartHeight);
            int ySt = chartBottom - (int) ((ws.staticRatio / maxRatio) * chartHeight);
            int yAd = chartBottom - (int) ((ws.adaptiveRatio / maxRatio) * chartHeight);

            gzipPts.add(new int[]{x, yGz});
            staticPts.add(new int[]{x, ySt});
            adaptivePts.add(new int[]{x, yAd});

            // X-axis tick & label
            svg.append(String.format("  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#cbd5e0\" stroke-width=\"1\"/>\n",
                    x, chartBottom, x, chartBottom + 5));
            svg.append(String.format("  <text x=\"%d\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"11\" fill=\"#718096\" text-anchor=\"middle\">%d</text>\n",
                    x, chartBottom + 20, ws.endPayload));
        }

        // Draw polylines
        drawPolyline(svg, gzipPts, "#718096", 2);
        drawPolyline(svg, staticPts, "#e53e3e", 2.5);
        drawPolyline(svg, adaptivePts, "#38a169", 2.5);

        // Draw data points
        for (int[] pt : gzipPts) {
            svg.append(String.format("  <circle cx=\"%d\" cy=\"%d\" r=\"3.5\" fill=\"#718096\"/>\n", pt[0], pt[1]));
        }
        for (int[] pt : staticPts) {
            svg.append(String.format("  <rect x=\"%d\" y=\"%d\" width=\"6\" height=\"6\" fill=\"#e53e3e\"/>\n", pt[0] - 3, pt[1] - 3));
        }
        for (int[] pt : adaptivePts) {
            svg.append(String.format("  <circle cx=\"%d\" cy=\"%d\" r=\"4\" fill=\"#38a169\" stroke=\"#ffffff\" stroke-width=\"1.5\"/>\n", pt[0], pt[1]));
        }

        // X-axis title
        svg.append(String.format("  <text x=\"%d\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"12\" fill=\"#718096\" text-anchor=\"middle\">Payload Sequence (100 per window)</text>\n",
                chartLeft + chartWidth / 2, chartBottom + 40));

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
        sb.append("# zdict Drift Scenario Benchmark\n\n");
        sb.append("- **Date**: `").append(result.date).append("`\n");
        sb.append("- **Traffic Stream**: **").append(result.totalPayloads).append(" payloads** total (")
                .append(result.countA).append(" Shape A, ").append(result.countB).append(" Shape B)\n");
        sb.append("- **Drift Retrains Triggered**: **").append(result.driftRetrainCount).append("**\n\n");

        sb.append("## Overall Comparison\n\n");
        sb.append("| Strategy | Total Compressed Size | Overall Ratio | Savings vs gzip | Description |\n");
        sb.append("|---|---|---|---|---|\n");
        sb.append(String.format("| **gzip (level 6)** | %,d B | **%.2fx** | baseline | Standard gzip compression |\n",
                result.totalGzipBytes, result.overallGzipRatio));
        sb.append(String.format("| **Static zstd+dict** | %,d B | **%.2fx** | %.1f%% | Trained on Shape A, never updated (suffers on Shape B) |\n",
                result.totalStaticBytes, result.overallStaticRatio,
                (1.0 - (double) result.totalStaticBytes / result.totalGzipBytes) * 100.0));
        sb.append(String.format("| **Adaptive zdict** | %,d B | **%.2fx** | **%.1f%%** | Self-learning: detects drift, retrains on fresh samples, recovers ratio |\n",
                result.totalAdaptiveBytes, result.overallAdaptiveRatio,
                (1.0 - (double) result.totalAdaptiveBytes / result.totalGzipBytes) * 100.0));
        sb.append("\n");

        sb.append(String.format("> Adaptive zdict saved **%,d bytes (%.1f%%)** compared to static dictionary compression after schema drift.\n\n",
                result.totalStaticBytes - result.totalAdaptiveBytes, result.adaptiveSavingsVsStaticPercent));

        sb.append("## Window-by-Window Breakdown\n\n");
        sb.append("| Window | Payloads | Dominant Schema | gzip Ratio | Static Ratio | Adaptive Ratio | Note |\n");
        sb.append("|---|---|---|---|---|---|---|\n");

        for (WindowStats ws : result.windows) {
            String note = "-";
            if (ws.activeShape.contains("Transition")) {
                note = "Schema drift begins";
            } else if ("Shape B".equals(ws.activeShape)) {
                if (ws.adaptiveRatio > ws.staticRatio * 1.5) {
                    note = "**Ratio recovered after retrain**";
                } else {
                    note = "Drift detected; accumulating fresh samples";
                }
            }
            sb.append(String.format("| #%d | %d - %d | %s | %.2fx | %.2fx | **%.2fx** | %s |\n",
                    ws.windowIndex + 1, ws.startPayload, ws.endPayload, ws.activeShape,
                    ws.gzipRatio, ws.staticRatio, ws.adaptiveRatio, note));
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
        System.out.println("\n=== Drift Benchmark Summary ===");
        System.out.printf("Total Payloads:       %,d (Shape A: %d, Shape B: %d)%n", r.totalPayloads, r.countA, r.countB);
        System.out.printf("Drift Retrains:       %d at payload(s): %s%n", r.driftRetrainCount, r.driftRetrainPayloadIndices);
        System.out.printf("  gzip (level 6):     %,d B | %.2fx ratio%n", r.totalGzipBytes, r.overallGzipRatio);
        System.out.printf("  static zstd+dict:   %,d B | %.2fx ratio%n", r.totalStaticBytes, r.overallStaticRatio);
        System.out.printf("  adaptive zdict:     %,d B | %.2fx ratio%n", r.totalAdaptiveBytes, r.overallAdaptiveRatio);
        System.out.printf("  adaptive savings:   %,d B saved vs static (%.1f%% improvement)%n",
                r.totalStaticBytes - r.totalAdaptiveBytes, r.adaptiveSavingsVsStaticPercent);
        System.out.println("================================\n");
    }
}
