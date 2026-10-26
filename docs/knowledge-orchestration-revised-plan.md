# PaiSmart 多索引知识编排修订计划

设计日期：2026-10-24。实施更新：2026-10-25，代码已落地并完成隔离环境验证。本文保留设计依据；实际类、表名、配置、验收结果及与原计划的调整见 [实施说明](knowledge-orchestration-implementation.md)。

## 已确定的边界

- 在现有 Spring Boot 应用内实现，MySQL 保存来源、图谱和任务状态，Elasticsearch 保存原文、实体和关系检索索引。
- 首期只有原文/图片索引和图谱索引；不引入独立 LightRAG 服务，不建设 PageIndex、时间线、Wiki 或新的知识库管理产品。
- 分块后分别构建索引；模型通过 `search_knowledge`、`search_graph` 选择一种或多种检索能力，允许多轮补查。
- 所有检索工具使用统一请求与证据协议、后端鉴权和最终回答生成路径。
- 复用现有 ReAct 循环及 `AgentRun`。不新增 `KnowledgeQueryOrchestrator`，也不在知识模块另建 Agent Runtime。
- 新流程默认关闭。开启后首期沿用最多 4 轮工具决策、8 次工具调用，最终回答使用现有流式模型接口。

## 1. 当前源码事实与必须调整的部分

当前已经实现 ReAct：`ChatHandler.runReActLoop()` 循环请求模型、执行返回的多个 `tool_calls`、追加工具结果，再请求模型。没有工具调用时结束；轮次耗尽时关闭工具，进入最终回答。工具在同轮内目前按顺序执行。

已有模型路由、上下文预算、运行记录、取消、流式输出、聊天持久化。`AgentRun.generationId` 可以直接作为一次运行的身份。现有 `ContextArtifact` 保存聊天上下文外置内容，与下面的知识索引产物不是同一概念。

需要扩展或修正的实际位置：

- `ChatHandler` 当前每次原文检索重新从 1 编号并覆盖引用映射；改为运行内累积。
- `LlmProviderRouter` 的系统提示词强制先调用原文工具；改为按问题选择原文、图谱或组合。
- `AgentContextService` 当前主要在运行开始时构建预算；新增每轮模型调用前的预算检查，保持 assistant/tool 消息配对完整。
- `generate_summary` 当前自行检索并直接流式输出；新流程中不暴露这一捷径，摘要也走检索证据与统一回答。旧流程保留兼容开关。
- `MultimodalSearchService` 当前权限条件在顶层 query 分支；同时给 kNN 分支设置权限过滤，并在返回证据前以 MySQL 当前授权和版本做最终校验。
- `MultimodalVectorizationService` 当前将来源组装与 Embedding 耦合；拆开来源阶段，避免图谱构建依赖原文向量成功。
- 上传记录按 MD5 与用户唯一，但多个删除、重建和索引方法只按 MD5 操作；新链路以 `file_upload.id` 标识文档，提前修正影响新链路的共享文件处理。

本节描述实施前的源码基线；实施后的端到端验证结果记录在实施说明中。

## 2. 类与职责

### 2.1 来源、权限与构建

| 类/接口 | 职责与边界 |
|---|---|
| `KnowledgeSourceAssembler` | 从现有解析结果组装文本、PDF 图片和独立图片来源；保留页码、阅读顺序、锚点和可重新读取的图片定位，不调用 Embedding。 |
| `KnowledgeChunkService` | 创建、分页写入并封存不可变 `KnowledgeChunkSet`；封存事务写入 `ChunksReady` outbox。未封存版本不提供给构建器。 |
| `KnowledgeAccessService` | 解析后端认证用户、当前有效组织、文档管理权限和规范授权策略；返回可检索范围，计算图谱合并 scope，并复核来源权限。 |
| `KnowledgeIndexBuilder` | `type()`、`capabilities()`、`build(context)`、`withdraw(contributionRef)`；withdraw 只撤回指定文档/分块版本贡献，不删除共享图谱产物。 |
| `ChunkIndexBuilder` | 根据来源快照构建原文和图片索引；复用现有多模态 Embedding 与用量统计。 |
| `GraphIndexBuilder` | 组织抽取与来源贡献提交；聚合描述和 ES 同步交给后续任务，不在一个长事务内完成。 |
| `KnowledgeIndexOrchestrator` | 根据分块完成、重建、撤回事件选取构建器并创建执行步骤；不直接实现租约、图谱事务、ES 写入或发布规则。 |
| `KnowledgeIndexTaskService` | 持久化任务、唯一键去重、租约与 fencing token、执行状态、重试和超时回收。构建、投影、同步任务共用任务基础设施。 |
| `KnowledgeArtifactService` | 管理索引产物、配置代次、发布校验和文档贡献状态；计算请求范围内的可用能力与覆盖度。 |
| `KnowledgeIndexConsumer` | 消费 Kafka 消息，按任务类型交给现有编排/投影/同步执行入口；不实现业务算法。 |
| `KnowledgeIndexOutboxDispatcher` | 只负责把已提交事件可靠投递到 Kafka；投递成功仅表示消息已进入队列。 |
| `IndexSyncWorker` | 消费 ES 同步任务，校验版本、执行批量写入、记录逐项结果与重试，确认搜索可见后申请发布。 |

`KnowledgeIndexOrchestrator` 保留为薄协调层；任务运行状态归 TaskService，索引可用状态归 ArtifactService。

### 2.2 图谱与检索基础设施

| 类/接口 | 职责与边界 |
|---|---|
| `GraphExtractionService` | 用现有模型客户端抽取实体、关系、方向和原文支持信息；校验结构，保存不可变抽取实例，支持相同输入配置重试复用。 |
| `GraphContributionService` | 统一处理添加、替换、撤回来源贡献；短事务提交贡献、对象版本、失效标记和 outbox。是图谱来源写入的唯一入口。 |
| `GraphMergeService` | 从某个来源版本的完整有效贡献集合生成归一名称、聚合描述等投影；模型调用在事务外，结果按版本条件提交。 |
| `GraphMaintenanceService` | 计算删除、重建、权限变化影响的贡献与图谱对象，调用 ContributionService；不另写一套图谱提交协议。 |
| `GraphKeywordExtractor` | 为 local/global 图谱检索提取实体关键词与主题关键词。 |
| `KnowledgeSearchIndexService` | 封装原文、实体、关系 ES 索引的初始化、批量写入、删除、关键词/向量查询、模型维度和索引代次校验；复用现有 ES 访问实现。 |
| `KnowledgeRetriever` | `type()`、`capabilities()`、`retrieve(request, access)`；能力描述为静态协议，可用状态和覆盖度由 ArtifactService 提供。 |
| `ChunkKnowledgeRetriever` | 适配现有多模态检索，支持 vector/keyword/hybrid，默认 hybrid。 |
| `GraphKnowledgeRetriever` | 支持 local/global/hybrid，默认 hybrid；ES 召回实体/关系，再查 MySQL 的一跳关联和原文来源。 |
| `KnowledgeRetrievalService` | 统一检索门面：鉴权、参数与能力校验、检索器分发、版本检查、状态与覆盖度返回。 |

Embedding 继续直接使用 `MultiEmbeddingClient`；首期不新增只转发同名方法的 `KnowledgeEmbeddingService`。删除旧计划中的大而全 `KnowledgeVectorIndexService`，其 ES 职责归 SearchIndexService，模型职责归现有客户端。

### 2.3 现有 Agent 的知识检索扩展

| 类 | 职责与边界 |
|---|---|
| `KnowledgeRetrievalCoordinator` | 接收运行内检索结果、组织证据合入、准备工具证据内容和最终回答证据上下文。不决定工具调用轮次，不调用最终回答模型，不另管取消和预算。 |
| `KnowledgeRetrievalState` | 替代旧名 `KnowledgeQuerySession`；绑定现有 `generationId`，保存证据池、引用注册和本次运行的重复检索缓存。 |
| `EvidencePool` | 按证据身份累积结果，保留类型、版本、来源和检索路径；不同类型证据不因共享 Chunk 而合并。 |
| `CitationRegistry` | 按稳定来源身份复用引用编号，维护从展示编号到来源的映射。 |
| `EvidenceAssembler` | 对证据排序、融合、去重和预算裁剪，生成工具消息及最终证据上下文，保留图片内容块。 |

运行关系：`Conversation → AgentRun(generationId) → KnowledgeRetrievalState`。不新增第二套 AgentRun 表、上下文存储或取消信号。

并行只用于同轮相互独立的只读检索工具；有副作用的既有工具仍串行执行。Worker 返回不可变结果，统一由运行线程按工具调用原始顺序、结果排名、稳定 ID 合入证据池并注册引用，避免并行修改共享可变对象。

### 2.4 接口和配置

- `KnowledgeRetrievalController`：`POST /api/v1/knowledge/retrieve`，支持直接指定检索器调试；身份由后端认证提供。
- `KnowledgeIndexController`：`GET /api/v1/knowledge/documents/{documentId}/indexes` 返回文档贡献状态；`GET /api/v1/knowledge/artifacts` 只列出授权范围内的产物；`POST /api/v1/knowledge/documents/{documentId}/indexes/rebuild` 接收 indexTypes，校验管理权限后返回 202 与 jobIds。失败重试复用重建入口和任务幂等规则。
- `KnowledgeRagProperties`：功能开关、启用类型、并发、超时、重试和 Agent 预算。沿用现有模型、配额、Kafka 和有界线程池配置。
- 知识库页面显示每种索引的状态、部分覆盖和重试入口；聊天沿用工具状态展示，引用协议增加稳定来源字段并兼容旧格式。

## 3. MySQL 与数据协议

### 3.1 首期 12 个逻辑实体/表

| 实体 | 表 | 关键内容 |
|---|---|---|
| `KnowledgeChunkSet` | `knowledge_chunk_set` | documentId、原文件版本、解析/切分配置版本、内容指纹、完整性状态、预期分块数。 |
| `KnowledgeChunk` | `knowledge_chunk` | chunkSetId、块身份、文本/图片类型、内容与定位快照。 |
| `KnowledgeArtifact` | `knowledge_artifact` | type、scopeType/scopeId、配置代次、当前发布代次、物理索引描述、版本和状态。 |
| `KnowledgeIndexState` | `knowledge_index_state` | 文档与索引类型的目标/可用 ChunkSet、贡献阶段、所依赖产物和错误。 |
| `KnowledgeIndexJob` | `knowledge_index_job` | 任务类型、目标及版本、配置代次、租约、fencing token、重试、逐项同步进度和诊断。 |
| `KnowledgeIndexOutbox` | `knowledge_index_outbox` | 不可变事件、幂等键、路由、投递状态和时间。 |
| `GraphScope` | `kg_scope` | 规范授权策略、策略版本和图谱隔离边界。 |
| `ChunkGraphExtraction` | `kg_chunk_extraction` | chunkSet/chunk 身份、实际完整输入哈希、模型及提示词配置、不可变抽取结果。 |
| `GraphEntity` | `kg_entity` | scope、归一名称、类型、来源版本、描述投影版本、ES 确认版本、可见状态。 |
| `GraphRelation` | `kg_relation` | scope、起点、终点、关系类型、方向类型、描述及上述版本/状态。 |
| `GraphEntitySource` | `kg_entity_source` | 实体与抽取实例的贡献、原始描述、来源版本、有效标记。 |
| `GraphRelationSource` | `kg_relation_source` | 关系与抽取实例的贡献、原始有序端点、方向、描述、关键词、来源定位。 |

每个持久化实体对应 Repository；Repository 不调用模型和 ES。已有 `ContextArtifact` 保持聊天上下文用途，不合并到 `KnowledgeArtifact`。

首期不新增独立 `GraphExtractionCache` 表，不做跨文档共享抽取缓存。相同文档 Chunk 的重试可复用抽取实例，但替代版本仍保存独立的来源身份；未来引入跨文档缓存时，缓存只承载计算结果，不能直接成为来源身份或权限依据。

文档使用 `file_upload.id`。需要为文档补充当前 ChunkSet、授权版本和逻辑删除状态，避免 ES 的异步删除决定文档是否仍可见。

关键唯一约束和索引：

- 分块：`(chunk_set_id, chunk_key)`；封存幂等：`(document_id, source_revision, parser_config_hash)`。
- 产物：`(artifact_type, scope_type, scope_id)`；文档贡献状态：`(document_id, index_type)`。
- 任务：目标、目标版本、任务类型、构建配置组成幂等键；重试通过租约接管同一任务，强制再构建则显式增加配置/构建代次。
- 实体：`(scope_id, normalized_name)`；首期不做模糊别名自动合并。
- 有向关系：`(scope_id, source_entity_id, target_entity_id, normalized_predicate, direction_type)`；对称/无方向关联按规范端点存储，但与有向关系分开。
- 来源贡献按“图谱对象 + 抽取实例 + 实例内事实 ID”唯一；同时索引 documentId/chunkSetId，支持撤回。
- 关系分别建立 `(scope_id, source_entity_id)` 和 `(scope_id, target_entity_id)` 索引。

### 3.2 身份、状态与能力

来源身份使用 documentId、chunkSetId、chunkId、来源类型及位置；同 MD5 的两个上传记录不会共用权限身份。`SourceReference` 另外保留 MD5、页码、锚点和图片定位，兼容现有预览。

`KnowledgeEvidence` 包含 evidenceId、类型、内容、对象版本、检索路径、排名及多个 SourceReference。原文 Chunk 与引用该 Chunk 的图谱关系是两条证据；引用编号只按 SourceReference 身份复用。不使用原始 ES 分数直接跨类型比较，同类结果按排名融合，最终在 token 预算内保留原文依据和图谱关系。

`KnowledgeRetrievalResult` 包含 executionStatus、evidence、coverage、artifactVersions、耗时及诊断。执行状态区分 SUCCESS、EMPTY、PARTIAL、NOT_READY、FAILED、CANCELLED；coverage 单独记录请求且授权范围内的已就绪、待构建与失败文档。空结果不等于完整覆盖，索引部分覆盖也不等于本次检索执行失败。

Artifact 表示一种可查询索引产物，不表示一次构建任务。首期按授权 scope 组织原文/图谱产物，跨 scope 的可用视图在查询时组合；不假设项目已经有 KB1 这样的知识库实体。字段预留 scopeType，使未来文档级 PageIndex 可接入。

文档贡献阶段为 PENDING、BUILDING、SYNCING、READY、FAILED、REVOKED。Artifact 的服务状态与覆盖度分别表达：没有可用代次为 NOT_READY；有可用投影为 AVAILABLE；暂时隔离为 BLOCKED。完整/部分覆盖根据授权请求范围计算，不以一个全局 READY 标记替代所有文档的状态。

## 4. 图谱提交、同步与权限协议

### 4.1 来源提交和描述生成分阶段

1. Extract：事务外调用模型，将校验后的抽取结果保存为不可变实例。缓存指纹覆盖实际输入、模型、提示词及抽取配置；重试不重复增加贡献。
2. Commit Contributions：通过 GraphContributionService 短事务添加/替换/撤回贡献。创建对象依赖唯一约束；已有受影响对象按稳定 ID 顺序行锁更新。来源版本递增、旧投影失效、outbox 在同一事务提交。死锁等瞬时冲突有限重试，失败交回任务重试。
3. Rebuild Projection：读取同一一致性快照下的对象版本 v 与全部有效贡献，在事务外生成聚合描述。不得只将旧聚合描述和新片段再次合并，遗漏已删除或并发新增的来源。
4. Conditional Commit：只有当前来源版本仍为 v、授权范围仍有效时，短事务提交描述投影及 ES 同步事件；不匹配则丢弃旧计算并为最新版本重试。
5. Sync and Publish：IndexSyncWorker 使用含对象版本和索引配置代次的 ES 文档身份；旧事件不能覆盖新内容。逐项成功且搜索可见后，按当前来源版本/投影版本/授权版本条件确认发布。

所有新增、删除、权限移动都通过同一来源提交协议。首期选择受影响对象在新版本发布前暂不可查，保持其他有效对象可用；通过 coverage/NOT_READY 表达可用性变化，不冒充完整图谱快照。

MySQL 是来源与当前可用版本的事实依据。ES 候选返回后批量核验版本与权限，过期投影不能进入模型上下文。

### 4.2 Outbox 与 ES

链路为：MySQL 事务 → outbox → Dispatcher → Kafka → Consumer/SyncWorker → ES → ArtifactService 条件发布。

- Kafka 确认只推进 outbox 投递状态，不能把索引标为 READY。
- ES 批量请求记录每项结果，重试保持相同身份，成功项可安全重放。同步进度保存在任务记录，不复用 outbox 的投递状态。
- 批量同步用 `refresh=wait_for` 或明确的搜索可见性屏障后才确认发布；配置超时，避免 refresh 关闭时无限等待。
- 延迟、重复、乱序事件都要校验目标版本、任务 fencing token 和当前索引配置代次。
- 切换 Embedding 模型或维度时创建新物理索引并回填。别名切换前核对配置和覆盖；查询使用产物绑定的模型配置，不以全局“最新模型”查询旧向量。
- 当前客户端若无法同时提供旧、新模型，先将受影响向量能力标为 NOT_READY，再完成回填切换；可用的关键词能力继续明确返回，不用维度相同冒充模型兼容。

### 4.3 方向与来源

有向关系保留有序端点和关系类型；对称关系或方向未知单独标记，不能凭实体共现推断依赖方向。来源贡献保存抽取时的原始方向和原文依据。

一跳扩展默认同时查入边和出边，返回方向元数据。模型回答方向性问题时能区分 A→B 与 B→A；方向未知或来源冲突时保留不确定性。首期不承诺任意深度图算法或精确关系推理。

### 4.4 权限和删除

当前读取规则仍为“本人上传 OR 公开 OR 有效组织标签”，有效组织沿用现有父组织规则。

非公开文档的 owner+orgTag 在当前规则下可以构成规范授权谓词；只有谓词相同的来源才能持久化聚合。scope 保存策略及版本，而不是某次查询得到的用户列表。首期不实现临时用户分享；新增 ACL 类型时必须扩展策略规范化和失效机制，不能直接套用旧 scope。

文档删除、公开性/组织变更时，在提交授权变化的同一 MySQL 事务中，使受影响旧投影失效并登记撤回/重建事件，然后异步恢复新范围的索引。组织成员/父子层级变化优先影响查询鉴权与有效标签缓存，不应无条件重建所有实体描述。

检索前解析当前权限，ES 的向量和关键词分支都过滤，MySQL 回查再核验；图谱扩展、来源回查、引用预览也要鉴权。权限不足或授权数据不可确认时不返回受限证据。

每轮模型调用及最终上下文组装前复核本次证据授权；已失效的检索缓存不得复用。运行中发现已使用来源被撤权时取消当前生成，避免继续基于旧证据输出；已经发送的历史文本无法靠重新过滤引用收回。

指定文档子集时，不能直接返回混合了其他文档的全 scope 聚合描述；使用该子集的来源贡献构成证据。

删除只撤回指定 documentId 的贡献与索引。共享实体按剩余来源重建；同 MD5 的 MinIO 对象仅在没有其他有效上传记录引用时物理清理。新消息携带 documentId，旧消息须按 MD5+用户明确解析，歧义时失败而非任取记录。

## 5. 查询、证据和现有类改造

```text
ChatHandler：唯一 ReAct 循环、预算、取消与输出
  → LlmProviderRouter：模型决定工具调用
  → AgentToolRegistry：search_knowledge / search_graph
  → KnowledgeRetrievalService：鉴权、能力校验、分发
  → ChunkKnowledgeRetriever / GraphKnowledgeRetriever
  → KnowledgeRetrievalResult
  → KnowledgeRetrievalCoordinator：合入本次 generationId 的证据
  → EvidencePool / CitationRegistry / EvidenceAssembler
  → 回填工具消息，必要时继续现有循环
  → 统一证据上下文 + 现有模型流式生成
  → ConversationService：保存答案及完整来源映射
```

`AgentToolRegistry.ToolExecutionResult` 增加可选的类型化 retrievalResult，保留既有非检索工具构造方式。不再靠工具名等于 search_knowledge 才处理证据、图片和引用；其他检索工具返回相同类型即可接入。

保留现有工具循环，但决策轮不直接把候选正文当作最终答案推给前端；无工具调用或预算耗尽后，统一组装证据，再使用现有无工具流式生成入口。继续复用既有 finishReason、续写、错误和持久化处理。

引用在每轮有序合入、工具结果送给模型之前分配，整个运行只追加、不重排。最终裁剪可导致编号不连续，不能为了连续而改掉模型或前端已见过的编号。CitationRegistry 保存 sourceId↔displayNumber，历史消息保存该次运行完整映射。

取消信号来自现有 generationId：阻止新模型/检索调用，尽力取消已发出的客户端请求，忽略取消后的迟到结果，不把 CANCELLED 当作 FAILED。已完成事件按现有历史策略保存，运行结束后清理检索内存状态。

资料内容、图谱描述和工具结果作为证据数据，不拼接成系统指令；引用要对应可访问来源。图谱抽取属于模型生成的结构化解释，保留原文支持与冲突信息，不能当作无需校验的事实。

其他必要调整：FileProcessingConsumer 接入来源封存事件；DocumentService 接入撤回和版本变化；AgentContextService 按轮预算；ChatGenerationStateService 保存累积引用；ConversationService 兼容旧引用 JSON；KafkaConfig/AsyncExecutorConfig/EsIndexInitializer 扩展现有基础设施。

## 6. 实施顺序与验收

| 阶段 | 交付 | 验收 |
|---|---|---|
| 1. 现有链路基线与来源协议 | 为现有 ReAct 增加多轮行为回归；documentId、ChunkSet、统一证据、权限服务、原文适配；修正 kNN 权限过滤和共享 MD5 范围。 | 原文/图片检索、引用预览不退化；同文件不同上传者隔离；向量与关键词路径都执行权限。 |
| 2. 构建与产物状态 | Artifact、TaskService、薄 Orchestrator、outbox、Kafka 分发、ES SyncWorker。 | 重复消费幂等；租约过期可恢复；Kafka 投递不会提前发布；覆盖度与失败状态正确。 |
| 3. 图谱来源与生命周期 | 抽取、有向关系、ContributionService、版本化 Merge、删除/权限变更；图谱投影可使用测试 Embedding 接口验证流程。 | 并发提交不丢来源；重复构建不加倍；共享实体删除保留剩余贡献；撤权立即阻止旧聚合内容进入新查询。 |
| 4. 图谱检索闭环 | 真实模型与 ES 同步、local/global/hybrid、入出边扩展、来源回查和授权范围覆盖。 | 过期事件不能覆盖或发布；图谱部分可用可解释；文档子集不混入范围外事实。 |
| 5. 现有 Agent 扩展 | 双检索工具、Coordinator、运行内证据池、稳定引用、按轮预算、独立检索并行、统一流式回答。 | 模型可单路/双路/多轮检索；引用不覆盖；取消和部分失败不导致串话或额外输出。 |
| 6. 回填与灰度 | 历史文档按上传身份回填、索引状态 UI、特性开关与检索评测。 | 旧引用 JSON 可读；PDF 页码和图片预览正常；新流程可关闭；授权修正不随开关回退。 |

重点测试类：

- `ChatHandlerReActFlowTest`：两轮以上工具结果回填、同轮多调用、4/8 预算、取消、无工具最终生成；模型使用测试替身，不请求真实付费模型。
- `KnowledgeIndexTaskServiceTest` / `KnowledgeArtifactServiceTest`：幂等、租约与旧 Worker、配置代次、覆盖度与发布门槛。
- `GraphConcurrencyIntegrationTest`：真实 MySQL 下同时新增同一实体、删除与新增交错、版本失配重试、死锁恢复，不只依赖 Mockito。
- `GraphLifecycleIntegrationTest`：贡献替换、零抽取结果、共享来源删除、方向与对称关系区分。
- `KnowledgeAccessIntegrationTest`：同 MD5 多用户、公开/组织变化、父组织规则、旧缓存和旧 ES 命中、文档子集、引用预览权限。
- `IndexSyncIntegrationTest`：真实 ES 的部分批量失败、乱序事件、refresh 可见性、模型配置不匹配和代次切换。
- `EvidenceAssemblerTest` / `CitationRegistryTest`：不同证据共用来源、多轮去重、并行完成顺序、编号不重排、图片与 token 裁剪。
- 浏览器回归：流式输出、取消、历史消息、工具状态、PDF 页码与图片预览；检查网络响应、MySQL 与 Redis 状态。

上线前记录：各类型待处理/失败/同步延迟、覆盖度、检索延迟、模型用量、过期投影拒绝次数、权限拒绝和取消。Java 变更优先编译触发 IDE 热加载。实施验证使用独立测试库和依赖端口，业务 `.env` 与业务数据库未迁移。

## 参考依据

- 现有源码：`ChatHandler`、`AgentToolRegistry`、`LlmProviderRouter`、`AgentContextService`、`AgentRun`、`MultimodalSearchService`、`DocumentService`、`FileUploadRepository`。
- [Elasticsearch 8.10 kNN 查询语义](https://www.elastic.co/guide/en/elasticsearch/reference/8.10/knn-search.html)：顶层 query 与 knn 结果按 OR 组合，向量分支需要自身的 filter。
- [Elasticsearch 8.10 refresh](https://www.elastic.co/guide/en/elasticsearch/reference/8.10/docs-refresh.html)：写入确认与搜索可见性不同，发布流程必须明确这一边界。
- [MySQL 8.0 locking reads](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)：短事务中的行锁用于串行更新来源版本，模型调用不持有行锁。
