package com.nookx.ingester.web.dto;

import lombok.Builder;

@Builder(setterPrefix = "with")
public record SourceView(
    String code,
    String ingestTargetCode,
    boolean enabled,
    String discoveryCron,
    long pendingFetch,
    long failedFetch,
    long pendingParse,
    long failedParse
) {
}
