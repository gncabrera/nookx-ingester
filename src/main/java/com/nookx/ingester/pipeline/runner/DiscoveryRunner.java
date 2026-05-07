package com.nookx.ingester.pipeline.runner;

import com.nookx.ingester.domain.JobRun;
import com.nookx.ingester.domain.enumeration.JobScopeType;
import com.nookx.ingester.domain.enumeration.JobStage;
import com.nookx.ingester.domain.enumeration.JobTrigger;
import com.nookx.ingester.job.JobContext;
import com.nookx.ingester.job.JobMetrics;
import com.nookx.ingester.job.JobRunService;
import com.nookx.ingester.pipeline.service.ScrapePageService;
import com.nookx.ingester.source.api.Source;
import com.nookx.ingester.source.api.dto.DiscoveredUrl;
import com.nookx.ingester.source.runtime.SourceRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiscoveryRunner {

    private final SourceRegistry sourceRegistry;
    private final ScrapePageService scrapePageService;
    private final JobRunService jobRunService;
    private final PipelineLockRegistry lockRegistry;

    public JobRun runForSource(final String sourceCode, final JobTrigger trigger, final String triggeredBy) {
        if (!lockRegistry.tryAcquire(JobStage.DISCOVERY, JobScopeType.SOURCE, sourceCode)) {
            return jobRunService.startSkipped(
                JobStage.DISCOVERY,
                JobScopeType.SOURCE,
                sourceCode,
                trigger,
                "Discovery already running for source " + sourceCode
            );
        }
        final Source source = sourceRegistry.findByCode(sourceCode).orElse(null);
        if (source == null) {
            lockRegistry.release(JobStage.DISCOVERY, JobScopeType.SOURCE, sourceCode);
            return jobRunService.startSkipped(
                JobStage.DISCOVERY,
                JobScopeType.SOURCE,
                sourceCode,
                trigger,
                "Unknown source: " + sourceCode
            );
        }
        if (!sourceRegistry.isEnabled(sourceCode)) {
            lockRegistry.release(JobStage.DISCOVERY, JobScopeType.SOURCE, sourceCode);
            return jobRunService.startSkipped(
                JobStage.DISCOVERY,
                JobScopeType.SOURCE,
                sourceCode,
                trigger,
                "Source disabled: " + sourceCode
            );
        }
        final JobRun run = jobRunService.start(JobStage.DISCOVERY, JobScopeType.SOURCE, sourceCode, trigger, triggeredBy);
        JobContext.bind(run.getId());
        final JobMetrics metrics = new JobMetrics();
        try {
            log.info("[Ingester/Discovery] - START: source={} trigger={}", sourceCode, trigger);
            final List<DiscoveredUrl> urls = source.discoverer().discover();
            metrics.inc("discovered", urls.size());
            int sourceIndex = 0;
            for (final DiscoveredUrl discovered : urls) {
                sourceIndex++;
                final boolean inserted = scrapePageService.enqueueIfAbsent(sourceCode, discovered);
                if (inserted) {
                    metrics.inc("inserted");
                }
                log.debug(
                    "[Ingester/Discovery] - PROGRESS: source={} {}/{} url={} inserted={}",
                    sourceCode,
                    sourceIndex,
                    urls.size(),
                    discovered.url(),
                    inserted
                );
            }
            log.info(
                "[Ingester/Discovery] - DONE: source={} discovered={} inserted={}",
                sourceCode,
                metrics.get("discovered"),
                metrics.get("inserted")
            );
            jobRunService.finishSuccess(run, metrics);
            return run;
        } catch (Exception ex) {
            log.error("[Ingester/Discovery] - ERROR: source={} message={}", sourceCode, ex.toString());
            jobRunService.finishFailure(run, metrics, ex.toString());
            return run;
        } finally {
            JobContext.clear();
            lockRegistry.release(JobStage.DISCOVERY, JobScopeType.SOURCE, sourceCode);
        }
    }
}
