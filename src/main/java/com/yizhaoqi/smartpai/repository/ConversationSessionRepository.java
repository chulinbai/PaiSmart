package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.ConversationSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationSessionRepository extends JpaRepository<ConversationSession, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from ConversationSession s where s.conversationId=:id")
    Optional<ConversationSession> lock(@org.springframework.data.repository.query.Param("id") String id);

    List<ConversationSession> findByUserIdAndStatusOrderByUpdatedAtDesc(Long userId, ConversationSession.SessionStatus status);

    List<ConversationSession> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<ConversationSession> findByConversationId(String conversationId);

    boolean existsByConversationId(String conversationId);
}
