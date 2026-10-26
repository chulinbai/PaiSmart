package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.handler.ChatWebSocketHandler;
import com.yizhaoqi.smartpai.service.AgentToolRegistry;
import com.yizhaoqi.smartpai.service.AgentContextService;
import com.yizhaoqi.smartpai.service.ContextArtifactService;
import com.yizhaoqi.smartpai.service.LlmProviderRouter;
import com.yizhaoqi.smartpai.model.ContextMemory;
import com.yizhaoqi.smartpai.service.ChatGenerationStateService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import com.yizhaoqi.smartpai.utils.LogUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final JwtUtils jwtUtils;
    private final ChatGenerationStateService chatGenerationStateService;
    private final AgentToolRegistry agentToolRegistry;
    private final AgentContextService agentContextService;
    private final ContextArtifactService contextArtifactService;
    private final LlmProviderRouter llmProviderRouter;

    public ChatController(JwtUtils jwtUtils,
                          ChatGenerationStateService chatGenerationStateService,
                          AgentToolRegistry agentToolRegistry,
                          AgentContextService agentContextService,
                          ContextArtifactService contextArtifactService,
                          LlmProviderRouter llmProviderRouter) {
        this.jwtUtils = jwtUtils;
        this.chatGenerationStateService = chatGenerationStateService;
        this.agentToolRegistry = agentToolRegistry;
        this.agentContextService = agentContextService;
        this.contextArtifactService = contextArtifactService;
        this.llmProviderRouter = llmProviderRouter;
    }
    
    /**
     * 获取WebSocket停止指令Token
     */
    @GetMapping("/websocket-token")
    public ResponseEntity<?> getWebSocketToken(@RequestHeader("Authorization") String token) {
        try {
            if (token == null || !token.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
            }
            String jwtToken = token.replace("Bearer ", "");
            if (!jwtUtils.validateToken(jwtToken)) {
                return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
            }

            String cmdToken = ChatWebSocketHandler.getInternalCmdToken();
            
            // 检查token是否有效
            if (cmdToken == null || cmdToken.trim().isEmpty()) {
                return ResponseEntity.status(500).body(responseBody(500, "Token生成失败", null));
            }
            
            return ResponseEntity.ok(responseBody(200, "获取WebSocket停止指令Token成功", Map.of("cmdToken", cmdToken)));
            
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_WEBSOCKET_TOKEN", "system", "获取WebSocket Token失败", e);
            return ResponseEntity.status(500).body(responseBody(500, "服务器内部错误：" + e.getMessage(), null));
        }
    }

    @GetMapping("/generation/{generationId}")
    public ResponseEntity<?> getGeneration(
            @PathVariable String generationId,
            @RequestHeader("Authorization") String token) {
        String userId = extractValidatedUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }

        return ResponseEntity.ok(responseBody(
                200,
                "获取生成状态成功",
                chatGenerationStateService.getGenerationForUser(generationId, userId).orElse(null)
        ));
    }

    @GetMapping("/active-generation")
    public ResponseEntity<?> getActiveGeneration(@RequestHeader("Authorization") String token) {
        String userId = extractValidatedUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }

        return ResponseEntity.ok(responseBody(
                200,
                "获取当前活动生成状态成功",
                chatGenerationStateService.getActiveGenerationForUser(userId).orElse(null)
        ));
    }

    @GetMapping("/conversations/{conversationId}/recovery")
    public ResponseEntity<?> getConversationRecovery(
            @PathVariable String conversationId,
            @RequestHeader("Authorization") String token) {
        String userId = extractValidatedUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }

        return ResponseEntity.ok(responseBody(
                200,
                "获取会话恢复状态成功",
                chatGenerationStateService.getRecoverableGenerationForConversation(conversationId, userId).orElse(null)
        ));
    }

    @PostMapping("/feedback")
    public ResponseEntity<?> submitFeedback(@RequestHeader("Authorization") String token,
                                            @RequestBody FeedbackRequest request) {
        String userId = extractValidatedUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
        }

        if (request == null || request.rating() == null || request.rating().isBlank()) {
            return ResponseEntity.badRequest().body(responseBody(400, "rating 不能为空", null));
        }

        Map<String, Object> arguments = new HashMap<>();
        arguments.put("rating", request.rating());
        String reason = buildFeedbackReason(request);
        if (!reason.isBlank()) {
            arguments.put("reason", reason);
        }

        AgentToolRegistry.ToolExecutionResult result =
                agentToolRegistry.executeTool("submit_feedback", arguments, userId);
        return ResponseEntity.ok(responseBody(200, "反馈已记录", result.data()));
    }

    @GetMapping("/conversations/{conversationId}/context")
    public ResponseEntity<?> getContext(@RequestHeader("Authorization") String token,
                                        @PathVariable String conversationId) {
        Long userId = validatedLongUserId(token);
        if (userId == null) return unauthorized();
        AgentContextService.ContextBundle context = agentContextService.buildContext(
                userId, conversationId, llmProviderRouter.buildReActSystemInstruction("", ""),
                agentToolRegistry.getTools(), 2000);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("contextWindowTokens", context.contextWindowTokens());
        view.put("promptBudgetTokens", context.promptBudgetTokens());
        view.put("contextUsedTokens", context.usedTokens());
        view.put("contextUsageRatio", context.usageRatio());
        view.put("breakdown", context.breakdown());
        view.put("snapshotVersion", context.snapshotVersion());
        view.put("contextDegraded", context.degraded());
        view.put("trimReasons", context.trimReasons());
        view.put("eventCount", context.eventIds().size());
        return ResponseEntity.ok(responseBody(200, "获取上下文状态成功", view));
    }

    @PostMapping("/conversations/{conversationId}/context/compact")
    public ResponseEntity<?> compactContext(@RequestHeader("Authorization") String token,
                                            @PathVariable String conversationId) {
        Long userId = validatedLongUserId(token);
        if (userId == null) return unauthorized();
        return ResponseEntity.ok(responseBody(200, "上下文压缩完成",
                agentContextService.compact(userId, conversationId)));
    }

    @PostMapping("/conversations/{conversationId}/context/reset")
    public ResponseEntity<?> resetContext(@RequestHeader("Authorization") String token,
                                          @PathVariable String conversationId) {
        Long userId = validatedLongUserId(token);
        if (userId == null) return unauthorized();
        agentContextService.reset(userId, conversationId);
        return ResponseEntity.ok(responseBody(200, "线程上下文已重置", Map.of("conversationId", conversationId)));
    }

    @GetMapping("/memories")
    public ResponseEntity<?> listMemories(@RequestHeader("Authorization") String token) {
        Long userId = validatedLongUserId(token);
        if (userId == null) return unauthorized();
        return ResponseEntity.ok(responseBody(200, "获取记忆成功", agentContextService.listMemories(userId)));
    }

    @PostMapping("/memories")
    public ResponseEntity<?> upsertMemory(@RequestHeader("Authorization") String token,
                                          @RequestBody MemoryRequest request) {
        Long userId = validatedLongUserId(token);
        if (userId == null) return unauthorized();
        if (request == null || request.scope() == null || request.scopeId() == null ||
                request.key() == null || request.content() == null) {
            return ResponseEntity.badRequest().body(responseBody(400, "记忆参数不完整", null));
        }
        return ResponseEntity.ok(responseBody(200, "记忆已保存", agentContextService.upsertMemory(
                userId, request.scope(), request.scopeId(), request.key(), request.content())));
    }

    @PostMapping("/memories/{memoryId}/forget")
    public ResponseEntity<?> forgetMemory(@RequestHeader("Authorization") String token,
                                          @PathVariable Long memoryId) {
        Long userId = validatedLongUserId(token);
        if (userId == null) return unauthorized();
        agentContextService.forgetMemory(userId, memoryId);
        return ResponseEntity.ok(responseBody(200, "记忆已删除", null));
    }

    @PostMapping("/workspaces/{workspaceId}")
    public ResponseEntity<?> updateWorkspace(@RequestHeader("Authorization") String token,
                                             @PathVariable String workspaceId,
                                             @RequestBody WorkspaceRequest request) {
        Long userId = validatedLongUserId(token);
        if (userId == null) return unauthorized();
        return ResponseEntity.ok(responseBody(200, "工作区已更新",
                agentContextService.updateWorkspace(userId, workspaceId, request.name(), request.instructions())));
    }

    @GetMapping("/artifacts/{artifactId}")
    public ResponseEntity<?> downloadArtifact(@RequestHeader("Authorization") String token,
                                              @PathVariable String artifactId) {
        Long userId = validatedLongUserId(token);
        if (userId == null) return unauthorized();
        ContextArtifactService.ArtifactDownload download = contextArtifactService.openForOwner(artifactId, userId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + artifactId + ".txt\"")
                .contentType(MediaType.parseMediaType(download.artifact().getMimeType()))
                .contentLength(download.artifact().getSize())
                .body(new InputStreamResource(download.stream()));
    }

    private String buildFeedbackReason(FeedbackRequest request) {
        StringBuilder reason = new StringBuilder();
        if (request.reason() != null && !request.reason().isBlank()) {
            reason.append(request.reason().trim());
        }
        if (request.conversationId() != null && !request.conversationId().isBlank()) {
            appendReasonPart(reason, "conversationId=" + request.conversationId().trim());
        }
        if (request.generationId() != null && !request.generationId().isBlank()) {
            appendReasonPart(reason, "generationId=" + request.generationId().trim());
        }
        return reason.toString();
    }

    private void appendReasonPart(StringBuilder reason, String part) {
        if (!reason.isEmpty()) {
            reason.append("; ");
        }
        reason.append(part);
    }

    private String extractValidatedUserId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }

        String jwtToken = authorization.replace("Bearer ", "");
        if (!jwtUtils.validateToken(jwtToken)) {
            return null;
        }
        return jwtUtils.extractUserIdFromToken(jwtToken);
    }

    private Long validatedLongUserId(String authorization) {
        String userId = extractValidatedUserId(authorization);
        if (userId == null) return null;
        try { return Long.parseLong(userId); }
        catch (NumberFormatException ignored) { return null; }
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(responseBody(401, "Invalid token", null));
    }

    private Map<String, Object> responseBody(int code, String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code);
        response.put("message", message);
        response.put("data", data);
        return response;
    }

    public record FeedbackRequest(
            String rating,
            String reason,
            String conversationId,
            String generationId
    ) {
    }

    public record MemoryRequest(ContextMemory.MemoryScope scope, String scopeId, String key, String content) {}
    public record WorkspaceRequest(String name, String instructions) {}
}
