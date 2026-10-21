package dev.zdict.client.okhttp;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictDecompress;
import dev.zdict.core.ZdictConstants;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OkHttp Interceptor that implements the zdict client protocol:
 * - Advertises cached dictionary IDs via X-Dict-Ids header
 * - Fetches new dictionaries announced via X-Dict-Available
 * - Decodes zstd-dict compressed response bodies
 * - Falls back to plain body on any decompression or download failure
 */
public class ZdictInterceptor implements Interceptor, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ZdictInterceptor.class);

    private final OkHttpClient fetchClient;
    private final Map<String, ZstdDictDecompress> dictCache = new ConcurrentHashMap<>();

    public ZdictInterceptor() {
        this(new OkHttpClient());
    }

    public ZdictInterceptor(OkHttpClient fetchClient) {
        this.fetchClient = fetchClient != null ? fetchClient : new OkHttpClient();
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request originalRequest = chain.request();
        Request.Builder requestBuilder = originalRequest.newBuilder();

        // 1. Advertise known dictionary IDs if not already set
        if (originalRequest.header(ZdictConstants.HEADER_DICT_IDS) == null && !dictCache.isEmpty()) {
            String ids = String.join(", ", dictCache.keySet());
            requestBuilder.header(ZdictConstants.HEADER_DICT_IDS, ids);
        }

        Response response = chain.proceed(requestBuilder.build());

        // 2. Check if server signals a new dictionary is available
        String availableId = response.header(ZdictConstants.HEADER_DICT_AVAILABLE);
        if (availableId != null && !dictCache.containsKey(availableId)) {
            fetchAndCacheDictionary(originalRequest.url(), availableId);
        }

        // Backward compatibility: also check legacy X-Dict-Id header on uncompressed responses
        String respDictId = response.header("X-Dict-Id");
        String contentEncoding = response.header("Content-Encoding");
        boolean isZstdDict = contentEncoding != null && ZdictConstants.ENCODING_ZSTD_DICT.equalsIgnoreCase(contentEncoding.trim());

        if (!isZstdDict && respDictId != null && !dictCache.containsKey(respDictId)) {
            fetchAndCacheDictionary(originalRequest.url(), respDictId);
        }

        // 3. Decompress if zstd-dict
        if (isZstdDict && respDictId != null) {
            ResponseBody body = response.body();
            if (body == null) {
                return response;
            }

            byte[] rawBytes;
            try {
                rawBytes = body.bytes();
            } catch (Exception e) {
                log.warn("Failed to read response body for zstd-dict decoding: {}", e.getMessage());
                return response;
            }

            ZstdDictDecompress ddict = dictCache.get(respDictId);
            if (ddict == null) {
                fetchAndCacheDictionary(originalRequest.url(), respDictId);
                ddict = dictCache.get(respDictId);
            }

            if (ddict != null) {
                try {
                    long decompressedSize = Zstd.decompressedSize(rawBytes);
                    byte[] decompressed = Zstd.decompress(rawBytes, ddict, (int) decompressedSize);
                    MediaType contentType = body.contentType();
                    ResponseBody newBody = ResponseBody.create(decompressed, contentType);

                    return response.newBuilder()
                            .removeHeader("Content-Encoding")
                            .body(newBody)
                            .build();
                } catch (Exception e) {
                    log.warn("Failed to decompress zstd-dict response with dict {}: {}. Falling back to plain.",
                            respDictId, e.getMessage());
                    // Fall back to plain with raw bytes
                    ResponseBody fallbackBody = ResponseBody.create(rawBytes, body.contentType());
                    return response.newBuilder()
                            .body(fallbackBody)
                            .build();
                }
            } else {
                log.warn("Missing dictionary {} for zstd-dict response, falling back to plain body", respDictId);
                ResponseBody fallbackBody = ResponseBody.create(rawBytes, body.contentType());
                return response.newBuilder()
                        .body(fallbackBody)
                        .build();
            }
        }

        return response;
    }

    private void fetchAndCacheDictionary(HttpUrl requestUrl, String dictId) {
        HttpUrl dictUrl = requestUrl.newBuilder()
                .encodedPath("/zdict/dict/" + dictId)
                .query(null)
                .build();

        Request dictReq = new Request.Builder()
                .url(dictUrl)
                .get()
                .build();

        try (Response dictResp = fetchClient.newCall(dictReq).execute()) {
            if (dictResp.isSuccessful() && dictResp.body() != null) {
                byte[] dictBytes = dictResp.body().bytes();
                if (dictBytes.length > 0) {
                    dictCache.put(dictId, new ZstdDictDecompress(dictBytes));
                    log.info("Fetched and cached dictionary id={}, size={} bytes", dictId, dictBytes.length);
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch dictionary id={} from {}: {}", dictId, dictUrl, e.getMessage());
        }

        // Fallback to legacy path /zdict/dictionary/{dictId}
        HttpUrl legacyUrl = requestUrl.newBuilder()
                .encodedPath("/zdict/dictionary/" + dictId)
                .query(null)
                .build();
        Request legacyReq = new Request.Builder()
                .url(legacyUrl)
                .get()
                .build();
        try (Response legacyResp = fetchClient.newCall(legacyReq).execute()) {
            if (legacyResp.isSuccessful() && legacyResp.body() != null) {
                byte[] dictBytes = legacyResp.body().bytes();
                if (dictBytes.length > 0) {
                    dictCache.put(dictId, new ZstdDictDecompress(dictBytes));
                    log.info("Fetched and cached dictionary id={} via legacy path", dictId);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch dictionary id={} via legacy path: {}", dictId, e.getMessage());
        }
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
