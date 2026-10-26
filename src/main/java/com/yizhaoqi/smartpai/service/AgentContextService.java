package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.PostConstruct;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentContextService {
    private com.yizhaoqi.smartpai.repository.AgentToolExecutionRepository toolExecutions;
    private org.springframework.transaction.support.TransactionTemplate toolTx;
    @org.springframework.beans.factory.annotation.Autowired
    void configureToolJournal(com.yizhaoqi.smartpai.repository.AgentToolExecutionRepository repository,
                              org.springframework.transaction.PlatformTransactionManager manager) {
        toolExecutions=repository;toolTx=new org.springframework.transaction.support.TransactionTemplate(manager);toolTx.setTimeout(5);
    }
    private static final int MAX_INLINE_EVENT_TOKENS = 8_000;
    private static final double PRECOMPACT_RATIO = 0.70;
    private static final double SYNCHRONOUS_COMPACT_RATIO = 0.85;
    private final AgentWorkspaceRepository workspaceRepository;
    private final ConversationSessionRepository sessionRepository;
    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;
    private final ContextEventRepository eventRepository;
    private final ContextSnapshotRepository snapshotRepository;
    private final ContextMemoryRepository memoryRepository;
    private final AgentRunRepository runRepository;
    private final ContextArtifactService artifactService;
    private final TokenCounter tokenCounter;
    private final ModelProviderConfigService providerConfigService;
    private final LlmProviderRouter llmProviderRouter;
    private final ObjectMapper objectMapper;
    private final Map<String, Object> threadLocks = new ConcurrentHashMap<>();

    public AgentContextService(AgentWorkspaceRepository workspaceRepository,
                               ConversationSessionRepository sessionRepository,
                               ConversationRepository conversationRepository,
                               UserRepository userRepository,
                               ContextEventRepository eventRepository,
                               ContextSnapshotRepository snapshotRepository,
                               ContextMemoryRepository memoryRepository,
                               AgentRunRepository runRepository,
                               ContextArtifactService artifactService,
                               TokenCounter tokenCounter,
                               ModelProviderConfigService providerConfigService,
                               LlmProviderRouter llmProviderRouter,
                               ObjectMapper objectMapper) {
        this.workspaceRepository = workspaceRepository;
        this.sessionRepository = sessionRepository;
        this.conversationRepository = conversationRepository;
        this.userRepository = userRepository;
        this.eventRepository = eventRepository;
        this.snapshotRepository = snapshotRepository;
        this.memoryRepository = memoryRepository;
        this.runRepository = runRepository;
        this.artifactService = artifactService;
        this.tokenCounter = tokenCounter;
        this.providerConfigService = providerConfigService;
        this.llmProviderRouter = llmProviderRouter;
        this.objectMapper = objectMapper;
    }

    public void markInterruptedRunsRecoverable() {
        for (AgentRun run : runRepository.findByStatusIn(List.of(AgentRun.RunStatus.CREATED, AgentRun.RunStatus.RUNNING))) {
            run.setStatus(AgentRun.RunStatus.RECOVERABLE);
            run.setCheckpointJson(writeJson(Map.of("reason", "service_restarted", "recoverable", true)));
            runRepository.save(run);
        }
    }

    @Transactional
    public AgentWorkspace ensureWorkspace(Long userId, String threadId) {
        ConversationSession session = sessionRepository.findByConversationId(threadId)
                .orElseThrow(() -> new CustomException("Conversation not found", HttpStatus.NOT_FOUND));
        if (!Objects.equals(session.getUser().getId(), userId)) {
            throw new CustomException("Forbidden", HttpStatus.FORBIDDEN);
        }
        if (session.getWorkspaceId() != null) {
            return workspaceRepository.findByIdAndOwnerUserId(session.getWorkspaceId(), userId)
                    .orElseGet(() -> bindDefaultWorkspace(session, userId));
        }
        return bindDefaultWorkspace(session, userId);
    }

    private AgentWorkspace bindDefaultWorkspace(ConversationSession session, Long userId) {
        AgentWorkspace workspace = workspaceRepository.findFirstByOwnerUserIdAndDefaultWorkspaceTrue(userId)
                .orElseGet(() -> createDefaultWorkspace(userId));
        session.setWorkspaceId(workspace.getId());
        sessionRepository.save(session);
        return workspace;
    }

    private AgentWorkspace createDefaultWorkspace(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));
        AgentWorkspace workspace = new AgentWorkspace();
        workspace.setId(UUID.randomUUID().toString());
        workspace.setOwnerUserId(userId);
        workspace.setOrgTag(user.getPrimaryOrg());
        workspace.setType(AgentWorkspace.WorkspaceType.KNOWLEDGE);
        workspace.setName("Default knowledge workspace");
        workspace.setDefaultWorkspace(true);
        return workspaceRepository.save(workspace);
    }

    @Transactional
    public void bootstrapLegacyConversation(Long userId, String threadId) {
        AgentWorkspace workspace = ensureWorkspace(userId, threadId);
        List<Conversation> conversations = conversationRepository.findByConversationIdOrderByTimestampAsc(threadId);
        for (Conversation conversation : conversations) {
            if(conversation.getAnswerStatus()!=null && !"COMPLETED".equals(conversation.getAnswerStatus())) continue;
            if(conversation.getGenerationId()!=null && eventRepository.existsByThreadIdAndSourceKey(threadId,"final:"+conversation.getGenerationId())) continue;
            appendBootstrap(workspace.getId(), threadId, "conversation:" + conversation.getId() + ":user",
                    "user", conversation.getQuestion());
            appendBootstrap(workspace.getId(), threadId, "conversation:" + conversation.getId() + ":assistant",
                    "assistant", conversation.getAnswer());
        }
    }

    private void appendBootstrap(String workspaceId, String threadId, String sourceKey, String role, String content) {
        if (eventRepository.existsByThreadIdAndSourceKey(threadId, sourceKey)) return;
        appendEventInternal(null, workspaceId, threadId, null, ContextEvent.EventType.MESSAGE,
                role, content, 40, false, Map.of("bootstrap", true), sourceKey);
    }

    public ContextEvent appendEvent(Long userId, String threadId, String runId, ContextEvent.EventType type,
                                    String role, String content, int priority, boolean pinned,
                                    Map<String, Object> metadata) {
        AgentWorkspace workspace = ensureWorkspace(userId, threadId);
        return appendEventInternal(userId, workspace.getId(), threadId, runId, type, role, content,
                priority, pinned, metadata, null);
    }

    @Transactional
    public ContextEvent appendEventOnce(Long userId,String threadId,String runId,ContextEvent.EventType type,
            String role,String content,Map<String,Object> metadata,String sourceKey) {
        Object lock=threadLocks.computeIfAbsent(threadId,ignored->new Object());
        sessionRepository.lock(threadId).orElseThrow();
        synchronized(lock) {
            var existing=eventRepository.findByThreadIdAndSourceKey(threadId,sourceKey);
            if(existing.isPresent()) return existing.get();
            var workspace=ensureWorkspace(userId,threadId);
            // These recoverable events stay in MySQL with their intent/result, not in an external artifact.
            ContextEvent event=new ContextEvent(); event.setThreadId(threadId);event.setWorkspaceId(workspace.getId());
            event.setRunId(runId);event.setSequence(eventRepository.findMaxSequence(threadId)+1);event.setEventType(type);
            event.setRole(role);event.setContent(content==null?"":content);event.setMetadataJson(writeJson(metadata));
            event.setTokenEstimate(tokenCounter.countText(event.getContent()));event.setSourceKey(sourceKey);event.setPriority(90);
            return eventRepository.saveAndFlush(event);
        }
    }

    public AgentToolRegistry.ToolExecutionResult executeRecordedTool(Long userId,String threadId,String generation,
            String logicalCall,LlmProviderRouter.ToolCallDecision call,
            java.util.function.Supplier<AgentToolRegistry.ToolExecutionResult> execute) {
        if(toolExecutions==null) return execute.get();
        String id=sha256(generation+"|"+logicalCall+"|"+call.id());
        String args;
        try { args=objectMapper.copy().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsString(call.arguments()); }
        catch(Exception e){throw new IllegalArgumentException("Invalid tool arguments",e);}
        final String serializedArgs=args;
        String fingerprint=sha256(call.name()+"|"+args);
        try {
            toolTx.executeWithoutResult(s->{
                if(toolExecutions.findById(id).isPresent()) return;
                var record=new com.yizhaoqi.smartpai.model.AgentToolExecution();record.setId(id);record.setGenerationId(generation);
                record.setLogicalCallId(logicalCall);record.setToolCallId(call.id());record.setToolName(call.name());
                record.setArgumentsFingerprint(fingerprint);record.setArgumentsJson(serializedArgs);toolExecutions.saveAndFlush(record);
            });
        } catch(org.springframework.dao.DataIntegrityViolationException race) {
            if(toolExecutions.findById(id).isEmpty()) throw race;
        }
        var claimed=toolTx.execute(s->{
            var record=toolExecutions.lock(id).orElseThrow();
            if(!fingerprint.equals(record.getArgumentsFingerprint())) throw new IllegalStateException("Tool identity conflicts with arguments");
            if(record.getStatus()==com.yizhaoqi.smartpai.model.AgentToolExecution.Status.SUCCEEDED
                || record.getStatus()==com.yizhaoqi.smartpai.model.AgentToolExecution.Status.FAILED) return record;
            if(record.getStatus()!=com.yizhaoqi.smartpai.model.AgentToolExecution.Status.PREPARED)
                throw new IllegalStateException("Tool execution outcome unknown; automatic replay forbidden");
            record.setStatus(com.yizhaoqi.smartpai.model.AgentToolExecution.Status.EXECUTING);record.setUpdatedAt(java.time.Instant.now());
            toolExecutions.save(record);return record;
        });
        if(claimed.getResultJson()!=null) return readToolResult(claimed.getResultJson());
        AgentToolRegistry.ToolExecutionResult result;
        try { result=execute.get(); }
        catch(Exception e) {
            if(e instanceof IllegalArgumentException || e instanceof SecurityException || Set.of("search_knowledge","search_graph","knowledge_stats").contains(call.name()))
                result=new AgentToolRegistry.ToolExecutionResult(call.name(),false,"工具调用失败："+e.getClass().getSimpleName(),Map.of());
            else {
                toolTx.executeWithoutResult(s->{var r=toolExecutions.lock(id).orElseThrow();r.setDiagnostic("OUTCOME_UNKNOWN: "+e.getClass().getSimpleName());toolExecutions.save(r);});
                throw e;
            }
        }
        final var completed=result;
        try {
            toolTx.executeWithoutResult(s->{
                var r=toolExecutions.lock(id).orElseThrow();
                r.setResultJson(writeJson(completed));r.setStatus(completed.success()?com.yizhaoqi.smartpai.model.AgentToolExecution.Status.SUCCEEDED:com.yizhaoqi.smartpai.model.AgentToolExecution.Status.FAILED);
                r.setUpdatedAt(java.time.Instant.now());toolExecutions.save(r);
                appendEventOnce(userId,threadId,generation,ContextEvent.EventType.TOOL_RESULT,"tool",
                    completed.retrievalResult()==null?completed.content():writeJson(completed.retrievalResult()),
                    Map.of("tool_call_id",call.id(),"tool",call.name(),"logicalCallId",logicalCall),"tool:"+id);
            });
        } catch(RuntimeException ambiguousCommit) {
            var saved=toolExecutions.findById(id).orElseThrow();
            if(saved.getResultJson()==null) throw ambiguousCommit;
            return readToolResult(saved.getResultJson());
        }
        return completed;
    }

    @Transactional
    public void decorateToolResult(String threadId,String generation,String logicalCall,String toolId,String content,
                                   Map<String,Map<String,Object>> references) {
        if(toolExecutions==null) return;
        String id=sha256(generation+"|"+logicalCall+"|"+toolId);
        var result=toolExecutions.lock(id).orElseThrow();
        if(result.getResultJson()==null) throw new IllegalStateException("Tool result not durable");
        result.setReferencesJson(writeJson(references));toolExecutions.save(result);
        var event=eventRepository.findByThreadIdAndSourceKey(threadId,"tool:"+id).orElseThrow();
        event.setContent(content);event.setTokenEstimate(tokenCounter.countText(content));eventRepository.save(event);
    }
    private AgentToolRegistry.ToolExecutionResult readToolResult(String json) {
        try {return objectMapper.readValue(json,AgentToolRegistry.ToolExecutionResult.class);}
        catch(Exception e){throw new IllegalStateException("Stored tool result is invalid",e);}
    }

    private ContextEvent appendEventInternal(Long userId, String workspaceId, String threadId, String runId,
                                             ContextEvent.EventType type, String role, String content,
                                             int priority, boolean pinned, Map<String, Object> metadata,
                                             String sourceKey) {
        if(toolTx!=null) return toolTx.execute(s->{
            sessionRepository.lock(threadId).orElseThrow();
            return appendEventInternalLocked(userId,workspaceId,threadId,runId,type,role,content,priority,pinned,metadata,sourceKey);
        });
        return appendEventInternalLocked(userId,workspaceId,threadId,runId,type,role,content,priority,pinned,metadata,sourceKey);
    }
    private ContextEvent appendEventInternalLocked(Long userId,String workspaceId,String threadId,String runId,
            ContextEvent.EventType type,String role,String content,int priority,boolean pinned,Map<String,Object> metadata,String sourceKey) {
        Object lock = threadLocks.computeIfAbsent(threadId, ignored -> new Object());
        synchronized (lock) {
            ContextEvent event = new ContextEvent();
            event.setThreadId(threadId);
            event.setWorkspaceId(workspaceId);
            event.setRunId(runId);
            event.setSequence(eventRepository.findMaxSequence(threadId) + 1);
            event.setEventType(type);
            event.setRole(role);
            event.setPriority(priority);
            event.setPinned(pinned);
            event.setSourceKey(sourceKey);
            String safeContent = content == null ? "" : content;
            int tokens = tokenCounter.countText(safeContent);
            boolean currentUserInput = type == ContextEvent.EventType.MESSAGE && "user".equals(role);
            if (userId != null && tokens > MAX_INLINE_EVENT_TOKENS && !currentUserInput) {
                ContextArtifact artifact = artifactService.storeText(userId, workspaceId, safeContent,
                        "text/plain; charset=utf-8", pinned);
                event.setArtifactId(artifact.getId());
                safeContent = artifact.getPreview() + "\n[artifact:" + artifact.getId() + "]";
                tokens = tokenCounter.countText(safeContent);
            }
            event.setContent(safeContent);
            event.setTokenEstimate(tokens);
            event.setMetadataJson(writeJson(metadata));
            return eventRepository.save(event);
        }
    }

    @Transactional
    public ContextBundle buildContext(Long userId, String threadId, String systemInstruction,
                                      List<AgentToolRegistry.AgentTool> tools, int maxCompletionTokens) {
        bootstrapLegacyConversation(userId, threadId);
        AgentWorkspace workspace = ensureWorkspace(userId, threadId);
        int contextWindow = Optional.ofNullable(providerConfigService
                        .getActiveProvider(ModelProviderConfigService.SCOPE_LLM).contextWindowTokens())
                .orElse(ModelProviderConfigService.DEFAULT_CONTEXT_WINDOW_TOKENS);
        int reserve = Math.max(1024, contextWindow / 10);
        int promptBudget = contextWindow - maxCompletionTokens - reserve;
        if (promptBudget <= 0) throw new ContextOverflowException("Invalid model context budget");

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        addMessage(messages, breakdown, "system", "system", systemInstruction);

        String memoryBlock = buildMemoryBlock(userId, workspace, threadId);
        if (!memoryBlock.isBlank()) addMessage(messages, breakdown, "memory", "system", memoryBlock);

        ContextSnapshot snapshot = snapshotRepository.findTopByThreadIdOrderByVersionDesc(threadId).orElse(null);
        long resetSequence = eventRepository.findTopByThreadIdAndEventTypeOrderBySequenceDesc(
                threadId, ContextEvent.EventType.RESET_BOUNDARY).map(ContextEvent::getSequence).orElse(0L);
        long covered = snapshot != null && snapshot.getCoveredThroughSequence() >= resetSequence
                ? snapshot.getCoveredThroughSequence() : resetSequence;
        if (snapshot != null && snapshot.getCoveredThroughSequence() >= resetSequence) {
            addMessage(messages, breakdown, "snapshot", "system", "Conversation summary:\n" + snapshot.getSummary());
        }

        List<ContextEvent> allEvents = eventRepository.findByThreadIdOrderBySequenceAsc(threadId).stream()
                .filter(e -> eligibleForHistory(e, true)).toList();
        List<ContextEvent> events = allEvents.stream().filter(e -> e.getSequence() > covered).toList();
        List<ContextEvent> pinned = allEvents.stream()
                .filter(e -> e.getSequence() > resetSequence && e.isPinned())
                .toList();
        for (ContextEvent event : pinned) addEventMessage(messages, breakdown, event);

        int mandatoryTokens = tokenCounter.countMessages(messages);
        ContextEvent currentUser = events.stream()
                .filter(e -> e.getEventType() == ContextEvent.EventType.MESSAGE && "user".equals(e.getRole()))
                .reduce((a, b) -> b).orElse(null);
        if (currentUser != null && !pinned.contains(currentUser)) mandatoryTokens += currentUser.getTokenEstimate() + 4;
        if (mandatoryTokens > promptBudget) {
            throw new ContextOverflowException("CONTEXT_OVERFLOW: required instructions and current input exceed model window");
        }

        Set<Long> included = new LinkedHashSet<>();
        pinned.forEach(e -> included.add(e.getId()));
        List<ContextEvent> candidates = events.stream().filter(e -> !included.contains(e.getId())).toList();
        List<ContextEvent> selected = selectRecentGroups(candidates, promptBudget - tokenCounter.countMessages(messages));
        selected.sort(Comparator.comparingLong(ContextEvent::getSequence));
        for (ContextEvent event : selected) {
            addEventMessage(messages, breakdown, event);
            included.add(event.getId());
        }

        int used = tokenCounter.countMessages(messages) + estimateTools(tools);
        boolean degraded = selected.size() < candidates.size();
        double ratio = contextWindow == 0 ? 0 : (double) used / contextWindow;
        List<Long> eventIds = allEvents.stream().filter(e -> included.contains(e.getId())).map(ContextEvent::getId).toList();
        return new ContextBundle(messages, contextWindow, promptBudget, used, ratio, breakdown,
                snapshot == null ? 0 : snapshot.getVersion(), eventIds, degraded,
                degraded ? List.of("oldest_non_pinned_rounds_trimmed") : List.of(),
                ratio >= PRECOMPACT_RATIO, ratio >= SYNCHRONOUS_COMPACT_RATIO);
    }

    private List<ContextEvent> selectRecentGroups(List<ContextEvent> events, int availableTokens) {
        LinkedHashMap<String, List<ContextEvent>> groups = new LinkedHashMap<>();
        for (ContextEvent event : events) {
            String group = event.getRunId() != null ? event.getRunId() : "event:" + event.getId();
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(event);
        }
        List<List<ContextEvent>> reversed = new ArrayList<>(groups.values());
        Collections.reverse(reversed);
        List<ContextEvent> selected = new ArrayList<>();
        int used = 0;
        for (List<ContextEvent> group : reversed) {
            int groupTokens = group.stream().mapToInt(ContextEvent::getTokenEstimate).sum() + group.size() * 4;
            if (used + groupTokens > availableTokens) continue;
            selected.addAll(group);
            used += groupTokens;
        }
        return selected;
    }

    private boolean eligibleForHistory(ContextEvent event, boolean includeRunning) {
        if(event.getRunId()!=null) {
            var run=runRepository.findById(event.getRunId()).orElse(null);
            if(run!=null && run.getStatus()!=AgentRun.RunStatus.COMPLETED
                    && !(includeRunning && run.getStatus()==AgentRun.RunStatus.RUNNING)) return false;
        }
        var metadata=readMap(event.getMetadataJson());
        return !"assistant".equals(event.getRole()) || metadata.get("status")==null
                || "COMPLETED".equals(String.valueOf(metadata.get("status")));
    }

    private void addEventMessage(List<Map<String, Object>> messages, Map<String, Integer> breakdown, ContextEvent event) {
        if (event.getEventType() == ContextEvent.EventType.RESET_BOUNDARY ||
                event.getEventType() == ContextEvent.EventType.COMPACTION_BOUNDARY ||
                event.getEventType() == ContextEvent.EventType.CHECKPOINT) return;
        Map<String, Object> message = new LinkedHashMap<>();
        String role = event.getRole() == null ? "system" : event.getRole();
        message.put("role", role);
        message.put("content", event.getContent());
        Map<String, Object> metadata = readMap(event.getMetadataJson());
        if("assistant".equals(role) && metadata.get("status")!=null && !"COMPLETED".equals(String.valueOf(metadata.get("status")))) return;
        if (event.getEventType() == ContextEvent.EventType.TOOL_CALL && metadata.get("tool_calls") != null) {
            message.put("tool_calls", metadata.get("tool_calls"));
        }
        if (event.getEventType() == ContextEvent.EventType.TOOL_RESULT && metadata.get("tool_call_id") != null) {
            message.put("tool_call_id", metadata.get("tool_call_id"));
        }
        messages.add(message);
        breakdown.merge(event.getEventType().name().toLowerCase(Locale.ROOT), event.getTokenEstimate(), Integer::sum);
    }

    private void addMessage(List<Map<String, Object>> messages, Map<String, Integer> breakdown,
                            String category, String role, String content) {
        if (content == null || content.isBlank()) return;
        messages.add(new LinkedHashMap<>(Map.of("role", role, "content", content)));
        breakdown.merge(category, tokenCounter.countText(content), Integer::sum);
    }

    private String buildMemoryBlock(Long userId, AgentWorkspace workspace, String threadId) {
        StringBuilder result = new StringBuilder();
        if (workspace.getInstructions() != null && !workspace.getInstructions().isBlank()) {
            result.append("Workspace instructions:\n").append(workspace.getInstructions()).append("\n");
        }
        for (ContextMemory memory : memoryRepository.findByOwnerUserIdAndActiveTrueOrderByUpdatedAtAsc(userId)) {
            boolean applies = switch (memory.getScope()) {
                case USER -> String.valueOf(userId).equals(memory.getScopeId());
                case WORKSPACE -> workspace.getId().equals(memory.getScopeId());
                case THREAD -> threadId.equals(memory.getScopeId());
            };
            if (applies) result.append("- ").append(memory.getKey()).append(": ").append(memory.getContent()).append("\n");
        }
        return result.toString();
    }

    private int estimateTools(List<AgentToolRegistry.AgentTool> tools) {
        if (tools == null || tools.isEmpty()) return 0;
        return tokenCounter.countText(writeJson(tools));
    }

    /** Trim complete assistant/tool groups, keeping instructions and the latest user request intact. */
    public void fitRound(List<Map<String,Object>> messages,List<AgentToolRegistry.AgentTool> tools,int maxCompletionTokens) {
        fitRound(messages,tools,maxCompletionTokens,null);
    }
    public void fitRound(List<Map<String,Object>> messages,List<AgentToolRegistry.AgentTool> tools,int maxCompletionTokens,Map<String,Object> currentRequest) {
        int window=Optional.ofNullable(providerConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM).contextWindowTokens())
            .orElse(ModelProviderConfigService.DEFAULT_CONTEXT_WINDOW_TOKENS);
        int budget=window-maxCompletionTokens-Math.max(1024,window/10)-estimateTools(tools);
        while(tokenCounter.countMessages(messages)>budget) {
            int latestUser=-1,previousUser=-1;
            for(int i=0;i<messages.size();i++) if("user".equals(messages.get(i).get("role"))) { previousUser=latestUser;latestUser=i; }
            int removable=-1;
            for(int i=0;i<messages.size();i++) if(!"system".equals(messages.get(i).get("role")) && messages.get(i)!=currentRequest && i!=latestUser && i!=previousUser) { removable=i;break; }
            if(removable<0) throw new ContextOverflowException("CONTEXT_OVERFLOW: instructions and current input exceed model window");
            int end=removable+1;
            if(messages.get(removable).containsKey("tool_calls")) while(end<messages.size() && "tool".equals(messages.get(end).get("role"))) end++;
            messages.subList(removable,end).clear();
        }
    }

    public ContextSnapshot compact(Long userId, String threadId) {
        AgentWorkspace workspace = ensureWorkspace(userId, threadId);
        List<ContextEvent> all = eventRepository.findByThreadIdOrderBySequenceAsc(threadId);
        long reset = all.stream().filter(e -> e.getEventType() == ContextEvent.EventType.RESET_BOUNDARY)
                .mapToLong(ContextEvent::getSequence).max().orElse(0);
        List<ContextEvent> eligible = all.stream().filter(e -> e.getSequence() > reset && !e.isPinned()
                && eligibleForHistory(e, false)).toList();
        if (eligible.size() <= 8) return snapshotRepository.findTopByThreadIdOrderByVersionDesc(threadId).orElse(null);
        int keepFrom = Math.max(0, eligible.size() - 8);
        List<ContextEvent> compacted = eligible.subList(0, keepFrom);
        String inputHash = sha256(compacted.stream().map(e -> e.getId() + ":" + e.getContent()).reduce("", String::concat));
        Optional<ContextSnapshot> existing = snapshotRepository.findByThreadIdAndInputHash(threadId, inputHash);
        if (existing.isPresent()) return existing.get();

        StringBuilder summary = new StringBuilder();
        for (ContextEvent event : compacted) {
            if (event.getContent() == null || event.getContent().isBlank()) continue;
            String text = event.getContent().length() > 600 ? event.getContent().substring(0, 600) + "..." : event.getContent();
            summary.append(event.getRole() == null ? event.getEventType() : event.getRole()).append(": ").append(text).append("\n");
        }
        ContextSnapshot previous = snapshotRepository.findTopByThreadIdOrderByVersionDesc(threadId).orElse(null);
        String structuredSummary;
        boolean degraded;
        try {
            structuredSummary = llmProviderRouter.summarizeContext(String.valueOf(userId), summary.toString());
            degraded = false;
        } catch (Exception exception) {
            structuredSummary = writeJson(Map.of("summary", summary.toString(), "source", "deterministic-fallback"));
            degraded = true;
        }
        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setThreadId(threadId);
        snapshot.setWorkspaceId(workspace.getId());
        snapshot.setVersion(previous == null ? 1 : previous.getVersion() + 1);
        snapshot.setCoveredThroughSequence(compacted.get(compacted.size() - 1).getSequence());
        snapshot.setSummary(structuredSummary);
        snapshot.setStructuredSummaryJson(structuredSummary);
        snapshot.setInputHash(inputHash);
        snapshot.setTokenEstimate(tokenCounter.countText(snapshot.getSummary()));
        snapshot.setDegraded(degraded);
        snapshotRepository.save(snapshot);
        appendEvent(userId, threadId, null, ContextEvent.EventType.COMPACTION_BOUNDARY, "system",
                "Compacted through sequence " + snapshot.getCoveredThroughSequence(), 100, true,
                Map.of("snapshotVersion", snapshot.getVersion()));
        return snapshot;
    }

    public void reset(Long userId, String threadId) {
        appendEvent(userId, threadId, null, ContextEvent.EventType.RESET_BOUNDARY, "system",
                "Thread context reset", 100, true, Map.of("resetAt", LocalDateTime.now().toString()));
    }

    @Transactional
    public ContextMemory upsertMemory(Long userId, ContextMemory.MemoryScope scope, String scopeId,
                                      String key, String content) {
        validateScope(userId, scope, scopeId);
        ContextMemory memory = memoryRepository.findByOwnerUserIdAndScopeAndScopeIdAndKey(userId, scope, scopeId, key)
                .orElseGet(ContextMemory::new);
        memory.setOwnerUserId(userId); memory.setScope(scope); memory.setScopeId(scopeId); memory.setKey(key);
        memory.setContent(content); memory.setTokenEstimate(tokenCounter.countText(content)); memory.setActive(true);
        return memoryRepository.save(memory);
    }

    public List<ContextMemory> listMemories(Long userId) {
        return memoryRepository.findByOwnerUserIdAndActiveTrueOrderByUpdatedAtAsc(userId);
    }

    @Transactional
    public void forgetMemory(Long userId, Long memoryId) {
        ContextMemory memory = memoryRepository.findByIdAndOwnerUserId(memoryId, userId)
                .orElseThrow(() -> new CustomException("Memory not found", HttpStatus.NOT_FOUND));
        memory.setActive(false);
        memoryRepository.save(memory);
    }

    private void validateScope(Long userId, ContextMemory.MemoryScope scope, String scopeId) {
        if (scope == ContextMemory.MemoryScope.USER && !String.valueOf(userId).equals(scopeId))
            throw new CustomException("Invalid user memory scope", HttpStatus.FORBIDDEN);
        if (scope == ContextMemory.MemoryScope.WORKSPACE && workspaceRepository.findByIdAndOwnerUserId(scopeId, userId).isEmpty())
            throw new CustomException("Workspace not found", HttpStatus.NOT_FOUND);
        if (scope == ContextMemory.MemoryScope.THREAD) ensureWorkspace(userId, scopeId);
    }

    public AgentWorkspace updateWorkspace(Long userId, String workspaceId, String name, String instructions) {
        AgentWorkspace workspace = workspaceRepository.findByIdAndOwnerUserId(workspaceId, userId)
                .orElseThrow(() -> new CustomException("Workspace not found", HttpStatus.NOT_FOUND));
        if (name != null && !name.isBlank()) workspace.setName(name.trim());
        workspace.setInstructions(instructions);
        return workspaceRepository.save(workspace);
    }

    public AgentRun startRun(Long userId, String threadId, String generationId) {
        AgentWorkspace workspace = ensureWorkspace(userId, threadId);
        ModelProviderConfigService.ActiveProviderView provider = providerConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);
        AgentRun run = new AgentRun();
        run.setGenerationId(generationId); run.setThreadId(threadId); run.setWorkspaceId(workspace.getId());
        run.setUserId(userId); run.setStatus(AgentRun.RunStatus.RUNNING); run.setProvider(provider.provider()); run.setModel(provider.model());
        return runRepository.save(run);
    }

    public void checkpointRun(String generationId, AgentRun.RunStatus status, long contextVersion,
                              int promptTokens, int completionTokens, String finishReason, Map<String, Object> checkpoint) {
        runRepository.findById(generationId).ifPresent(run -> {
            run.setStatus(status); run.setContextVersion(contextVersion); run.setPromptTokens(promptTokens);
            run.setCompletionTokens(completionTokens); run.setFinishReason(finishReason); run.setCheckpointJson(writeJson(checkpoint));
            runRepository.save(run);
            appendEventInternal(run.getUserId(), run.getWorkspaceId(), run.getThreadId(), generationId,
                    ContextEvent.EventType.CHECKPOINT, "system", "Run " + status.name().toLowerCase(Locale.ROOT),
                    100, true, Map.of("status", status, "finishReason", finishReason == null ? "unknown" : finishReason,
                            "checkpoint", checkpoint == null ? Map.of() : checkpoint), null);
        });
    }

    private String writeJson(Object value) {
        if (value == null) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid context metadata", e); }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception ignored) { return Map.of(); }
    }

    private String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public record ContextBundle(List<Map<String, Object>> messages, int contextWindowTokens, int promptBudgetTokens,
                                int usedTokens, double usageRatio, Map<String, Integer> breakdown,
                                long snapshotVersion, List<Long> eventIds, boolean degraded,
                                List<String> trimReasons, boolean shouldPrecompact, boolean requiresSynchronousCompaction) {}

    public static class ContextOverflowException extends RuntimeException {
        public ContextOverflowException(String message) { super(message); }
    }
}
