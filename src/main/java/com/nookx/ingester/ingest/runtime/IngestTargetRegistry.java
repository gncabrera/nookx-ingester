package com.nookx.ingester.ingest.runtime;

import com.nookx.ingester.config.IngesterProperties;
import com.nookx.ingester.config.IngesterProperties.IngestTargetConfig;
import com.nookx.ingester.ingest.api.IngestTarget;
import com.nookx.ingester.ingest.api.NormalizedPayload;
import com.nookx.ingester.source.api.Source;
import com.nookx.ingester.source.runtime.SourceRegistry;
import jakarta.annotation.PostConstruct;
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
public class IngestTargetRegistry {

    private final List<IngestTarget<? extends NormalizedPayload>> targets;
    private final IngesterProperties properties;
    private final SourceRegistry sourceRegistry;

    @PostConstruct
    public void validateWiring() {
        final Map<String, IngestTarget<? extends NormalizedPayload>> indexed = indexedByCode();
        for (final Source source : sourceRegistry.all()) {
            final String targetCode = source.ingestTargetCode();
            if (targetCode == null || targetCode.isBlank()) {
                throw new IllegalStateException("Source " + source.code() + " does not declare an ingest target code");
            }
            if (!indexed.containsKey(targetCode)) {
                throw new IllegalStateException(
                    "Source " + source.code() + " references unknown ingest target '" + targetCode + "'"
                );
            }
        }
        for (final IngestTarget<? extends NormalizedPayload> target : targets) {
            log.info(
                "[Ingester/IngestTargetRegistry] - REGISTER: target={} payloadType={} enabled={}",
                target.code(),
                target.payloadType().getSimpleName(),
                isEnabled(target.code())
            );
        }
    }

    public List<IngestTarget<? extends NormalizedPayload>> all() {
        return Collections.unmodifiableList(targets);
    }

    public Map<String, IngestTarget<? extends NormalizedPayload>> indexedByCode() {
        final Map<String, IngestTarget<? extends NormalizedPayload>> map = new LinkedHashMap<>();
        for (final IngestTarget<? extends NormalizedPayload> target : targets) {
            map.put(target.code(), target);
        }
        return map;
    }

    public Optional<IngestTarget<? extends NormalizedPayload>> findByCode(final String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (final IngestTarget<? extends NormalizedPayload> target : targets) {
            if (code.equals(target.code())) {
                return Optional.of(target);
            }
        }
        return Optional.empty();
    }

    public boolean isEnabled(final String targetCode) {
        final IngestTargetConfig config = properties.getIngestTargets().get(targetCode);
        return config == null || config.isEnabled();
    }

    public IngestTargetConfig configOf(final String targetCode) {
        return properties.getIngestTargets().get(targetCode);
    }
}
