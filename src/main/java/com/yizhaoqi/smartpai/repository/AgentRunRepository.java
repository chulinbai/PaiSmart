package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.AgentRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRunRepository extends JpaRepository<AgentRun, String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from AgentRun r where r.generationId=:id")
    java.util.Optional<AgentRun> lock(@org.springframework.data.repository.query.Param("id") String id);
    List<AgentRun> findByStatusIn(List<AgentRun.RunStatus> statuses);
    java.util.Optional<AgentRun> findTopByUserIdOrderByCreatedAtDesc(Long userId);
    java.util.Optional<AgentRun> findTopByThreadIdAndUserIdOrderByCreatedAtDesc(String threadId,Long userId);
}
