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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Servlet filter that compresses JSON responses using zstd with a shared dictionary.
 *
 * <p>Compression is applied only when ALL conditions are met:
 * <ul>
 *   <li>zdict.enabled = true</li>
 *   <li>HTTP method is GET</li>
 *   <li>Response status is 200</li>
 *   <li>Content-Type is application/json</li>
 *   <li>Request path starts with one of the configured allowlist prefixes</li>
 *   <li>Response body is between 200 bytes and 1 MB</li>
 *   <li>The request sends X-Dict-Id matching the server's current dictionary id</li>
 * </ul>
 *
 * <p>Fails open: any compression error logs a warning and sends the uncompressed body.
 */
@Component
@EnableConfigurationProperties(ZdictProperties.class)
public class ZstdDictFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ZstdDictFilter.class);

    private static final int LEVEL = 3;
    private static final int MIN_SIZE = 200;
    private static final int MAX_SIZE = 1_000_000;

    private final ZdictProperties props;

    private byte[] dictBytes;
    private String dictId;
    private ZstdDictCompress cdict;

    public ZstdDictFilter(ZdictProperties props) {
        this.props = props;
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
        if (!props.isEnabled() || cdict == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Only compress GET requests
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        // Check X-Dict-Id header
        String clientDictId = request.getHeader("X-Dict-Id");
        if (clientDictId == null || !clientDictId.equals(dictId)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Check path allowlist
        if (!isAllowedPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        // Wrap response to capture output
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, wrappedResponse);

        try {
            // Check status
            if (wrappedResponse.getStatus() != 200) {
                wrappedResponse.copyBodyToResponse();
                return;
            }

            // Check content type
            String contentType = wrappedResponse.getContentType();
            if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
                wrappedResponse.copyBodyToResponse();
                return;
            }

            byte[] body = wrappedResponse.getContentAsByteArray();

            // Check size bounds
            if (body.length < MIN_SIZE || body.length > MAX_SIZE) {
                wrappedResponse.copyBodyToResponse();
                return;
            }

            // Compress
            byte[] compressed = Zstd.compress(body, cdict);

            response.setContentLength(compressed.length);
            response.setHeader("Content-Encoding", "zstd-dict");
            response.setHeader("X-Dict-Id", dictId);
            response.setHeader("Vary", "X-Dict-Id, Accept-Encoding");
            response.getOutputStream().write(compressed);
            response.getOutputStream().flush();

        } catch (Exception e) {
            // Fail open: log and send original body
            log.warn("zstd compression failed, sending uncompressed: {}", e.getMessage());
            try {
                wrappedResponse.copyBodyToResponse();
            } catch (Exception inner) {
                log.error("Failed to send fallback uncompressed body", inner);
            }
        }
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
    static String computeDictId(byte[] dict) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(dict);
            return HexFormat.of().formatHex(hash).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // Package-private accessors for tests and the dictionary endpoint
    String getDictId() {
        return dictId;
    }

    byte[] getDictBytes() {
        return dictBytes;
    }

    void setCdictForTesting(ZstdDictCompress cdict) {
        this.cdict = cdict;
    }
}
