package com.nookx.ingester.repository;

import com.nookx.ingester.domain.JobRun;
import com.nookx.ingester.domain.enumeration.JobStage;
import com.nookx.ingester.domain.enumeration.JobStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface JobRunRepository extends JpaRepository<JobRun, Long> {

    List<JobRun> findByStatusOrderByStartedAtDesc(JobStatus status);

    List<JobRun> findAllByOrderByStartedAtDesc(Pageable pageable);

    List<JobRun> findByStageOrderByStartedAtDesc(JobStage stage, Pageable pageable);

    List<JobRun> findByScopeCodeOrderByStartedAtDesc(String scopeCode, Pageable pageable);

    @Query(
        """
            select count(j) from JobRun j
            where j.status = :status and j.startedAt >= :since
            """
    )
    long countByStatusSince(@Param("status") JobStatus status, @Param("since") Instant since);
}
