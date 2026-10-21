package dev.zdict.client.okhttp;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import dev.zdict.core.ZdictConstants;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ZdictInterceptorTest {

    private MockWebServer server;
    private ZdictInterceptor interceptor;
    private OkHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        interceptor = new ZdictInterceptor();
        client = new OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        interceptor.close();
        server.shutdown();
    }

    @Test
    void plainResponsePassesThrough() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\":\"hello\"}"));

        Request request = new Request.Builder()
                .url(server.url("/api/test"))
                .build();

        try (Response response = client.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).isEqualTo("{\"message\":\"hello\"}");
        }

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getHeader("X-Dict-Ids")).isNull();
    }

    @Test
    void fetchesDictionaryOnDictAvailableAndAdvertisesOnNextRequest() throws Exception {
        byte[] dictBytes = "sample-dictionary-bytes-content-12345".getBytes(StandardCharsets.UTF_8);
        String dictId = ZdictConstants.computeDictId(dictBytes);

        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/zdict/dict/" + dictId)) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setBody(new Buffer().write(dictBytes));
                }
                if (request.getPath().equals("/api/first")) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("X-Dict-Available", dictId)
                            .setBody("{\"step\":\"one\"}");
                }
                if (request.getPath().equals("/api/second")) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setBody("{\"step\":\"two\"}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });

        // First request receives X-Dict-Available
        Request req1 = new Request.Builder().url(server.url("/api/first")).build();
        try (Response resp1 = client.newCall(req1).execute()) {
            assertThat(resp1.body().string()).isEqualTo("{\"step\":\"one\"}");
        }

        assertThat(interceptor.hasDictionary(dictId)).isTrue();

        // Second request sends X-Dict-Ids header
        Request req2 = new Request.Builder().url(server.url("/api/second")).build();
        try (Response resp2 = client.newCall(req2).execute()) {
            assertThat(resp2.body().string()).isEqualTo("{\"step\":\"two\"}");
        }

        // Check recorded requests: /api/first, /zdict/dict/..., /api/second
        RecordedRequest r1 = server.takeRequest();
        assertThat(r1.getPath()).isEqualTo("/api/first");

        RecordedRequest rDict = server.takeRequest();
        assertThat(rDict.getPath()).isEqualTo("/zdict/dict/" + dictId);

        RecordedRequest r2 = server.takeRequest();
        assertThat(r2.getPath()).isEqualTo("/api/second");
        assertThat(r2.getHeader("X-Dict-Ids")).contains(dictId);
    }

    @Test
    void decodesZstdDictCompressedResponse() throws Exception {
        byte[] dictBytes = "common-json-dictionary-sample-training-bytes".getBytes(StandardCharsets.UTF_8);
        String dictId = ZdictConstants.computeDictId(dictBytes);

        String jsonPayload = "{\"id\":42,\"name\":\"Super Widget\",\"category\":\"Tools\"}";
        byte[] originalBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);

        byte[] compressed;
        try (ZstdDictCompress cdict = new ZstdDictCompress(dictBytes, 3)) {
            compressed = Zstd.compress(originalBytes, cdict);
        }

        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/zdict/dict/" + dictId)) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setBody(new Buffer().write(dictBytes));
                }
                if (request.getPath().equals("/api/compressed")) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Encoding", "zstd-dict")
                            .setHeader("X-Dict-Id", dictId)
                            .setHeader("Content-Type", "application/json")
                            .setBody(new Buffer().write(compressed));
                }
                return new MockResponse().setResponseCode(404);
            }
        });

        Request req = new Request.Builder().url(server.url("/api/compressed")).build();
        try (Response resp = client.newCall(req).execute()) {
            assertThat(resp.code()).isEqualTo(200);
            assertThat(resp.header("Content-Encoding")).isNull(); // stripped after decompress
            assertThat(resp.body().string()).isEqualTo(jsonPayload);
        }
    }

    @Test
    void fallsBackToPlainOnCorruptedZstdPayload() throws Exception {
        byte[] dictBytes = "common-json-dictionary-sample-training-bytes".getBytes(StandardCharsets.UTF_8);
        String dictId = ZdictConstants.computeDictId(dictBytes);

        byte[] corruptPayload = "not-really-zstd-compressed-data".getBytes(StandardCharsets.UTF_8);

        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/zdict/dict/" + dictId)) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setBody(new Buffer().write(dictBytes));
                }
                if (request.getPath().equals("/api/corrupt")) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Encoding", "zstd-dict")
                            .setHeader("X-Dict-Id", dictId)
                            .setBody(new Buffer().write(corruptPayload));
                }
                return new MockResponse().setResponseCode(404);
            }
        });

        Request req = new Request.Builder().url(server.url("/api/corrupt")).build();
        try (Response resp = client.newCall(req).execute()) {
            assertThat(resp.code()).isEqualTo(200);
            // Returns raw body without crashing
            assertThat(resp.body().string()).isEqualTo("not-really-zstd-compressed-data");
        }
    }

    @Test
    void fallsBackToPlainWhenDictionaryMissingAndDownloadFails() throws Exception {
        byte[] rawBytes = "{\"message\":\"fallback-bytes\"}".getBytes(StandardCharsets.UTF_8);

        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().startsWith("/zdict/dict/")) {
                    return new MockResponse().setResponseCode(404);
                }
                if (request.getPath().equals("/api/missing-dict")) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Encoding", "zstd-dict")
                            .setHeader("X-Dict-Id", "unknown-dict-id")
                            .setBody(new Buffer().write(rawBytes));
                }
                return new MockResponse().setResponseCode(404);
            }
        });

        Request req = new Request.Builder().url(server.url("/api/missing-dict")).build();
        try (Response resp = client.newCall(req).execute()) {
            assertThat(resp.code()).isEqualTo(200);
            assertThat(resp.body().string()).isEqualTo("{\"message\":\"fallback-bytes\"}");
        }
    }
}
