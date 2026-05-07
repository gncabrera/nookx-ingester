package com.nookx.ingester.source.klickypedia;

import com.nookx.ingester.ingest.api.NormalizedPayload;
import com.nookx.ingester.source.api.PageParser;
import com.nookx.ingester.source.api.Source;
import com.nookx.ingester.source.api.SourceDiscoverer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class KlickypediaSource implements Source {

    private final SourceDiscoverer discoverer;
    private final Map<String, PageParser<? extends NormalizedPayload>> parsers;

    public KlickypediaSource(
        final KlickypediaDiscoverer discoverer,
        final KlickypediaSetListParser setListParser,
        final KlickypediaSetDetailParser setDetailParser
    ) {
        this.discoverer = discoverer;
        final Map<String, PageParser<? extends NormalizedPayload>> map = new LinkedHashMap<>();
        map.put(setListParser.pageType(), setListParser);
        map.put(setDetailParser.pageType(), setDetailParser);
        this.parsers = Map.copyOf(map);
    }

    @Override
    public String code() {
        return KlickypediaConstants.SOURCE_CODE;
    }

    @Override
    public String ingestTargetCode() {
        return KlickypediaConstants.INGEST_TARGET_CODE;
    }

    @Override
    public SourceDiscoverer discoverer() {
        return discoverer;
    }

    @Override
    public Optional<PageParser<? extends NormalizedPayload>> parserFor(final String pageType) {
        return Optional.ofNullable(parsers.get(pageType));
    }
}
