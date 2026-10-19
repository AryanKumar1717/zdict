package dev.zdict.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class SampleStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void reservoirStaysBoundedInMemoryAndOnDisk() throws Exception {
        int capacity = 25;
        SampleStore store = new SampleStore(tempDir, capacity, 1.0, new PayloadScrubber(), new Random(42));
        String group = "/api/products";

        for (int i = 0; i < 150; i++) {
            String json = "{\"id\":" + i + ",\"data\":\"payload number " + i + "\"}";
            store.offer(group, json.getBytes(StandardCharsets.UTF_8));
        }

        // In-memory count must not exceed capacity
        assertThat(store.getSampleCount(group)).isEqualTo(capacity);
        assertThat(store.getSamples(group)).hasSize(capacity);

        // Disk files count must not exceed capacity
        String slug = ZstdDictFilter.toGroupSlug(group);
        Path sampleDir = tempDir.resolve("samples").resolve(slug);
        assertThat(Files.isDirectory(sampleDir)).isTrue();
        try (var files = Files.list(sampleDir)) {
            long count = files.filter(p -> p.getFileName().toString().endsWith(".json")).count();
            assertThat(count).isEqualTo(capacity);
        }
    }

    @Test
    void uniformIshRetentionAcrossStream() {
        int capacity = 100;
        int totalItems = 1000;
        SampleStore store = new SampleStore(tempDir, capacity, 1.0, new PayloadScrubber(), new Random(12345));
        String group = "/api/items";

        for (int i = 0; i < totalItems; i++) {
            String json = "{\"id\":" + i + "}";
            store.offer(group, json.getBytes(StandardCharsets.UTF_8));
        }

        List<byte[]> samples = store.getSamples(group);
        assertThat(samples).hasSize(capacity);

        // Extract IDs from retained samples
        boolean hasQ1 = false; // [0, 250)
        boolean hasQ2 = false; // [250, 500)
        boolean hasQ3 = false; // [500, 750)
        boolean hasQ4 = false; // [750, 1000)

        for (byte[] s : samples) {
            String text = new String(s, StandardCharsets.UTF_8);
            int id = Integer.parseInt(text.replaceAll("[^0-9]", ""));
            if (id < 250) hasQ1 = true;
            else if (id < 500) hasQ2 = true;
            else if (id < 750) hasQ3 = true;
            else hasQ4 = true;
        }

        assertThat(hasQ1).as("Has samples from Q1 (0-249)").isTrue();
        assertThat(hasQ2).as("Has samples from Q2 (250-499)").isTrue();
        assertThat(hasQ3).as("Has samples from Q3 (500-749)").isTrue();
        assertThat(hasQ4).as("Has samples from Q4 (750-999)").isTrue();
    }

    @Test
    void scrubberIsAlwaysAppliedBeforeStoring() {
        SampleStore store = new SampleStore(tempDir, 10, 1.0, new PayloadScrubber(), new Random());
        String group = "/api/secure";

        String json = """
                {
                  "username": "bob",
                  "password": "superSecretPassword",
                  "info": "Contact user at bob@example.com",
                  "card": "1234567890123"
                }
                """;

        store.offer(group, json.getBytes(StandardCharsets.UTF_8));

        List<byte[]> samples = store.getSamples(group);
        assertThat(samples).hasSize(1);

        String scrubbed = new String(samples.get(0), StandardCharsets.UTF_8);
        assertThat(scrubbed).doesNotContain("superSecretPassword");
        assertThat(scrubbed).doesNotContain("bob@example.com");
        assertThat(scrubbed).doesNotContain("1234567890123");
        assertThat(scrubbed).contains("\"password\":\"x\"");
        assertThat(scrubbed).contains("\"card\":\"x\"");
        assertThat(scrubbed).contains("Contact user at x");
    }

    @Test
    void nonJsonResponsesAreNotStored() {
        SampleStore store = new SampleStore(tempDir, 10, 1.0, new PayloadScrubber(), new Random());
        String group = "/api/bad";

        store.offer(group, "plain text not json".getBytes(StandardCharsets.UTF_8));
        assertThat(store.getSampleCount(group)).isEqualTo(0);
    }
}
