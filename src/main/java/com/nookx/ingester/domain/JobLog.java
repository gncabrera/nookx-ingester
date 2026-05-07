package com.nookx.ingester.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
    name = "job_log",
    indexes = {
        @Index(name = "ix_job_log_run", columnList = "job_run_id, ts")
    }
)
public class JobLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_run_id", nullable = false)
    private Long jobRunId;

    @Column(name = "ts", nullable = false)
    private Instant ts;

    @Column(name = "level", nullable = false, length = 8)
    private String level;

    @Column(name = "logger_name", length = 255)
    private String loggerName;

    @Column(name = "message", length = 2000)
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_json", columnDefinition = "json")
    private JsonNode contextJson;
}
