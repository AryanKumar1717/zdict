package dev.zdict;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Fetches JSON responses from Open-Meteo APIs and saves each as
 * benchmark-data/real/&lt;group&gt;/&lt;seq&gt;.json (zero-padded seq, in order).
 *
 * Groups:
 * - "forecast": 300 varied coordinates across India
 *   GET https://api.open-meteo.com/v1/forecast?latitude=&lt;lat&gt;&amp;longitude=&lt;lon&gt;&amp;hourly=temperature_2m,precipitation&amp;forecast_days=1
 * - "geocoding": 200 city names (Indian and world cities)
 *   GET https://geocoding-api.open-meteo.com/v1/search?name=&lt;name&gt;&amp;count=5
 *
 * Rules:
 * - 200 ms delay between requests
 * - User-Agent set
 * - Skip non-200
 * - Max 2 retries (up to 3 attempts total)
 */
public class OpenMeteoFetcher {

    public static final int FORECAST_COUNT = 300;
    public static final int GEOCODING_COUNT = 200;
    public static final int DEFAULT_DELAY_MS = 200;
    public static final int MAX_RETRIES = 2;
    public static final String USER_AGENT = "zdict-bench/0.0.1 (https://github.com/aryan/zdict)";

    // 200 real cities (100 Indian cities + 100 world cities)
    public static final List<String> CITIES = List.of(
            // 100 Indian cities
            "Mumbai", "Delhi", "Bengaluru", "Hyderabad", "Ahmedabad", "Chennai", "Kolkata", "Surat", "Pune", "Jaipur",
            "Lucknow", "Kanpur", "Nagpur", "Indore", "Thane", "Bhopal", "Visakhapatnam", "Pimpri-Chinchwad", "Patna", "Vadodara",
            "Ghaziabad", "Ludhiana", "Agra", "Nashik", "Faridabad", "Meerut", "Rajkot", "Kalyan-Dombivli", "Vasai-Virar", "Varanasi",
            "Srinagar", "Aurangabad", "Dhanbad", "Amritsar", "Navi Mumbai", "Prayagraj", "Howrah", "Ranchi", "Gwalior", "Jabalpur",
            "Coimbatore", "Vijayawada", "Jodhpur", "Madurai", "Raipur", "Kota", "Chandigarh", "Guwahati", "Solapur", "Hubballi-Dharwad",
            "Bareilly", "Moradabad", "Mysore", "Gurgaon", "Aligarh", "Jalandhar", "Tiruchirappalli", "Bhubaneswar", "Salem", "Mira-Bhayandar",
            "Warangal", "Thiruvananthapuram", "Guntur", "Bhiwandi", "Saharanpur", "Gorakhpur", "Bikaner", "Amravati", "Noida", "Jamshedpur",
            "Bhilai", "Cuttack", "Firozabad", "Kochi", "Nellore", "Bhavnagar", "Dehradun", "Durgapur", "Asansol", "Rourkela",
            "Nanded", "Kolhapur", "Ajmer", "Akola", "Gulbarga", "Jamnagar", "Ujjain", "Loni", "Siliguri", "Jhansi",
            "Ulhasnagar", "Jammu", "Sangli-Miraj", "Mangalore", "Erode", "Belgaum", "Ambattur", "Tirunelveli", "Malegaon", "Gaya",
            // 100 World cities
            "Tokyo", "New York", "London", "Paris", "Singapore", "Sydney", "Toronto", "Berlin", "Madrid", "Rome",
            "Dubai", "San Francisco", "Chicago", "Los Angeles", "Seoul", "Beijing", "Shanghai", "Hong Kong", "Bangkok", "Istanbul",
            "Cairo", "Buenos Aires", "Sao Paulo", "Mexico City", "Johannesburg", "Nairobi", "Lagos", "Jakarta", "Kuala Lumpur", "Manila",
            "Taipei", "Osaka", "Vienna", "Zurich", "Amsterdam", "Brussels", "Stockholm", "Oslo", "Copenhagen", "Helsinki",
            "Dublin", "Lisbon", "Athens", "Warsaw", "Prague", "Budapest", "Bucharest", "Doha", "Abu Dhabi", "Riyadh",
            "Tel Aviv", "Auckland", "Vancouver", "Montreal", "Melbourne", "Brisbane", "Perth", "Seattle", "Boston", "Miami",
            "Atlanta", "Dallas", "Houston", "Austin", "Denver", "Philadelphia", "Washington", "San Diego", "Minneapolis", "Detroit",
            "Munich", "Frankfurt", "Hamburg", "Milan", "Barcelona", "Lyon", "Marseille", "Manchester", "Birmingham", "Edinburgh",
            "Glasgow", "Rotterdam", "Antwerp", "Geneva", "Basel", "Krakow", "Seville", "Valencia", "Florence", "Venice",
            "Naples", "Porto", "Rio de Janeiro", "Lima", "Bogota", "Santiago", "Wellington", "Cape Town", "Casablanca", "Reykjavik"
    );

    public static class Coordinate {
        public final double latitude;
        public final double longitude;

        public Coordinate(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    /**
     * Generate N distinct coordinates distributed across India.
     */
    public static Coordinate generateIndiaCoordinate(int index, int total) {
        int cols = 20;
        int row = index / cols;
        int col = index % cols;
        int maxRows = (total + cols - 1) / cols;

        double latMin = 8.5;
        double latMax = 34.5;
        double lonMin = 69.0;
        double lonMax = 95.0;

        double lat = latMin + (row * (latMax - latMin) / Math.max(1, maxRows - 1)) + (col * 0.041);
        double lon = lonMin + (col * (lonMax - lonMin) / Math.max(1, cols - 1)) - (row * 0.033);

        // Keep strictly in bounding range
        lat = Math.max(latMin, Math.min(latMax, lat));
        lon = Math.max(lonMin, Math.min(lonMax, lon));

        return new Coordinate(lat, lon);
    }

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

        int forecastCount = args.length >= 2 ? Integer.parseInt(args[1]) : FORECAST_COUNT;
        int geocodingCount = args.length >= 3 ? Integer.parseInt(args[2]) : GEOCODING_COUNT;
        int delayMs = args.length >= 4 ? Integer.parseInt(args[3]) : DEFAULT_DELAY_MS;

        System.out.printf("Open-Meteo Fetcher starting:%n");
        System.out.printf("  Output Directory: %s%n", outputDir.toAbsolutePath());
        System.out.printf("  Forecast Count:   %d coordinates across India%n", forecastCount);
        System.out.printf("  Geocoding Count:  %d cities%n", geocodingCount);
        System.out.printf("  Delay:            %d ms between requests%n", delayMs);
        System.out.printf("  User-Agent:       %s%n%n", USER_AGENT);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            // 1. Group: forecast
            FetchStats forecastStats = fetchForecastGroup(client, forecastCount, delayMs, MAX_RETRIES, outputDir.resolve("forecast"));

            // 2. Group: geocoding
            FetchStats geocodingStats = fetchGeocodingGroup(client, geocodingCount, delayMs, MAX_RETRIES, outputDir.resolve("geocoding"));

            System.out.println("\n=== Open-Meteo Fetch Summary ===");
            printStats(forecastStats);
            printStats(geocodingStats);
            System.out.printf("Total Saved Payloads: %d%n", forecastStats.saved + geocodingStats.saved);
            System.out.printf("Total Real Bytes:     %,d B%n", forecastStats.totalBytes + geocodingStats.totalBytes);
            System.out.println("================================\n");
        } catch (Exception e) {
            System.err.println("Fatal error during Open-Meteo fetch: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static FetchStats fetchForecastGroup(HttpClient client, int count, int delayMs, int maxRetries, Path targetDir)
            throws IOException, InterruptedException {
        Files.createDirectories(targetDir);

        FetchStats stats = new FetchStats();
        stats.group = "forecast";
        stats.requested = count;

        System.out.printf("[forecast] Fetching %d weather forecasts into %s...%n", count, targetDir);

        for (int seq = 1; seq <= count; seq++) {
            Coordinate coord = generateIndiaCoordinate(seq - 1, count);
            String url = String.format(Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&hourly=temperature_2m,precipitation&forecast_days=1",
                    coord.latitude, coord.longitude);
            Path file = targetDir.resolve(String.format(Locale.ROOT, "%06d.json", seq));

            boolean success = executeFetch(client, url, file, stats, maxRetries, "forecast", seq);
            if (!success) {
                stats.skipped++;
            }

            if (seq % 50 == 0 || seq == count) {
                System.out.printf("[forecast] Progress: %d/%d (saved: %d, skipped: %d, bytes: %,d B)%n",
                        seq, count, stats.saved, stats.skipped, stats.totalBytes);
            }

            if (delayMs > 0 && seq < count) {
                Thread.sleep(delayMs);
            }
        }

        return stats;
    }

    public static FetchStats fetchGeocodingGroup(HttpClient client, int count, int delayMs, int maxRetries, Path targetDir)
            throws IOException, InterruptedException {
        Files.createDirectories(targetDir);

        FetchStats stats = new FetchStats();
        stats.group = "geocoding";
        stats.requested = count;

        System.out.printf("[geocoding] Fetching %d city geocoding responses into %s...%n", count, targetDir);

        int cityListSize = CITIES.size();
        for (int seq = 1; seq <= count; seq++) {
            String cityName = CITIES.get((seq - 1) % cityListSize);
            String encodedName = URLEncoder.encode(cityName, StandardCharsets.UTF_8);
            String url = String.format(Locale.ROOT,
                    "https://geocoding-api.open-meteo.com/v1/search?name=%s&count=5",
                    encodedName);
            Path file = targetDir.resolve(String.format(Locale.ROOT, "%06d.json", seq));

            boolean success = executeFetch(client, url, file, stats, maxRetries, "geocoding", seq);
            if (!success) {
                stats.skipped++;
            }

            if (seq % 50 == 0 || seq == count) {
                System.out.printf("[geocoding] Progress: %d/%d (saved: %d, skipped: %d, bytes: %,d B)%n",
                        seq, count, stats.saved, stats.skipped, stats.totalBytes);
            }

            if (delayMs > 0 && seq < count) {
                Thread.sleep(delayMs);
            }
        }

        return stats;
    }

    private static boolean executeFetch(HttpClient client, String url, Path file, FetchStats stats,
                                       int maxRetries, String group, int seq) {
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
                    return true;
                } else if (status >= 400 && status < 500) {
                    // Client error (e.g. 404), skip without retry
                    System.out.printf("[%s] Seq %d returned HTTP %d, skipping.%n", group, seq, status);
                    return false;
                } else {
                    // 5xx Server error, allow retry
                    System.out.printf("[%s] Seq %d returned HTTP %d (attempt %d/%d).%n",
                            group, seq, status, attempt + 1, maxRetries + 1);
                    if (attempt < maxRetries) {
                        Thread.sleep(500);
                    }
                }
            } catch (IOException e) {
                System.out.printf("[%s] Seq %d network error: %s (attempt %d/%d).%n",
                        group, seq, e.getMessage(), attempt + 1, maxRetries + 1);
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void printStats(FetchStats s) {
        System.out.printf("Group: %-10s | Saved: %3d / %3d | Skipped: %2d | Bytes: %,10d B%n",
                s.group, s.saved, s.requested, s.skipped, s.totalBytes);
    }
}
