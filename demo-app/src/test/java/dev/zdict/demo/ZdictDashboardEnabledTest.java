package dev.zdict.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "zdict.enabled=true",
        "zdict.dashboard.enabled=true",
        "management.endpoints.web.exposure.include=health,info,zdict"
})
@AutoConfigureMockMvc
class ZdictDashboardEnabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void dashboardReturns200AndHtmlWhenEnabled() throws Exception {
        mockMvc.perform(get("/zdict/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("zdict")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Dashboard")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/actuator/zdict/groups")));

        mockMvc.perform(get("/zdict/dashboard.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    void actuatorGroupsIncludesExtendedMetrics() throws Exception {
        mockMvc.perform(get("/actuator/zdict/groups"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].bytesOriginal").exists())
                .andExpect(jsonPath("$[0].bytesCompressed").exists())
                .andExpect(jsonPath("$[0].bytesSaved").exists())
                .andExpect(jsonPath("$[0].compressedResponses").exists());
    }

    @Test
    void actuatorEventsReturnsList() throws Exception {
        mockMvc.perform(get("/actuator/zdict/events"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }
}
