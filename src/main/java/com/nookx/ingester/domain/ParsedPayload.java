package com.nookx.ingester.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.nookx.ingester.domain.enumeration.PushStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(
    name = "parsed_payload",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "ux_parsed_payload_source_target_external",
            columnNames = { "source_code", "ingest_target_code", "external_id" }
        )
    },
    indexes = {
        @Index(name = "ix_parsed_payload_target_status", columnList = "ingest_target_code, push_status, push_retry_count"),
        @Index(name = "ix_parsed_payload_scrape_page", columnList = "scrape_page_id")
    }
)
public class ParsedPayload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_code", nullable = false, length = 64)
    private String sourceCode;

    @Column(name = "ingest_target_code", nullable = false, length = 64)
    private String ingestTargetCode;

    @Column(name = "payload_type", nullable = false, length = 255)
    private String payloadType;

    @Column(name = "external_id", nullable = false, length = 255)
    private String externalId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "json", nullable = false)
    private JsonNode payloadJson;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "push_status", nullable = false, length = 32)
    private PushStatus pushStatus = PushStatus.PENDING;

    @Column(name = "push_retry_count", nullable = false)
    private Integer pushRetryCount = 0;

    @Column(name = "push_last_error", length = 2000)
    private String pushLastError;

    @Column(name = "external_ref", length = 255)
    private String externalRef;

    @ManyToOne
    @JoinColumn(name = "scrape_page_id")
    private ScrapePage scrapePage;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_parsed_at", nullable = false)
    private Instant lastParsedAt;

    @Column(name = "pushed_at")
    private Instant pushedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        final Instant now = Instant.now();
        if (firstSeenAt == null) {
            firstSeenAt = now;
        }
        if (lastParsedAt == null) {
            lastParsedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }
}
