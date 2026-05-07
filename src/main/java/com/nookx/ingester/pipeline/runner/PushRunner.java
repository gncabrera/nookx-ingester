package com.nookx.ingester.pipeline.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nookx.ingester.config.IngesterProperties.IngestTargetConfig;
import com.nookx.ingester.domain.JobRun;
import com.nookx.ingester.domain.ParsedAsset;
import com.nookx.ingester.domain.ParsedPayload;
import com.nookx.ingester.domain.enumeration.JobScopeType;
import com.nookx.ingester.domain.enumeration.JobStage;
import com.nookx.ingester.domain.enumeration.JobTrigger;
import com.nookx.ingester.ingest.api.DownloadedAsset;
import com.nookx.ingester.ingest.api.IngestTarget;
import com.nookx.ingester.ingest.api.NormalizedPayload;
import com.nookx.ingester.ingest.api.PushOutcome;
import com.nookx.ingester.ingest.api.PushOutcome.AssetOutcome;
import com.nookx.ingester.ingest.runtime.IngestTargetRegistry;
import com.nookx.ingester.job.JobContext;
import com.nookx.ingester.job.JobMetrics;
import com.nookx.ingester.job.JobRunService;
import com.nookx.ingester.pipeline.service.AssetDownloader;
import com.nookx.ingester.pipeline.service.ParsedPayloadService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PushRunner {

    private static final int DEFAULT_LIMIT = 100;

    private final IngestTargetRegistry ingestTargetRegistry;
    private final ParsedPayloadService parsedPayloadService;
    private final AssetDownloader assetDownloader;
    private final JobRunService jobRunService;
    private final PipelineLockRegistry lockRegistry;
    private final ObjectMapper objectMapper;

    public JobRun runForTarget(final String targetCode, final JobTrigger trigger, final String triggeredBy, final int limit) {
        if (!lockRegistry.tryAcquire(JobStage.PUSH, JobScopeType.INGEST_TARGET, targetCode)) {
            return jobRunService.startSkipped(
                JobStage.PUSH,
                JobScopeType.INGEST_TARGET,
                targetCode,
                trigger,
                "Push already running for target " + targetCode
            );
        }
        final IngestTarget<? extends NormalizedPayload> target = ingestTargetRegistry.findByCode(targetCode).orElse(null);
        if (target == null || !ingestTargetRegistry.isEnabled(targetCode)) {
            lockRegistry.release(JobStage.PUSH, JobScopeType.INGEST_TARGET, targetCode);
            return jobRunService.startSkipped(
                JobStage.PUSH,
                JobScopeType.INGEST_TARGET,
                targetCode,
                trigger,
                "Target unavailable: " + targetCode
            );
        }
        final JobRun run = jobRunService.start(JobStage.PUSH, JobScopeType.INGEST_TARGET, targetCode, trigger, triggeredBy);
        JobContext.bind(run.getId());
        final JobMetrics metrics = new JobMetrics();
        try {
            runForTargetInternal(target, limit, metrics);
            jobRunService.finishSuccess(run, metrics);
            return run;
        } catch (Exception ex) {
            log.error("[Ingester/Push] - ERROR: target={} message={}", targetCode, ex.toString());
            jobRunService.finishFailure(run, metrics, ex.toString());
            return run;
        } finally {
            JobContext.clear();
            lockRegistry.release(JobStage.PUSH, JobScopeType.INGEST_TARGET, targetCode);
        }
    }

    private <P extends NormalizedPayload> void runForTargetInternal(
        final IngestTarget<P> target,
        final int limit,
        final JobMetrics metrics
    ) {
        final IngestTargetConfig config = ingestTargetRegistry.configOf(target.code());
        final int effectiveLimit = limit <= 0 ? (config != null ? config.getMaxBatchSize() : DEFAULT_LIMIT) : limit;
        final List<ParsedPayload> pending = parsedPayloadService.findPushable(target.code(), effectiveLimit);
        if (pending.isEmpty()) {
            return;
        }
        log.info("[Ingester/Push] - START: target={} pending={} limit={}", target.code(), pending.size(), effectiveLimit);
        int index = 0;
        for (final ParsedPayload row : pending) {
            index++;
            log.debug(
                "[Ingester/Push] - PROGRESS: target={} {}/{} payloadId={} externalId={}",
                target.code(),
                index,
                pending.size(),
                row.getId(),
                row.getExternalId()
            );
            try {
                pushOne(target, row, metrics);
            } catch (Exception ex) {
                parsedPayloadService.markPushFailure(row, ex.toString());
                metrics.inc("failed");
                log.warn("[Ingester/Push] - FAIL: payloadId={} message={}", row.getId(), ex.toString());
            }
        }
        log.info(
            "[Ingester/Push] - DONE: target={} pushed={} alreadyExists={} failed={} assetsPushed={} assetsFailed={}",
            target.code(),
            metrics.get("pushed"),
            metrics.get("alreadyExists"),
            metrics.get("failed"),
            metrics.get("assetsPushed"),
            metrics.get("assetsFailed")
        );
    }

    private <P extends NormalizedPayload> void pushOne(
        final IngestTarget<P> target,
        final ParsedPayload row,
        final JobMetrics metrics
    ) throws Exception {
        final P payload = objectMapper.treeToValue(row.getPayloadJson(), target.payloadType());
        target.validate(payload);

        final List<ParsedAsset> assets = parsedPayloadService.findAllAssetsForPayload(row);
        final List<DownloadedAsset> downloaded = new ArrayList<>();
        for (final ParsedAsset asset : assets) {
            final Path file = assetDownloader.ensureDownloaded(asset);
            if (file == null) {
                continue;
            }
            final String contentType = probeContentType(file);
            downloaded.add(new DownloadedAsset(asset, file, contentType));
        }

        final PushOutcome outcome = target.push(payload, downloaded);
        applyAssetOutcomes(downloaded, outcome.assetOutcomes(), metrics);

        switch (outcome.status()) {
            case PUSHED -> {
                parsedPayloadService.markPushPushed(row, outcome.externalRef());
                metrics.inc("pushed");
            }
            case ALREADY_EXISTS -> {
                parsedPayloadService.markPushAlreadyExists(row, outcome.externalRef());
                metrics.inc("alreadyExists");
            }
            case FAILED -> {
                parsedPayloadService.markPushFailure(row, outcome.errorMessage() == null ? "Push failed" : outcome.errorMessage());
                metrics.inc("failed");
            }
            case PENDING -> {
                parsedPayloadService.markPushFailure(row, "Target returned PENDING outcome");
                metrics.inc("failed");
            }
        }
    }

    private void applyAssetOutcomes(
        final List<DownloadedAsset> downloaded,
        final List<AssetOutcome> outcomes,
        final JobMetrics metrics
    ) {
        if (outcomes == null || outcomes.isEmpty()) {
            return;
        }
        final Map<Long, ParsedAsset> byId = new HashMap<>();
        for (final DownloadedAsset asset : downloaded) {
            byId.put(asset.asset().getId(), asset.asset());
        }
        for (final AssetOutcome outcome : outcomes) {
            final ParsedAsset asset = byId.get(outcome.parsedAssetId());
            if (asset == null) {
                continue;
            }
            switch (outcome.status()) {
                case PUSHED -> {
                    parsedPayloadService.markAssetPushSuccess(asset);
                    metrics.inc("assetsPushed");
                }
                case ALREADY_EXISTS -> {
                    parsedPayloadService.markAssetPushSuccess(asset);
                    metrics.inc("assetsAlreadyExists");
                }
                case FAILED -> {
                    parsedPayloadService.markAssetPushFailure(asset, outcome.errorMessage());
                    metrics.inc("assetsFailed");
                }
                case PENDING -> {
                    parsedPayloadService.markAssetPushFailure(asset, "Target returned PENDING outcome");
                    metrics.inc("assetsFailed");
                }
            }
        }
    }

    private static String probeContentType(final Path file) {
        try {
            final String type = Files.probeContentType(file);
            return type != null ? type : "application/octet-stream";
        } catch (Exception ex) {
            return "application/octet-stream";
        }
    }
}
