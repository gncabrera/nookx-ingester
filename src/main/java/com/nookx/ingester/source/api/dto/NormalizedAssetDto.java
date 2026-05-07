package com.nookx.ingester.source.api.dto;

import com.nookx.ingester.domain.enumeration.AssetKind;

public record NormalizedAssetDto(String externalUrl, AssetKind kind, String label, Integer sortOrder) {
}
