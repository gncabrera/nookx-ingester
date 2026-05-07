package com.nookx.ingester.pipeline.runner;

import com.nookx.ingester.core.store.RawContentStore;
import com.nookx.ingester.domain.JobRun;
import com.nookx.ingester.domain.ScrapePage;
import com.nookx.ingester.domain.enumeration.JobScopeType;
import com.nookx.ingester.domain.enumeration.JobStage;
import com.nookx.ingester.domain.enumeration.JobTrigger;
import com.nookx.ingester.ingest.api.NormalizedPayload;
import com.nookx.ingester.job.JobContext;
import com.nookx.ingester.job.JobMetrics;
import com.nookx.ingester.job.JobRunService;
import com.nookx.ingester.pipeline.service.ParsedPayloadService;
import com.nookx.ingester.pipeline.service.ScrapePageService;
import com.nookx.ingester.source.api.PageParser;
import com.nookx.ingester.source.api.ParseContext;
import com.nookx.ingester.source.api.ParseResult;
import com.nookx.ingester.source.api.Source;
import com.nookx.ingester.source.api.dto.DiscoveredUrl;
import com.nookx.ingester.source.runtime.SourceRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ParseRunner {

    private static final int DEFAULT_LIMIT = 500;

    private final ScrapePageService scrapePageService;
    private final RawContentStore rawContentStore;
    private final SourceRegistry sourceRegistry;
    private final ParsedPayloadService parsedPayloadService;
    private final JobRunService jobRunService;
    private final PipelineLockRegistry lockRegistry;

    public JobRun runGlobal(final JobTrigger trigger, final String triggeredBy, final int limit) {
        if (!lockRegistry.tryAcquire(JobStage.PARSE, JobScopeType.GLOBAL, null)) {
            return jobRunService.startSkipped(JobStage.PARSE, JobScopeType.GLOBAL, null, trigger, "Parse already running");
        }
        final JobRun run = jobRunService.start(JobStage.PARSE, JobScopeType.GLOBAL, null, trigger, triggeredBy);
        JobContext.bind(run.getId());
        final JobMetrics metrics = new JobMetrics();
        try {
            final List<ScrapePage> pages = scrapePageService.pickPendingParse(limit <= 0 ? DEFAULT_LIMIT : limit);
            if (!pages.isEmpty()) {
                log.info("[Ingester/Parse] - START: pending={} limit={}", pages.size(), limit);
            }
            int index = 0;
            for (final ScrapePage page : pages) {
                index++;
                log.debug(
                    "[Ingester/Parse] - PROGRESS: {}/{} pageId={} source={} pageType={}",
                    index,
                    pages.size(),
                    page.getId(),
                    page.getSourceCode(),
                    page.getPageType()
                );
                try {
                    parsePage(page, metrics);
                } catch (Exception ex) {
                    scrapePageService.markParseFailure(page, ex.toString());
                    metrics.inc("failed");
                    log.warn("[Ingester/Parse] - FAIL: pageId={} message={}", page.getId(), ex.toString());
                }
            }
            if (!pages.isEmpty()) {
                log.info(
                    "[Ingester/Parse] - DONE: parsed={} failed={} skipped={} payloadsUpserted={} newUrlsQueued={}",
                    metrics.get("parsed"),
                    metrics.get("failed"),
                    metrics.get("skipped"),
                    metrics.get("payloadsUpserted"),
                    metrics.get("newUrlsQueued")
                );
            }
            jobRunService.finishSuccess(run, metrics);
            return run;
        } catch (Exception ex) {
            log.error("[Ingester/Parse] - ERROR: message={}", ex.toString());
            jobRunService.finishFailure(run, metrics, ex.toString());
            return run;
        } finally {
            JobContext.clear();
            lockRegistry.release(JobStage.PARSE, JobScopeType.GLOBAL, null);
        }
    }

    private void parsePage(final ScrapePage page, final JobMetrics metrics) {
        final Optional<Source> sourceOpt = sourceRegistry.findByCode(page.getSourceCode());
        if (sourceOpt.isEmpty()) {
            scrapePageService.markParseFailure(page, "Unknown source code: " + page.getSourceCode());
            metrics.inc("failed");
            return;
        }
        final Source source = sourceOpt.get();
        final Optional<PageParser<? extends NormalizedPayload>> parserOpt = source.parserFor(page.getPageType());
        if (parserOpt.isEmpty()) {
            scrapePageService.markParseSuccess(page);
            metrics.inc("skipped");
            return;
        }
        final byte[] bytes = rawContentStore.read(page.getStoragePath());
        if (bytes == null) {
            scrapePageService.markParseFailure(page, "Missing raw content: " + page.getStoragePath());
            metrics.inc("failed");
            return;
        }
        final ParseContext context = new ParseContext(
            page.getSourceCode(),
            page.getPageType(),
            page.getUrl(),
            page.getNaturalKey(),
            new String(bytes, StandardCharsets.UTF_8)
        );
        final ParseResult<? extends NormalizedPayload> result = parserOpt.get().parse(context);
        for (final NormalizedPayload payload : result.payloads()) {
            parsedPayloadService.upsertFromParse(payload, page.getSourceCode(), source.ingestTargetCode(), page);
            metrics.inc("payloadsUpserted");
        }
        for (final DiscoveredUrl discoveredUrl : result.newUrls()) {
            scrapePageService.enqueueIfAbsent(page.getSourceCode(), discoveredUrl);
            metrics.inc("newUrlsQueued");
        }
        scrapePageService.markParseSuccess(page);
        metrics.inc("parsed");
    }
}
