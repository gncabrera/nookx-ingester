package com.nookx.ingester.core.http;

public record FetchResult(int status, byte[] body, String contentType, String etag, String lastModified, String errorMessage) {

    public boolean isOk() {
        return status >= 200 && status < 300 && body != null;
    }

    public boolean isNotModified() {
        return status == 304;
    }

    public boolean isNotFound() {
        return status == 404 || status == 410;
    }

    public boolean isTransient() {
        return status == 408 || status == 429 || status >= 500;
    }

    public boolean isTransportError() {
        return status == 0;
    }

    public static FetchResult transportError(final String errorMessage) {
        return new FetchResult(0, null, null, null, null, errorMessage);
    }
}
