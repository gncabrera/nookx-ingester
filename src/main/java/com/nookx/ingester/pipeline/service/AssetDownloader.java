package com.nookx.ingester.pipeline.service;

import com.nookx.ingester.core.HashUtils;
import com.nookx.ingester.core.http.FetchResult;
import com.nookx.ingester.core.http.ScraperHttpClient;
import com.nookx.ingester.core.store.AssetFileStore;
import com.nookx.ingester.domain.ParsedAsset;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssetDownloader {

    private final AssetFileStore assetFileStore;
    private final ScraperHttpClient scraperHttpClient;
    private final ParsedPayloadService parsedPayloadService;

    public Path ensureDownloaded(final ParsedAsset asset) {
        final String localPath = assetFileStore.buildPath(asset);
        if (assetFileStore.exists(localPath)) {
            log.debug("[Ingester/AssetDownloader] - CACHE-HIT: assetId={} path={}", asset.getId(), localPath);
            return assetFileStore.resolveAbsolutePath(localPath);
        }
        final FetchResult result = scraperHttpClient.get(asset.getExternalUrl());
        if (!result.isOk()) {
            parsedPayloadService.markAssetDownloadFailure(asset, "Could not download asset: HTTP " + result.status());
            log.warn(
                "[Ingester/AssetDownloader] - DOWNLOAD-FAIL: assetId={} externalUrl={} status={}",
                asset.getId(),
                asset.getExternalUrl(),
                result.status()
            );
            return null;
        }
        final String storedLocalPath = assetFileStore.store(asset, result.body());
        parsedPayloadService.markAssetDownloaded(
            asset,
            storedLocalPath,
            result.contentType(),
            HashUtils.sha256(result.body()),
            result.body().length
        );
        log.debug(
            "[Ingester/AssetDownloader] - DOWNLOAD-OK: assetId={} bytes={} path={}",
            asset.getId(),
            result.body().length,
            storedLocalPath
        );
        return assetFileStore.resolveAbsolutePath(storedLocalPath);
    }
}
