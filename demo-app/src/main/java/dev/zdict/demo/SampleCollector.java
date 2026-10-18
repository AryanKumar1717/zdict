package dev.zdict.demo;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Calls the product endpoints ~1000 times and saves each JSON response
 * to demo-app/samples/ so they can be fed to zdict-bench.
 *
 * Activate with: --spring.profiles.active=collect
 */
@Component
@Profile("collect")
public class SampleCollector implements CommandLineRunner {

    @Override
    public void run(String... args) throws Exception {
        Path dir = Path.of("samples");
        Files.createDirectories(dir);

        RestTemplate rest = new RestTemplate();
        String base = "http://localhost:8080";
        int count = 0;

        // ~500 list calls across pages
        for (int page = 1; page <= 500; page++) {
            String json = rest.getForObject(base + "/api/products?page=" + page, String.class);
            Files.writeString(dir.resolve("list_" + page + ".json"), json);
            count++;
        }

        // ~500 single-product calls
        for (int id = 0; id < 500; id++) {
            String json = rest.getForObject(base + "/api/products/" + id, String.class);
            Files.writeString(dir.resolve("single_" + id + ".json"), json);
            count++;
        }

        System.out.println("Collected " + count + " samples into " + dir.toAbsolutePath());
    }
}
