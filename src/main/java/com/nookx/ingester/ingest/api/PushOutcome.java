package com.nookx.ingester.ingest.api;

import com.nookx.ingester.domain.enumeration.PushStatus;
import java.util.List;

public record PushOutcome(
    PushStatus status,
    String externalRef,
    String errorMessage,
    List<AssetOutcome> assetOutcomes
) {

    public PushOutcome {
        if (assetOutcomes == null) {
            assetOutcomes = List.of();
        }
    }

    public static PushOutcome pushed(final String externalRef, final List<AssetOutcome> assetOutcomes) {
        return new PushOutcome(PushStatus.PUSHED, externalRef, null, assetOutcomes);
    }

    public static PushOutcome alreadyExists(final String externalRef) {
        return new PushOutcome(PushStatus.ALREADY_EXISTS, externalRef, null, List.of());
    }

    public static PushOutcome failed(final String errorMessage) {
        return new PushOutcome(PushStatus.FAILED, null, errorMessage, List.of());
    }

    public record AssetOutcome(Long parsedAssetId, PushStatus status, String errorMessage) {
    }
}
