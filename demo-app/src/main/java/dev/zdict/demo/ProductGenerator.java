package dev.zdict.demo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates deterministic fake product data from a fixed seed.
 */
public class ProductGenerator {

    private static final String[] CATEGORIES = {
            "electronics", "grocery", "fashion", "books", "toys", "home"
    };
    private static final String[] TAGS = {
            "new", "sale", "popular", "eco", "limited", "gift"
    };

    private final Random random;

    public ProductGenerator(long seed) {
        this.random = new Random(seed);
    }

    /**
     * Generate a single product with the given id.
     */
    public Product generate(int id) {
        String name = "Product " + id;
        String category = CATEGORIES[random.nextInt(CATEGORIES.length)];
        double price = Math.round((1 + random.nextDouble() * 500) * 100.0) / 100.0;
        boolean inStock = random.nextBoolean();
        double rating = Math.round((1 + random.nextDouble() * 4) * 10.0) / 10.0;

        int tagCount = 1 + random.nextInt(3);
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < tagCount; i++) {
            String tag = TAGS[random.nextInt(TAGS.length)];
            if (!tags.contains(tag)) tags.add(tag);
        }

        LocalDate createdAt = LocalDate.of(2026, 1 + random.nextInt(9),
                1 + random.nextInt(28));

        return new Product(id, name, category, price, inStock, rating, tags, createdAt);
    }

    /**
     * Generate a page of products. Each page has 1–40 items.
     */
    public List<Product> generatePage(int page) {
        // Re-seed per page so pages are deterministic regardless of call order
        Random pageRandom = new Random(31L * page + 7);
        int count = 1 + pageRandom.nextInt(40);
        List<Product> products = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int id = page * 100 + i;
            products.add(generate(id));
        }
        return products;
    }
}
