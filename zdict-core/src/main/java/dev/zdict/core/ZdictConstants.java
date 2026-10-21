package dev.zdict.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Protocol constants and naming utilities for zdict.
 */
public final class ZdictConstants {

    public static final String HEADER_DICT_IDS = "X-Dict-Ids";
    public static final String HEADER_DICT_AVAILABLE = "X-Dict-Available";
    public static final String ENCODING_ZSTD_DICT = "zstd-dict";
    public static final String ENCODING_ZSTD = "zstd";
    public static final String ENCODING_GZIP = "gzip";

    private ZdictConstants() {}

    /**
     * Convert an endpoint path / route pattern into a filesystem-safe group slug.
     */
    public static String toGroupSlug(String groupKey) {
        if (groupKey == null || groupKey.isBlank()) {
            return "default";
        }
        String slug = groupKey.replaceAll("^/+", "").replaceAll("[^a-zA-Z0-9]+", "_");
        if (slug.endsWith("_")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return slug.isEmpty() ? "root" : slug;
    }

    /**
     * Compute a deterministic 8-character hex dictionary ID from its raw bytes.
     */
    public static String computeDictId(byte[] dict) {
        if (dict == null || dict.length == 0) {
            return "00000000";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(dict);
            return HexFormat.of().formatHex(hash).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
