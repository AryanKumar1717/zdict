package dev.zdict.demo;

import dev.zdict.core.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads real-world API payloads (forecast, geocoding, species, pokemon) into SampleStore,
 * invokes DictionaryTrainerService in a fresh data directory for each, and reports
 * the worth-it gate decision, holdout compression gains, dictionary size, and sample count.
 *
 * Skips gracefully if benchmark-data/real is missing (gitignored).
 */
public class RealDataTrainerTest {

    private static final List<String> GROUPS = List.of("forecast", "geocoding", "species", "pokemon");

    @Test
    void testRealDataTraining() throws Exception {
        runTraining(resolveRealDataDir());
    }

    public static void main(String[] args) throws Exception {
        Path realDataDir = resolveRealDataDir();
        runTraining(realDataDir);
    }

    private static Path resolveRealDataDir() {
        Path dir = Path.of("benchmark-data/real");
        if (!Files.exists(dir)) {
            dir = Path.of("../benchmark-data/real");
        }
        return dir;
    }

    private static void runTraining(Path realDataDir) throws Exception {
        if (!Files.exists(realDataDir)) {
            System.out.println("Skipping real data trainer: benchmark-data/real directory not found.");
            return;
        }

        System.out.println("===============================================================================");
        System.out.println("                 zdict Real Data Dictionary Trainer Evaluation                 ");
        System.out.println("===============================================================================");
        System.out.printf("%-12s | %-13s | %-14s | %-14s | %-10s | %-12s%n",
                "Group", "Decision", "Gain vs gzip", "Gain vs zstd", "Dict Size", "Sample Count");
        System.out.println("-------------+---------------+----------------+----------------+------------+-------------");

        for (String group : GROUPS) {
            Path groupDir = realDataDir.resolve(group);
            if (!Files.exists(groupDir) || !Files.isDirectory(groupDir)) {
                System.out.printf("%-12s | MISSING (skipped)%n", group);
                continue;
            }

            // Fresh data directory per group
            Path freshDataDir = Files.createTempDirectory("zdict-real-" + group + "-");
            try (DictionaryRegistry registry = new DictionaryRegistry(freshDataDir)) {
                registry.init();

                PayloadScrubber scrubber = new PayloadScrubber();
                SampleStore sampleStore = new SampleStore(freshDataDir, 2000, 1.0, scrubber);

                // Collect samples
                List<Path> jsonFiles;
                try (Stream<Path> stream = Files.list(groupDir)) {
                    jsonFiles = stream
                            .filter(p -> p.getFileName().toString().endsWith(".json"))
                            .sorted(Comparator.comparing(Path::getFileName))
                            .toList();
                }

                for (Path jsonFile : jsonFiles) {
                    byte[] rawPayload = Files.readAllBytes(jsonFile);
                    sampleStore.offer(group, rawPayload);
                }

                int sampleCount = sampleStore.getSampleCount(group);

                // Configure trainer: 100 min-samples, 10% min-gain worth-it gate, 16 KB dict size
                ZdictConfig config = new ZdictConfig();
                config.setDataDir(freshDataDir.toString());
                config.getTrain().setMinSamples(100);
                config.getTrain().setMinGain(0.10);
                config.getTrain().setDictSize(16384);

                DictionaryTrainerService trainerService = new DictionaryTrainerService(sampleStore, registry, config);
                TrainResult result = trainerService.train(group);

                String decision = "NOT_WORTH_IT".equals(result.getDecision())
                        ? "NOT_WORTH_IT"
                        : (result.isPromoted() ? "ACTIVE" : result.getDecision());

                double gainVsGzip = result.getGainVsGzip() != null ? result.getGainVsGzip() : 0.0;
                double gainVsZstd = result.getGainVsZstd() != null ? result.getGainVsZstd() : 0.0;
                int dictSize = result.getDictSize() > 0 ? result.getDictSize() : 16384;

                System.out.printf("%-12s | %-13s | %+13.2f%% | %+13.2f%% | %,7d B  | %,11d%n",
                        group, decision, gainVsGzip * 100, gainVsZstd * 100, dictSize, sampleCount);

                // Print detailed breakdown
                System.out.printf("  -> Details [%s]: %s (Holdout: %d train / %d test, newRatio=%.4f)%n",
                        group, result.getReason(), result.getTrainCount(), result.getHoldoutCount(), result.getNewHoldoutRatio());
            } finally {
                deleteRecursively(freshDataDir);
            }
        }
        System.out.println("===============================================================================");
    }

    private static void deleteRecursively(Path root) {
        try {
            if (Files.exists(root)) {
                try (Stream<Path> stream = Files.walk(root)) {
                    stream.sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
                }
            }
        } catch (IOException ignored) {
        }
    }
}
