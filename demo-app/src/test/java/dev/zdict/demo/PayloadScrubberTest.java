package dev.zdict.demo;

import dev.zdict.core.PayloadScrubber;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadScrubberTest {

    private PayloadScrubber scrubber;

    @BeforeEach
    void setUp() {
        scrubber = new PayloadScrubber();
    }

    @Test
    void denylistedFieldsReplacedWithXAtAnyDepth() {
        String json = """
                {
                  "username": "alice",
                  "password": "superSecretPassword1",
                  "token": "tok_12345",
                  "accessToken": "acc_xyz",
                  "refreshToken": "ref_abc",
                  "secret": "my-secret",
                  "email": "alice@corp.com",
                  "phone": "555-1234",
                  "mobile": "+1234567890",
                  "address": "123 Main St, Springfield",
                  "ssn": "000-12-3456",
                  "aadhaar": "1234 5678 9012",
                  "pan": "ABCDE1234F",
                  "card": "4111222233334444",
                  "cvv": "123",
                  "authorization": "Bearer token",
                  "nested": {
                    "token": "nested_tok",
                    "deep": {
                      "secret": "deep_secret",
                      "regular": "keepMe"
                    }
                  }
                }
                """;

        Optional<String> scrubbed = scrubber.scrub(json);
        assertThat(scrubbed).isPresent();
        String result = scrubbed.get();

        assertThat(result).contains("\"password\":\"x\"");
        assertThat(result).contains("\"token\":\"x\"");
        assertThat(result).contains("\"accessToken\":\"x\"");
        assertThat(result).contains("\"refreshToken\":\"x\"");
        assertThat(result).contains("\"secret\":\"x\"");
        assertThat(result).contains("\"email\":\"x\"");
        assertThat(result).contains("\"phone\":\"x\"");
        assertThat(result).contains("\"mobile\":\"x\"");
        assertThat(result).contains("\"address\":\"x\"");
        assertThat(result).contains("\"ssn\":\"x\"");
        assertThat(result).contains("\"aadhaar\":\"x\"");
        assertThat(result).contains("\"pan\":\"x\"");
        assertThat(result).contains("\"card\":\"x\"");
        assertThat(result).contains("\"cvv\":\"x\"");
        assertThat(result).contains("\"authorization\":\"x\"");
        assertThat(result).contains("\"regular\":\"keepMe\"");
        assertThat(result).contains("\"username\":\"alice\"");
    }

    @Test
    void regexScrubsEmailAddressesInStringValues() {
        String json = "{\"notes\":\"Contact user at john.doe@example.com for help\"}";
        Optional<String> scrubbed = scrubber.scrub(json);
        assertThat(scrubbed).isPresent();
        assertThat(scrubbed.get()).isEqualTo("{\"notes\":\"Contact user at x for help\"}");
    }

    @Test
    void regexScrubsJwtTokensInStringValues() {
        String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String json = "{\"headerInfo\":\"Bearer " + jwt + "\"}";
        Optional<String> scrubbed = scrubber.scrub(json);
        assertThat(scrubbed).isPresent();
        assertThat(scrubbed.get()).isEqualTo("{\"headerInfo\":\"Bearer x\"}");
    }

    @Test
    void regexScrubsDigitRunsOf9OrMore() {
        String json = "{\"tracking\":\"Shipment 123456789 arrived\", \"shortNum\":\"12345\"}";
        Optional<String> scrubbed = scrubber.scrub(json);
        assertThat(scrubbed).isPresent();
        assertThat(scrubbed.get()).contains("\"tracking\":\"Shipment x arrived\"");
        assertThat(scrubbed.get()).contains("\"shortNum\":\"12345\"");
    }

    @Test
    void nestedArraysAndObjectsScrubbedRecursively() {
        String json = """
                {
                  "items": [
                    {"id": 1, "secret": "s1", "info": "call 12345678901"},
                    {"id": 2, "tags": ["admin@example.com", "normalTag"]}
                  ]
                }
                """;
        Optional<String> scrubbed = scrubber.scrub(json);
        assertThat(scrubbed).isPresent();
        String result = scrubbed.get();

        assertThat(result).contains("\"id\":1");
        assertThat(result).contains("\"secret\":\"x\"");
        assertThat(result).contains("\"info\":\"call x\"");
        assertThat(result).contains("\"tags\":[\"x\",\"normalTag\"]");
    }

    @Test
    void nonJsonPayloadsAreDropped() {
        assertThat(scrubber.scrub("not json")).isEmpty();
        assertThat(scrubber.scrub("<xml><body>hello</body></xml>")).isEmpty();
        assertThat(scrubber.scrub("{unclosed: ")).isEmpty();
        assertThat(scrubber.scrub("")).isEmpty();
        assertThat(scrubber.scrub((String) null)).isEmpty();
        assertThat(scrubber.scrub(new byte[0])).isEmpty();
        assertThat(scrubber.scrub("hello world".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    void originalPayloadNotMutated() {
        byte[] original = "{\"password\":\"superSecret\",\"info\":\"plain\"}".getBytes(StandardCharsets.UTF_8);
        byte[] copy = Arrays.copyOf(original, original.length);

        Optional<byte[]> scrubbed = scrubber.scrub(original);
        assertThat(scrubbed).isPresent();

        // Original byte array must remain byte-for-byte identical
        assertThat(original).isEqualTo(copy);
        // Scrubbed result must have "x"
        assertThat(new String(scrubbed.get(), StandardCharsets.UTF_8)).contains("\"password\":\"x\"");
    }
}
