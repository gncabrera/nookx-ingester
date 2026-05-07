package com.nookx.ingester.core.http;

import com.nookx.ingester.config.IngesterProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class ScraperHttpClient {

    private final HttpClient httpClient;
    private final IngesterProperties properties;

    public ScraperHttpClient(final IngesterProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getHttp().getConnectTimeoutMs()))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public FetchResult get(final String url) {
        return get(url, null, null);
    }

    public FetchResult get(final String url, final String etag, final String lastModified) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(properties.getHttp().getRequestTimeoutMs()))
            .header("User-Agent", properties.getHttp().getUserAgent())
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .GET();
        if (etag != null && !etag.isBlank()) {
            builder.header("If-None-Match", etag);
        }
        if (lastModified != null && !lastModified.isBlank()) {
            builder.header("If-Modified-Since", lastModified);
        }
        try {
            final HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            final int status = response.statusCode();
            final byte[] body = status == 304 ? null : response.body();
            return new FetchResult(
                status,
                body,
                response.headers().firstValue("Content-Type").orElse(null),
                response.headers().firstValue("ETag").orElse(null),
                response.headers().firstValue("Last-Modified").orElse(null),
                null
            );
        } catch (Exception ex) {
            return FetchResult.transportError(ex.toString());
        }
    }
}
