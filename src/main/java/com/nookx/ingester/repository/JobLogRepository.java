package com.nookx.ingester.repository;

import com.nookx.ingester.domain.JobLog;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface JobLogRepository extends JpaRepository<JobLog, Long> {

    List<JobLog> findByJobRunIdOrderByTsAsc(Long jobRunId);

    @Modifying
    @Query("delete from JobLog l where l.ts < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
