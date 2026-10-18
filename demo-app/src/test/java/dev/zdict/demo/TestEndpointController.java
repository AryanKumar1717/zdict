package dev.zdict.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Helper test endpoints under /api/products for testing edge cases
 * (small body < 200 bytes, non-JSON Content-Type).
 */
@RestController
class TestEndpointController {

    @GetMapping("/api/products/tiny")
    public String tiny() {
        // Valid JSON under 200 bytes (15 bytes)
        return "{\"status\":\"ok\"}";
    }

    @GetMapping(value = "/api/products/text", produces = "text/plain")
    public String plainText() {
        // Plain text (non-JSON) over 200 bytes
        return "This is a plain text response that exceeds the minimum size of 200 bytes for zdict compression. ".repeat(4);
    }
}
