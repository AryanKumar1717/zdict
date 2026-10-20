package dev.zdict.demo;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Controller
public class ZdictDashboardController {

    private final ZdictProperties properties;
    private final Resource dashboardResource = new ClassPathResource("static/zdict/dashboard.html");

    public ZdictDashboardController(ZdictProperties properties) {
        this.properties = properties;
    }

    @GetMapping(value = {"/zdict/dashboard", "/zdict/dashboard.html"})
    public ResponseEntity<String> getDashboard() throws IOException {
        if (!properties.isDashboardEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!dashboardResource.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String html = StreamUtils.copyToString(dashboardResource.getInputStream(), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }
}
