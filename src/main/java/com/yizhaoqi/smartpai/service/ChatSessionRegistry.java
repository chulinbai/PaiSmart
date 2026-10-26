package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatSessionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ChatSessionRegistry.class);

    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WebSocketSession> generationSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public ChatSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void registerSession(String userId, WebSocketSession session) {
        sessions.put(userId, session);
    }

    public void unregisterSession(String userId, WebSocketSession session) {
        sessions.computeIfPresent(userId, (key, current) -> current == session ? null : current);
    }

    public WebSocketSession getCurrentSession(String userId) {
        return sessions.get(userId);
    }

    public void bindGeneration(String generationId, WebSocketSession session) {
        if (generationId != null && session != null) {
            generationSessions.put(generationId, session);
        }
    }

    public void unbindGeneration(String generationId) {
        if (generationId != null) {
            generationSessions.remove(generationId);
        }
    }

    public void sendJsonToGeneration(String generationId, Map<String, ?> payload) {
        sendJson(generationSessions.get(generationId), "generation " + generationId, payload);
    }

    public void sendJsonToSession(WebSocketSession session, Map<String, ?> payload) {
        sendJson(session, session == null ? "unknown session" : "session " + session.getId(), payload);
    }

    public void sendJsonToUser(String userId, Map<String, ?> payload) {
        sendJson(sessions.get(userId), "user " + userId, payload);
    }

    private void sendJson(WebSocketSession session, String target, Map<String, ?> payload) {
        if (session == null || !session.isOpen()) {
            logger.debug("No open WebSocket session for {}; skipping message", target);
            return;
        }

        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to send WebSocket message to {}: {}", target, e.getMessage(), e);
        }
    }
}
