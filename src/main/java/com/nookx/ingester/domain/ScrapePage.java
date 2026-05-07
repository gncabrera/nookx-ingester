package com.nookx.ingester.domain;

import com.nookx.ingester.domain.enumeration.FetchStatus;
import com.nookx.ingester.domain.enumeration.ParseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "scrape_page",
    uniqueConstraints = {
        @UniqueConstraint(name = "ux_scrape_page_source_url", columnNames = { "source_code", "url_hash" })
    },
    indexes = {
        @Index(name = "ix_scrape_page_fetch", columnList = "source_code, fetch_status, next_check_at"),
        @Index(name = "ix_scrape_page_parse", columnList = "parse_status, fetched_at")
    }
)
public class ScrapePage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_code", nullable = false, length = 64)
    private String sourceCode;

    @Column(name = "url_hash", nullable = false, length = 64)
    private String urlHash;

    @Column(name = "page_type", nullable = false, length = 64)
    private String pageType;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    @Column(name = "natural_key", length = 255)
    private String naturalKey;

    @Column(name = "storage_path", length = 1024)
    private String storagePath;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "etag", length = 255)
    private String etag;

    @Column(name = "last_modified", length = 255)
    private String lastModified;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "content_size_bytes")
    private Long contentSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "fetch_status", nullable = false, length = 32)
    private FetchStatus fetchStatus = FetchStatus.PENDING;

    @Column(name = "fetch_retry_count", nullable = false)
    private Integer fetchRetryCount = 0;

    @Column(name = "fetch_last_error", length = 2000)
    private String fetchLastError;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 32)
    private ParseStatus parseStatus = ParseStatus.PENDING;

    @Column(name = "parse_retry_count", nullable = false)
    private Integer parseRetryCount = 0;

    @Column(name = "parse_last_error", length = 2000)
    private String parseLastError;

    @Column(name = "discovered_at", nullable = false)
    private Instant discoveredAt;

    @Column(name = "fetched_at")
    private Instant fetchedAt;

    @Column(name = "parsed_at")
    private Instant parsedAt;

    @Column(name = "next_check_at", nullable = false)
    private Instant nextCheckAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        final Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (discoveredAt == null) {
            discoveredAt = now;
        }
        if (nextCheckAt == null) {
            nextCheckAt = now;
        }
    }
}
