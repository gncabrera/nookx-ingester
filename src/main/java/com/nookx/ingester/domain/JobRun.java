package com.nookx.ingester.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.nookx.ingester.domain.enumeration.JobScopeType;
import com.nookx.ingester.domain.enumeration.JobStage;
import com.nookx.ingester.domain.enumeration.JobStatus;
import com.nookx.ingester.domain.enumeration.JobTrigger;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(
    name = "job_run",
    indexes = {
        @Index(name = "ix_job_run_status", columnList = "status, started_at"),
        @Index(name = "ix_job_run_stage_scope", columnList = "stage, scope_code, started_at")
    }
)
public class JobRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 32)
    private JobStage stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 32)
    private JobScopeType scopeType;

    @Column(name = "scope_code", length = 128)
    private String scopeCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 32)
    private JobTrigger triggerType;

    @Column(name = "triggered_by", length = 128)
    private String triggeredBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private JobStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics_json", columnDefinition = "json")
    private JsonNode metricsJson;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;
}
