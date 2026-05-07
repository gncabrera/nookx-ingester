package com.nookx.ingester.pipeline.runner;

import com.nookx.ingester.core.HashUtils;
import com.nookx.ingester.core.ScraperJitter;
import com.nookx.ingester.core.http.FetchResult;
import com.nookx.ingester.core.http.ScraperHttpClient;
import com.nookx.ingester.core.store.RawContentStore;
import com.nookx.ingester.domain.JobRun;
import com.nookx.ingester.domain.ScrapePage;
import com.nookx.ingester.domain.enumeration.JobScopeType;
import com.nookx.ingester.domain.enumeration.JobStage;
import com.nookx.ingester.domain.enumeration.JobTrigger;
import com.nookx.ingester.job.JobContext;
import com.nookx.ingester.job.JobMetrics;
import com.nookx.ingester.job.JobRunService;
import com.nookx.ingester.pipeline.service.ScrapePageService;
import com.nookx.ingester.source.api.Source;
import com.nookx.ingester.source.runtime.SourceRegistry;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CrawlRunner {

    private static final int DEFAULT_LIMIT = 200;

    private final SourceRegistry sourceRegistry;
    private final ScrapePageService scrapePageService;
    private final ScraperHttpClient httpClient;
    private final RawContentStore rawContentStore;
    private final JobRunService jobRunService;
    private final PipelineLockRegistry lockRegistry;

    public JobRun runGlobal(final JobTrigger trigger, final String triggeredBy, final int limit, final boolean force) {
        if (!lockRegistry.tryAcquire(JobStage.CRAWL, JobScopeType.GLOBAL, null)) {
            return jobRunService.startSkipped(JobStage.CRAWL, JobScopeType.GLOBAL, null, trigger, "Crawl already running");
        }
        final JobRun run = jobRunService.start(JobStage.CRAWL, JobScopeType.GLOBAL, null, trigger, triggeredBy);
        JobContext.bind(run.getId());
        final JobMetrics metrics = new JobMetrics();
        try {
            for (final Source source : sourceRegistry.all()) {
                if (!sourceRegistry.isEnabled(source.code())) {
                    continue;
                }
                runForSourceInternal(source, limit <= 0 ? DEFAULT_LIMIT : limit, force, metrics);
            }
            jobRunService.finishSuccess(run, metrics);
            return run;
        } catch (Exception ex) {
            log.error("[Ingester/Crawl] - ERROR: message={}", ex.toString());
            jobRunService.finishFailure(run, metrics, ex.toString());
            return run;
        } finally {
            JobContext.clear();
            lockRegistry.release(JobStage.CRAWL, JobScopeType.GLOBAL, null);
        }
    }

    public JobRun runForSource(
        final String sourceCode,
        final JobTrigger trigger,
        final String triggeredBy,
        final int limit,
        final boolean force
    ) {
        if (!lockRegistry.tryAcquire(JobStage.CRAWL, JobScopeType.SOURCE, sourceCode)) {
            return jobRunService.startSkipped(
                JobStage.CRAWL,
                JobScopeType.SOURCE,
                sourceCode,
                trigger,
                "Crawl already running for source " + sourceCode
            );
        }
        final Source source = sourceRegistry.findByCode(sourceCode).orElse(null);
        if (source == null || !sourceRegistry.isEnabled(sourceCode)) {
            lockRegistry.release(JobStage.CRAWL, JobScopeType.SOURCE, sourceCode);
            return jobRunService.startSkipped(
                JobStage.CRAWL,
                JobScopeType.SOURCE,
                sourceCode,
                trigger,
                "Source unavailable: " + sourceCode
            );
        }
        final JobRun run = jobRunService.start(JobStage.CRAWL, JobScopeType.SOURCE, sourceCode, trigger, triggeredBy);
        JobContext.bind(run.getId());
        final JobMetrics metrics = new JobMetrics();
        try {
            runForSourceInternal(source, limit <= 0 ? DEFAULT_LIMIT : limit, force, metrics);
            jobRunService.finishSuccess(run, metrics);
            return run;
        } catch (Exception ex) {
            log.error("[Ingester/Crawl] - ERROR: source={} message={}", sourceCode, ex.toString());
            jobRunService.finishFailure(run, metrics, ex.toString());
            return run;
        } finally {
            JobContext.clear();
            lockRegistry.release(JobStage.CRAWL, JobScopeType.SOURCE, sourceCode);
        }
    }

    private void runForSourceInternal(final Source source, final int limit, final boolean force, final JobMetrics metrics) {
        final String sourceCode = source.code();
        final List<ScrapePage> duePages = scrapePageService.pickDueForFetch(sourceCode, limit);
        if (duePages.isEmpty()) {
            return;
        }
        log.info(
            "[Ingester/Crawl] - START: source={} duePages={} limit={} force={}",
            sourceCode,
            duePages.size(),
            limit,
            force
        );
        final int sourceTotal = duePages.size();
        int sourceProcessed = 0;
        for (final ScrapePage page : duePages) {
            sourceProcessed++;
            log.debug(
                "[Ingester/Crawl] - PROGRESS: source={} {}/{} pageId={} pageType={} url={}",
                sourceCode,
                sourceProcessed,
                sourceTotal,
                page.getId(),
                page.getPageType(),
                page.getUrl()
            );
            final String existingStoragePath = rawContentStore.buildPath(
                page.getSourceCode(),
                page.getPageType(),
                page.getNaturalKey(),
                String.valueOf(page.getId())
            );
            if (!force && rawContentStore.exists(existingStoragePath)) {
                if (page.getStoragePath() == null) {
                    page.setStoragePath(existingStoragePath);
                }
                scrapePageService.markNotModified(page);
                metrics.inc("skippedByCache");
                continue;
            }
            final FetchResult result = httpClient.get(page.getUrl(), page.getEtag(), page.getLastModified());
            if (result.isTransportError()) {
                scrapePageService.markFetchFailure(page, result.errorMessage());
                metrics.inc("failed");
                continue;
            }
            if (result.isNotModified()) {
                scrapePageService.markNotModified(page);
                metrics.inc("notModified");
                continue;
            }
            if (result.isNotFound() || result.isTransient() || !result.isOk()) {
                scrapePageService.markFetchFailure(page, "HTTP " + result.status());
                metrics.inc("failed");
                continue;
            }
            final Optional<String> invalidReason = sourceRegistry.invalidFetchReason(
                page.getSourceCode(),
                page.getPageType(),
                page.getUrl(),
                result.body()
            );
            if (invalidReason.isPresent()) {
                scrapePageService.markFetchFailure(page, invalidReason.get());
                metrics.inc("failed");
                continue;
            }
            final String hash = HashUtils.sha256(result.body());
            final String storagePath = rawContentStore.store(
                page.getSourceCode(),
                page.getPageType(),
                page.getNaturalKey(),
                String.valueOf(page.getId()),
                result.body()
            );
            scrapePageService.markFetchSuccess(
                page,
                result.status(),
                result.etag(),
                result.lastModified(),
                storagePath,
                hash,
                result.body().length
            );
            metrics.inc("processed");
            sleepBetweenRequests(sourceCode);
        }
        log.info(
            "[Ingester/Crawl] - DONE: source={} processed={} notModified={} skippedByCache={} failed={}",
            sourceCode,
            metrics.get("processed"),
            metrics.get("notModified"),
            metrics.get("skippedByCache"),
            metrics.get("failed")
        );
    }

    private void sleepBetweenRequests(final String sourceCode) {
        final long minDelay = sourceRegistry.minDelayMs(sourceCode);
        final long jitter = ScraperJitter.pickJitter(sourceRegistry.jitterMs(sourceCode));
        final long total = minDelay + jitter;
        if (total <= 0L) {
            return;
        }
        try {
            Thread.sleep(total);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
