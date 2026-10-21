package dev.zdict.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * In-memory and disk reservoir sampler of scrubbed JSON responses per endpoint group.
 */
public class SampleStore {

    private static final Logger log = LoggerFactory.getLogger(SampleStore.class);

    private final Path dataDir;
    private final int perGroupMax;
    private final double sampleRate;
    private final PayloadScrubber scrubber;
    private final Random random;

    private final ConcurrentHashMap<String, GroupReservoir> reservoirs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> slugToGroup = new ConcurrentHashMap<>();

    public SampleStore(ZdictConfig config, PayloadScrubber scrubber) {
        this(
                Path.of(config.getDataDir()),
                config.getSample() != null ? config.getSample().getPerGroupMax() : 2000,
                config.getSample() != null ? config.getSample().getRate() : 0.05,
                scrubber,
                new Random()
        );
    }

    public SampleStore(Path dataDir, int perGroupMax, double sampleRate, PayloadScrubber scrubber) {
        this(dataDir, perGroupMax, sampleRate, scrubber, new Random());
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
            String slug = ZdictConstants.toGroupSlug(groupKey);
            slugToGroup.putIfAbsent(slug, groupKey);
            Path groupDir = dataDir.resolve("samples").resolve(slug);

            GroupReservoir res = reservoirs.computeIfAbsent(slug, k -> {
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
        String slug = ZdictConstants.toGroupSlug(groupKey);
        GroupReservoir res = reservoirs.get(slug);
        if (res == null) {
            // Check if exists on disk
            Path groupDir = dataDir.resolve("samples").resolve(slug);
            if (Files.isDirectory(groupDir)) {
                res = reservoirs.computeIfAbsent(slug, k -> {
                    GroupReservoir r = new GroupReservoir(perGroupMax, random);
                    r.loadFromDisk(groupDir);
                    return r;
                });
            }
        }
        return res != null ? res.getSamples() : List.of();
    }

    public int getSampleCount(String groupKey) {
        String slug = ZdictConstants.toGroupSlug(groupKey);
        GroupReservoir res = reservoirs.get(slug);
        return res != null ? res.size() : 0;
    }

    public Set<String> getGroups() {
        Set<String> groups = new HashSet<>();
        for (String slug : reservoirs.keySet()) {
            groups.add(slugToGroup.getOrDefault(slug, "/" + slug.replace('_', '/')));
        }
        Path samplesBase = dataDir.resolve("samples");
        if (Files.isDirectory(samplesBase)) {
            try (Stream<Path> dirs = Files.list(samplesBase)) {
                dirs.filter(Files::isDirectory)
                        .forEach(d -> {
                            String s = d.getFileName().toString();
                            groups.add(slugToGroup.getOrDefault(s, "/" + s.replace('_', '/')));
                        });
            } catch (IOException ignored) {
            }
        }
        return groups;
    }

    public void clear(String groupKey) {
        String slug = ZdictConstants.toGroupSlug(groupKey);
        GroupReservoir res = reservoirs.remove(slug);
        if (res != null) {
            res.clear();
        }
        Path groupDir = dataDir.resolve("samples").resolve(slug);
        if (Files.isDirectory(groupDir)) {
            try (Stream<Path> files = Files.list(groupDir)) {
                files.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
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
