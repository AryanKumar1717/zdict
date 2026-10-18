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
 * HTTP client that negotiates and decompresses zstd dictionary responses.
 */
public class ZdictClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ZdictClient.class);

    private final HttpClient httpClient;
    private final Map<String, ZstdDictDecompress> dictCache = new ConcurrentHashMap<>();
    private final Map<String, String> hostToDictId = new ConcurrentHashMap<>();

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
        String host = uri.getAuthority();
        if (host != null) {
            String cachedDictId = hostToDictId.get(host);
            if (cachedDictId != null && dictCache.containsKey(cachedDictId)) {
                builder.header("X-Dict-Id", cachedDictId);
            }
        }
        return send(builder.build());
    }

    public ZdictResponse send(HttpRequest request) throws IOException, InterruptedException {
        URI uri = request.uri();
        String host = uri.getAuthority();

        // If the request doesn't explicitly have X-Dict-Id, attach cached one if known
        HttpRequest actualRequest = request;
        if (request.headers().firstValue("X-Dict-Id").isEmpty() && host != null) {
            String cachedDictId = hostToDictId.get(host);
            if (cachedDictId != null && dictCache.containsKey(cachedDictId)) {
                HttpRequest.Builder builder = copyRequest(request);
                builder.header("X-Dict-Id", cachedDictId);
                actualRequest = builder.build();
            }
        }

        HttpResponse<byte[]> response = httpClient.send(actualRequest, HttpResponse.BodyHandlers.ofByteArray());

        // Check if server advertises a dictionary ID
        String respDictId = response.headers().firstValue("X-Dict-Id").orElse(null);
        if (respDictId != null && !dictCache.containsKey(respDictId)) {
            fetchAndCacheDictionary(uri, host, respDictId);
        }

        // Check if response is compressed with zstd-dict
        boolean isZstdDict = response.headers().firstValue("Content-Encoding")
                .map("zstd-dict"::equalsIgnoreCase)
                .orElse(false);

        byte[] rawBytes = response.body();
        if (isZstdDict && respDictId != null) {
            ZstdDictDecompress ddict = dictCache.get(respDictId);
            if (ddict != null) {
                try {
                    long decompressedSize = Zstd.decompressedSize(rawBytes);
                    byte[] decompressed = Zstd.decompress(rawBytes, ddict, (int) decompressedSize);
                    String body = new String(decompressed, StandardCharsets.UTF_8);
                    return new ZdictResponse(response.statusCode(), body, rawBytes, true, response);
                } catch (Exception e) {
                    log.warn("Failed to decompress zstd-dict response: {}", e.getMessage());
                }
            }
        }

        String body = new String(rawBytes, StandardCharsets.UTF_8);
        return new ZdictResponse(response.statusCode(), body, rawBytes, false, response);
    }

    private void fetchAndCacheDictionary(URI baseUri, String host, String dictId) {
        try {
            URI dictUri = baseUri.resolve("/zdict/dictionary/" + dictId);
            HttpRequest dictReq = HttpRequest.newBuilder(dictUri).GET().build();
            HttpResponse<byte[]> dictResp = httpClient.send(dictReq, HttpResponse.BodyHandlers.ofByteArray());
            if (dictResp.statusCode() == 200 && dictResp.body().length > 0) {
                byte[] dictBytes = dictResp.body();
                dictCache.put(dictId, new ZstdDictDecompress(dictBytes));
                if (host != null) {
                    hostToDictId.put(host, dictId);
                }
                log.info("Fetched and cached dictionary id={}, size={} bytes", dictId, dictBytes.length);
            } else {
                log.warn("Dictionary fetch failed with status: {}", dictResp.statusCode());
            }
        } catch (Exception e) {
            // Fall back to plain requests with no error to the caller
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

    @Override
    public void close() {
        for (ZstdDictDecompress ddict : dictCache.values()) {
            ddict.close();
        }
        dictCache.clear();
        hostToDictId.clear();
    }
}
