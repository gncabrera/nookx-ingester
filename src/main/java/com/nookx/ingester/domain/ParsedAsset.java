package com.nookx.ingester.domain;

import com.nookx.ingester.domain.enumeration.AssetKind;
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

@Getter
@Setter
@Entity
@Table(
    name = "parsed_asset",
    uniqueConstraints = {
        @UniqueConstraint(name = "ux_parsed_asset_payload_url", columnNames = { "parsed_payload_id", "external_url_hash" })
    },
    indexes = {
        @Index(name = "ix_parsed_asset_push", columnList = "push_status, push_retry_count"),
        @Index(name = "ix_parsed_asset_download", columnList = "downloaded, download_retry_count")
    }
)
public class ParsedAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "parsed_payload_id", nullable = false)
    private ParsedPayload parsedPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private AssetKind kind;

    @Column(name = "external_url_hash", nullable = false, length = 64)
    private String externalUrlHash;

    @Column(name = "external_url", nullable = false, length = 2048)
    private String externalUrl;

    @Column(name = "label", length = 255)
    private String label;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "downloaded", nullable = false)
    private boolean downloaded;

    @Column(name = "download_retry_count", nullable = false)
    private Integer downloadRetryCount = 0;

    @Column(name = "download_last_error", length = 2000)
    private String downloadLastError;

    @Column(name = "local_path", length = 1024)
    private String localPath;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "content_type", length = 255)
    private String contentType;

    @Column(name = "content_size_bytes")
    private Long contentSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "push_status", nullable = false, length = 32)
    private PushStatus pushStatus = PushStatus.PENDING;

    @Column(name = "push_retry_count", nullable = false)
    private Integer pushRetryCount = 0;

    @Column(name = "push_last_error", length = 2000)
    private String pushLastError;

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
    }
}
