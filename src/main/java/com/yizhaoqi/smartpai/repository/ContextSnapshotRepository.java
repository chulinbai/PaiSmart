package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.ContextSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContextSnapshotRepository extends JpaRepository<ContextSnapshot, Long> {
    Optional<ContextSnapshot> findTopByThreadIdOrderByVersionDesc(String threadId);
    Optional<ContextSnapshot> findByThreadIdAndInputHash(String threadId, String inputHash);
}
