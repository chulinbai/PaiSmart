package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Consumer;

/** Consumes decoded SSE data events, never raw TCP fragments. */
public final class LlmResponseAccumulator {
    private final ObjectMapper mapper;
    private final StringBuilder content = new StringBuilder(), reasoning = new StringBuilder();
    private final SortedMap<Integer, Call> calls = new TreeMap<>();
    private Integer prompt, completion;
    private String finish, requestId;
    private String rawUsageJson;
    private boolean done, responseReceived;
    public LlmResponseAccumulator(ObjectMapper mapper) { this.mapper = mapper; }
    public synchronized boolean accept(String data, Consumer<String> onContent) {
        if (data == null || data.isBlank() || data.startsWith(":")) return false;
        String payload = data.trim();
        if (payload.startsWith("data:")) payload = payload.substring(5).trim();
        if ("[DONE]".equals(payload)) { done = true; return false; }
        if (done) throw LlmResponseValidator.protocol("Data after stream end");
        try {
            JsonNode root = mapper.readTree(payload);
            responseReceived = true;
            if (root == null || !root.isObject() || root.hasNonNull("error")) throw LlmResponseValidator.protocol("Invalid provider event");
            readUsage(root);
            if (root.hasNonNull("id")) requestId = root.path("id").asText();
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.isEmpty() && root.path("usage").isObject()) return false;
            JsonNode choice = choices.path(0);
            if (!choice.isObject()) throw LlmResponseValidator.protocol("Missing choice");
            if (choice.hasNonNull("finish_reason")) {
                String next = choice.path("finish_reason").asText();
                if (finish != null && !finish.equals(next)) throw LlmResponseValidator.protocol("Conflicting finish reasons");
                finish = next;
            }
            JsonNode delta = choice.path("delta");
            boolean progress = appendText(delta, "reasoning_content", reasoning, null);
            progress |= appendText(delta, "content", content, onContent);
            JsonNode tools = delta.path("tool_calls");
            if (!tools.isMissingNode() && !tools.isNull() && !tools.isArray()) throw LlmResponseValidator.protocol("Invalid tool calls");
            for (JsonNode item : tools) {
                if (!item.path("index").isIntegralNumber() || item.path("index").asInt() < 0) throw LlmResponseValidator.protocol("Missing tool index");
                Call call = calls.computeIfAbsent(item.path("index").asInt(), ignored -> new Call());
                if (item.hasNonNull("id")) {
                    String id = item.path("id").asText();
                    if (call.id != null && !call.id.equals(id)) throw LlmResponseValidator.protocol("Conflicting tool ID");
                    call.id = id;
                }
                if (item.hasNonNull("type") && !"function".equals(item.path("type").asText())) throw LlmResponseValidator.protocol("Unsupported tool type");
                JsonNode function = item.path("function");
                progress |= appendText(function, "name", call.name, null);
                progress |= appendText(function, "arguments", call.arguments, null);
            }
            return progress;
        } catch (LlmResponseValidator.Failure e) { throw e; }
        catch (Exception e) { throw LlmResponseValidator.protocol("Malformed provider event"); }
    }
    private boolean appendText(JsonNode node, String field, StringBuilder target, Consumer<String> callback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return false;
        if (!value.isTextual()) throw LlmResponseValidator.protocol("Non-text " + field);
        String text = value.asText();
        if (text.isEmpty()) return false;
        target.append(text);
        if (callback != null) callback.accept(text);
        return true;
    }
    private void readUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        if(usage.isObject()) rawUsageJson=usage.toString();
        prompt = usageValue(usage, "prompt_tokens", prompt);
        completion = usageValue(usage, "completion_tokens", completion);
    }
    private Integer usageValue(JsonNode usage, String field, Integer previous) {
        JsonNode value = usage.path(field);
        if (value.isMissingNode() || value.isNull()) return previous;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0) throw LlmResponseValidator.protocol("Invalid usage");
        return value.asInt();
    }
    public synchronized LlmResult result() {
        List<LlmProviderRouter.ToolCallDecision> decisions = new ArrayList<>();
        List<Map<String, Object>> serialized = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Call call : calls.values()) {
            if (call.id == null || call.id.isBlank() || !ids.add(call.id) || call.name.isEmpty()) throw LlmResponseValidator.protocol("Missing or duplicate tool identity");
            Map<String, Object> arguments;
            try {
                JsonNode node = mapper.readTree(call.arguments.toString());
                if (node == null || !node.isObject()) throw LlmResponseValidator.protocol("Tool arguments must be an object");
                arguments = mapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) { throw LlmResponseValidator.protocol("Incomplete tool arguments"); }
            decisions.add(new LlmProviderRouter.ToolCallDecision(call.id, call.name.toString(), arguments));
            serialized.add(Map.of("id", call.id, "type", "function", "function", Map.of("name", call.name.toString(), "arguments", call.arguments.toString())));
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant"); message.put("content", content.toString());
        if (!serialized.isEmpty()) message.put("tool_calls", serialized);
        if (!reasoning.isEmpty()) message.put("reasoning_content", reasoning.toString());
        return new LlmResult(content.toString(), reasoning.toString(), List.copyOf(decisions), message, finish, done && finish != null, prompt, completion, requestId, rawUsageJson);
    }
    public synchronized Integer promptTokens() { return prompt; }
    public synchronized Integer completionTokens() { return completion; }
    public synchronized String observedOutput() {
        StringBuilder text = new StringBuilder(content).append(reasoning);
        calls.values().forEach(c -> text.append(c.name).append(c.arguments));
        return text.toString();
    }
    public synchronized String requestId() { return requestId; }
    public synchronized String rawUsageJson() { return rawUsageJson; }
    public synchronized boolean responseReceived() { return responseReceived; }
    public static LlmResult parse(ObjectMapper mapper, String body) {
        try {
            if (body == null || body.isBlank()) throw LlmResponseValidator.protocol("Empty provider response");
            JsonNode root = mapper.readTree(body);
            JsonNode choice = root.path("choices").path(0), message = choice.path("message");
            if (!message.isObject() || root.hasNonNull("error")) throw LlmResponseValidator.protocol("Missing assistant message");
            var delta = message.deepCopy();
            JsonNode tools = delta.path("tool_calls");
            for (int i = 0; i < tools.size(); i++) ((com.fasterxml.jackson.databind.node.ObjectNode) tools.get(i)).put("index", i);
            var event = mapper.createObjectNode();
            if (root.has("usage")) event.set("usage", root.get("usage"));
            if (root.has("id")) event.set("id", root.get("id"));
            var eventChoice = event.putArray("choices").addObject();
            eventChoice.set("delta", delta); eventChoice.set("finish_reason", choice.path("finish_reason"));
            LlmResponseAccumulator accumulator = new LlmResponseAccumulator(mapper);
            accumulator.accept(mapper.writeValueAsString(event), ignored -> {}); accumulator.done = true;
            return accumulator.result();
        } catch (LlmResponseValidator.Failure e) { throw e; }
        catch (Exception e) { throw LlmResponseValidator.protocol("Malformed provider response"); }
    }
    private static final class Call {
        String id;
        final StringBuilder name = new StringBuilder(), arguments = new StringBuilder();
    }
}
