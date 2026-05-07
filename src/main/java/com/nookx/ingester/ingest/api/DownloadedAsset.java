package com.nookx.ingester.ingest.api;

import com.nookx.ingester.domain.ParsedAsset;
import java.nio.file.Path;

public record DownloadedAsset(ParsedAsset asset, Path file, String contentType) {
}
