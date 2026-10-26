package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.ContextMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContextMemoryRepository extends JpaRepository<ContextMemory, Long> {
    List<ContextMemory> findByOwnerUserIdAndActiveTrueOrderByUpdatedAtAsc(Long ownerUserId);
    Optional<ContextMemory> findByIdAndOwnerUserId(Long id, Long ownerUserId);
    Optional<ContextMemory> findByOwnerUserIdAndScopeAndScopeIdAndKey(
            Long ownerUserId, ContextMemory.MemoryScope scope, String scopeId, String key);
}
