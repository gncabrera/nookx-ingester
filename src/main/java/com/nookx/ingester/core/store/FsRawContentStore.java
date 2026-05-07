package com.nookx.ingester.core.store;

import com.nookx.ingester.config.IngesterProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Component;

@Component
public class FsRawContentStore implements RawContentStore {

    private static final String FALLBACK_KEY = "__";

    private final Path baseDir;

    public FsRawContentStore(final IngesterProperties properties) {
        this.baseDir = Path.of(properties.getStorage().getRawDir()).toAbsolutePath().normalize();
    }

    @Override
    public String buildPath(final String sourceCode, final String pageType, final String naturalKey, final String fallbackKey) {
        final Path target = buildTargetPath(sourceCode, pageType, naturalKey, fallbackKey);
        return baseDir.relativize(target).toString().replace('\\', '/');
    }

    @Override
    public String store(final String sourceCode, final String pageType, final String naturalKey, final String fallbackKey, final byte[] bytes) {
        final Path target = buildTargetPath(sourceCode, pageType, naturalKey, fallbackKey);
        final Path dir = target.getParent();
        try {
            Files.createDirectories(dir);
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream(bytes.length / 4 + 64);
            try (
                ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                GZIPOutputStream gzip = new GZIPOutputStream(buffer)
            ) {
                in.transferTo(gzip);
            }
            Files.write(target, buffer.toByteArray());
            return buildPath(sourceCode, pageType, naturalKey, fallbackKey);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not store raw payload", ex);
        }
    }

    @Override
    public byte[] read(final String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return null;
        }
        final Path target = baseDir.resolve(storagePath).normalize();
        if (!target.startsWith(baseDir) || !Files.isRegularFile(target)) {
            return null;
        }
        try (GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(target))) {
            return gzip.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read raw payload", ex);
        }
    }

    @Override
    public boolean exists(final String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return false;
        }
        final Path target = baseDir.resolve(storagePath).normalize();
        return target.startsWith(baseDir) && Files.isRegularFile(target);
    }

    private Path buildTargetPath(final String sourceCode, final String pageType, final String naturalKey, final String fallbackKey) {
        final String key = sanitize(resolveKey(naturalKey, fallbackKey));
        final String prefix = key.length() >= 2 ? key.substring(0, 2) : FALLBACK_KEY;
        final Path dir = baseDir
            .resolve(sanitize(sourceCode))
            .resolve(sanitize(pageType).toLowerCase(Locale.ROOT))
            .resolve(prefix);
        return dir.resolve(key + ".html.gz");
    }

    private static String resolveKey(final String naturalKey, final String fallbackKey) {
        if (naturalKey != null && !naturalKey.isBlank()) {
            return naturalKey;
        }
        return fallbackKey;
    }

    private static String sanitize(final String value) {
        if (value == null || value.isBlank()) {
            return FALLBACK_KEY;
        }
        final StringBuilder out = new StringBuilder();
        final int maxLength = Math.min(value.length(), 128);
        for (int i = 0; i < maxLength; i++) {
            final char c = value.charAt(i);
            final boolean allowed =
                (c >= 'a' && c <= 'z') ||
                (c >= 'A' && c <= 'Z') ||
                (c >= '0' && c <= '9') ||
                c == '-' ||
                c == '_' ||
                c == '.';
            out.append(allowed ? c : '_');
        }
        return out.isEmpty() ? FALLBACK_KEY : out.toString();
    }
}
