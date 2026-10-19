package dev.zdict.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * In-memory and disk reservoir sampler of scrubbed JSON responses per endpoint group.
 */
@Component
public class SampleStore {

    private static final Logger log = LoggerFactory.getLogger(SampleStore.class);

    private final Path dataDir;
    private final int perGroupMax;
    private final double sampleRate;
    private final PayloadScrubber scrubber;
    private final Random random;

    private final ConcurrentHashMap<String, GroupReservoir> reservoirs = new ConcurrentHashMap<>();

    @Autowired
    public SampleStore(ZdictProperties props, PayloadScrubber scrubber) {
        this(
                Path.of(props.getDataDir()),
                props.getSample().getPerGroupMax(),
                props.getSample().getRate(),
                scrubber,
                new Random()
        );
    }

    public SampleStore(Path dataDir, int perGroupMax, double sampleRate, PayloadScrubber scrubber, Random random) {
        this.dataDir = dataDir;
        this.perGroupMax = perGroupMax > 0 ? perGroupMax : 2000;
        this.sampleRate = sampleRate > 0 ? sampleRate : 0.05;
        this.scrubber = scrubber != null ? scrubber : new PayloadScrubber();
        this.random = random != null ? random : new Random();
    }

    /**
     * Offers a response payload for sampling. Applies sample rate, scrubs PII,
     * updates the reservoir in memory and on disk.
     */
    public void offer(String groupKey, byte[] rawPayload) {
        if (groupKey == null || rawPayload == null || rawPayload.length == 0) {
            return;
        }
        if (random.nextDouble() > sampleRate) {
            return;
        }

        try {
            Optional<byte[]> scrubbed = scrubber.scrub(rawPayload);
            if (scrubbed.isEmpty()) {
                return;
            }

            byte[] safePayload = scrubbed.get();
            String slug = ZstdDictFilter.toGroupSlug(groupKey);
            Path groupDir = dataDir.resolve("samples").resolve(slug);

            GroupReservoir res = reservoirs.computeIfAbsent(groupKey, k -> {
                GroupReservoir r = new GroupReservoir(perGroupMax, random);
                r.loadFromDisk(groupDir);
                return r;
            });

            res.add(safePayload, groupDir);
        } catch (Exception e) {
            log.warn("Failed to sample payload for group {}: {}", groupKey, e.getMessage());
        }
    }

    public List<byte[]> getSamples(String groupKey) {
        GroupReservoir res = reservoirs.get(groupKey);
        if (res == null) {
            // Check if exists on disk
            String slug = ZstdDictFilter.toGroupSlug(groupKey);
            Path groupDir = dataDir.resolve("samples").resolve(slug);
            if (Files.isDirectory(groupDir)) {
                res = reservoirs.computeIfAbsent(groupKey, k -> {
                    GroupReservoir r = new GroupReservoir(perGroupMax, random);
                    r.loadFromDisk(groupDir);
                    return r;
                });
            }
        }
        return res != null ? res.getSamples() : List.of();
    }

    public int getSampleCount(String groupKey) {
        GroupReservoir res = reservoirs.get(groupKey);
        return res != null ? res.size() : 0;
    }

    public void clear(String groupKey) {
        GroupReservoir res = reservoirs.remove(groupKey);
        if (res != null) {
            res.clear();
        }
    }

    /**
     * Internal reservoir structure for a single group.
     */
    static class GroupReservoir {
        private final int capacity;
        private final Random random;
        private final List<byte[]> samples = new ArrayList<>();
        private int seen = 0;

        GroupReservoir(int capacity, Random random) {
            this.capacity = capacity;
            this.random = random;
        }

        synchronized void add(byte[] sample, Path dir) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                log.warn("Failed to create sample directory {}: {}", dir, e.getMessage());
                return;
            }

            if (samples.size() < capacity) {
                int index = samples.size();
                samples.add(sample);
                writeDiskSample(dir, index, sample);
            } else {
                int j = random.nextInt(seen + 1);
                if (j < capacity) {
                    samples.set(j, sample);
                    writeDiskSample(dir, j, sample);
                }
            }
            seen++;
        }

        synchronized List<byte[]> getSamples() {
            return new ArrayList<>(samples);
        }

        synchronized int size() {
            return samples.size();
        }

        synchronized void clear() {
            samples.clear();
            seen = 0;
        }

        synchronized void loadFromDisk(Path dir) {
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .limit(capacity)
                        .forEach(p -> {
                            try {
                                samples.add(Files.readAllBytes(p));
                            } catch (IOException e) {
                                log.warn("Failed to load sample from {}: {}", p, e.getMessage());
                            }
                        });
                seen = samples.size();
            } catch (IOException e) {
                log.warn("Failed to list sample directory {}: {}", dir, e.getMessage());
            }
        }

        private void writeDiskSample(Path dir, int index, byte[] data) {
            Path file = dir.resolve("sample_" + index + ".json");
            try {
                Files.write(file, data);
            } catch (IOException e) {
                log.warn("Failed to persist sample to {}: {}", file, e.getMessage());
            }
        }
    }
}
