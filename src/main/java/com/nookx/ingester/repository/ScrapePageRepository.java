package com.nookx.ingester.repository;

import com.nookx.ingester.domain.ScrapePage;
import com.nookx.ingester.domain.enumeration.FetchStatus;
import com.nookx.ingester.domain.enumeration.ParseStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ScrapePageRepository extends JpaRepository<ScrapePage, Long> {

    Optional<ScrapePage> findBySourceCodeAndUrlHash(String sourceCode, String urlHash);

    @Query(
        """
            select p from ScrapePage p
            where p.sourceCode = :sourceCode
              and p.fetchStatus in (:fetchStatuses)
              and p.nextCheckAt <= :now
            order by p.nextCheckAt asc
            """
    )
    List<ScrapePage> findDueForFetch(
        @Param("sourceCode") String sourceCode,
        @Param("fetchStatuses") List<FetchStatus> fetchStatuses,
        @Param("now") Instant now,
        Pageable pageable
    );

    List<ScrapePage> findByParseStatusAndFetchStatusInOrderByFetchedAtAsc(
        ParseStatus parseStatus,
        List<FetchStatus> fetchStatuses,
        Pageable pageable
    );

    long countBySourceCodeAndFetchStatus(String sourceCode, FetchStatus fetchStatus);

    long countBySourceCodeAndParseStatus(String sourceCode, ParseStatus parseStatus);

    long countByFetchStatus(FetchStatus fetchStatus);

    long countByParseStatus(ParseStatus parseStatus);

    @Modifying
    @Query(
        """
            update ScrapePage p
            set p.fetchStatus = :newStatus,
                p.fetchRetryCount = 0,
                p.fetchLastError = null,
                p.nextCheckAt = :now,
                p.updatedAt = :now
            where p.sourceCode = :sourceCode
              and p.fetchStatus = :oldStatus
            """
    )
    int resetFetchStatusBySource(
        @Param("sourceCode") String sourceCode,
        @Param("oldStatus") FetchStatus oldStatus,
        @Param("newStatus") FetchStatus newStatus,
        @Param("now") Instant now
    );

    @Modifying
    @Query(
        """
            update ScrapePage p
            set p.parseStatus = :newStatus,
                p.parseRetryCount = 0,
                p.parseLastError = null,
                p.updatedAt = :now
            where p.sourceCode = :sourceCode
              and p.parseStatus = :oldStatus
            """
    )
    int resetParseStatusBySource(
        @Param("sourceCode") String sourceCode,
        @Param("oldStatus") ParseStatus oldStatus,
        @Param("newStatus") ParseStatus newStatus,
        @Param("now") Instant now
    );
}
