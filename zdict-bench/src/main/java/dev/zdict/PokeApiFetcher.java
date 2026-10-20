package dev.zdict;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * Fetches JSON responses from PokeAPI and saves each as
 * benchmark-data/real/&lt;group&gt;/&lt;seq&gt;.json (zero-padded seq, in order).
 *
 * Groups:
 * - "pokemon": /pokemon/{id} for ids 1-300
 * - "species": /pokemon-species/{id} for ids 1-300
 *
 * Rules:
 * - 150 ms delay between requests
 * - Skip non-200 responses
 * - No retries beyond 2 (maximum 3 attempts total)
 * - User-Agent header set on every request
 */
public class PokeApiFetcher {

    public static final String DEFAULT_BASE_URL = "https://pokeapi.co/api/v2/";
    public static final int DEFAULT_MAX_ID = 300;
    public static final int DEFAULT_DELAY_MS = 150;
    public static final int MAX_RETRIES = 2; // no retries beyond 2
    public static final String USER_AGENT = "zdict-bench/0.0.1 (https://github.com/aryan/zdict)";

    public static class FetchStats {
        public String group;
        public int requested;
        public int saved;
        public int skipped;
        public long totalBytes;
    }

    public static void main(String[] args) {
        Path outputDir;
        if (args.length >= 1) {
            outputDir = Path.of(args[0]);
        } else if (Files.exists(Path.of("benchmark-data"))) {
            outputDir = Path.of("benchmark-data/real");
        } else if (Files.exists(Path.of("../benchmark-data"))) {
            outputDir = Path.of("../benchmark-data/real");
        } else {
            outputDir = Path.of("benchmark-data/real");
        }

        int maxId = args.length >= 2 ? Integer.parseInt(args[1]) : DEFAULT_MAX_ID;
        int delayMs = args.length >= 3 ? Integer.parseInt(args[2]) : DEFAULT_DELAY_MS;

        System.out.printf("PokeAPI Fetcher starting:%n");
        System.out.printf("  Output Directory: %s%n", outputDir.toAbsolutePath());
        System.out.printf("  IDs:              1 to %d%n", maxId);
        System.out.printf("  Delay:            %d ms between requests%n", delayMs);
        System.out.printf("  User-Agent:       %s%n%n", USER_AGENT);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            // 1. Group: pokemon (/pokemon/{id})
            FetchStats pokemonStats = fetchGroup(client, DEFAULT_BASE_URL, "pokemon", "pokemon/",
                    maxId, delayMs, MAX_RETRIES, outputDir.resolve("pokemon"));

            // 2. Group: species (/pokemon-species/{id})
            FetchStats speciesStats = fetchGroup(client, DEFAULT_BASE_URL, "species", "pokemon-species/",
                    maxId, delayMs, MAX_RETRIES, outputDir.resolve("species"));

            System.out.println("\n=== PokeAPI Fetch Summary ===");
            printStats(pokemonStats);
            printStats(speciesStats);
            System.out.printf("Total Saved Payloads: %d%n", pokemonStats.saved + speciesStats.saved);
            System.out.printf("Total Real Bytes:     %,d B%n", pokemonStats.totalBytes + speciesStats.totalBytes);
            System.out.println("=============================\n");
        } catch (Exception e) {
            System.err.println("Fatal error during PokeAPI fetch: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static FetchStats fetchGroup(HttpClient client, String baseUrl, String groupName, String endpointPrefix,
                                        int maxId, int delayMs, int maxRetries, Path targetDir) throws IOException, InterruptedException {
        Files.createDirectories(targetDir);

        FetchStats stats = new FetchStats();
        stats.group = groupName;
        stats.requested = maxId;

        System.out.printf("[%s] Fetching %d payloads into %s...%n", groupName, maxId, targetDir);

        for (int id = 1; id <= maxId; id++) {
            String url = baseUrl + endpointPrefix + id;
            Path file = targetDir.resolve(String.format(Locale.ROOT, "%06d.json", id));

            boolean success = false;
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "application/json")
                            .timeout(Duration.ofSeconds(20))
                            .GET()
                            .build();

                    HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    int status = response.statusCode();

                    if (status == 200) {
                        byte[] body = response.body();
                        Files.write(file, body);
                        stats.saved++;
                        stats.totalBytes += body.length;
                        success = true;
                        break;
                    } else if (status >= 400 && status < 500) {
                        // Client error (e.g. 404 Not Found), skip without further retries
                        System.out.printf("[%s] ID %d returned HTTP %d, skipping.%n", groupName, id, status);
                        break;
                    } else {
                        // 5xx Server error, allow retry
                        System.out.printf("[%s] ID %d returned HTTP %d (attempt %d/%d).%n",
                                groupName, id, status, attempt + 1, maxRetries + 1);
                        if (attempt < maxRetries) {
                            Thread.sleep(500);
                        }
                    }
                } catch (IOException e) {
                    System.out.printf("[%s] ID %d network error: %s (attempt %d/%d).%n",
                            groupName, id, e.getMessage(), attempt + 1, maxRetries + 1);
                    if (attempt < maxRetries) {
                        Thread.sleep(500);
                    }
                }
            }

            if (!success) {
                stats.skipped++;
            }

            if (id % 50 == 0 || id == maxId) {
                System.out.printf("[%s] Progress: %d/%d (saved: %d, skipped: %d, bytes: %,d B)%n",
                        groupName, id, maxId, stats.saved, stats.skipped, stats.totalBytes);
            }

            if (delayMs > 0 && id < maxId) {
                Thread.sleep(delayMs);
            }
        }

        return stats;
    }

    private static void printStats(FetchStats s) {
        System.out.printf("Group: %-10s | Saved: %3d / %3d | Skipped: %2d | Bytes: %,10d B%n",
                s.group, s.saved, s.requested, s.skipped, s.totalBytes);
    }
}
