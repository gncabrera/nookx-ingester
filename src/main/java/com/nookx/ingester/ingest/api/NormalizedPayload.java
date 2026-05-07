package com.nookx.ingester.ingest.api;

import com.nookx.ingester.source.api.dto.NormalizedAssetDto;
import java.util.List;

public interface NormalizedPayload {

    String externalId();

    default List<NormalizedAssetDto> assets() {
        return List.of();
    }
}
