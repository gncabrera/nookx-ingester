package com.nookx.ingester.ingest.klickypediasets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nookx.ingester.config.IngesterProperties;
import com.nookx.ingester.config.IngesterProperties.IngestTargetConfig;
import com.nookx.ingester.core.HashUtils;
import com.nookx.ingester.domain.enumeration.PushStatus;
import com.nookx.ingester.ingest.api.DownloadedAsset;
import com.nookx.ingester.ingest.api.IngestTarget;
import com.nookx.ingester.ingest.api.PushOutcome;
import com.nookx.ingester.ingest.api.PushOutcome.AssetOutcome;
import com.nookx.ingester.ingest.klickypediasets.NookxIngestClient.IngestHttpResult;
import com.nookx.ingester.ingest.klickypediasets.NookxIngestClient.UploadImagesRequest;
import com.nookx.ingester.source.klickypedia.KlickypediaConstants;
import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KlickypediaSetsTarget implements IngestTarget<NormalizedSetPayload> {

    private static final String OPTION_INTEREST_ID = "interest-id";
    private static final long DEFAULT_INTEREST_ID = 1L;
    private static final int DUPLICATE_DETECTION_THRESHOLD = 1;

    private final IngesterProperties properties;
    private final ObjectMapper objectMapper;

    private NookxIngestClient client;
    private long interestId;

    @PostConstruct
    public void init() {
        final IngestTargetConfig config = properties.getIngestTargets().get(code());
        if (config == null) {
            throw new IllegalStateException("Missing ingest-target config for " + code());
        }
        this.client = new NookxIngestClient(objectMapper, config.getBaseUrl(), config.getApiKey());
        this.interestId = parseInterestId(config);
        log.info("[Ingester/KlickypediaSetsTarget] - INIT: baseUrl={} interestId={}", config.getBaseUrl(), this.interestId);
    }

    @Override
    public String code() {
        return KlickypediaConstants.INGEST_TARGET_CODE;
    }

    @Override
    public Class<NormalizedSetPayload> payloadType() {
        return NormalizedSetPayload.class;
    }

    @Override
    public void validate(final NormalizedSetPayload payload) {
        if (payload.setNumber() == null || payload.setNumber().isBlank()) {
            throw new IllegalArgumentException("setNumber is required");
        }
        if (payload.name() == null || payload.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
    }

    @Override
    public String idempotencyKey(final NormalizedSetPayload payload) {
        return "set-" + HashUtils.sha256(payload.externalId()).substring(0, 20);
    }

    @Override
    public PushOutcome push(final NormalizedSetPayload payload, final List<DownloadedAsset> assets) {
        final ObjectNode setNode = buildSetNode(payload);
        final ObjectNode body = objectMapper.createObjectNode();
        body.putArray("sets").add(setNode);
        final String idempotencyKey = idempotencyKey(payload);
        final IngestHttpResult response = client.createSetsBatch(body, idempotencyKey);
        if (response.status() != 201 && response.status() != 207) {
            return PushOutcome.failed("Sets batch unexpected status " + response.status());
        }
        final JsonNode results = response.body().path("results");
        if (!results.isArray() || results.size() < DUPLICATE_DETECTION_THRESHOLD) {
            return PushOutcome.failed("Malformed sets response: missing results array");
        }
        final JsonNode first = results.path(0);
        final int status = first.path("status").asInt(0);
        if (status == 201) {
            final long nookxSetId = first.path("set").path("id").asLong(0L);
            if (nookxSetId <= 0L) {
                return PushOutcome.failed("Sets response missing set.id");
            }
            final List<AssetOutcome> assetOutcomes = pushAssets(payload, nookxSetId, assets);
            return PushOutcome.pushed(String.valueOf(nookxSetId), assetOutcomes);
        }
        final String error = first.path("error").asText("Set push failed");
        if (error.contains("duplicate key value violates unique constraint")) {
            return PushOutcome.alreadyExists(null);
        }
        return PushOutcome.failed(error);
    }

    private ObjectNode buildSetNode(final NormalizedSetPayload payload) {
        final ObjectNode setNode = objectMapper.createObjectNode();
        setNode.put("setNumber", payload.setNumber());
        setNode.put("name", payload.name());
        setNode.put("publicItem", true);
        if (payload.description() != null) {
            setNode.put("description", payload.description());
        }
        if (payload.releaseDate() != null) {
            setNode.put("releaseDate", payload.releaseDate().toString());
        }
        setNode.put("notes", "imported by ingester");
        if (payload.rawAttributes() != null) {
            setNode.set("attributes", payload.rawAttributes());
        } else {
            setNode.putObject("attributes");
        }
        setNode.putObject("interest").put("id", interestId);
        return setNode;
    }

    private List<AssetOutcome> pushAssets(
        final NormalizedSetPayload payload,
        final long nookxSetId,
        final List<DownloadedAsset> assets
    ) {
        if (assets == null || assets.isEmpty()) {
            return List.of();
        }
        final List<Path> files = new ArrayList<>(assets.size());
        int sortOrderStart = Integer.MAX_VALUE;
        for (final DownloadedAsset asset : assets) {
            files.add(asset.file());
            final Integer assetSortOrder = asset.asset().getSortOrder();
            if (assetSortOrder != null && assetSortOrder < sortOrderStart) {
                sortOrderStart = assetSortOrder;
            }
        }
        if (files.isEmpty()) {
            return List.of();
        }
        if (sortOrderStart == Integer.MAX_VALUE) {
            sortOrderStart = 0;
        }
        final String keyMaterial = nookxSetId + ":" + files.size() + ":" + files.getFirst().getFileName();
        final String idempotencyKey = "assets-" + HashUtils.sha256(keyMaterial).substring(0, 20);
        final IngestHttpResult response = client.uploadSetImagesBatch(
            new UploadImagesRequest(nookxSetId, files, idempotencyKey, payload.name(), sortOrderStart)
        );
        final List<AssetOutcome> outcomes = new ArrayList<>(assets.size());
        if (response.status() != 201 && response.status() != 207) {
            for (final DownloadedAsset asset : assets) {
                outcomes.add(new AssetOutcome(asset.asset().getId(), PushStatus.FAILED, "Unexpected status " + response.status()));
            }
            return outcomes;
        }
        final JsonNode results = response.body().path("results");
        for (int i = 0; i < assets.size(); i++) {
            final DownloadedAsset asset = assets.get(i);
            final JsonNode itemResult = results.path(i);
            final int itemStatus = itemResult.path("status").asInt(0);
            if (itemStatus == 201) {
                outcomes.add(new AssetOutcome(asset.asset().getId(), PushStatus.PUSHED, null));
                continue;
            }
            outcomes.add(new AssetOutcome(asset.asset().getId(), PushStatus.FAILED, itemResult.path("error").asText("Asset push failed")));
        }
        return outcomes;
    }

    private static long parseInterestId(final IngestTargetConfig config) {
        final String raw = config.getOptions().get(OPTION_INTEREST_ID);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_INTEREST_ID;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return DEFAULT_INTEREST_ID;
        }
    }
}
