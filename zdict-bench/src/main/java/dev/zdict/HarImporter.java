package dev.zdict;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads a browser HAR export, extracts application/json status 200 responses,
 * groups them by normalized URL path (numeric/UUID segments replaced by {id}),
 * and writes them in original order to &lt;output-dir&gt;/&lt;group&gt;/&lt;seq&gt;.json.
 */
public class HarImporter {

    public static final int MAX_BODY_BYTES = 1024 * 1024; // 1 MB

    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^[0-9]+$");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final ObjectMapper mapper = new ObjectMapper();

    public static class GroupStats {
        private final String groupPath;
        private final String filesystemGroup;
        private int fileCount;
        private long totalBytes;

        public GroupStats(String groupPath, String filesystemGroup) {
            this.groupPath = groupPath;
            this.filesystemGroup = filesystemGroup;
        }

        public String getGroupPath() {
            return groupPath;
        }

        public String getFilesystemGroup() {
            return filesystemGroup;
        }

        public int getFileCount() {
            return fileCount;
        }

        public long getTotalBytes() {
            return totalBytes;
        }
    }

    public static class ImportSummary {
        private int totalEntries;
        private int filesWritten;
        private long totalBytesWritten;
        private final Map<String, GroupStats> groups = new LinkedHashMap<>();

        public int getTotalEntries() {
            return totalEntries;
        }

        public int getFilesWritten() {
            return filesWritten;
        }

        public long getTotalBytesWritten() {
            return totalBytesWritten;
        }

        public Map<String, GroupStats> getGroups() {
            return groups;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java dev.zdict.HarImporter <file.har> <output-dir>");
            System.exit(1);
        }

        Path harFile = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);

        ImportSummary summary = importHar(harFile, outputDir);
        printSummary(summary);
    }

    public static ImportSummary importHar(Path harFile, Path outputDir) throws IOException {
        if (!Files.isRegularFile(harFile)) {
            throw new IllegalArgumentException("HAR file not found: " + harFile);
        }

        Files.createDirectories(outputDir);

        JsonNode root = mapper.readTree(harFile.toFile());
        JsonNode entries = root.path("log").path("entries");

        ImportSummary summary = new ImportSummary();
        if (!entries.isArray()) {
            return summary;
        }

        Map<String, Integer> groupSeqMap = new LinkedHashMap<>();

        for (JsonNode entry : entries) {
            summary.totalEntries++;

            // Status must be 200
            int status = entry.path("response").path("status").asInt(-1);
            if (status != 200) {
                continue;
            }

            // Content-Type must be application/json
            JsonNode contentNode = entry.path("response").path("content");
            String mimeType = contentNode.path("mimeType").asText("");
            if (mimeType.isEmpty() || !mimeType.toLowerCase().contains("application/json")) {
                continue;
            }

            // Body text
            JsonNode textNode = contentNode.path("text");
            if (textNode.isMissingNode() || textNode.isNull()) {
                continue;
            }
            String bodyText = textNode.asText();
            if (bodyText.isBlank()) {
                continue;
            }

            byte[] bodyBytes = bodyText.getBytes(StandardCharsets.UTF_8);
            if (bodyBytes.length > MAX_BODY_BYTES) {
                continue;
            }

            // Validate valid JSON
            try {
                mapper.readTree(bodyBytes);
            } catch (Exception e) {
                continue; // Skip invalid JSON
            }

            // Determine URL and normalized group
            String url = entry.path("request").path("url").asText("");
            String groupPath = extractGroupPath(url);
            String fsGroup = toFilesystemSafe(groupPath);

            Path groupDir = outputDir.resolve(fsGroup);
            Files.createDirectories(groupDir);

            int seq = groupSeqMap.getOrDefault(fsGroup, 0) + 1;
            groupSeqMap.put(fsGroup, seq);

            String fileName = String.format("%06d.json", seq);
            Path targetFile = groupDir.resolve(fileName);
            Files.write(targetFile, bodyBytes);

            summary.filesWritten++;
            summary.totalBytesWritten += bodyBytes.length;

            GroupStats stats = summary.groups.computeIfAbsent(fsGroup, k -> new GroupStats(groupPath, fsGroup));
            stats.fileCount++;
            stats.totalBytes += bodyBytes.length;
        }

        return summary;
    }

    public static String extractGroupPath(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            return "/";
        }
        String path;
        try {
            URI uri = URI.create(urlString);
            path = uri.getPath();
        } catch (Exception e) {
            int q = urlString.indexOf('?');
            String withoutQuery = (q >= 0) ? urlString.substring(0, q) : urlString;
            int schemeIdx = withoutQuery.indexOf("://");
            if (schemeIdx >= 0) {
                int firstSlash = withoutQuery.indexOf('/', schemeIdx + 3);
                path = (firstSlash >= 0) ? withoutQuery.substring(firstSlash) : "/";
            } else {
                path = withoutQuery;
            }
        }

        if (path == null || path.isBlank()) {
            return "/";
        }

        // Normalize segments: replace numeric or UUID segments with {id}
        String[] rawSegments = path.split("/");
        List<String> normalized = new ArrayList<>();
        for (String seg : rawSegments) {
            if (seg.isEmpty()) continue;
            if (NUMERIC_PATTERN.matcher(seg).matches() || UUID_PATTERN.matcher(seg).matches()) {
                normalized.add("{id}");
            } else {
                normalized.add(seg);
            }
        }

        if (normalized.isEmpty()) {
            return "/";
        }
        return "/" + String.join("/", normalized);
    }

    public static String toFilesystemSafe(String groupPath) {
        if (groupPath == null || groupPath.isBlank() || "/".equals(groupPath)) {
            return "root";
        }
        String clean = groupPath.replaceAll("^/+", "").replaceAll("[^a-zA-Z0-9_{}]+", "_");
        if (clean.endsWith("_")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean.isEmpty() ? "root" : clean;
    }

    public static void printSummary(ImportSummary summary) {
        System.out.println("=== HAR Import Summary ===");
        System.out.printf("Total entries scanned: %d%n", summary.getTotalEntries());
        System.out.printf("JSON payloads saved:   %d%n", summary.getFilesWritten());
        System.out.printf("Total payload bytes:   %d%n", summary.getTotalBytesWritten());
        System.out.printf("Groups found:          %d%n", summary.getGroups().size());
        System.out.println("-----------------------------------------------------------------");
        System.out.printf("%-35s %10s %15s%n", "Group (Filesystem)", "Payloads", "Total Bytes");
        System.out.println("-----------------------------------------------------------------");
        for (GroupStats stats : summary.getGroups().values()) {
            System.out.printf("%-35s %10d %15d%n", stats.getFilesystemGroup(), stats.getFileCount(), stats.getTotalBytes());
        }
        System.out.println("-----------------------------------------------------------------");
    }
}
