package dev.zdict;

import com.github.luben.zstd.ZstdDictTrainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Trains a 16 KB zstd dictionary from a folder of sample JSON files.
 *
 * Usage: java DictTrainer <samples-folder> <output-path>
 */
public class DictTrainer {

    static final int DICT_SIZE = 16 * 1024;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: DictTrainer <samples-folder> <output-path>");
            System.exit(1);
        }

        Path samplesDir = Path.of(args[0]);
        Path outputPath = Path.of(args[1]);

        List<byte[]> samples = loadDir(samplesDir);
        System.out.printf("Loaded %d samples from %s%n", samples.size(), samplesDir);

        long totalBytes = samples.stream().mapToLong(s -> s.length).sum();
        ZstdDictTrainer trainer = new ZstdDictTrainer((int) (totalBytes + 1024), DICT_SIZE);
        for (byte[] s : samples) {
            trainer.addSample(s);
        }
        byte[] dict = trainer.trainSamples();

        Files.createDirectories(outputPath.getParent());
        Files.write(outputPath, dict);

        String dictId = computeDictId(dict);
        System.out.printf("Dictionary written: %s (%d bytes)%n", outputPath, dict.length);
        System.out.printf("Dictionary id: %s%n", dictId);
    }

    static String computeDictId(byte[] dict) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(dict);
        return HexFormat.of().formatHex(hash).substring(0, 8);
    }

    static List<byte[]> loadDir(Path dir) throws IOException {
        List<byte[]> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                out.add(Files.readAllBytes(f));
            }
        }
        return out;
    }
}
