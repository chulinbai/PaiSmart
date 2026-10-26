package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.LlmRequestAttempt;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface LlmRequestAttemptRepository extends JpaRepository<LlmRequestAttempt, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LlmRequestAttempt a where a.id=:id")
    Optional<LlmRequestAttempt> lock(@Param("id") String id);
    List<LlmRequestAttempt> findTop100BySettlementStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(List<LlmRequestAttempt.SettlementStatus> states, Instant before);
    List<LlmRequestAttempt> findByGenerationId(String generationId);
}
