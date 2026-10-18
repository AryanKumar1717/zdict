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

    private final long seed;

    public ProductGenerator(long seed) {
        this.seed = seed;
    }

    /**
     * Generate a single product with the given id.
     */
    public Product generate(int id) {
        Random r = new Random(seed ^ ((long) id * 31_337L));
        String name = "Product " + id;
        String category = CATEGORIES[r.nextInt(CATEGORIES.length)];
        double price = Math.round((1 + r.nextDouble() * 500) * 100.0) / 100.0;
        boolean inStock = r.nextBoolean();
        double rating = Math.round((1 + r.nextDouble() * 4) * 10.0) / 10.0;

        int tagCount = 1 + r.nextInt(3);
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < tagCount; i++) {
            String tag = TAGS[r.nextInt(TAGS.length)];
            if (!tags.contains(tag)) tags.add(tag);
        }

        LocalDate createdAt = LocalDate.of(2026, 1 + r.nextInt(9),
                1 + r.nextInt(28));

        return new Product(id, name, category, price, inStock, rating, tags, createdAt);
    }

    /**
     * Generate a page of products. Each page has 1–40 items.
     */
    public List<Product> generatePage(int page) {
        Random pageRandom = new Random(seed ^ ((long) page * 1_000_003L));
        int count = 1 + pageRandom.nextInt(40);
        List<Product> products = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int id = page * 100 + i;
            products.add(generate(id));
        }
        return products;
    }
}
