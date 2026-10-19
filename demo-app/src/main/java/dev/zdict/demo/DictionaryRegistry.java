package dev.zdict.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Manages versioned zstd dictionaries per endpoint group with disk persistence,
 * retention of the latest 3 versions, and single-ACTIVE invariant per group.
 */
@Component
public class DictionaryRegistry implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(DictionaryRegistry.class);
    private static final int MAX_VERSIONS_PER_GROUP = 3;
    private static final int COMPRESSION_LEVEL = 3;

    private final Path dataDir;
    private final ObjectMapper mapper;
    private final java.time.Clock clock;

    // slug -> list of versions sorted newest to oldest
    private final ConcurrentHashMap<String, List<DictionaryVersion>> groupVersions = new ConcurrentHashMap<>();
    // id -> version across all groups
    private final ConcurrentHashMap<String, DictionaryVersion> versionsById = new ConcurrentHashMap<>();

    @Autowired
    public DictionaryRegistry(ZdictProperties props, @Autowired(required = false) java.time.Clock clock) {
        this(Path.of(props.getDataDir()), createDefaultMapper(), clock != null ? clock : java.time.Clock.systemUTC());
    }

    public DictionaryRegistry(Path dataDir) {
        this(dataDir, createDefaultMapper(), java.time.Clock.systemUTC());
    }

    public DictionaryRegistry(Path dataDir, ObjectMapper mapper) {
        this(dataDir, mapper, java.time.Clock.systemUTC());
    }

    public DictionaryRegistry(Path dataDir, ObjectMapper mapper, java.time.Clock clock) {
        this.dataDir = dataDir;
        this.mapper = mapper != null ? mapper : createDefaultMapper();
        this.clock = clock != null ? clock : java.time.Clock.systemUTC();
    }

    private static ObjectMapper createDefaultMapper() {
        ObjectMapper om = new ObjectMapper();
        om.enable(SerializationFeature.INDENT_OUTPUT);
        return om;
    }

    @PostConstruct
    public void init() {
        loadFromDisk();
    }

    /**
     * Scan zdict.data-dir/dicts/<group-slug>/ and load dictionary versions into memory.
     */
    public synchronized void loadFromDisk() {
        Path dictsBase = dataDir.resolve("dicts");
        if (!Files.isDirectory(dictsBase)) {
            return;
        }

        try (Stream<Path> groupDirs = Files.list(dictsBase)) {
            groupDirs.filter(Files::isDirectory).forEach(groupDir -> {
                String slug = groupDir.getFileName().toString();
                loadGroupFromDisk(slug, groupDir);
            });
        } catch (IOException e) {
            log.warn("Failed to list dicts directory: {}", e.getMessage());
        }
    }

    private void loadGroupFromDisk(String slug, Path groupDir) {
        List<DictionaryMetadata> metaList = new ArrayList<>();
        Map<String, byte[]> bytesMap = new HashMap<>();

        try (Stream<Path> files = Files.list(groupDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(jsonFile -> {
                try {
                    DictionaryMetadata meta = mapper.readValue(jsonFile.toFile(), DictionaryMetadata.class);
                    if (meta.getBaseline() <= 0.0) {
                        double b = (meta.getHoldoutRatio() > 0 && meta.getHoldoutRatio() < 1.0) ? (1.0 / meta.getHoldoutRatio()) : meta.getHoldoutRatio();
                        meta.setBaseline(b);
                    }
                    String id = meta.getId();
                    Path dictFile = groupDir.resolve(id + ".dict");
                    if (Files.isRegularFile(dictFile)) {
                        byte[] bytes = Files.readAllBytes(dictFile);
                        metaList.add(meta);
                        bytesMap.put(id, bytes);
                    } else {
                        log.warn("Dictionary file missing for metadata: {}", dictFile);
                    }
                } catch (Exception e) {
                    log.warn("Failed reading dictionary metadata from {}: {}", jsonFile, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Error reading group directory {}: {}", groupDir, e.getMessage());
            return;
        }

        if (metaList.isEmpty()) {
            return;
        }

        // Sort newest first by createdAt
        metaList.sort((a, b) -> {
            String ca = a.getCreatedAt() != null ? a.getCreatedAt() : "";
            String cb = b.getCreatedAt() != null ? b.getCreatedAt() : "";
            return cb.compareTo(ca);
        });

        // Retention: keep at most MAX_VERSIONS_PER_GROUP
        while (metaList.size() > MAX_VERSIONS_PER_GROUP) {
            DictionaryMetadata oldest = metaList.remove(metaList.size() - 1);
            deleteFromDisk(slug, oldest.getId());
            bytesMap.remove(oldest.getId());
        }

        // Enforce exactly one ACTIVE per group
        int activeIdx = -1;
        for (int i = 0; i < metaList.size(); i++) {
            if (metaList.get(i).isActive()) {
                if (activeIdx == -1) {
                    activeIdx = i; // keep the newest active
                } else {
                    // Demote older active to retired
                    DictionaryMetadata demoted = metaList.get(i);
                    demoted.setState("RETIRED");
                    saveMetadata(slug, demoted);
                }
            }
        }

        if (activeIdx == -1 && !metaList.isEmpty()) {
            // No active version found; promote newest
            DictionaryMetadata promoted = metaList.get(0);
            promoted.setState("ACTIVE");
            saveMetadata(slug, promoted);
            activeIdx = 0;
        }

        // Instantiate DictionaryVersions
        List<DictionaryVersion> versions = new ArrayList<>();
        for (int i = 0; i < metaList.size(); i++) {
            DictionaryMetadata meta = metaList.get(i);
            byte[] bytes = bytesMap.get(meta.getId());
            DictionaryVersion v;
            if (i == activeIdx) {
                v = DictionaryVersion.createActive(meta, bytes, COMPRESSION_LEVEL);
            } else {
                v = DictionaryVersion.createRetired(meta, bytes);
            }
            versions.add(v);
            versionsById.put(v.getId(), v);
        }

        groupVersions.put(slug, versions);
    }

    /**
     * Register a new dictionary version for the group as ACTIVE.
     * Demotes any existing ACTIVE version to RETIRED and enforces retention limit of 3.
     */
    public synchronized DictionaryVersion register(String group, byte[] dictBytes, int sampleCount, double holdoutRatio) throws IOException {
        double baseline = (holdoutRatio > 0 && holdoutRatio < 1.0) ? (1.0 / holdoutRatio) : holdoutRatio;
        return register(group, dictBytes, sampleCount, holdoutRatio, baseline);
    }

    public synchronized DictionaryVersion register(String group, byte[] dictBytes, int sampleCount, double holdoutRatio, double baseline) throws IOException {
        String slug = ZstdDictFilter.toGroupSlug(group);
        String id = ZstdDictFilter.computeDictId(dictBytes);

        Path groupDir = dataDir.resolve("dicts").resolve(slug);
        Files.createDirectories(groupDir);

        // Write .dict file
        Path dictFile = groupDir.resolve(id + ".dict");
        Files.write(dictFile, dictBytes);

        List<DictionaryVersion> currentList = groupVersions.computeIfAbsent(slug, k -> new ArrayList<>());

        // Demote previous active version
        for (DictionaryVersion existing : currentList) {
            if (existing.isActive()) {
                existing.retire();
                saveMetadata(slug, existing.getMetadata());
            }
        }

        // Create new active version
        DictionaryMetadata newMeta = new DictionaryMetadata(
                id,
                group,
                Instant.now(clock).toString(),
                sampleCount,
                dictBytes.length,
                holdoutRatio,
                baseline,
                "ACTIVE"
        );
        saveMetadata(slug, newMeta);

        DictionaryVersion newVersion = DictionaryVersion.createActive(newMeta, dictBytes, COMPRESSION_LEVEL);
        currentList.add(0, newVersion); // prepend newest
        versionsById.put(id, newVersion);

        // Enforce retention of 3 versions per group
        while (currentList.size() > MAX_VERSIONS_PER_GROUP) {
            DictionaryVersion removed = currentList.remove(currentList.size() - 1);
            versionsById.remove(removed.getId());
            removed.close();
            deleteFromDisk(slug, removed.getId());
        }

        return newVersion;
    }

    private void saveMetadata(String slug, DictionaryMetadata meta) {
        Path jsonFile = dataDir.resolve("dicts").resolve(slug).resolve(meta.getId() + ".json");
        try {
            mapper.writeValue(jsonFile.toFile(), meta);
        } catch (IOException e) {
            log.error("Failed saving metadata to {}: {}", jsonFile, e.getMessage(), e);
        }
    }

    private void deleteFromDisk(String slug, String id) {
        Path base = dataDir.resolve("dicts").resolve(slug);
        try {
            Files.deleteIfExists(base.resolve(id + ".dict"));
            Files.deleteIfExists(base.resolve(id + ".json"));
        } catch (IOException e) {
            log.warn("Failed deleting old dictionary files for {}: {}", id, e.getMessage());
        }
    }

    /**
     * Get the active dictionary for an endpoint group (by group key or slug).
     */
    public DictionaryVersion getActive(String group) {
        String slug = ZstdDictFilter.toGroupSlug(group);
        List<DictionaryVersion> list = groupVersions.get(slug);
        if (list == null || list.isEmpty()) {
            return null;
        }
        for (DictionaryVersion v : list) {
            if (v.isActive()) {
                return v;
            }
        }
        return null;
    }

    /**
     * Look up any dictionary version by id (ACTIVE or RETIRED).
     */
    public DictionaryVersion getById(String id) {
        if (id == null) return null;
        return versionsById.get(id);
    }

    /**
     * Get all versions stored for a group, ordered newest to oldest.
     */
    public List<DictionaryVersion> getVersions(String group) {
        String slug = ZstdDictFilter.toGroupSlug(group);
        List<DictionaryVersion> list = groupVersions.get(slug);
        if (list == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(list);
    }

    public Set<String> getGroups() {
        Set<String> groups = new HashSet<>();
        for (List<DictionaryVersion> list : groupVersions.values()) {
            if (!list.isEmpty()) {
                groups.add(list.get(0).getGroup());
            }
        }
        return groups;
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        for (DictionaryVersion v : versionsById.values()) {
            v.close();
        }
        versionsById.clear();
        groupVersions.clear();
    }
}
