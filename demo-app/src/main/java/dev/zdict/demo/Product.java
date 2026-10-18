package dev.zdict.demo;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.util.List;

/**
 * A fake product record for the demo API.
 */
public record Product(
        int id,
        String name,
        String category,
        double price,
        boolean inStock,
        double rating,
        List<String> tags,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate createdAt
) {}
