package com.nookx.ingester.ingest.api;

import java.util.List;

public interface IngestTarget<P extends NormalizedPayload> {

    String code();

    Class<P> payloadType();

    void validate(P payload);

    PushOutcome push(P payload, List<DownloadedAsset> assets);

    String idempotencyKey(P payload);
}
