package com.nookx.ingester.pipeline.scheduling;

import com.nookx.ingester.config.IngesterProperties;
import com.nookx.ingester.config.IngesterProperties.IngestTargetConfig;
import com.nookx.ingester.config.IngesterProperties.SourceConfig;
import com.nookx.ingester.domain.enumeration.JobTrigger;
import com.nookx.ingester.ingest.api.IngestTarget;
import com.nookx.ingester.ingest.api.NormalizedPayload;
import com.nookx.ingester.ingest.runtime.IngestTargetRegistry;
import com.nookx.ingester.pipeline.runner.CrawlRunner;
import com.nookx.ingester.pipeline.runner.DiscoveryRunner;
import com.nookx.ingester.pipeline.runner.ParseRunner;
import com.nookx.ingester.pipeline.runner.PushRunner;
import com.nookx.ingester.source.api.Source;
import com.nookx.ingester.source.runtime.SourceRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ingester.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class PipelineScheduleRegistrar {

    private static final String SCHEDULED_TRIGGER = "scheduled";

    private final IngesterProperties properties;
    private final SourceRegistry sourceRegistry;
    private final IngestTargetRegistry ingestTargetRegistry;
    private final DiscoveryRunner discoveryRunner;
    private final CrawlRunner crawlRunner;
    private final ParseRunner parseRunner;
    private final PushRunner pushRunner;
    private final TaskScheduler taskScheduler;

    @PostConstruct
    public void registerSchedules() {
        registerDiscoverySchedules();
        registerPushSchedules();
        registerCrawlSchedule();
        registerParseSchedule();
    }

    private void registerDiscoverySchedules() {
        for (final Source source : sourceRegistry.all()) {
            final SourceConfig config = properties.getSources().get(source.code());
            if (config == null || !config.isEnabled() || config.getDiscoveryCron() == null || config.getDiscoveryCron().isBlank()) {
                log.info("[Ingester/Scheduler] - DISCOVERY: source={} skipped (no cron / disabled)", source.code());
                continue;
            }
            taskScheduler.schedule(
                () -> discoveryRunner.runForSource(source.code(), JobTrigger.SCHEDULED, SCHEDULED_TRIGGER),
                new CronTrigger(config.getDiscoveryCron())
            );
            log.info("[Ingester/Scheduler] - DISCOVERY: source={} cron={}", source.code(), config.getDiscoveryCron());
        }
    }

    private void registerPushSchedules() {
        final Map<String, IngestTarget<? extends NormalizedPayload>> indexed = ingestTargetRegistry.indexedByCode();
        for (final IngestTarget<? extends NormalizedPayload> target : indexed.values()) {
            final IngestTargetConfig config = properties.getIngestTargets().get(target.code());
            if (config == null || !config.isEnabled() || config.getPushCron() == null || config.getPushCron().isBlank()) {
                log.info("[Ingester/Scheduler] - PUSH: target={} skipped (no cron / disabled)", target.code());
                continue;
            }
            taskScheduler.schedule(
                () -> pushRunner.runForTarget(target.code(), JobTrigger.SCHEDULED, SCHEDULED_TRIGGER, config.getMaxBatchSize()),
                new CronTrigger(config.getPushCron())
            );
            log.info("[Ingester/Scheduler] - PUSH: target={} cron={}", target.code(), config.getPushCron());
        }
    }

    private void registerCrawlSchedule() {
        final Duration delay = Duration.ofMillis(properties.getSchedule().getCrawlDelayMs());
        final Instant start = Instant.now().plusMillis(properties.getSchedule().getCrawlInitialDelayMs());
        taskScheduler.scheduleWithFixedDelay(
            () -> crawlRunner.runGlobal(JobTrigger.SCHEDULED, SCHEDULED_TRIGGER, 0, false),
            start,
            delay
        );
        log.info(
            "[Ingester/Scheduler] - CRAWL: fixedDelayMs={} initialDelayMs={}",
            properties.getSchedule().getCrawlDelayMs(),
            properties.getSchedule().getCrawlInitialDelayMs()
        );
    }

    private void registerParseSchedule() {
        final Duration delay = Duration.ofMillis(properties.getSchedule().getParseDelayMs());
        final Instant start = Instant.now().plusMillis(properties.getSchedule().getParseInitialDelayMs());
        taskScheduler.scheduleWithFixedDelay(
            () -> parseRunner.runGlobal(JobTrigger.SCHEDULED, SCHEDULED_TRIGGER, 0),
            start,
            delay
        );
        log.info(
            "[Ingester/Scheduler] - PARSE: fixedDelayMs={} initialDelayMs={}",
            properties.getSchedule().getParseDelayMs(),
            properties.getSchedule().getParseInitialDelayMs()
        );
    }
}
