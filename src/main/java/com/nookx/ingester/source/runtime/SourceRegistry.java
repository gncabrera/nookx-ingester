package com.nookx.ingester.source.runtime;

import com.nookx.ingester.config.IngesterProperties;
import com.nookx.ingester.config.IngesterProperties.SourceConfig;
import com.nookx.ingester.source.api.Source;
import com.nookx.ingester.source.api.SourceFetchValidator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SourceRegistry {

    private static final long DEFAULT_MIN_DELAY_MS = 1000L;
    private static final long DEFAULT_JITTER_MS = 500L;

    private final List<Source> sources;
    private final List<SourceFetchValidator> fetchValidators;
    private final IngesterProperties properties;

    public List<Source> all() {
        return Collections.unmodifiableList(sources);
    }

    public Map<String, Source> indexedByCode() {
        final Map<String, Source> map = new LinkedHashMap<>();
        for (final Source source : sources) {
            map.put(source.code(), source);
        }
        return map;
    }

    public Optional<Source> findByCode(final String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (final Source source : sources) {
            if (code.equals(source.code())) {
                return Optional.of(source);
            }
        }
        return Optional.empty();
    }

    public boolean isEnabled(final String sourceCode) {
        final SourceConfig config = properties.getSources().get(sourceCode);
        return config == null || config.isEnabled();
    }

    public long minDelayMs(final String sourceCode) {
        final SourceConfig config = properties.getSources().get(sourceCode);
        if (config == null) {
            return DEFAULT_MIN_DELAY_MS;
        }
        return config.getMinDelayMs();
    }

    public long jitterMs(final String sourceCode) {
        final SourceConfig config = properties.getSources().get(sourceCode);
        if (config == null) {
            return DEFAULT_JITTER_MS;
        }
        return config.getJitterMs();
    }

    public Optional<String> invalidFetchReason(final String sourceCode, final String pageType, final String url, final byte[] body) {
        for (final SourceFetchValidator validator : fetchValidators) {
            if (sourceCode.equals(validator.sourceCode())) {
                return validator.invalidFetchReason(pageType, url, body);
            }
        }
        return Optional.empty();
    }

    public void logRegisteredSources() {
        for (final Source source : sources) {
            log.info(
                "[Ingester/SourceRegistry] - REGISTER: source={} ingestTarget={} enabled={}",
                source.code(),
                source.ingestTargetCode(),
                isEnabled(source.code())
            );
        }
    }
}
