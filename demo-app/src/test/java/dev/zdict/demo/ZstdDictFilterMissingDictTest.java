package dev.zdict.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests that the filter disables itself when the dictionary file is missing.
 */
@SpringBootTest(properties = {
        "zdict.enabled=true",
        "zdict.dictionary-path=/nonexistent/path/dict.bin",
        "zdict.paths=/api/products"
})
@AutoConfigureMockMvc
class ZstdDictFilterMissingDictTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void passThroughWhenDictionaryMissing() throws Exception {
        mockMvc.perform(get("/api/products?page=1")
                        .header("X-Dict-Id", "anything"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Encoding"))
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
