package dev.zdict.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Scrubs PII from JSON payloads before dictionary training / sample storage.
 *
 * <p>Replaces denylisted field values with "x" at any depth.
 * Also scrubs string values containing email addresses, JWT-like tokens, or runs of 9+ digits.
 * If input is invalid JSON, it is dropped.
 */
@Component
public class PayloadScrubber {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern JWT_PATTERN =
            Pattern.compile("\\b[a-zA-Z0-9_-]{4,}\\.[a-zA-Z0-9_-]{4,}\\.[a-zA-Z0-9_-]{4,}\\b");
    private static final Pattern DIGIT_RUN_PATTERN =
            Pattern.compile("\\d{9,}");

    private final ObjectMapper mapper;
    private final Set<String> denylistedFields;

    public PayloadScrubber() {
        this(new ObjectMapper(), Set.of(
                "password", "token", "accesstoken", "refreshtoken", "secret",
                "email", "phone", "mobile", "address", "ssn", "aadhaar",
                "pan", "card", "cvv", "authorization"
        ));
    }

    @Autowired
    public PayloadScrubber(ZdictProperties props) {
        this(new ObjectMapper(), props.getDenylistedFields());
    }

    public PayloadScrubber(ObjectMapper mapper, Set<String> denylistedFields) {
        this.mapper = mapper;
        this.denylistedFields = new HashSet<>();
        if (denylistedFields != null) {
            for (String f : denylistedFields) {
                this.denylistedFields.add(f.toLowerCase(Locale.ROOT));
            }
        }
    }

    /**
     * Scrubs byte array payload. Returns empty if invalid JSON.
     */
    public Optional<byte[]> scrub(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(payload);
            if (root == null || root.isMissingNode()) {
                return Optional.empty();
            }
            JsonNode scrubbed = scrubNode(root.deepCopy());
            return Optional.of(mapper.writeValueAsBytes(scrubbed));
        } catch (Exception e) {
            // Invalid JSON -> drop
            return Optional.empty();
        }
    }

    /**
     * Scrubs String payload. Returns empty if invalid JSON.
     */
    public Optional<String> scrub(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || root.isMissingNode()) {
                return Optional.empty();
            }
            JsonNode scrubbed = scrubNode(root.deepCopy());
            return Optional.of(mapper.writeValueAsString(scrubbed));
        } catch (Exception e) {
            // Invalid JSON -> drop
            return Optional.empty();
        }
    }

    private JsonNode scrubNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            Map<String, JsonNode> updates = new LinkedHashMap<>();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String fieldName = entry.getKey();
                JsonNode child = entry.getValue();

                if (isDenylisted(fieldName)) {
                    updates.put(fieldName, new TextNode("x"));
                } else {
                    updates.put(fieldName, scrubNode(child));
                }
            }
            updates.forEach(obj::set);
            return obj;
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, scrubNode(arr.get(i)));
            }
            return arr;
        } else if (node.isTextual()) {
            String text = node.asText();
            String scrubbed = scrubString(text);
            return scrubbed.equals(text) ? node : new TextNode(scrubbed);
        }
        return node;
    }

    private String scrubString(String val) {
        if (val == null || val.isEmpty()) {
            return val;
        }
        String s = EMAIL_PATTERN.matcher(val).replaceAll("x");
        s = JWT_PATTERN.matcher(s).replaceAll("x");
        s = DIGIT_RUN_PATTERN.matcher(s).replaceAll("x");
        return s;
    }

    private boolean isDenylisted(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        return denylistedFields.contains(fieldName.toLowerCase(Locale.ROOT));
    }
}
