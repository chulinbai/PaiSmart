package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionRegistryTest {

    @Test
    void generationMessagesStayOnOriginatingSession() throws Exception {
        ChatSessionRegistry registry = new ChatSessionRegistry(new ObjectMapper());
        WebSocketSession first = mock(WebSocketSession.class);
        WebSocketSession second = mock(WebSocketSession.class);
        when(first.isOpen()).thenReturn(true);
        when(second.isOpen()).thenReturn(true);

        registry.registerSession("user-1", first);
        registry.bindGeneration("generation-1", first);
        registry.registerSession("user-1", second);

        registry.sendJsonToGeneration("generation-1", Map.of("type", "chunk"));

        verify(first).sendMessage(any(TextMessage.class));
        verify(second, never()).sendMessage(any(TextMessage.class));
    }
}
