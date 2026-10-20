package dev.zdict;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import com.github.luben.zstd.ZstdDictTrainer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Replays captured response payloads in strict time order (2/3 train, 1/3 test),
 * compares gzip (level 6), zstd (level 3), and zstd+dict (16 KB),
 * verifies round trip decompression, and writes reports and SVG charts.
 */
public class ReplayBench {

    public static final int DEFAULT_MIN_PAYLOADS = 100;
    public static final int DICT_TARGET_SIZE = 16 * 1024;
    public static final int WARMUP_ROUNDS = 5;

    private static final ObjectMapper jsonMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // =========================================================================
    // Data Models
    // =========================================================================

    public static class CodecResult {
        public String name;
        public long compressedBytes;
        public double ratio;
        public double savingsPercent;
        public double p50Micros;
        public double p99Micros;

        public CodecResult() {}

        public CodecResult(String name, long originalBytes, long compressedBytes, double p50, double p99) {
            this.name = name;
            this.compressedBytes = compressedBytes;
            this.ratio = compressedBytes > 0 ? (double) originalBytes / compressedBytes : 0.0;
            this.savingsPercent = originalBytes > 0 ? (1.0 - (double) compressedBytes / originalBytes) * 100.0 : 0.0;
            this.p50Micros = p50;
            this.p99Micros = p99;
        }
    }

    public static class BucketStats {
        public String bucketName;
        public int count;
        public long originalBytes;
        public double gzipRatio;
        public double zstdRatio;
        public double zstdDictRatio;
    }

    public static class GroupResult {
        public String group;
        public int totalPayloads;
        public int trainPayloads;
        public int testPayloads;
        public long originalBytes;
        public int dictionarySizeBytes;
        public CodecResult gzip;
        public CodecResult zstd;
        public CodecResult zstdDict;
        public List<BucketStats> buckets = new ArrayList<>();
        public Double avgBytesSavedVsGzip;
        public Integer breakEvenPayloads;
    }

    public static class SuiteResult {
        public String date;
        public String javaVersion;
        public String os;
        public String timingNotice = "Timing is a simple loop after JVM warmup, not JMH.";
        public List<GroupResult> groups = new ArrayList<>();
        public GroupResult overall;
    }

    // =========================================================================
    // Main Entry Point
    // =========================================================================

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java dev.zdict.ReplayBench <samples-dir> [output-dir]");
            System.exit(1);
        }

        Path samplesDir = Path.of(args[0]);
        Path outputDir = args.length >= 2 ? Path.of(args[1]) : Path.of("benchmark-results");

        SuiteResult result = run(samplesDir, outputDir, DEFAULT_MIN_PAYLOADS);
        printConsoleSummary(result);
    }

    public static SuiteResult run(Path samplesDir, Path outputDir, int minPayloads) throws Exception {
        if (!Files.isDirectory(samplesDir)) {
            throw new IllegalArgumentException("Samples directory not found: " + samplesDir);
        }

        Files.createDirectories(outputDir);

        SuiteResult suite = new SuiteResult();
        suite.date = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC));
        suite.javaVersion = System.getProperty("java.version");
        suite.os = System.getProperty("os.name") + " " + System.getProperty("os.arch");

        List<Path> groupDirs;
        try (Stream<Path> stream = Files.list(samplesDir)) {
            groupDirs = stream.filter(Files::isDirectory).sorted().toList();
        }

        for (Path gDir : groupDirs) {
            String groupName = gDir.getFileName().toString();
            List<byte[]> payloads = loadPayloads(gDir);

            if (payloads.size() < minPayloads) {
                System.out.printf("Skipping group '%s': %d payloads < required minimum %d%n",
                        groupName, payloads.size(), minPayloads);
                continue;
            }

            GroupResult gr = evaluateGroup(groupName, payloads);
            suite.groups.add(gr);
        }

        if (suite.groups.isEmpty()) {
            System.out.println("No groups met the minimum payload threshold (" + minPayloads + ").");
            return suite;
        }

        // Compute overall summary across all processed groups
        suite.overall = computeOverall(suite.groups);

        // 1. Write benchmark-results/results.json
        Path jsonPath = outputDir.resolve("results.json");
        jsonMapper.writeValue(jsonPath.toFile(), suite);
        System.out.println("Wrote JSON results to: " + jsonPath.toAbsolutePath());

        // 2. Write benchmark-results/results.md
        Path mdPath = outputDir.resolve("results.md");
        String mdContent = generateMarkdown(suite);
        Files.writeString(mdPath, mdContent, StandardCharsets.UTF_8);
        System.out.println("Wrote Markdown report to: " + mdPath.toAbsolutePath());

        // 3. Write benchmark-results/ratio.svg
        Path ratioSvgPath = outputDir.resolve("ratio.svg");
        String ratioSvg = generateRatioSvg(suite);
        Files.writeString(ratioSvgPath, ratioSvg, StandardCharsets.UTF_8);
        System.out.println("Wrote ratio chart to: " + ratioSvgPath.toAbsolutePath());

        // 4. Write benchmark-results/latency.svg
        Path latencySvgPath = outputDir.resolve("latency.svg");
        String latencySvg = generateLatencySvg(suite);
        Files.writeString(latencySvgPath, latencySvg, StandardCharsets.UTF_8);
        System.out.println("Wrote latency chart to: " + latencySvgPath.toAbsolutePath());

        return suite;
    }

    // =========================================================================
    // Core Benchmark Logic per Group
    // =========================================================================

    public static GroupResult evaluateGroup(String groupName, List<byte[]> payloads) throws Exception {
        int totalCount = payloads.size();
        int trainCount = (totalCount * 2) / 3;
        int testCount = totalCount - trainCount;

        List<byte[]> trainSet = payloads.subList(0, trainCount);
        List<byte[]> testSet = payloads.subList(trainCount, totalCount);

        long totalOrigBytes = testSet.stream().mapToLong(p -> p.length).sum();

        // 1. Train 16 KB dictionary on trainSet (first 2/3)
        long trainBytesSum = trainSet.stream().mapToLong(p -> p.length).sum();
        ZstdDictTrainer trainer = new ZstdDictTrainer((int) (trainBytesSum + 1024), DICT_TARGET_SIZE);
        for (byte[] s : trainSet) {
            trainer.addSample(s);
        }
        byte[] dictBytes = trainer.trainSamples();
        if (dictBytes == null || dictBytes.length == 0) {
            throw new IllegalStateException("Dictionary training failed for group: " + groupName);
        }

        ZstdDictCompress cdict = new ZstdDictCompress(dictBytes, 3);
        ZstdDictDecompress ddict = new ZstdDictDecompress(dictBytes);

        // 2. Verify round trip and calculate compressed sizes
        long gzipBytes = 0;
        long zstdBytes = 0;
        long zstdDictBytes = 0;

        for (byte[] orig : testSet) {
            // Gzip (level 6)
            byte[] gzComp = compressGzip(orig, 6);
            byte[] gzDecomp = decompressGzip(gzComp);
            if (!Arrays.equals(orig, gzDecomp)) {
                throw new IllegalStateException("Gzip round-trip decompression failure in group " + groupName);
            }
            gzipBytes += gzComp.length;

            // Zstd (level 3)
            byte[] zstdComp = Zstd.compress(orig, 3);
            long zstdDecompSize = Zstd.decompressedSize(zstdComp);
            byte[] zstdDecomp = Zstd.decompress(zstdComp, (int) zstdDecompSize);
            if (!Arrays.equals(orig, zstdDecomp)) {
                throw new IllegalStateException("Zstd round-trip decompression failure in group " + groupName);
            }
            zstdBytes += zstdComp.length;

            // Zstd+Dict (level 3)
            byte[] zdComp = Zstd.compress(orig, cdict);
            long zdDecompSize = Zstd.decompressedSize(zdComp);
            byte[] zdDecomp = Zstd.decompress(zdComp, ddict, (int) zdDecompSize);
            if (!Arrays.equals(orig, zdDecomp)) {
                throw new IllegalStateException("Zstd+dict round-trip decompression failure in group " + groupName);
            }
            zstdDictBytes += zdComp.length;
        }

        // 3. Warm-up pass before timing
        for (int w = 0; w < WARMUP_ROUNDS; w++) {
            for (byte[] orig : testSet) {
                compressGzip(orig, 6);
                Zstd.compress(orig, 3);
                Zstd.compress(orig, cdict);
            }
        }

        // 4. Measure per-payload compression time in microseconds
        List<Double> gzipTimes = new ArrayList<>(testCount);
        List<Double> zstdTimes = new ArrayList<>(testCount);
        List<Double> zdTimes = new ArrayList<>(testCount);

        for (byte[] orig : testSet) {
            long t0 = System.nanoTime();
            compressGzip(orig, 6);
            gzipTimes.add((System.nanoTime() - t0) / 1000.0);

            long t1 = System.nanoTime();
            Zstd.compress(orig, 3);
            zstdTimes.add((System.nanoTime() - t1) / 1000.0);

            long t2 = System.nanoTime();
            Zstd.compress(orig, cdict);
            zdTimes.add((System.nanoTime() - t2) / 1000.0);
        }

        Collections.sort(gzipTimes);
        Collections.sort(zstdTimes);
        Collections.sort(zdTimes);

        double gzP50 = percentile(gzipTimes, 0.50);
        double gzP99 = percentile(gzipTimes, 0.99);

        double zstdP50 = percentile(zstdTimes, 0.50);
        double zstdP99 = percentile(zstdTimes, 0.99);

        double zdP50 = percentile(zdTimes, 0.50);
        double zdP99 = percentile(zdTimes, 0.99);

        cdict.close();
        ddict.close();

        GroupResult gr = new GroupResult();
        gr.group = groupName;
        gr.totalPayloads = totalCount;
        gr.trainPayloads = trainCount;
        gr.testPayloads = testCount;
        gr.originalBytes = totalOrigBytes;
        gr.dictionarySizeBytes = dictBytes.length;

        gr.gzip = new CodecResult("gzip (level 6)", totalOrigBytes, gzipBytes, gzP50, gzP99);
        gr.zstd = new CodecResult("zstd (level 3)", totalOrigBytes, zstdBytes, zstdP50, zstdP99);
        gr.zstdDict = new CodecResult("zstd+dict (level 3)", totalOrigBytes, zstdDictBytes, zdP50, zdP99);

        // 5. Size buckets (<1KB, 1-4KB, >4KB)
        gr.buckets = computeBuckets(testSet, dictBytes);

        // 6. Break-even vs gzip
        long totalSavedVsGzip = gzipBytes - zstdDictBytes;
        if (testCount > 0 && totalSavedVsGzip > 0) {
            double avgSaved = (double) totalSavedVsGzip / testCount;
            gr.avgBytesSavedVsGzip = avgSaved;
            gr.breakEvenPayloads = (int) Math.ceil((double) dictBytes.length / avgSaved);
        } else {
            gr.avgBytesSavedVsGzip = 0.0;
            gr.breakEvenPayloads = null;
        }

        return gr;
    }

    private static List<BucketStats> computeBuckets(List<byte[]> testSet, byte[] dictBytes) throws Exception {
        ZstdDictCompress cdict = new ZstdDictCompress(dictBytes, 3);
        try {
            Map<String, List<byte[]>> bucketMap = new LinkedHashMap<>();
            bucketMap.put("<1KB", new ArrayList<>());
            bucketMap.put("1-4KB", new ArrayList<>());
            bucketMap.put(">4KB", new ArrayList<>());

            for (byte[] p : testSet) {
                if (p.length < 1024) {
                    bucketMap.get("<1KB").add(p);
                } else if (p.length <= 4096) {
                    bucketMap.get("1-4KB").add(p);
                } else {
                    bucketMap.get(">4KB").add(p);
                }
            }

            List<BucketStats> list = new ArrayList<>();
            for (Map.Entry<String, List<byte[]>> entry : bucketMap.entrySet()) {
                BucketStats bs = new BucketStats();
                bs.bucketName = entry.getKey();
                List<byte[]> samples = entry.getValue();
                bs.count = samples.size();
                if (samples.isEmpty()) {
                    list.add(bs);
                    continue;
                }

                long origSum = 0;
                long gzSum = 0;
                long zstdSum = 0;
                long zdSum = 0;

                for (byte[] s : samples) {
                    origSum += s.length;
                    gzSum += compressGzip(s, 6).length;
                    zstdSum += Zstd.compress(s, 3).length;
                    zdSum += Zstd.compress(s, cdict).length;
                }

                bs.originalBytes = origSum;
                bs.gzipRatio = gzSum > 0 ? (double) origSum / gzSum : 0.0;
                bs.zstdRatio = zstdSum > 0 ? (double) origSum / zstdSum : 0.0;
                bs.zstdDictRatio = zdSum > 0 ? (double) origSum / zdSum : 0.0;
                list.add(bs);
            }
            return list;
        } finally {
            cdict.close();
        }
    }

    private static GroupResult computeOverall(List<GroupResult> groups) {
        GroupResult o = new GroupResult();
        o.group = "ALL_GROUPS_TOTAL";
        for (GroupResult g : groups) {
            o.totalPayloads += g.totalPayloads;
            o.trainPayloads += g.trainPayloads;
            o.testPayloads += g.testPayloads;
            o.originalBytes += g.originalBytes;
            o.dictionarySizeBytes += g.dictionarySizeBytes;
        }

        long totalGz = groups.stream().mapToLong(g -> g.gzip.compressedBytes).sum();
        long totalZstd = groups.stream().mapToLong(g -> g.zstd.compressedBytes).sum();
        long totalZd = groups.stream().mapToLong(g -> g.zstdDict.compressedBytes).sum();

        double gzP50Avg = groups.stream().mapToDouble(g -> g.gzip.p50Micros).average().orElse(0.0);
        double gzP99Avg = groups.stream().mapToDouble(g -> g.gzip.p99Micros).average().orElse(0.0);

        double zstdP50Avg = groups.stream().mapToDouble(g -> g.zstd.p50Micros).average().orElse(0.0);
        double zstdP99Avg = groups.stream().mapToDouble(g -> g.zstd.p99Micros).average().orElse(0.0);

        double zdP50Avg = groups.stream().mapToDouble(g -> g.zstdDict.p50Micros).average().orElse(0.0);
        double zdP99Avg = groups.stream().mapToDouble(g -> g.zstdDict.p99Micros).average().orElse(0.0);

        o.gzip = new CodecResult("gzip (level 6)", o.originalBytes, totalGz, gzP50Avg, gzP99Avg);
        o.zstd = new CodecResult("zstd (level 3)", o.originalBytes, totalZstd, zstdP50Avg, zstdP99Avg);
        o.zstdDict = new CodecResult("zstd+dict (level 3)", o.originalBytes, totalZd, zdP50Avg, zdP99Avg);

        long saved = totalGz - totalZd;
        if (o.testPayloads > 0 && saved > 0) {
            o.avgBytesSavedVsGzip = (double) saved / o.testPayloads;
            o.breakEvenPayloads = (int) Math.ceil((double) o.dictionarySizeBytes / o.avgBytesSavedVsGzip);
        } else {
            o.avgBytesSavedVsGzip = 0.0;
            o.breakEvenPayloads = null;
        }
        return o;
    }

    // =========================================================================
    // Markdown Generation
    // =========================================================================

    public static String generateMarkdown(SuiteResult suite) {
        StringBuilder sb = new StringBuilder();
        sb.append("# zdict Replay Benchmark Results\n\n");
        sb.append("- **Date**: `").append(suite.date).append("`\n");
        sb.append("- **Java Version**: `").append(suite.javaVersion).append("`\n");
        sb.append("- **OS**: `").append(suite.os).append("`\n");
        sb.append("- **Note**: *").append(suite.timingNotice).append("*\n\n");

        sb.append("## Overall Summary\n\n");
        if (suite.overall != null) {
            appendGroupMarkdown(sb, suite.overall);
        }

        sb.append("## Per-Group Results\n\n");
        for (GroupResult gr : suite.groups) {
            sb.append("### Group: `").append(gr.group).append("`\n\n");
            appendGroupMarkdown(sb, gr);
        }

        return sb.toString();
    }

    private static void appendGroupMarkdown(StringBuilder sb, GroupResult gr) {
        sb.append(String.format("- Total Payloads: **%d** (Train: %d, Test: %d)\n",
                gr.totalPayloads, gr.trainPayloads, gr.testPayloads));
        sb.append(String.format("- Uncompressed Test Bytes: **%,d B**\n", gr.originalBytes));
        if (gr.dictionarySizeBytes > 0) {
            sb.append(String.format("- Dictionary Size: **%,d B**\n", gr.dictionarySizeBytes));
        }
        if (gr.breakEvenPayloads != null) {
            sb.append(String.format("- Break-even vs gzip: **%d payloads** (avg savings: **%.1f B/payload**)\n",
                    gr.breakEvenPayloads, gr.avgBytesSavedVsGzip));
        }
        sb.append("\n");

        sb.append("| Codec | Compressed Size | Compression Ratio | Space Savings | p50 Latency (µs) | p99 Latency (µs) |\n");
        sb.append("|---|---|---|---|---|---|\n");
        appendCodecRow(sb, gr.gzip);
        appendCodecRow(sb, gr.zstd);
        appendCodecRow(sb, gr.zstdDict);
        sb.append("\n");

        if (!gr.buckets.isEmpty()) {
            sb.append("#### Ratio by Payload Size Bucket\n\n");
            sb.append("| Size Bucket | Payload Count | gzip Ratio | zstd Ratio | zstd+dict Ratio |\n");
            sb.append("|---|---|---|---|---|\n");
            for (BucketStats b : gr.buckets) {
                sb.append(String.format("| %s | %,d | %.2fx | %.2fx | **%.2fx** |\n",
                        b.bucketName, b.count, b.gzipRatio, b.zstdRatio, b.zstdDictRatio));
            }
            sb.append("\n");
        }
    }

    private static void appendCodecRow(StringBuilder sb, CodecResult c) {
        sb.append(String.format("| %s | %,d B | **%.2fx** | %.1f%% | %.1f µs | %.1f µs |\n",
                c.name, c.compressedBytes, c.ratio, c.savingsPercent, c.p50Micros, c.p99Micros));
    }

    // =========================================================================
    // SVG Chart Generation (Hand-crafted, self-contained XML)
    // =========================================================================

    public static String generateRatioSvg(SuiteResult suite) {
        List<GroupResult> list = new ArrayList<>(suite.groups);
        if (suite.overall != null && suite.groups.size() > 1) {
            list.add(suite.overall);
        }

        int groupCount = list.size();
        int width = Math.max(720, 160 + groupCount * 140);
        int height = 420;
        int chartTop = 80;
        int chartBottom = 340;
        int chartHeight = chartBottom - chartTop;

        double maxRatio = 1.0;
        for (GroupResult gr : list) {
            maxRatio = Math.max(maxRatio, gr.gzip.ratio);
            maxRatio = Math.max(maxRatio, gr.zstd.ratio);
            maxRatio = Math.max(maxRatio, gr.zstdDict.ratio);
        }
        maxRatio = Math.ceil(maxRatio * 1.15); // headroom

        StringBuilder svg = new StringBuilder();
        svg.append(String.format("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\">\n",
                width, height, width, height));
        // White background for light & dark mode support
        svg.append(String.format("  <rect width=\"100%%\" height=\"100%%\" fill=\"#ffffff\" rx=\"8\"/>\n"));
        svg.append(String.format("  <rect width=\"100%%\" height=\"100%%\" fill=\"none\" stroke=\"#e2e8f0\" stroke-width=\"1\" rx=\"8\"/>\n"));

        // Title & subtitle
        svg.append("  <text x=\"30\" y=\"35\" font-family=\"system-ui, -apple-system, sans-serif\" font-size=\"18\" font-weight=\"bold\" fill=\"#1a202c\">Compression Ratio by Endpoint Group</text>\n");
        svg.append("  <text x=\"30\" y=\"55\" font-family=\"system-ui, -apple-system, sans-serif\" font-size=\"12\" fill=\"#718096\">Ratio = uncompressed / compressed (Higher is better)</text>\n");

        // Legend
        int legX = width - 340;
        drawLegendItem(svg, legX, 30, "#718096", "gzip (lvl 6)");
        drawLegendItem(svg, legX + 110, 30, "#3182ce", "zstd (lvl 3)");
        drawLegendItem(svg, legX + 215, 30, "#38a169", "zstd+dict (16KB)");

        // Y-axis gridlines
        int gridSteps = 5;
        for (int i = 0; i <= gridSteps; i++) {
            double val = (maxRatio / gridSteps) * i;
            int y = chartBottom - (int) ((val / maxRatio) * chartHeight);
            svg.append(String.format("  <line x1=\"60\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#edf2f7\" stroke-width=\"1\"/>\n",
                    y, width - 40, y));
            svg.append(String.format("  <text x=\"50\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"11\" fill=\"#a0aec0\" text-anchor=\"end\">%.1fx</text>\n",
                    y + 4, val));
        }

        // Draw grouped bars
        int chartLeft = 80;
        int availableWidth = width - chartLeft - 60;
        int groupSlot = availableWidth / groupCount;
        int barW = Math.min(26, (groupSlot - 20) / 3);

        for (int i = 0; i < groupCount; i++) {
            GroupResult gr = list.get(i);
            int gx = chartLeft + i * groupSlot + (groupSlot - (barW * 3 + 8)) / 2;

            drawBar(svg, gx, chartBottom, barW, chartHeight, gr.gzip.ratio, maxRatio, "#718096");
            drawBar(svg, gx + barW + 4, chartBottom, barW, chartHeight, gr.zstd.ratio, maxRatio, "#3182ce");
            drawBar(svg, gx + (barW + 4) * 2, chartBottom, barW, chartHeight, gr.zstdDict.ratio, maxRatio, "#38a169");

            String label = gr.group.length() > 14 ? gr.group.substring(0, 12) + ".." : gr.group;
            svg.append(String.format("  <text x=\"%d\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"11\" font-weight=\"500\" fill=\"#4a5568\" text-anchor=\"middle\">%s</text>\n",
                    gx + (barW * 3 + 8) / 2, chartBottom + 20, label));
        }

        svg.append("</svg>\n");
        return svg.toString();
    }

    public static String generateLatencySvg(SuiteResult suite) {
        List<GroupResult> list = new ArrayList<>(suite.groups);
        if (suite.overall != null && suite.groups.size() > 1) {
            list.add(suite.overall);
        }

        int groupCount = list.size();
        int width = Math.max(720, 160 + groupCount * 140);
        int height = 420;
        int chartTop = 80;
        int chartBottom = 340;
        int chartHeight = chartBottom - chartTop;

        double maxLatency = 10.0;
        for (GroupResult gr : list) {
            maxLatency = Math.max(maxLatency, gr.gzip.p50Micros);
            maxLatency = Math.max(maxLatency, gr.zstd.p50Micros);
            maxLatency = Math.max(maxLatency, gr.zstdDict.p50Micros);
        }
        maxLatency = Math.ceil(maxLatency * 1.25); // headroom

        StringBuilder svg = new StringBuilder();
        svg.append(String.format("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\">\n",
                width, height, width, height));
        // White background for light & dark mode support
        svg.append("  <rect width=\"100%\" height=\"100%\" fill=\"#ffffff\" rx=\"8\"/>\n");
        svg.append("  <rect width=\"100%\" height=\"100%\" fill=\"none\" stroke=\"#e2e8f0\" stroke-width=\"1\" rx=\"8\"/>\n");

        // Title & subtitle
        svg.append("  <text x=\"30\" y=\"35\" font-family=\"system-ui, -apple-system, sans-serif\" font-size=\"18\" font-weight=\"bold\" fill=\"#1a202c\">Compression Latency p50 (µs) by Group</text>\n");
        svg.append("  <text x=\"30\" y=\"55\" font-family=\"system-ui, -apple-system, sans-serif\" font-size=\"12\" fill=\"#718096\">Simple loop per payload in microseconds (Lower is better)</text>\n");

        // Legend
        int legX = width - 340;
        drawLegendItem(svg, legX, 30, "#718096", "gzip (lvl 6)");
        drawLegendItem(svg, legX + 110, 30, "#3182ce", "zstd (lvl 3)");
        drawLegendItem(svg, legX + 215, 30, "#38a169", "zstd+dict (16KB)");

        // Y-axis gridlines
        int gridSteps = 5;
        for (int i = 0; i <= gridSteps; i++) {
            double val = (maxLatency / gridSteps) * i;
            int y = chartBottom - (int) ((val / maxLatency) * chartHeight);
            svg.append(String.format("  <line x1=\"60\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#edf2f7\" stroke-width=\"1\"/>\n",
                    y, width - 40, y));
            svg.append(String.format("  <text x=\"50\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"11\" fill=\"#a0aec0\" text-anchor=\"end\">%.0f µs</text>\n",
                    y + 4, val));
        }

        // Draw grouped bars
        int chartLeft = 80;
        int availableWidth = width - chartLeft - 60;
        int groupSlot = availableWidth / groupCount;
        int barW = Math.min(26, (groupSlot - 20) / 3);

        for (int i = 0; i < groupCount; i++) {
            GroupResult gr = list.get(i);
            int gx = chartLeft + i * groupSlot + (groupSlot - (barW * 3 + 8)) / 2;

            drawBar(svg, gx, chartBottom, barW, chartHeight, gr.gzip.p50Micros, maxLatency, "#718096");
            drawBar(svg, gx + barW + 4, chartBottom, barW, chartHeight, gr.zstd.p50Micros, maxLatency, "#3182ce");
            drawBar(svg, gx + (barW + 4) * 2, chartBottom, barW, chartHeight, gr.zstdDict.p50Micros, maxLatency, "#38a169");

            String label = gr.group.length() > 14 ? gr.group.substring(0, 12) + ".." : gr.group;
            svg.append(String.format("  <text x=\"%d\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"11\" font-weight=\"500\" fill=\"#4a5568\" text-anchor=\"middle\">%s</text>\n",
                    gx + (barW * 3 + 8) / 2, chartBottom + 20, label));
        }

        svg.append("</svg>\n");
        return svg.toString();
    }

    private static void drawLegendItem(StringBuilder svg, int x, int y, String color, String text) {
        svg.append(String.format("  <rect x=\"%d\" y=\"%d\" width=\"12\" height=\"12\" rx=\"2\" fill=\"%s\"/>\n", x, y, color));
        svg.append(String.format("  <text x=\"%d\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"11\" fill=\"#4a5568\">%s</text>\n",
                x + 18, y + 10, text));
    }

    private static void drawBar(StringBuilder svg, int x, int bottom, int w, int maxH, double val, double maxVal, String color) {
        int barH = (int) Math.round((val / maxVal) * maxH);
        barH = Math.max(2, Math.min(maxH, barH));
        int y = bottom - barH;

        svg.append(String.format("  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"3\" fill=\"%s\"/>\n",
                x, y, w, barH, color));
        String valStr = (val >= 10.0) ? String.format("%.1f", val) : String.format("%.2f", val);
        svg.append(String.format("  <text x=\"%d\" y=\"%d\" font-family=\"system-ui, sans-serif\" font-size=\"10\" font-weight=\"600\" fill=\"#2d3748\" text-anchor=\"middle\">%s</text>\n",
                x + w / 2, y - 4, valStr));
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    public static byte[] compressGzip(byte[] input, int level) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos) {
            {
                def.setLevel(level);
            }
        }) {
            gzip.write(input);
        }
        return baos.toByteArray();
    }

    public static byte[] decompressGzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gzip.readAllBytes();
        }
    }

    private static double percentile(List<Double> sorted, double pct) {
        if (sorted.isEmpty()) return 0.0;
        int idx = (int) Math.round(pct * (sorted.size() - 1));
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }

    private static List<byte[]> loadPayloads(Path dir) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }

        List<byte[]> payloads = new ArrayList<>(files.size());
        for (Path f : files) {
            payloads.add(Files.readAllBytes(f));
        }
        return payloads;
    }

    private static void printConsoleSummary(SuiteResult suite) {
        System.out.println("\n=== Replay Benchmark Summary ===");
        System.out.println("Date: " + suite.date);
        for (GroupResult gr : suite.groups) {
            System.out.printf("Group: %-25s | Test: %,d payloads (%,d B)%n", gr.group, gr.testPayloads, gr.originalBytes);
            System.out.printf("  gzip (lvl 6):       %,10d B | %5.2fx ratio | p50: %6.1f µs%n",
                    gr.gzip.compressedBytes, gr.gzip.ratio, gr.gzip.p50Micros);
            System.out.printf("  zstd (lvl 3):       %,10d B | %5.2fx ratio | p50: %6.1f µs%n",
                    gr.zstd.compressedBytes, gr.zstd.ratio, gr.zstd.p50Micros);
            System.out.printf("  zstd+dict (16KB):   %,10d B | %5.2fx ratio | p50: %6.1f µs%n",
                    gr.zstdDict.compressedBytes, gr.zstdDict.ratio, gr.zstdDict.p50Micros);
            if (gr.breakEvenPayloads != null) {
                System.out.printf("  break-even vs gzip: %d payloads (%.1f B saved/payload)%n",
                        gr.breakEvenPayloads, gr.avgBytesSavedVsGzip);
            }
        }
        System.out.println("================================\n");
    }
}
