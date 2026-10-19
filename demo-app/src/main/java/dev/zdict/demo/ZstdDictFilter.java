package dev.zdict.demo;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.lang.Nullable;
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
import java.util.HexFormat;
import java.util.Map;

/**
 * Servlet filter that compresses JSON responses using zstd with a shared dictionary.
 *
 * <p>Includes a safety layer (auth/cookie skip, BREACH protection), circuit breaker,
 * kill switch, and Micrometer metrics.
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

    private volatile boolean runtimeEnabled = true;

    private byte[] dictBytes;
    private String dictId;
    private ZstdDictCompress cdict;

    public ZstdDictFilter(ZdictProperties props) {
        this(props, new ZdictCircuitBreaker(), null, null);
    }

    public ZstdDictFilter(ZdictProperties props,
                          ZdictCircuitBreaker circuitBreaker,
                          @Nullable ZdictMetrics metrics) {
        this(props, circuitBreaker, metrics, null);
    }

    @Autowired
    public ZstdDictFilter(ZdictProperties props,
                          ZdictCircuitBreaker circuitBreaker,
                          @Nullable ZdictMetrics metrics,
                          @Nullable SampleStore sampleStore) {
        this.props = props;
        this.circuitBreaker = circuitBreaker != null ? circuitBreaker : new ZdictCircuitBreaker();
        this.metrics = metrics;
        this.sampleStore = sampleStore;
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

    @PostConstruct
    void init() {
        if (!props.isEnabled()) {
            log.info("zdict filter is disabled");
            return;
        }
        String pathStr = props.getDictionaryPath();
        if (pathStr == null || pathStr.isBlank()) {
            log.warn("zdict.enabled=true but no dictionary-path configured; filter disabled");
            props.setEnabled(false);
            return;
        }
        Path dictPath = Path.of(pathStr);
        if (!Files.isRegularFile(dictPath)) {
            Path fallback = Path.of("demo-app", pathStr);
            if (Files.isRegularFile(fallback)) {
                dictPath = fallback;
            } else {
                log.warn("Dictionary file not found: {}; zdict filter disabled", dictPath);
                props.setEnabled(false);
                return;
            }
        }
        try {
            dictBytes = Files.readAllBytes(dictPath);
            dictId = computeDictId(dictBytes);
            cdict = new ZstdDictCompress(dictBytes, LEVEL);
            log.info("zdict filter enabled, dictionary id={}, size={} bytes", dictId, dictBytes.length);
        } catch (Exception e) {
            log.warn("Failed to load dictionary: {}; zdict filter disabled", e.getMessage());
            props.setEnabled(false);
        }
    }

    @PreDestroy
    void cleanup() {
        if (cdict != null) {
            cdict.close();
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

            // Response is eligible for compression and sampling!
            if (dictId != null) {
                response.setHeader("X-Dict-Id", dictId);
            }

            boolean canCompress = isEnabled() && cdict != null && !circuitBreaker.isOpen();
            String clientDictId = request.getHeader("X-Dict-Id");
            boolean clientMatches = clientDictId != null && clientDictId.equals(dictId);

            if (canCompress && clientMatches) {
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

                    response.setContentLength(compressed.length);
                    response.setHeader("Content-Encoding", "zstd-dict");
                    response.setHeader("X-Dict-Id", dictId);
                    response.setHeader("Vary", "X-Dict-Id, Accept-Encoding");
                    response.getOutputStream().write(compressed);
                    response.getOutputStream().flush();
                } catch (Exception e) {
                    long elapsed = System.nanoTime() - start;
                    circuitBreaker.recordFailure(elapsed);
                    if (metrics != null) {
                        metrics.recordResponse("failed", "compression_error");
                    }
                    log.warn("zstd compression failed, sending uncompressed: {}", e.getMessage());
                    wrappedResponse.copyBodyToResponse();
                }
            } else {
                if (!isEnabled() || cdict == null) {
                    recordSkipped("disabled");
                } else if (circuitBreaker.isOpen()) {
                    recordSkipped("circuit_open");
                } else if (clientDictId == null) {
                    recordSkipped("no_dict_id");
                } else {
                    recordSkipped("wrong_dict_id");
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

    /**
     * Dictionary id = first 8 hex chars of the SHA-256 of the dictionary bytes.
     */
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
        return dictId;
    }

    public byte[] getDictBytes() {
        return dictBytes;
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
        this.cdict = cdict;
    }
}
