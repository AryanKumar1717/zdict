package dev.zdict.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

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

    private final ProductController controller;
    private final ObjectMapper mapper;

    public SampleCollector(ProductController controller, ObjectMapper mapper) {
        this.controller = controller;
        this.mapper = mapper;
    }

    @Override
    public void run(String... args) throws Exception {
        Path dir = Files.isDirectory(Path.of("demo-app")) ? Path.of("demo-app/samples") : Path.of("samples");
        Files.createDirectories(dir);

        int count = 0;

        // ~500 list calls across pages
        for (int page = 1; page <= 500; page++) {
            String json = mapper.writeValueAsString(controller.list(page));
            Files.writeString(dir.resolve("list_" + page + ".json"), json);
            count++;
        }

        // ~500 single-product calls
        for (int id = 0; id < 500; id++) {
            String json = mapper.writeValueAsString(controller.single(id));
            Files.writeString(dir.resolve("single_" + id + ".json"), json);
            count++;
        }

        System.out.println("Collected " + count + " samples into " + dir.toAbsolutePath());
    }

    public static void main(String[] args) throws Exception {
        SampleCollector collector = new SampleCollector(
                new ProductController(),
                new ObjectMapper().findAndRegisterModules()
        );
        collector.run(args);
    }
}
