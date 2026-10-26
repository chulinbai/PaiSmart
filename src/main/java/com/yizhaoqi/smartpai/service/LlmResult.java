package com.yizhaoqi.smartpai.service;

import java.util.List;
import java.util.Map;

/** Provider facts; absent usage is deliberately different from an actual zero. */
public record LlmResult(String content, String reasoning, List<LlmProviderRouter.ToolCallDecision> toolCalls,
                        Map<String, Object> assistantMessage, String finishReason, boolean protocolComplete,
                        Integer promptTokens, Integer completionTokens, String providerRequestId, String rawUsageJson) {}
