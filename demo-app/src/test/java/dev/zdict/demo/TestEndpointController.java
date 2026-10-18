package dev.zdict.demo;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Helper test endpoints under /api/products for testing edge cases
 * (small body < 200 bytes, non-JSON Content-Type, Set-Cookie, private cache, reflected input).
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

    @GetMapping("/api/products/set-cookie")
    public String withSetCookie(HttpServletResponse response) {
        response.setHeader("Set-Cookie", "sessionId=abc12345; Path=/");
        return "{\"status\":\"ok\",\"data\":\"" + "safe-content-filler-bytes-".repeat(15) + "\"}";
    }

    @GetMapping("/api/products/private-cache")
    public String withPrivateCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        return "{\"status\":\"ok\",\"data\":\"" + "safe-content-filler-bytes-".repeat(15) + "\"}";
    }

    @GetMapping("/api/products/reflect")
    public String reflect(@RequestParam("search") String search) {
        return "{\"status\":\"ok\",\"search\":\"" + search + "\",\"data\":\"" + "safe-content-filler-bytes-".repeat(15) + "\"}";
    }
}
