package com.nookx.ingester.job;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.nookx.ingester.domain.JobLog;
import com.nookx.ingester.repository.JobLogRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JobLogAppender {

    private static final Logger LOG = LoggerFactory.getLogger(JobLogAppender.class);
    private static final int QUEUE_CAPACITY = 5000;
    private static final long DRAIN_INTERVAL_MS = 250L;

    private final JobLogRepository jobLogRepository;

    @Value("${ingester.job.log-min-level:INFO}")
    private String logMinLevelName;

    private final BlockingQueue<JobLog> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private Level minLevel;
    private Thread worker;
    private DelegatingAppender appender;

    @PostConstruct
    public void install() {
        this.minLevel = Level.toLevel(logMinLevelName, Level.INFO);
        this.appender = new DelegatingAppender(this);
        final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        this.appender.setContext(loggerContext);
        this.appender.setName("jobLogAppender");
        this.appender.start();
        loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(this.appender);
        this.worker = new Thread(this::drainLoop, "job-log-flush");
        this.worker.setDaemon(true);
        this.worker.start();
        LOG.info("[Ingester/JobLogAppender] - INSTALL: minLevel={}", this.minLevel);
    }

    void enqueue(final ILoggingEvent event) {
        if (event.getLevel().toInt() < minLevel.toInt()) {
            return;
        }
        final String jobRunIdRaw = event.getMDCPropertyMap().get(JobContext.MDC_JOB_RUN_ID);
        if (jobRunIdRaw == null || jobRunIdRaw.isBlank()) {
            return;
        }
        final Long jobRunId;
        try {
            jobRunId = Long.parseLong(jobRunIdRaw);
        } catch (NumberFormatException ex) {
            return;
        }
        final JobLog log = new JobLog();
        log.setJobRunId(jobRunId);
        log.setTs(Instant.ofEpochMilli(event.getTimeStamp()));
        log.setLevel(event.getLevel().toString());
        log.setLoggerName(event.getLoggerName());
        log.setMessage(truncate(event.getFormattedMessage()));
        queue.offer(log);
    }

    private void drainLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                final JobLog first = queue.poll(DRAIN_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                final java.util.List<JobLog> batch = new java.util.ArrayList<>();
                batch.add(first);
                queue.drainTo(batch, 200);
                try {
                    jobLogRepository.saveAll(batch);
                } catch (Exception ex) {
                    LOG.warn("[Ingester/JobLogAppender] - DRAIN: persist batch failed: {}", ex.getMessage());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String truncate(final String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 1990) {
            return value;
        }
        return value.substring(0, 1990);
    }

    private static final class DelegatingAppender extends AppenderBase<ILoggingEvent> {

        private final JobLogAppender owner;

        private DelegatingAppender(final JobLogAppender owner) {
            this.owner = owner;
        }

        @Override
        protected void append(final ILoggingEvent eventObject) {
            owner.enqueue(eventObject);
        }
    }
}
