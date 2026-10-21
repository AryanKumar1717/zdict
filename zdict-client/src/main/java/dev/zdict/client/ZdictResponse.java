package dev.zdict.client;

import java.net.http.HttpResponse;

/**
 * HTTP response wrapper returned by {@link ZdictClient}.
 */
public record ZdictResponse(
        int statusCode,
        String body,
        byte[] rawBytes,
        boolean compressed,
        HttpResponse<byte[]> httpResponse
) {
    public int wireSize() {
        return rawBytes != null ? rawBytes.length : 0;
    }
}
