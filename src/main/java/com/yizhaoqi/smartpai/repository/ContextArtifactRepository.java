package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.ContextArtifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContextArtifactRepository extends JpaRepository<ContextArtifact, String> {
    Optional<ContextArtifact> findByIdAndOwnerUserId(String id, Long ownerUserId);
}
