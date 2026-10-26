package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.AgentWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentWorkspaceRepository extends JpaRepository<AgentWorkspace, String> {
    Optional<AgentWorkspace> findFirstByOwnerUserIdAndDefaultWorkspaceTrue(Long ownerUserId);
    Optional<AgentWorkspace> findByIdAndOwnerUserId(String id, Long ownerUserId);
    List<AgentWorkspace> findByOwnerUserIdOrderByCreatedAtAsc(Long ownerUserId);
}
