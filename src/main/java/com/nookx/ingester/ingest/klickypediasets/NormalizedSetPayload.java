package com.nookx.ingester.ingest.klickypediasets;

import com.fasterxml.jackson.databind.JsonNode;
import com.nookx.ingester.ingest.api.NormalizedPayload;
import com.nookx.ingester.source.api.dto.NormalizedAssetDto;
import java.time.LocalDate;
import java.util.List;

public record NormalizedSetPayload(
    String externalId,
    String setNumber,
    String name,
    String description,
    LocalDate releaseDate,
    String theme,
    JsonNode rawAttributes,
    List<NormalizedAssetDto> assets
) implements NormalizedPayload {

    public NormalizedSetPayload {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId is required");
        }
        if (assets == null) {
            assets = List.of();
        }
    }
}
