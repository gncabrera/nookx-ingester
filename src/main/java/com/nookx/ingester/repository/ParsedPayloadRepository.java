package com.nookx.ingester.repository;

import com.nookx.ingester.domain.ParsedPayload;
import com.nookx.ingester.domain.enumeration.PushStatus;
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
public interface ParsedPayloadRepository extends JpaRepository<ParsedPayload, Long> {

    Optional<ParsedPayload> findBySourceCodeAndIngestTargetCodeAndExternalId(
        String sourceCode,
        String ingestTargetCode,
        String externalId
    );

    List<ParsedPayload> findByIngestTargetCodeAndPushStatusInOrderByIdAsc(
        String ingestTargetCode,
        List<PushStatus> statuses,
        Pageable pageable
    );

    long countByIngestTargetCodeAndPushStatus(String ingestTargetCode, PushStatus status);

    long countByPushStatus(PushStatus status);

    @Modifying
    @Query(
        """
            update ParsedPayload p
            set p.pushStatus = :newStatus,
                p.pushRetryCount = 0,
                p.pushLastError = null,
                p.updatedAt = :now
            where p.ingestTargetCode = :ingestTargetCode
              and p.pushStatus = :oldStatus
            """
    )
    int resetPushStatus(
        @Param("ingestTargetCode") String ingestTargetCode,
        @Param("oldStatus") PushStatus oldStatus,
        @Param("newStatus") PushStatus newStatus,
        @Param("now") Instant now
    );
}
