package dev.zdict;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HarImporterTest {

    @TempDir
    Path tempDir;

    @Test
    void testExtractGroupPathAndFilesystemSafe() {
        assertEquals("/api/products/{id}", HarImporter.extractGroupPath("https://example.com/api/products/123"));
        assertEquals("api_products_{id}", HarImporter.toFilesystemSafe("/api/products/{id}"));

        assertEquals("/api/orders/{id}/items",
                HarImporter.extractGroupPath("https://example.com/api/orders/550e8400-e29b-41d4-a716-446655440000/items?sort=asc"));
        assertEquals("api_orders_{id}_items", HarImporter.toFilesystemSafe("/api/orders/{id}/items"));

        assertEquals("/api/users/{id}/details/{id}",
                HarImporter.extractGroupPath("http://localhost:8080/api/users/999/details/42"));
        assertEquals("api_users_{id}_details_{id}", HarImporter.toFilesystemSafe("/api/users/{id}/details/{id}"));

        assertEquals("/api/status", HarImporter.extractGroupPath("https://api.test.io/api/status"));
        assertEquals("api_status", HarImporter.toFilesystemSafe("/api/status"));
    }

    @Test
    void testImportHandMadeHar() throws IOException {
        String harContent = """
                {
                  "log": {
                    "version": "1.2",
                    "entries": [
                      {
                        "request": { "url": "https://shop.example.com/api/products/101" },
                        "response": {
                          "status": 200,
                          "content": {
                            "mimeType": "application/json; charset=utf-8",
                            "text": "{\\"id\\":101,\\"name\\":\\"Keyboard\\"}"
                          }
                        }
                      },
                      {
                        "request": { "url": "https://shop.example.com/api/products/102" },
                        "response": {
                          "status": 200,
                          "content": {
                            "mimeType": "application/json",
                            "text": "{\\"id\\":102,\\"name\\":\\"Mouse\\"}"
                          }
                        }
                      },
                      {
                        "request": { "url": "https://shop.example.com/api/products/999" },
                        "response": {
                          "status": 404,
                          "content": {
                            "mimeType": "application/json",
                            "text": "{\\"error\\":\\"Not Found\\"}"
                          }
                        }
                      },
                      {
                        "request": { "url": "https://shop.example.com/static/style.css" },
                        "response": {
                          "status": 200,
                          "content": {
                            "mimeType": "text/css",
                            "text": "body { color: red; }"
                          }
                        }
                      },
                      {
                        "request": { "url": "https://shop.example.com/api/orders/e3b0c442-98fc-1c14-9afb-4c7bca74e2d2" },
                        "response": {
                          "status": 200,
                          "content": {
                            "mimeType": "application/json",
                            "text": "{\\"order_id\\":\\"e3b0c442-98fc-1c14-9afb-4c7bca74e2d2\\",\\"amount\\":49.99}"
                          }
                        }
                      },
                      {
                        "request": { "url": "https://shop.example.com/api/bad-json" },
                        "response": {
                          "status": 200,
                          "content": {
                            "mimeType": "application/json",
                            "text": "{this is not valid json!}"
                          }
                        }
                      },
                      {
                        "request": { "url": "https://shop.example.com/api/empty-body" },
                        "response": {
                          "status": 200,
                          "content": {
                            "mimeType": "application/json",
                            "text": "   "
                          }
                        }
                      }
                    ]
                  }
                }
                """;

        Path harFile = tempDir.resolve("traffic.har");
        Files.writeString(harFile, harContent);

        Path outputDir = tempDir.resolve("extracted");
        HarImporter.ImportSummary summary = HarImporter.importHar(harFile, outputDir);

        assertEquals(7, summary.getTotalEntries());
        assertEquals(3, summary.getFilesWritten());
        assertEquals(2, summary.getGroups().size());

        Path productGroupDir = outputDir.resolve("api_products_{id}");
        assertTrue(Files.isDirectory(productGroupDir));
        Path prodFile1 = productGroupDir.resolve("000001.json");
        Path prodFile2 = productGroupDir.resolve("000002.json");
        assertTrue(Files.isRegularFile(prodFile1));
        assertTrue(Files.isRegularFile(prodFile2));

        assertEquals("{\"id\":101,\"name\":\"Keyboard\"}", Files.readString(prodFile1));
        assertEquals("{\"id\":102,\"name\":\"Mouse\"}", Files.readString(prodFile2));

        Path orderGroupDir = outputDir.resolve("api_orders_{id}");
        assertTrue(Files.isDirectory(orderGroupDir));
        Path orderFile1 = orderGroupDir.resolve("000001.json");
        assertTrue(Files.isRegularFile(orderFile1));
        assertTrue(Files.readString(orderFile1).contains("49.99"));
    }
}
