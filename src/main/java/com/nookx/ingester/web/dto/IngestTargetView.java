package com.nookx.ingester.web.dto;

import lombok.Builder;

@Builder(setterPrefix = "with")
public record IngestTargetView(
    String code,
    String payloadType,
    boolean enabled,
    String pushCron,
    String baseUrl,
    int maxBatchSize,
    long pendingPush,
    long pushedCount,
    long failedPush,
    long alreadyExists
) {
}
