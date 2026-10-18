package dev.zdict.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests that the filter is a no-op when zdict.enabled=false (the default).
 */
@SpringBootTest(properties = {"zdict.enabled=false"})
@AutoConfigureMockMvc
class ZstdDictFilterDisabledTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void passThroughWhenDisabled() throws Exception {
        mockMvc.perform(get("/api/products?page=1")
                        .header("X-Dict-Id", "anything"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Encoding"))
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
