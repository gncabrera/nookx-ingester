package com.nookx.ingester.ingest.klickypediasets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public class NookxIngestClient {

    private static final String SETS_PATH = "/api/admin/ingest/sets";
    private static final String ASSETS_PATH = "/api/admin/ingest/assets";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;

    public NookxIngestClient(final ObjectMapper objectMapper, final String baseUrl, final String apiKey) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public IngestHttpResult createSetsBatch(final JsonNode payload, final String idempotencyKey) {
        try {
            final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + SETS_PATH))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", apiKey)
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            final JsonNode data = objectMapper.readTree(response.body());
            return new IngestHttpResult(response.statusCode(), data);
        } catch (Exception ex) {
            throw new IllegalStateException("Error calling " + SETS_PATH + ": " + ex.getMessage(), ex);
        }
    }

    public IngestHttpResult uploadSetImagesBatch(final UploadImagesRequest requestData) {
        final String boundary = "nookx-boundary-" + UUID.randomUUID();
        try {
            final byte[] body = buildMultipartBody(boundary, requestData);
            final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + ASSETS_PATH))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Api-Key", apiKey)
                .header("Idempotency-Key", requestData.idempotencyKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            final JsonNode data = objectMapper.readTree(response.body());
            return new IngestHttpResult(response.statusCode(), data);
        } catch (Exception ex) {
            throw new IllegalStateException("Error calling " + ASSETS_PATH + ": " + ex.getMessage(), ex);
        }
    }

    private static byte[] buildMultipartBody(final String boundary, final UploadImagesRequest requestData) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        addTextPart(out, boundary, "setId", String.valueOf(requestData.setId()));
        if (requestData.description() != null) {
            addTextPart(out, boundary, "description", requestData.description());
        }
        addTextPart(out, boundary, "isPublic", "true");
        addTextPart(out, boundary, "sortOrderStart", String.valueOf(requestData.sortOrderStart()));
        addTextPart(out, boundary, "primaryIndex", "0");
        for (final Path file : requestData.files()) {
            addFilePart(out, boundary, "files", file);
        }
        out.write(("--" + boundary + "--\r\n").getBytes());
        return out.toByteArray();
    }

    private static void addTextPart(final ByteArrayOutputStream out, final String boundary, final String name, final String value)
        throws IOException {
        final String text = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
            + value + "\r\n";
        out.write(text.getBytes());
    }

    private static void addFilePart(final ByteArrayOutputStream out, final String boundary, final String name, final Path file)
        throws IOException {
        final String probed = Files.probeContentType(file);
        final String contentType = probed != null ? probed : "application/octet-stream";
        final String header = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + file.getFileName() + "\"\r\n"
            + "Content-Type: " + contentType + "\r\n\r\n";
        out.write(header.getBytes());
        out.write(Files.readAllBytes(file));
        out.write("\r\n".getBytes());
    }

    public record IngestHttpResult(int status, JsonNode body) {}

    public record UploadImagesRequest(
        long setId,
        List<Path> files,
        String idempotencyKey,
        String description,
        int sortOrderStart
    ) {}
}
