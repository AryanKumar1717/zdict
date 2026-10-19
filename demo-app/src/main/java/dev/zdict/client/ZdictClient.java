package dev.zdict.client;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictDecompress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP client that holds multiple dictionaries by id, negotiates via X-Dict-Ids,
 * fetches dictionaries announced via X-Dict-Available, and decompresses by response X-Dict-Id.
 */
public class ZdictClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ZdictClient.class);

    private final HttpClient httpClient;
    private final Map<String, ZstdDictDecompress> dictCache = new ConcurrentHashMap<>();

    public ZdictClient() {
        this(HttpClient.newHttpClient());
    }

    public ZdictClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public ZdictResponse get(String url) throws IOException, InterruptedException {
        return get(URI.create(url));
    }

    public ZdictResponse get(URI uri) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET();
        attachDictIdsHeader(builder);
        return send(builder.build());
    }

    public ZdictResponse send(HttpRequest request) throws IOException, InterruptedException {
        HttpRequest actualRequest = request;
        if (request.headers().firstValue("X-Dict-Ids").isEmpty() && !dictCache.isEmpty()) {
            HttpRequest.Builder builder = copyRequest(request);
            attachDictIdsHeader(builder);
            actualRequest = builder.build();
        }

        HttpResponse<byte[]> response = httpClient.send(actualRequest, HttpResponse.BodyHandlers.ofByteArray());
        URI uri = actualRequest.uri();

        // 1. Check if server signals a new dictionary is available
        String availableId = response.headers().firstValue("X-Dict-Available").orElse(null);
        if (availableId != null && !dictCache.containsKey(availableId)) {
            fetchAndCacheDictionary(uri, availableId);
        }

        // Backward compatibility: also check legacy X-Dict-Id header on uncompressed responses
        String respDictId = response.headers().firstValue("X-Dict-Id").orElse(null);
        boolean isZstdDict = response.headers().firstValue("Content-Encoding")
                .map("zstd-dict"::equalsIgnoreCase)
                .orElse(false);

        if (!isZstdDict && respDictId != null && !dictCache.containsKey(respDictId)) {
            fetchAndCacheDictionary(uri, respDictId);
        }

        byte[] rawBytes = response.body();

        // 2. If compressed with zstd-dict, decode using the dictionary specified in X-Dict-Id
        if (isZstdDict && respDictId != null) {
            ZstdDictDecompress ddict = dictCache.get(respDictId);
            if (ddict == null) {
                // Fetch on demand if somehow missing
                fetchAndCacheDictionary(uri, respDictId);
                ddict = dictCache.get(respDictId);
            }

            if (ddict != null) {
                try {
                    long decompressedSize = Zstd.decompressedSize(rawBytes);
                    byte[] decompressed = Zstd.decompress(rawBytes, ddict, (int) decompressedSize);
                    String body = new String(decompressed, StandardCharsets.UTF_8);
                    return new ZdictResponse(response.statusCode(), body, rawBytes, true, response);
                } catch (Exception e) {
                    log.warn("Failed to decompress zstd-dict response with dict {}: {}", respDictId, e.getMessage());
                }
            } else {
                log.warn("Missing dictionary {} for zstd-dict response, falling back to raw body", respDictId);
            }
        }

        // Fall back to plain string
        String body = new String(rawBytes, StandardCharsets.UTF_8);
        return new ZdictResponse(response.statusCode(), body, rawBytes, false, response);
    }

    private void attachDictIdsHeader(HttpRequest.Builder builder) {
        if (!dictCache.isEmpty()) {
            String ids = String.join(", ", dictCache.keySet());
            builder.header("X-Dict-Ids", ids);
        }
    }

    private void fetchAndCacheDictionary(URI baseUri, String dictId) {
        try {
            URI dictUri = baseUri.resolve("/zdict/dictionary/" + dictId);
            HttpRequest dictReq = HttpRequest.newBuilder(dictUri).GET().build();
            HttpResponse<byte[]> dictResp = httpClient.send(dictReq, HttpResponse.BodyHandlers.ofByteArray());
            if (dictResp.statusCode() == 200 && dictResp.body().length > 0) {
                byte[] dictBytes = dictResp.body();
                dictCache.put(dictId, new ZstdDictDecompress(dictBytes));
                log.info("Fetched and cached dictionary id={}, size={} bytes", dictId, dictBytes.length);
            } else {
                log.warn("Dictionary fetch for {} failed with status: {}", dictId, dictResp.statusCode());
            }
        } catch (Exception e) {
            log.warn("Failed to fetch dictionary id={}: {}", dictId, e.getMessage());
        }
    }

    private HttpRequest.Builder copyRequest(HttpRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri());
        builder.method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
        request.headers().map().forEach((name, values) -> {
            for (String val : values) {
                builder.header(name, val);
            }
        });
        return builder;
    }

    public boolean hasDictionary(String dictId) {
        return dictCache.containsKey(dictId);
    }

    public int getDictionaryCount() {
        return dictCache.size();
    }

    @Override
    public void close() {
        for (ZstdDictDecompress ddict : dictCache.values()) {
            try {
                ddict.close();
            } catch (Exception ignored) {
            }
        }
        dictCache.clear();
    }
}
