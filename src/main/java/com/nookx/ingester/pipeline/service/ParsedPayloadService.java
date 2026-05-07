package com.nookx.ingester.pipeline.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nookx.ingester.core.HashUtils;
import com.nookx.ingester.domain.ParsedAsset;
import com.nookx.ingester.domain.ParsedPayload;
import com.nookx.ingester.domain.ScrapePage;
import com.nookx.ingester.domain.enumeration.PushStatus;
import com.nookx.ingester.ingest.api.NormalizedPayload;
import com.nookx.ingester.repository.ParsedAssetRepository;
import com.nookx.ingester.repository.ParsedPayloadRepository;
import com.nookx.ingester.source.api.dto.NormalizedAssetDto;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class ParsedPayloadService {

    private final ParsedPayloadRepository parsedPayloadRepository;
    private final ParsedAssetRepository parsedAssetRepository;
    private final ObjectMapper objectMapper;

    public ParsedPayload upsertFromParse(
        final NormalizedPayload payload,
        final String sourceCode,
        final String ingestTargetCode,
        final ScrapePage page
    ) {
        final ParsedPayload existingOrNew = parsedPayloadRepository
            .findBySourceCodeAndIngestTargetCodeAndExternalId(sourceCode, ingestTargetCode, payload.externalId())
            .orElseGet(ParsedPayload::new);
        final Instant now = Instant.now();
        final JsonNode payloadJson = objectMapper.valueToTree(payload);
        final String payloadHash = HashUtils.sha256(payloadJson.toString());
        if (existingOrNew.getId() == null) {
            existingOrNew.setFirstSeenAt(now);
            existingOrNew.setCreatedAt(now);
            existingOrNew.setPushStatus(PushStatus.PENDING);
            existingOrNew.setPushRetryCount(0);
        } else if (!payloadHash.equals(existingOrNew.getPayloadHash())) {
            existingOrNew.setPushStatus(PushStatus.PENDING);
            existingOrNew.setPushRetryCount(0);
            existingOrNew.setPushLastError(null);
        }
        existingOrNew.setSourceCode(sourceCode);
        existingOrNew.setIngestTargetCode(ingestTargetCode);
        existingOrNew.setPayloadType(payload.getClass().getName());
        existingOrNew.setExternalId(payload.externalId());
        existingOrNew.setPayloadJson(payloadJson);
        existingOrNew.setPayloadHash(payloadHash);
        existingOrNew.setScrapePage(page);
        existingOrNew.setLastParsedAt(now);
        existingOrNew.setUpdatedAt(now);
        final ParsedPayload saved = parsedPayloadRepository.save(existingOrNew);
        for (final NormalizedAssetDto asset : payload.assets()) {
            upsertAsset(saved, asset, now);
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ParsedPayload> findPushable(final String ingestTargetCode, final int limit) {
        return parsedPayloadRepository.findByIngestTargetCodeAndPushStatusInOrderByIdAsc(
            ingestTargetCode,
            List.of(PushStatus.PENDING, PushStatus.FAILED),
            PageRequest.of(0, limit)
        );
    }

    @Transactional(readOnly = true)
    public List<ParsedAsset> findAllAssetsForPayload(final ParsedPayload payload) {
        return parsedAssetRepository.findByParsedPayloadOrderBySortOrderAsc(payload);
    }

    public void markPushPushed(final ParsedPayload payload, final String externalRef) {
        final Instant now = Instant.now();
        payload.setExternalRef(externalRef);
        payload.setPushStatus(PushStatus.PUSHED);
        payload.setPushRetryCount(0);
        payload.setPushLastError(null);
        payload.setPushedAt(now);
        payload.setUpdatedAt(now);
        parsedPayloadRepository.save(payload);
    }

    public void markPushAlreadyExists(final ParsedPayload payload, final String externalRef) {
        final Instant now = Instant.now();
        if (externalRef != null) {
            payload.setExternalRef(externalRef);
        }
        payload.setPushStatus(PushStatus.ALREADY_EXISTS);
        payload.setPushLastError(null);
        payload.setPushedAt(now);
        payload.setUpdatedAt(now);
        parsedPayloadRepository.save(payload);
    }

    public void markPushFailure(final ParsedPayload payload, final String error) {
        final Instant now = Instant.now();
        payload.setPushStatus(PushStatus.FAILED);
        payload.setPushRetryCount(payload.getPushRetryCount() + 1);
        payload.setPushLastError(truncate(error));
        payload.setUpdatedAt(now);
        parsedPayloadRepository.save(payload);
    }

    public void markAssetDownloaded(
        final ParsedAsset asset,
        final String localPath,
        final String contentType,
        final String contentHash,
        final long size
    ) {
        asset.setDownloaded(true);
        asset.setLocalPath(localPath);
        asset.setContentType(contentType);
        asset.setContentHash(contentHash);
        asset.setContentSizeBytes(size);
        asset.setDownloadLastError(null);
        asset.setUpdatedAt(Instant.now());
        parsedAssetRepository.save(asset);
    }

    public void markAssetDownloadFailure(final ParsedAsset asset, final String error) {
        asset.setDownloaded(false);
        asset.setDownloadRetryCount(asset.getDownloadRetryCount() + 1);
        asset.setDownloadLastError(truncate(error));
        asset.setUpdatedAt(Instant.now());
        parsedAssetRepository.save(asset);
    }

    public void markAssetPushSuccess(final ParsedAsset asset) {
        asset.setPushStatus(PushStatus.PUSHED);
        asset.setPushRetryCount(0);
        asset.setPushLastError(null);
        asset.setUpdatedAt(Instant.now());
        parsedAssetRepository.save(asset);
    }

    public void markAssetPushFailure(final ParsedAsset asset, final String error) {
        asset.setPushStatus(PushStatus.FAILED);
        asset.setPushRetryCount(asset.getPushRetryCount() + 1);
        asset.setPushLastError(truncate(error));
        asset.setUpdatedAt(Instant.now());
        parsedAssetRepository.save(asset);
    }

    public int resetPushErrors(final String ingestTargetCode) {
        return parsedPayloadRepository.resetPushStatus(
            ingestTargetCode,
            PushStatus.FAILED,
            PushStatus.PENDING,
            Instant.now()
        );
    }

    private void upsertAsset(final ParsedPayload payload, final NormalizedAssetDto dto, final Instant now) {
        final String externalUrlHash = HashUtils.sha256(dto.externalUrl());
        final Optional<ParsedAsset> existing = parsedAssetRepository.findByParsedPayloadAndExternalUrlHash(payload, externalUrlHash);
        final ParsedAsset asset = existing.orElseGet(ParsedAsset::new);
        if (asset.getId() == null) {
            asset.setParsedPayload(payload);
            asset.setDownloaded(false);
            asset.setPushStatus(PushStatus.PENDING);
            asset.setCreatedAt(now);
        }
        asset.setKind(dto.kind());
        asset.setExternalUrlHash(externalUrlHash);
        asset.setExternalUrl(dto.externalUrl());
        asset.setLabel(dto.label());
        asset.setSortOrder(dto.sortOrder());
        asset.setUpdatedAt(now);
        parsedAssetRepository.save(asset);
    }

    private static String truncate(final String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 1990) {
            return value;
        }
        return value.substring(0, 1990);
    }
}
