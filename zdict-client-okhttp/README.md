# zdict-client-okhttp

OkHttp interceptor for the `zdict` per-endpoint dictionary compression protocol.

## Features

- Transparently handles dictionary negotiation via the `X-Dict-Ids` and `X-Dict-Available` HTTP headers.
- Caches decompressed dictionary instances (`ZstdDictDecompress`) locally in-memory.
- Fetches new dictionary versions out-of-band via a separate internal client when announced by the server.
- Decodes `Content-Encoding: zstd-dict` responses and strips the header so downstream interceptors and caller code see plain uncompressed responses.
- Resilient fallback: on any failure (network error fetching dictionary, decompression error, corrupt payload), falls back to plain uncompressed request/response handling without failing the application.

## Quickstart

```java
import dev.zdict.client.okhttp.ZdictInterceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

OkHttpClient client = new OkHttpClient.Builder()
    .addInterceptor(new ZdictInterceptor())
    .build();

Request request = new Request.Builder()
    .url("http://example.com/api/products")
    .build();

try (Response response = client.newCall(request).execute()) {
    String body = response.body().string(); // automatically decompressed JSON
}
```

## Android Packaging

> **Note:** Android packaging not yet verified.
>
> While `zdict-client-okhttp` relies on `com.github.luben:zstd-jni` for decompression, standard Java JARs bundle native libraries (`.dll`, `.so`, `.dylib`) targeted at desktop and server platforms. On Android, `zstd-jni` typically requires the `@aar` packaging artifact or bundling architecture-specific native libraries (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`) into your APK/AAB. Until verified and published with Android test automation, use this client on JVM / server runtimes.
