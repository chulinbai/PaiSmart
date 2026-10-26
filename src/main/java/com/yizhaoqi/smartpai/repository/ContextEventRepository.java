package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.ContextEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContextEventRepository extends JpaRepository<ContextEvent, Long> {
    @Query("select coalesce(max(e.sequence), 0) from ContextEvent e where e.threadId = :threadId")
    long findMaxSequence(@Param("threadId") String threadId);
    List<ContextEvent> findByThreadIdOrderBySequenceAsc(String threadId);
    List<ContextEvent> findByThreadIdAndSequenceGreaterThanOrderBySequenceAsc(String threadId, long sequence);
    Optional<ContextEvent> findTopByThreadIdAndEventTypeOrderBySequenceDesc(String threadId, ContextEvent.EventType type);
    boolean existsByThreadIdAndSourceKey(String threadId, String sourceKey);
    Optional<ContextEvent> findByThreadIdAndSourceKey(String threadId, String sourceKey);
    Optional<ContextEvent> findTopByRunIdAndRoleOrderBySequenceAsc(String runId,String role);
}
