package dev.zdict.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "zdict.enabled=true",
        "zdict.dashboard.enabled=false"
})
@AutoConfigureMockMvc
class ZdictDashboardDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void dashboardReturns404WhenDisabled() throws Exception {
        mockMvc.perform(get("/zdict/dashboard"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/zdict/dashboard.html"))
                .andExpect(status().isNotFound());
    }
}
