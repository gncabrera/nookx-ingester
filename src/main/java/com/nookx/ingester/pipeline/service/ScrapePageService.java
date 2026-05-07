package com.nookx.ingester.pipeline.service;

import com.nookx.ingester.core.HashUtils;
import com.nookx.ingester.domain.ScrapePage;
import com.nookx.ingester.domain.enumeration.FetchStatus;
import com.nookx.ingester.domain.enumeration.ParseStatus;
import com.nookx.ingester.repository.ScrapePageRepository;
import com.nookx.ingester.source.api.dto.DiscoveredUrl;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class ScrapePageService {

    private static final Duration TEN_YEARS = Duration.ofDays(3650);
    private static final Duration ONE_WEEK = Duration.ofDays(7);
    private static final Duration ONE_YEAR = Duration.ofDays(365);
    private static final int MAX_BACKOFF_EXPONENT = 20;

    private final ScrapePageRepository scrapePageRepository;

    public boolean enqueueIfAbsent(final String sourceCode, final DiscoveredUrl discoveredUrl) {
        final String urlHash = HashUtils.sha256(discoveredUrl.url());
        final Optional<ScrapePage> existing = scrapePageRepository.findBySourceCodeAndUrlHash(sourceCode, urlHash);
        if (existing.isPresent()) {
            return false;
        }
        final Instant now = Instant.now();
        final ScrapePage row = new ScrapePage();
        row.setSourceCode(sourceCode);
        row.setUrlHash(urlHash);
        row.setPageType(discoveredUrl.pageType());
        row.setUrl(discoveredUrl.url());
        row.setNaturalKey(discoveredUrl.naturalKey());
        row.setFetchStatus(FetchStatus.PENDING);
        row.setParseStatus(ParseStatus.PENDING);
        row.setDiscoveredAt(now);
        row.setNextCheckAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        scrapePageRepository.save(row);
        return true;
    }

    @Transactional(readOnly = true)
    public List<ScrapePage> pickDueForFetch(final String sourceCode, final int limit) {
        return scrapePageRepository.findDueForFetch(
            sourceCode,
            List.of(FetchStatus.PENDING, FetchStatus.FAILED, FetchStatus.DONE, FetchStatus.NOT_MODIFIED),
            Instant.now(),
            PageRequest.of(0, limit)
        );
    }

    @Transactional(readOnly = true)
    public List<ScrapePage> pickPendingParse(final int limit) {
        return scrapePageRepository.findByParseStatusAndFetchStatusInOrderByFetchedAtAsc(
            ParseStatus.PENDING,
            List.of(FetchStatus.DONE, FetchStatus.NOT_MODIFIED),
            PageRequest.of(0, limit)
        );
    }

    public void markFetchSuccess(
        final ScrapePage page,
        final int status,
        final String etag,
        final String lastModified,
        final String storagePath,
        final String contentHash,
        final long sizeBytes
    ) {
        final Instant now = Instant.now();
        final boolean contentChanged = page.getContentHash() == null || !page.getContentHash().equals(contentHash);
        page.setHttpStatus(status);
        page.setEtag(etag);
        page.setLastModified(lastModified);
        page.setStoragePath(storagePath);
        page.setContentHash(contentHash);
        page.setContentSizeBytes(sizeBytes);
        page.setFetchStatus(FetchStatus.DONE);
        page.setFetchRetryCount(0);
        page.setFetchLastError(null);
        page.setFetchedAt(now);
        page.setNextCheckAt(now.plus(TEN_YEARS));
        if (contentChanged) {
            page.setParseStatus(ParseStatus.PENDING);
        }
        page.setUpdatedAt(now);
        scrapePageRepository.save(page);
    }

    public void markNotModified(final ScrapePage page) {
        final Instant now = Instant.now();
        page.setHttpStatus(304);
        page.setFetchStatus(FetchStatus.NOT_MODIFIED);
        page.setFetchRetryCount(0);
        page.setFetchLastError(null);
        page.setFetchedAt(now);
        page.setNextCheckAt(now.plus(TEN_YEARS));
        page.setUpdatedAt(now);
        scrapePageRepository.save(page);
    }

    public void markFetchFailure(final ScrapePage page, final String error) {
        final Instant now = Instant.now();
        final int currentRetryCount = page.getFetchRetryCount() == null ? 0 : page.getFetchRetryCount();
        final int nextRetryCount = currentRetryCount + 1;
        final Duration backoff = computeFetchFailureBackoff(nextRetryCount);
        page.setFetchStatus(FetchStatus.FAILED);
        page.setFetchRetryCount(nextRetryCount);
        page.setFetchLastError(truncate(error));
        page.setFetchedAt(now);
        page.setNextCheckAt(now.plus(backoff));
        page.setUpdatedAt(now);
        scrapePageRepository.save(page);
    }

    public void markParseSuccess(final ScrapePage page) {
        final Instant now = Instant.now();
        page.setParseStatus(ParseStatus.DONE);
        page.setParseRetryCount(0);
        page.setParseLastError(null);
        page.setParsedAt(now);
        page.setUpdatedAt(now);
        scrapePageRepository.save(page);
    }

    public void markParseFailure(final ScrapePage page, final String error) {
        final Instant now = Instant.now();
        page.setParseStatus(ParseStatus.FAILED);
        page.setParseRetryCount(page.getParseRetryCount() + 1);
        page.setParseLastError(truncate(error));
        page.setParsedAt(now);
        page.setUpdatedAt(now);
        scrapePageRepository.save(page);
    }

    public int resetFetchErrorsBySource(final String sourceCode) {
        return scrapePageRepository.resetFetchStatusBySource(
            sourceCode,
            FetchStatus.FAILED,
            FetchStatus.PENDING,
            Instant.now()
        );
    }

    public int resetParseErrorsBySource(final String sourceCode) {
        return scrapePageRepository.resetParseStatusBySource(
            sourceCode,
            ParseStatus.FAILED,
            ParseStatus.PENDING,
            Instant.now()
        );
    }

    private static Duration computeFetchFailureBackoff(final int retryCount) {
        final int safeRetryCount = Math.max(1, retryCount);
        final int exponent = Math.min(safeRetryCount - 1, MAX_BACKOFF_EXPONENT);
        final long multiplier = 1L << exponent;
        final Duration candidate = ONE_WEEK.multipliedBy(multiplier);
        if (candidate.compareTo(ONE_YEAR) > 0) {
            return ONE_YEAR;
        }
        return candidate;
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
