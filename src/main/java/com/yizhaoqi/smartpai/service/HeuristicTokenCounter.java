package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class HeuristicTokenCounter implements TokenCounter {
    private final UsageQuotaService usageQuotaService;
    private final ObjectMapper objectMapper;

    public HeuristicTokenCounter(UsageQuotaService usageQuotaService, ObjectMapper objectMapper) {
        this.usageQuotaService = usageQuotaService;
        this.objectMapper = objectMapper;
    }

    @Override
    public int countText(String text) {
        return usageQuotaService.estimateTextTokens(text);
    }

    @Override
    public int countMessages(List<Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> message : messages) {
            total += 4 + countText(String.valueOf(message.getOrDefault("role", "")));
            total += countText(String.valueOf(message.getOrDefault("content", "")));
            Object toolCalls = message.get("tool_calls");
            if (toolCalls != null) {
                try {
                    total += countText(objectMapper.writeValueAsString(toolCalls));
                } catch (Exception ignored) {
                    total += countText(String.valueOf(toolCalls));
                }
            }
        }
        return total + 3;
    }
}
