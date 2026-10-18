package dev.zdict;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import com.github.luben.zstd.ZstdDictTrainer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Day 1-2 experiment: does a trained zstd dictionary beat gzip / plain zstd on small JSON payloads?
 *
 * Run (synthetic data):   mvn -q compile exec:java
 * Run (your real data):   mvn -q compile exec:java -Dexec.args="path/to/folder"
 *                         (one JSON response per file)
 */
public class Bench {
    static final int DICT_SIZE = 16 * 1024;
    static final int LEVEL = 3;
    static final String[] BUCKETS = {"<1KB", "1-4KB", ">4KB"};

    record Codec(String name, Function<byte[], byte[]> compress) {}

    public static void main(String[] args) throws Exception {
        List<byte[]> all = args.length > 0 ? loadDir(Path.of(args[0])) : generate(3000);
        Collections.shuffle(all, new Random(42));

        // Train and test on DIFFERENT payloads, otherwise the numbers are inflated.
        int split = all.size() * 2 / 3;
        List<byte[]> train = all.subList(0, split);
        List<byte[]> test = all.subList(split, all.size());

        byte[] dict = trainDict(train);
        ZstdDictCompress cdict = new ZstdDictCompress(dict, LEVEL);
        ZstdDictDecompress ddict = new ZstdDictDecompress(dict);

        System.out.printf("payloads: %d train / %d test, dictionary: %d bytes%n%n",
                train.size(), test.size(), dict.length);

        // Round-trip check: compression must be lossless.
        for (byte[] p : test) {
            byte[] back = Zstd.decompress(Zstd.compress(p, cdict), ddict, p.length);
            if (!Arrays.equals(p, back)) throw new IllegalStateException("round-trip mismatch!");
        }
        System.out.println("round-trip OK on all test payloads\n");

        List<Codec> codecs = List.of(
                new Codec("gzip", Bench::gzip),
                new Codec("zstd", p -> Zstd.compress(p, LEVEL)),
                new Codec("zstd+dict", p -> Zstd.compress(p, cdict)));

        long origTotal = test.stream().mapToLong(p -> p.length).sum();
        System.out.printf("%-10s %10s %8s %10s   %s%n", "codec", "bytes", "ratio", "us/payload", "ratio by size (<1KB | 1-4KB | >4KB)");

        for (Codec c : codecs) {
            for (byte[] p : test) c.compress().apply(p); // warm-up pass

            long[] orig = new long[3], comp = new long[3];
            long t0 = System.nanoTime();
            for (byte[] p : test) {
                byte[] out = c.compress().apply(p);
                int b = bucket(p.length);
                orig[b] += p.length;
                comp[b] += out.length;
            }
            long ns = System.nanoTime() - t0;

            long compTotal = comp[0] + comp[1] + comp[2];
            System.out.printf("%-10s %10d %7.2fx %10.1f   %s%n", c.name(), compTotal,
                    (double) origTotal / compTotal, ns / 1000.0 / test.size(),
                    String.format("%s | %s | %s", ratio(orig[0], comp[0]), ratio(orig[1], comp[1]), ratio(orig[2], comp[2])));
        }
        System.out.printf("%noriginal total: %d bytes. Buckets: %s%n", origTotal, String.join(", ", BUCKETS));
    }

    static byte[] trainDict(List<byte[]> samples) {
        long total = samples.stream().mapToLong(s -> s.length).sum();
        ZstdDictTrainer trainer = new ZstdDictTrainer((int) (total + 1024), DICT_SIZE);
        for (byte[] s : samples) trainer.addSample(s);
        return trainer.trainSamples();
    }

    static byte[] gzip(byte[] p) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(p); }
            return bos.toByteArray();
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    static int bucket(int n) { return n < 1024 ? 0 : n < 4096 ? 1 : 2; }

    static String ratio(long orig, long comp) {
        return orig == 0 ? "  n/a " : String.format("%5.2fx", (double) orig / comp);
    }

    static List<byte[]> loadDir(Path dir) throws IOException {
        List<byte[]> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) out.add(Files.readAllBytes(f));
        }
        return out;
    }

    /** Fake "product listing" API responses. Replace with real captured payloads for honest numbers. */
    static List<byte[]> generate(int n) {
        Random r = new Random(7);
        String[] cats = {"electronics", "grocery", "fashion", "books", "toys", "home"};
        String[] tags = {"new", "sale", "popular", "eco", "limited", "gift"};
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int items = 1 + r.nextInt(i % 5 == 0 ? 40 : 4);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"status\":\"ok\",\"requestId\":\"").append(new UUID(r.nextLong(), r.nextLong()))
              .append("\",\"data\":{\"items\":[");
            for (int k = 0; k < items; k++) {
                if (k > 0) sb.append(',');
                int id = r.nextInt(100000);
                sb.append("{\"id\":").append(id)
                  .append(",\"name\":\"Product ").append(id)
                  .append("\",\"category\":\"").append(cats[r.nextInt(cats.length)])
                  .append("\",\"price\":").append(String.format("%.2f", 1 + r.nextDouble() * 500))
                  .append(",\"inStock\":").append(r.nextBoolean())
                  .append(",\"rating\":").append(String.format("%.1f", 1 + r.nextDouble() * 4))
                  .append(",\"tags\":[\"").append(tags[r.nextInt(tags.length)]).append("\",\"")
                  .append(tags[r.nextInt(tags.length)]).append("\"]")
                  .append(",\"createdAt\":\"2026-0").append(1 + r.nextInt(9)).append('-')
                  .append(String.format("%02d", 1 + r.nextInt(28))).append("T10:22:11Z\"}");
            }
            sb.append("],\"page\":").append(1 + r.nextInt(20)).append(",\"total\":").append(items * 10).append("}}");
            out.add(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }
}