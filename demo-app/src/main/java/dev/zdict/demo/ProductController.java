package dev.zdict.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductGenerator generator = new ProductGenerator(42);

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page) {
        List<Product> items = generator.generatePage(page);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("page", page);
        data.put("total", items.size() * 10);
        return wrap(data, "page=" + page);
    }

    @GetMapping("/{id}")
    public Map<String, Object> single(@PathVariable int id) {
        Product product = generator.generate(id);
        return wrap(product, "product=" + id);
    }

    private Map<String, Object> wrap(Object data, String key) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", "ok");
        envelope.put("requestId", UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString());
        envelope.put("data", data);
        return envelope;
    }
}
