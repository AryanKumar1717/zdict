package dev.zdict.demo;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Filter that compresses eligible responses with active zstd dictionaries per endpoint group,
 * advertises available dictionaries via X-Dict-Available, and samples responses for learning.
 */
@Component
@EnableConfigurationProperties(ZdictProperties.class)
public class ZstdDictFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ZstdDictFilter.class);

    private static final int LEVEL = 3;
    private static final int MIN_SIZE = 200;
    private static final int MAX_SIZE = 1_000_000;

    private final ZdictProperties props;
    private final ZdictCircuitBreaker circuitBreaker;
    private final ZdictMetrics metrics;
    private final SampleStore sampleStore;
    private final DictionaryRegistry registry;
    private final RatioTracker ratioTracker;

    private volatile boolean runtimeEnabled = true;
    private volatile Random random = new Random();
    private ZstdDictCompress testingCdict;

    public void setRandom(Random random) {
        this.random = random != null ? random : new Random();
    }

    public ZstdDictFilter(ZdictProperties props) {
        this(props, new ZdictCircuitBreaker(), null, null, null, null);
    }

    public ZstdDictFilter(ZdictProperties props,
                          ZdictCircuitBreaker circuitBreaker,
                          @Nullable ZdictMetrics metrics) {
        this(props, circuitBreaker, metrics, null, null, null);
    }

    public ZstdDictFilter(ZdictProperties props,
                          ZdictCircuitBreaker circuitBreaker,
                          @Nullable ZdictMetrics metrics,
                          @Nullable SampleStore sampleStore) {
        this(props, circuitBreaker, metrics, sampleStore, null, null);
    }

    public ZstdDictFilter(ZdictProperties props,
                          ZdictCircuitBreaker circuitBreaker,
                          @Nullable ZdictMetrics metrics,
                          @Nullable SampleStore sampleStore,
                          @Nullable DictionaryRegistry registry) {
        this(props, circuitBreaker, metrics, sampleStore, registry, null);
    }

    @Autowired
    public ZstdDictFilter(ZdictProperties props,
                          ZdictCircuitBreaker circuitBreaker,
                          @Nullable ZdictMetrics metrics,
                          @Nullable SampleStore sampleStore,
                          @Nullable DictionaryRegistry registry,
                          @Nullable RatioTracker ratioTracker) {
        this.props = props;
        this.circuitBreaker = circuitBreaker != null ? circuitBreaker : new ZdictCircuitBreaker();
        this.metrics = metrics;
        this.sampleStore = sampleStore;
        this.registry = registry;
        this.ratioTracker = ratioTracker;
    }

    public boolean isEnabled() {
        return props.isEnabled() && runtimeEnabled;
    }

    public void setEnabled(boolean enabled) {
        this.runtimeEnabled = enabled;
    }

    public ZdictCircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public ZdictMetrics getMetrics() {
        return metrics;
    }

    public DictionaryRegistry getRegistry() {
        return registry;
    }

    public RatioTracker getRatioTracker() {
        return ratioTracker;
    }

    @PostConstruct
    void init() {
        if (!props.isEnabled()) {
            log.info("zdict filter is disabled");
            return;
        }
        String pathStr = props.getSeedDictionaryPath();
        if (pathStr != null && !pathStr.isBlank()) {
            Path dictPath = Path.of(pathStr);
            if (!Files.isRegularFile(dictPath)) {
                Path fallback = Path.of("demo-app", pathStr);
                if (Files.isRegularFile(fallback)) {
                    dictPath = fallback;
                }
            }
            if (Files.isRegularFile(dictPath)) {
                try {
                    byte[] bytes = Files.readAllBytes(dictPath);
                    String id = computeDictId(bytes);
                    if (registry != null) {
                        DictionaryVersion active = registry.getActive("/api/products");
                        if (active == null || !active.getId().equals(id)) {
                            registry.register("/api/products", bytes, 1000, 0.40);
                            log.info("Seeded initial dictionary for /api/products from {}", dictPath);
                        }
                        DictionaryVersion activeId = registry.getActive("/api/products/{id}");
                        if (activeId == null || !activeId.getId().equals(id)) {
                            registry.register("/api/products/{id}", bytes, 1000, 0.40);
                            log.info("Seeded initial dictionary for /api/products/{id} from {}", dictPath);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to seed dictionary from {}: {}", dictPath, e.getMessage());
                }
            } else {
                log.warn("Dictionary file not found: {}", dictPath);
            }
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();

        // Check path allowlist
        if (!isAllowedPath(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Only compress GET requests
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            recordSkipped("unsupported_method");
            filterChain.doFilter(request, response);
            return;
        }

        // Safety: skip if request has Authorization or Cookie header (unless allow-authenticated=true)
        if (!props.isAllowAuthenticated()) {
            if (request.getHeader("Authorization") != null || request.getHeader("Cookie") != null) {
                recordSkipped("authenticated_request");
                filterChain.doFilter(request, response);
                return;
            }
        }

        // Wrap response to capture output for compression and sampling
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, wrappedResponse);

        String groupKey = resolveGroupKey(request);

        try {
            // Check status
            if (wrappedResponse.getStatus() != 200) {
                recordSkipped("status_not_200");
                wrappedResponse.copyBodyToResponse();
                return;
            }

            // Safety: response has Set-Cookie or Cache-Control: private/no-store
            if (!props.isAllowAuthenticated()) {
                if (wrappedResponse.getHeader("Set-Cookie") != null) {
                    recordSkipped("set_cookie_response");
                    wrappedResponse.copyBodyToResponse();
                    return;
                }
                String cacheControl = wrappedResponse.getHeader("Cache-Control");
                if (cacheControl != null) {
                    String ccLower = cacheControl.toLowerCase();
                    if (ccLower.contains("private") || ccLower.contains("no-store")) {
                        recordSkipped("private_cache_response");
                        wrappedResponse.copyBodyToResponse();
                        return;
                    }
                }
            }

            // Check content type
            String contentType = wrappedResponse.getContentType();
            if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
                recordSkipped("non_json");
                wrappedResponse.copyBodyToResponse();
                return;
            }

            byte[] body = wrappedResponse.getContentAsByteArray();

            // Check size bounds
            if (body.length < MIN_SIZE || body.length > MAX_SIZE) {
                recordSkipped("size_out_of_bounds");
                wrappedResponse.copyBodyToResponse();
                return;
            }

            // BREACH guard: skip compression if query param value (length >= 4) appears verbatim in body
            if (props.isSkipReflectedInput() && isReflectedInput(request, body)) {
                recordSkipped("reflected_input");
                wrappedResponse.copyBodyToResponse();
                return;
            }

            // Active and Canary dictionary lookup for this group
            DictionaryVersion activeDict = (registry != null) ? registry.getActive(groupKey) : null;
            DictionaryVersion canaryDict = (registry != null) ? registry.getCanary(groupKey) : null;

            Set<String> clientDictIds = getClientDictIds(request);
            boolean clientHasCanary = (canaryDict != null) && clientDictIds.contains(canaryDict.getId());
            boolean clientHasActive = (activeDict != null) && clientDictIds.contains(activeDict.getId());

            int canaryPercent = props.getCanary().getPercent();
            boolean isCanaryRoll = (random.nextInt(100) < canaryPercent);

            DictionaryVersion chosenDict = null;
            if (testingCdict != null) {
                if (clientDictIds.contains("testdict")) {
                    chosenDict = null; // testing override
                }
            } else if (clientHasCanary && isCanaryRoll) {
                chosenDict = canaryDict;
            } else if (clientHasActive) {
                chosenDict = activeDict;
            }

            ZstdDictCompress cdict = (testingCdict != null) ? testingCdict : ((chosenDict != null) ? chosenDict.getCdict() : null);
            String dictId = (chosenDict != null) ? chosenDict.getId() : (testingCdict != null && clientDictIds.contains("testdict") ? "testdict" : null);

            boolean canCompress = isEnabled() && cdict != null && !circuitBreaker.isOpen();

            if (canCompress && dictId != null) {
                long start = System.nanoTime();
                try {
                    byte[] compressed = Zstd.compress(body, cdict);
                    long elapsed = System.nanoTime() - start;
                    circuitBreaker.recordSuccess(elapsed);

                    if (metrics != null) {
                        metrics.recordCompressTime(elapsed);
                        metrics.recordBytesOriginal(groupKey, body.length);
                        metrics.recordBytesCompressed(groupKey, compressed.length);
                        metrics.recordResponse("compressed", "none");
                    }
                    if (ratioTracker != null) {
                        ratioTracker.recordSuccess(groupKey, dictId, body.length, compressed.length);
                    }

                    response.setContentLength(compressed.length);
                    response.setHeader("Content-Encoding", "zstd-dict");
                    response.setHeader("X-Dict-Id", dictId);
                    response.setHeader("Vary", "X-Dict-Ids, Accept-Encoding");

                    // For roughly the same percent of requests where client lacks canary id, send X-Dict-Available
                    if (canaryDict != null && !clientHasCanary && isCanaryRoll) {
                        response.setHeader("X-Dict-Available", canaryDict.getId());
                    }

                    response.getOutputStream().write(compressed);
                    response.getOutputStream().flush();
                } catch (Exception e) {
                    long elapsed = System.nanoTime() - start;
                    circuitBreaker.recordFailure(elapsed);
                    if (metrics != null) {
                        metrics.recordResponse("failed", "compression_error");
                    }
                    if (ratioTracker != null) {
                        ratioTracker.recordFailure(groupKey, dictId);
                    }
                    log.warn("zstd compression failed, sending uncompressed: {}", e.getMessage());
                    wrappedResponse.copyBodyToResponse();
                }
            } else {
                if (!isEnabled() || (activeDict == null && canaryDict == null && testingCdict == null)) {
                    recordSkipped("disabled");
                } else if (circuitBreaker.isOpen()) {
                    recordSkipped("circuit_open");
                } else if (clientDictIds.isEmpty()) {
                    recordSkipped("no_dict_id");
                } else {
                    recordSkipped("wrong_dict_id");
                }

                // If canary exists and client lacks it, send canary hint on canary roll; else send active hint
                if (canaryDict != null && !clientHasCanary && isCanaryRoll) {
                    response.setHeader("X-Dict-Available", canaryDict.getId());
                } else if (activeDict != null) {
                    response.setHeader("X-Dict-Available", activeDict.getId());
                    response.setHeader("X-Dict-Id", activeDict.getId()); // for backward compatibility
                }
                wrappedResponse.copyBodyToResponse();
            }

            // Sampling: only sample responses eligible for compression
            if (sampleStore != null) {
                try {
                    sampleStore.offer(groupKey, body);
                } catch (Exception e) {
                    log.warn("Sampling error for group {}: {}", groupKey, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.warn("Error processing response: {}", e.getMessage());
            try {
                wrappedResponse.copyBodyToResponse();
            } catch (Exception inner) {
                log.error("Failed to copy body to response", inner);
            }
        }
    }

    private Set<String> getClientDictIds(HttpServletRequest request) {
        Set<String> ids = new HashSet<>();
        String idsHeader = request.getHeader("X-Dict-Ids");
        if (idsHeader != null && !idsHeader.isBlank()) {
            for (String part : idsHeader.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) ids.add(trimmed);
            }
        }
        String singleId = request.getHeader("X-Dict-Id");
        if (singleId != null && !singleId.isBlank()) {
            for (String part : singleId.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) ids.add(trimmed);
            }
        }
        return ids;
    }

    private void recordSkipped(String reason) {
        if (metrics != null) {
            metrics.recordResponse("skipped", reason);
        }
    }

    private boolean isReflectedInput(HttpServletRequest request, byte[] body) {
        Map<String, String[]> params = request.getParameterMap();
        if (params == null || params.isEmpty()) {
            return false;
        }
        String bodyString = new String(body, StandardCharsets.UTF_8);
        for (String[] values : params.values()) {
            if (values == null) continue;
            for (String val : values) {
                if (val != null && val.length() >= 4 && bodyString.contains(val)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isAllowedPath(String uri) {
        if (props.getPaths() == null) {
            return false;
        }
        for (String prefix : props.getPaths()) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static String computeDictId(byte[] dict) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(dict);
            return HexFormat.of().formatHex(hash).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public String getDictId() {
        if (registry != null) {
            DictionaryVersion v = registry.getActive("/api/products");
            if (v != null) return v.getId();
        }
        return null;
    }

    public byte[] getDictBytes() {
        if (registry != null) {
            DictionaryVersion v = registry.getActive("/api/products");
            if (v != null) return v.getDictBytes();
        }
        return null;
    }

    public static String resolveGroupKey(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern != null) {
            return pattern.toString();
        }
        return request.getRequestURI();
    }

    public static String toGroupSlug(String groupKey) {
        if (groupKey == null || groupKey.isBlank()) {
            return "default";
        }
        String slug = groupKey.replaceAll("^/+", "").replaceAll("[^a-zA-Z0-9]+", "_");
        if (slug.endsWith("_")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return slug.isEmpty() ? "root" : slug;
    }

    void setCdictForTesting(ZstdDictCompress cdict) {
        this.testingCdict = cdict;
    }
}
