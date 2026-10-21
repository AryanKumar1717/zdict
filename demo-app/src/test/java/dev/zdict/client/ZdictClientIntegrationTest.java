package dev.zdict.client;

import dev.zdict.spring.ZstdDictFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zdict.demo.DemoApplication;

@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ZdictClientIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ZstdDictFilter filter;

    private ZdictClient client;

    @BeforeEach
    void setUp() {
        client = new ZdictClient();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void clientNegotiatesDictionaryAndDecompresses() throws Exception {
        String url = "http://localhost:" + port + "/api/products?page=2";

        // First request: sent without X-Dict-Id
        ZdictResponse first = client.get(url);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.compressed()).isFalse();
        assertThat(first.body()).contains("\"status\":\"ok\"");
        assertThat(first.body()).contains("\"items\":[");

        // Client should now have fetched and cached the dictionary
        assertThat(filter.getDictId()).isNotNull();
        assertThat(client.hasDictionary(filter.getDictId())).isTrue();

        // Second request: client now sends cached X-Dict-Id, receives compressed response
        ZdictResponse second = client.get(url);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.compressed()).isTrue();

        // Assert JSON equals the uncompressed response
        assertThat(second.body()).isEqualTo(first.body());

        // Assert compressed bytes on wire are smaller than the original
        assertThat(second.wireSize()).isLessThan(first.wireSize());
    }

    @Test
    void clientFallsBackToPlainRequestsIfDictionaryUnavailable() throws Exception {
        String nonExistentDictUrl = "http://localhost:" + port + "/api/products/tiny";
        // Plain request should succeed without error
        ZdictResponse response = client.get(nonExistentDictUrl);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"status\":\"ok\"}");
    }

    @Test
    void clientSendsMultipleDictIdsAndReceivesHintWhenNotPresent() throws Exception {
        String url = "http://localhost:" + port + "/api/products?page=1";
        try (ZdictClient multiClient = new ZdictClient()) {
            java.lang.reflect.Field field = ZdictClient.class.getDeclaredField("dictCache");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, com.github.luben.zstd.ZstdDictDecompress> cache =
                    (java.util.Map<String, com.github.luben.zstd.ZstdDictDecompress>) field.get(multiClient);
            cache.put("dummy111", new com.github.luben.zstd.ZstdDictDecompress(new byte[]{0, 1, 2, 3}));
            cache.put("dummy222", new com.github.luben.zstd.ZstdDictDecompress(new byte[]{4, 5, 6, 7}));

            ZdictResponse resp1 = multiClient.get(url);
            assertThat(resp1.statusCode()).isEqualTo(200);
            assertThat(resp1.compressed()).isFalse();

            String activeId = filter.getDictId();
            assertThat(multiClient.hasDictionary(activeId)).isTrue();
            assertThat(multiClient.getDictionaryCount()).isEqualTo(3);

            ZdictResponse resp2 = multiClient.get(url);
            assertThat(resp2.statusCode()).isEqualTo(200);
            assertThat(resp2.compressed()).isTrue();
            assertThat(resp2.httpResponse().headers().firstValue("Vary").orElse(""))
                    .containsIgnoringCase("X-Dict-Ids");
        }
    }
}
