package dev.zdict.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DictionaryRegistryTest {

    @TempDir
    Path tempDir;

    private byte[] makeDummyDict(String tag) {
        // Dict bytes must be valid non-empty byte array
        return ("dummy-dict-content-for-testing-" + tag).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void persistenceAcrossRestart() throws Exception {
        String group = "/api/products/{id}";
        byte[] dict1 = makeDummyDict("v1");

        try (DictionaryRegistry reg1 = new DictionaryRegistry(tempDir)) {
            DictionaryVersion v1 = reg1.register(group, dict1, 250, 0.45);
            assertThat(v1.isActive()).isTrue();
            assertThat(reg1.getActive(group).getId()).isEqualTo(v1.getId());
        }

        // Restart: simulate new registry instance pointing to same directory
        try (DictionaryRegistry reg2 = new DictionaryRegistry(tempDir)) {
            reg2.loadFromDisk();

            DictionaryVersion active = reg2.getActive(group);
            assertThat(active).isNotNull();
            assertThat(active.isActive()).isTrue();
            assertThat(active.getDictBytes()).isEqualTo(dict1);
            assertThat(active.getMetadata().getSampleCount()).isEqualTo(250);
            assertThat(active.getMetadata().getHoldoutRatio()).isEqualTo(0.45);
            assertThat(reg2.getById(active.getId())).isNotNull();
        }
    }

    @Test
    void onlyOneActivePerGroup() throws Exception {
        String group = "/api/items";
        byte[] d1 = makeDummyDict("item-v1");
        byte[] d2 = makeDummyDict("item-v2");
        byte[] d3 = makeDummyDict("item-v3");

        try (DictionaryRegistry reg = new DictionaryRegistry(tempDir)) {
            DictionaryVersion v1 = reg.register(group, d1, 100, 0.30);
            assertThat(v1.isActive()).isTrue();
            assertThat(reg.getActive(group).getId()).isEqualTo(v1.getId());

            DictionaryVersion v2 = reg.register(group, d2, 200, 0.35);
            assertThat(v2.isActive()).isTrue();
            assertThat(reg.getActive(group).getId()).isEqualTo(v2.getId());
            assertThat(reg.getById(v1.getId()).isActive()).isFalse();
            assertThat(reg.getById(v1.getId()).getState()).isEqualTo("RETIRED");

            DictionaryVersion v3 = reg.register(group, d3, 300, 0.40);
            assertThat(v3.isActive()).isTrue();
            assertThat(reg.getActive(group).getId()).isEqualTo(v3.getId());
            assertThat(reg.getById(v1.getId()).getState()).isEqualTo("RETIRED");
            assertThat(reg.getById(v2.getId()).getState()).isEqualTo("RETIRED");

            List<DictionaryVersion> versions = reg.getVersions(group);
            long activeCount = versions.stream().filter(DictionaryVersion::isActive).count();
            assertThat(activeCount).isEqualTo(1);
        }

        // Verify across reload
        try (DictionaryRegistry regReloaded = new DictionaryRegistry(tempDir)) {
            regReloaded.loadFromDisk();
            List<DictionaryVersion> versions = regReloaded.getVersions(group);
            long activeCount = versions.stream().filter(DictionaryVersion::isActive).count();
            assertThat(activeCount).isEqualTo(1);
            assertThat(regReloaded.getActive(group).getDictBytes()).isEqualTo(d3);
        }
    }

    @Test
    void retentionKeepsLast3VersionsAndDeleteOlder() throws Exception {
        String group = "/api/users";
        String slug = ZstdDictFilter.toGroupSlug(group);
        Path groupDir = tempDir.resolve("dicts").resolve(slug);

        try (DictionaryRegistry reg = new DictionaryRegistry(tempDir)) {
            DictionaryVersion v1 = reg.register(group, makeDummyDict("u1"), 100, 0.1);
            DictionaryVersion v2 = reg.register(group, makeDummyDict("u2"), 120, 0.2);
            DictionaryVersion v3 = reg.register(group, makeDummyDict("u3"), 140, 0.3);

            assertThat(reg.getVersions(group)).hasSize(3);
            assertThat(Files.isRegularFile(groupDir.resolve(v1.getId() + ".dict"))).isTrue();
            assertThat(Files.isRegularFile(groupDir.resolve(v2.getId() + ".dict"))).isTrue();
            assertThat(Files.isRegularFile(groupDir.resolve(v3.getId() + ".dict"))).isTrue();

            // Register 4th version -> v1 must be deleted
            DictionaryVersion v4 = reg.register(group, makeDummyDict("u4"), 160, 0.4);

            List<DictionaryVersion> versionsAfter4 = reg.getVersions(group);
            assertThat(versionsAfter4).hasSize(3);
            assertThat(versionsAfter4.stream().map(DictionaryVersion::getId))
                    .containsExactly(v4.getId(), v3.getId(), v2.getId());

            // v1 deleted from disk and memory
            assertThat(Files.exists(groupDir.resolve(v1.getId() + ".dict"))).isFalse();
            assertThat(Files.exists(groupDir.resolve(v1.getId() + ".json"))).isFalse();
            assertThat(reg.getById(v1.getId())).isNull();

            // Register 5th version -> v2 must be deleted
            DictionaryVersion v5 = reg.register(group, makeDummyDict("u5"), 180, 0.5);
            List<DictionaryVersion> versionsAfter5 = reg.getVersions(group);
            assertThat(versionsAfter5).hasSize(3);
            assertThat(versionsAfter5.stream().map(DictionaryVersion::getId))
                    .containsExactly(v5.getId(), v4.getId(), v3.getId());

            assertThat(Files.exists(groupDir.resolve(v2.getId() + ".dict"))).isFalse();
            assertThat(Files.exists(groupDir.resolve(v2.getId() + ".json"))).isFalse();
            assertThat(reg.getById(v2.getId())).isNull();
        }
    }
}
