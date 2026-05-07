package com.nookx.ingester.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nookx.ingester.domain.JobRun;
import com.nookx.ingester.domain.enumeration.JobScopeType;
import com.nookx.ingester.domain.enumeration.JobStage;
import com.nookx.ingester.domain.enumeration.JobStatus;
import com.nookx.ingester.domain.enumeration.JobTrigger;
import com.nookx.ingester.repository.JobRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class JobRunService {

    private static final int DEFAULT_RECENT_LIMIT = 100;

    private final JobRunRepository jobRunRepository;
    private final ObjectMapper objectMapper;

    public JobRun start(
        final JobStage stage,
        final JobScopeType scopeType,
        final String scopeCode,
        final JobTrigger trigger,
        final String triggeredBy
    ) {
        final JobRun run = new JobRun();
        run.setStage(stage);
        run.setScopeType(scopeType);
        run.setScopeCode(scopeCode);
        run.setTriggerType(trigger);
        run.setTriggeredBy(triggeredBy);
        run.setStatus(JobStatus.RUNNING);
        run.setStartedAt(Instant.now());
        return jobRunRepository.save(run);
    }

    public void finishSuccess(final JobRun run, final JobMetrics metrics) {
        run.setStatus(JobStatus.SUCCESS);
        run.setEndedAt(Instant.now());
        if (metrics != null) {
            run.setMetricsJson(metrics.toJson(objectMapper));
        }
        jobRunRepository.save(run);
    }

    public void finishSkipped(final JobRun run, final String reason) {
        run.setStatus(JobStatus.SKIPPED);
        run.setEndedAt(Instant.now());
        run.setErrorMessage(truncate(reason));
        jobRunRepository.save(run);
    }

    public void finishFailure(final JobRun run, final JobMetrics metrics, final String errorMessage) {
        run.setStatus(JobStatus.FAILED);
        run.setEndedAt(Instant.now());
        run.setErrorMessage(truncate(errorMessage));
        if (metrics != null) {
            run.setMetricsJson(metrics.toJson(objectMapper));
        }
        jobRunRepository.save(run);
    }

    public JobRun startSkipped(
        final JobStage stage,
        final JobScopeType scopeType,
        final String scopeCode,
        final JobTrigger trigger,
        final String reason
    ) {
        final JobRun run = new JobRun();
        run.setStage(stage);
        run.setScopeType(scopeType);
        run.setScopeCode(scopeCode);
        run.setTriggerType(trigger);
        run.setStatus(JobStatus.SKIPPED);
        final Instant now = Instant.now();
        run.setStartedAt(now);
        run.setEndedAt(now);
        run.setErrorMessage(truncate(reason));
        return jobRunRepository.save(run);
    }

    @Transactional(readOnly = true)
    public List<JobRun> running() {
        return jobRunRepository.findByStatusOrderByStartedAtDesc(JobStatus.RUNNING);
    }

    @Transactional(readOnly = true)
    public List<JobRun> recent(final int limit) {
        final int safeLimit = limit <= 0 ? DEFAULT_RECENT_LIMIT : limit;
        return jobRunRepository.findAllByOrderByStartedAtDesc(PageRequest.of(0, safeLimit));
    }

    @Transactional(readOnly = true)
    public long countSinceByStatus(final JobStatus status, final Duration window) {
        final Instant since = Instant.now().minus(window);
        return jobRunRepository.countByStatusSince(status, since);
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
}
