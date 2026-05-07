package com.nookx.ingester.source.api;

import com.nookx.ingester.ingest.api.NormalizedPayload;

public interface PageParser<P extends NormalizedPayload> {

    String pageType();

    Class<P> payloadType();

    ParseResult<P> parse(ParseContext context);
}
