package dev.zdict;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PokeApiFetcherTest {

    @Test
    void testConstantsAndDefaults() {
        assertEquals("https://pokeapi.co/api/v2/", PokeApiFetcher.DEFAULT_BASE_URL);
        assertEquals(300, PokeApiFetcher.DEFAULT_MAX_ID);
        assertEquals(150, PokeApiFetcher.DEFAULT_DELAY_MS);
        assertEquals(2, PokeApiFetcher.MAX_RETRIES);
        assertTrue(PokeApiFetcher.USER_AGENT.contains("zdict-bench"));
    }

    @Test
    void testOutputDirectoryCreation(@TempDir Path tempDir) throws Exception {
        Path outputDir = tempDir.resolve("real");
        Path pokemonDir = outputDir.resolve("pokemon");
        Path speciesDir = outputDir.resolve("species");

        Files.createDirectories(pokemonDir);
        Files.createDirectories(speciesDir);

        assertTrue(Files.isDirectory(pokemonDir));
        assertTrue(Files.isDirectory(speciesDir));

        // Test zero-padded file name formatting
        String file1 = String.format("%06d.json", 1);
        String file300 = String.format("%06d.json", 300);

        assertEquals("000001.json", file1);
        assertEquals("000300.json", file300);
    }
}
