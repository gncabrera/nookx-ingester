package com.nookx.ingester.source.api;

import com.nookx.ingester.ingest.api.NormalizedPayload;
import com.nookx.ingester.source.api.dto.DiscoveredUrl;
import java.util.List;

public record ParseResult<P extends NormalizedPayload>(List<P> payloads, List<DiscoveredUrl> newUrls) {

    public ParseResult {
        if (payloads == null) {
            payloads = List.of();
        }
        if (newUrls == null) {
            newUrls = List.of();
        }
    }

    public static <P extends NormalizedPayload> ParseResult<P> empty() {
        return new ParseResult<>(List.of(), List.of());
    }

    public static <P extends NormalizedPayload> ParseResult<P> ofUrls(final List<DiscoveredUrl> urls) {
        return new ParseResult<>(List.of(), urls);
    }

    public static <P extends NormalizedPayload> ParseResult<P> ofPayload(final P payload) {
        return new ParseResult<>(List.of(payload), List.of());
    }

    public static <P extends NormalizedPayload> ParseResult<P> ofPayloads(final List<P> payloads) {
        return new ParseResult<>(payloads, List.of());
    }
}
