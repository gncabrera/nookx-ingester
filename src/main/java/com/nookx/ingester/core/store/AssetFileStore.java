package com.nookx.ingester.core.store;

import com.nookx.ingester.config.IngesterProperties;
import com.nookx.ingester.domain.ParsedAsset;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class AssetFileStore {

    private static final String FALLBACK = "__";
    private static final int MAX_FILENAME_PART = 128;

    private final Path baseDir;

    public AssetFileStore(final IngesterProperties properties) {
        this.baseDir = Path.of(properties.getStorage().getAssetDir()).toAbsolutePath().normalize();
    }

    public String buildPath(final ParsedAsset asset) {
        final String sourceCode = sanitize(asset.getParsedPayload().getSourceCode());
        final String externalId = sanitize(asset.getParsedPayload().getExternalId());
        final String fileName = buildFilename(asset);
        final Path relativePath = Path.of(sourceCode).resolve(externalId).resolve(fileName).normalize();
        return relativePath.toString().replace('\\', '/');
    }

    public String store(final ParsedAsset asset, final byte[] bytes) {
        final String relativePath = buildPath(asset);
        final Path target = resolveAbsolutePath(relativePath);
        if (!target.startsWith(baseDir)) {
            throw new IllegalStateException("Asset path escaped base directory");
        }
        try {
            final Path parentDir = target.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            Files.write(target, bytes);
            return relativePath;
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not store asset file", ex);
        }
    }

    public Path resolveAbsolutePath(final String relativePath) {
        return baseDir.resolve(relativePath).normalize();
    }

    public boolean exists(final String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        final Path file = resolveAbsolutePath(relativePath);
        return file.startsWith(baseDir) && Files.isRegularFile(file);
    }

    private static String buildFilename(final ParsedAsset asset) {
        final String prefix = asset.getSortOrder() == null ? "asset" : "asset-" + asset.getSortOrder();
        final String externalUrl = asset.getExternalUrl();
        if (externalUrl == null || !externalUrl.contains(".")) {
            return prefix + ".bin";
        }
        final int idx = externalUrl.lastIndexOf('.');
        final String ext = externalUrl.substring(idx).toLowerCase(Locale.ROOT);
        if (ext.length() > 10 || ext.contains("/") || ext.contains("?")) {
            return prefix + ".bin";
        }
        return prefix + ext;
    }

    private static String sanitize(final String value) {
        if (value == null || value.isBlank()) {
            return FALLBACK;
        }
        final StringBuilder out = new StringBuilder();
        for (final char c : value.toCharArray()) {
            final boolean allowed =
                (c >= 'a' && c <= 'z') ||
                (c >= 'A' && c <= 'Z') ||
                (c >= '0' && c <= '9') ||
                c == '-' ||
                c == '_' ||
                c == '.';
            out.append(allowed ? c : '_');
            if (out.length() >= MAX_FILENAME_PART) {
                break;
            }
        }
        return out.isEmpty() ? FALLBACK : out.toString();
    }
}
