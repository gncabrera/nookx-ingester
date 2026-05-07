package com.nookx.ingester.pipeline.runner;

import com.nookx.ingester.domain.enumeration.JobTrigger;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobDispatcher {

    private static final String MANUAL_TRIGGER_LABEL = "dashboard";

    private final TaskScheduler taskScheduler;
    private final DiscoveryRunner discoveryRunner;
    private final CrawlRunner crawlRunner;
    private final ParseRunner parseRunner;
    private final PushRunner pushRunner;

    public void dispatchDiscoveryForSource(final String sourceCode) {
        submit(() -> discoveryRunner.runForSource(sourceCode, JobTrigger.MANUAL, MANUAL_TRIGGER_LABEL));
    }

    public void dispatchCrawlForSource(final String sourceCode, final int limit, final boolean force) {
        submit(() -> crawlRunner.runForSource(sourceCode, JobTrigger.MANUAL, MANUAL_TRIGGER_LABEL, limit, force));
    }

    public void dispatchCrawlGlobal(final int limit, final boolean force) {
        submit(() -> crawlRunner.runGlobal(JobTrigger.MANUAL, MANUAL_TRIGGER_LABEL, limit, force));
    }

    public void dispatchParseGlobal(final int limit) {
        submit(() -> parseRunner.runGlobal(JobTrigger.MANUAL, MANUAL_TRIGGER_LABEL, limit));
    }

    public void dispatchPushForTarget(final String targetCode, final int limit) {
        submit(() -> pushRunner.runForTarget(targetCode, JobTrigger.MANUAL, MANUAL_TRIGGER_LABEL, limit));
    }

    private void submit(final Runnable task) {
        taskScheduler.schedule(task, Instant.now());
    }
}
