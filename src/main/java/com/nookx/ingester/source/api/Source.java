package com.nookx.ingester.source.api;

import com.nookx.ingester.ingest.api.NormalizedPayload;
import java.util.Optional;

public interface Source {

    String code();

    String ingestTargetCode();

    SourceDiscoverer discoverer();

    Optional<PageParser<? extends NormalizedPayload>> parserFor(String pageType);
}
