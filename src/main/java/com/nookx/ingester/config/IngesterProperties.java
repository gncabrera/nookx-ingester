package com.nookx.ingester.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "ingester")
public class IngesterProperties {

    private final Map<String, SourceConfig> sources = new HashMap<>();
    private final Map<String, IngestTargetConfig> ingestTargets = new HashMap<>();
    private final HttpConfig http = new HttpConfig();
    private final StorageConfig storage = new StorageConfig();
    private final ScheduleConfig schedule = new ScheduleConfig();
    private final JobConfig job = new JobConfig();
    private final DashboardConfig dashboard = new DashboardConfig();

    @Getter
    @Setter
    public static class SourceConfig {

        private boolean enabled = true;

        @Min(0)
        private long minDelayMs = 1000L;

        @Min(0)
        private long jitterMs = 500L;

        private String discoveryCron = "0 0 0 ? * MON";
    }

    @Getter
    @Setter
    public static class IngestTargetConfig {

        private boolean enabled = true;

        @NotBlank
        private String baseUrl;

        @NotBlank
        private String apiKey;

        @Min(1)
        private int maxBatchSize = 100;

        private String pushCron = "0 */5 * * * *";

        private final Map<String, String> options = new HashMap<>();
    }

    @Getter
    @Setter
    public static class HttpConfig {

        @NotBlank
        private String userAgent;

        @Min(1)
        private int connectTimeoutMs = 10000;

        @Min(1)
        private int requestTimeoutMs = 30000;
    }

    @Getter
    @Setter
    public static class StorageConfig {

        @NotBlank
        private String rawDir;

        @NotBlank
        private String assetDir;
    }

    @Getter
    @Setter
    public static class ScheduleConfig {

        @Min(0)
        private long crawlDelayMs = 15000L;

        @Min(0)
        private long crawlInitialDelayMs = 5000L;

        @Min(0)
        private long parseDelayMs = 5000L;

        @Min(0)
        private long parseInitialDelayMs = 5000L;
    }

    @Getter
    @Setter
    public static class JobConfig {

        @Min(1)
        private int logRetentionDays = 90;

        @NotBlank
        private String logMinLevel = "INFO";

        private String retentionCron = "0 0 3 * * *";
    }

    @Getter
    @Setter
    public static class DashboardConfig {

        @Min(500)
        private long pollIntervalMs = 3000L;

        @Min(50)
        private int logTailLines = 500;
    }
}
