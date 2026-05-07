package com.nookx.ingester.repository;

import com.nookx.ingester.domain.ParsedAsset;
import com.nookx.ingester.domain.ParsedPayload;
import com.nookx.ingester.domain.enumeration.PushStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ParsedAssetRepository extends JpaRepository<ParsedAsset, Long> {

    Optional<ParsedAsset> findByParsedPayloadAndExternalUrlHash(ParsedPayload parsedPayload, String externalUrlHash);

    List<ParsedAsset> findByParsedPayloadAndPushStatusInOrderBySortOrderAsc(
        ParsedPayload parsedPayload,
        List<PushStatus> statuses,
        Pageable pageable
    );

    List<ParsedAsset> findByParsedPayloadOrderBySortOrderAsc(ParsedPayload parsedPayload);
}
