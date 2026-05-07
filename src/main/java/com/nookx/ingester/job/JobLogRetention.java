package com.nookx.ingester.job;

import com.nookx.ingester.config.IngesterProperties;
import com.nookx.ingester.repository.JobLogRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ingester.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class JobLogRetention {

    private final JobLogRepository jobLogRepository;
    private final IngesterProperties properties;

    @Scheduled(cron = "${ingester.job.retention-cron:0 0 3 * * *}")
    @Transactional
    public void purgeOldLogs() {
        final int retentionDays = properties.getJob().getLogRetentionDays();
        final Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        final int deleted = jobLogRepository.deleteOlderThan(cutoff);
        log.info("[Ingester/JobLogRetention] - PURGE: deleted={} olderThan={} days", deleted, retentionDays);
    }
}
