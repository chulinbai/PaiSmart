# PaiSmart 多索引知识编排实施说明

更新：2026-10-25。实现位于现有 Spring Boot 应用内，默认关闭新链路。

## 已实现的链路

```text
上传 / 历史回填 / 启动知识文档
  → 现有解析器
  → KnowledgeSourceAssembler：文本、PDF 内嵌图片、独立图片
  → KnowledgeChunkService：封存不可变 ChunkSet
  → 同一 MySQL 事务创建 CHUNK / GRAPH 任务及 outbox
  → Kafka / GraphTaskRunner
      ├─ ChunkIndexBuilder → Embedding → ES → 条件发布
      └─ GraphIndexBuilder → 抽取 → 来源贡献事务
           → GraphMergeService → 描述投影 → IndexSyncWorker → ES → 条件发布

现有 ChatHandler ReAct 循环
  → 模型选择 search_knowledge / search_graph，可同轮并行、后续轮补查
  → KnowledgeRetrievalService：当前权限、覆盖度、版本校验
  → 原文 / 图谱检索器 → 统一 Evidence
  → 运行内证据池、稳定引用编号、每轮上下文预算
  → 最终无工具流式回答 → MySQL 聊天记录及引用映射
```

原文索引失败不阻止图谱任务发布；Kafka 投递成功不会将索引标记为 READY。新链路使用现有 AgentRun/generationId、取消机制和聊天持久化，不新增另一套 Agent Runtime。

## 类与职责

类位于 `src/main/java/com/yizhaoqi/smartpai/service/knowledge/`，图谱基础设施在其 `graph/` 子目录。

| 模块 | 主要类 | 职责 |
|---|---|---|
| 来源与权限 | `KnowledgeSourceAssembler`、`KnowledgeChunkService`、`KnowledgeAccessService` | 先生成来源快照，再独立建索引；以上传记录 ID 隔离权限；从当前 MySQL 用户/组织关系计算访问范围。 |
| 构建 | `KnowledgeIndexBuilder`、`ChunkIndexBuilder`、`GraphIndexBuilder` | 原文/图片 embedding；实体和有向关系抽取；返回可条件提交的构建结果。 |
| 生命周期 | `KnowledgeIndexOrchestrator`、`KnowledgeIndexTaskService`、`KnowledgeArtifactService` | 构建/重建/撤回/权限变化，任务租约及 fencing，按文档展示阶段和覆盖度。 |
| 图谱写入 | `GraphExtractionService`、`GraphContributionService`、`GraphMergeService`、`GraphMaintenanceService` | 缓存不可变抽取；仅替换指定文档贡献；按完整有效来源重新聚合；拒绝旧版本提交。 |
| 异步基础设施 | `GraphStore`、`GraphTaskRunner`、`KnowledgeIndexOutboxDispatcher`、`KnowledgeGraphWorkConsumer`、`IndexSyncWorker` | 显式 JDBC 短事务、租约续期、失败重试、超时回收、outbox 投递、搜索可见后发布。 |
| 检索 | `KnowledgeRetriever`、`ChunkKnowledgeRetriever`、`GraphKnowledgeRetriever`、`GraphKeywordExtractor`、`KnowledgeRetrievalService` | 原文 vector/keyword/hybrid；图谱 local/global/hybrid；实体/关系候选、一跳入出边和来源回查。 |
| 证据 | `KnowledgeRetrievalCoordinator`、`KnowledgeRetrievalState`、`EvidencePool`、`CitationRegistry`、`EvidenceAssembler`、`KnowledgeTypes` | 按排名融合、去重、预算裁剪、保留图片定位；同一来源整个运行复用编号；缓存复用前重新鉴权。 |
| ES | `KnowledgeSearchIndexService` | 模型隔离的物理索引、不可变投影、批量逐项检查、refresh 可见性、带权限的 kNN/关键词召回及限定代次删除。 |

已有类的主要接入点：`FileProcessingConsumer`、`UploadController`、`DocumentService`、`BootstrapKnowledgeInitializer`、`AgentToolRegistry`、`LlmProviderRouter`、`ChatHandler`、`AgentContextService`、引用详情接口和知识库索引状态组件。

原计划中的 `KnowledgeIndexConsumer` 由实际的 `KnowledgeGraphWorkConsumer` 承担；构建和投影复用同一任务系统。`withdraw` 集中在生命周期与贡献服务中，不要求每个 Builder 重复实现删除协议。ChunkSet 封存事务直接写两种构建任务及 outbox，不再增加中间 `ChunksReady` 消息。

## MySQL 表与一致性

执行顺序：

1. `src/main/resources/db/knowledge-graph.sql`
2. `src/main/resources/db/knowledge-orchestration.sql`

两份脚本新增 14 张 InnoDB 表，不删除现有表。它们用于首次创建；`CREATE TABLE IF NOT EXISTS` 不承担已部署旧版本的列升级。

| 表 | 内容与关键约束 |
|---|---|
| `knowledge_chunk_set` | 上传记录 ID、内容指纹、解析配置、封存状态与块数量；`(document_id,fingerprint,parser_config)` 唯一。 |
| `knowledge_chunk` | 不可变来源 JSON，保留页码、锚点、图片定位；主键 `(chunk_set_id,chunk_key)`。 |
| `knowledge_document` | 当前 ChunkSet、授权版本、scope、逻辑删除标记；是候选来源当前性检查的依据。 |
| `knowledge_artifact` | 类型、scope、配置版本、服务状态、物理索引与发布代次；类型与 scope 唯一。 |
| `knowledge_index_state` | 每文档每索引类型的目标/就绪 ChunkSet、构建代次、阶段、任务 ID 和错误。 |
| `kg_scope` | 规范授权策略：公开，或 owner + org。不同授权谓词的来源不能持久化聚合。 |
| `kg_document_state` | 图谱贡献批次版本、不可变指纹和 payload；与来源快照当前指针分开管理。 |
| `kg_chunk_extraction` | 同一文档、构建代次、Chunk、完整来源输入与模型/提示配置对应的不可变抽取结果。 |
| `kg_entity` / `kg_relation` | 规范身份、来源版本、构建代次、描述来源版本、投影 ID、ES 确认 ID、删除和可查询标记。关系保留有序端点及 DIRECTED/SYMMETRIC/UNKNOWN。 |
| `kg_entity_source` / `kg_relation_source` | 对象与上传身份、抽取实例的贡献关系、原始描述及事实；删除只撤回指定文档。 |
| `kg_work` | 构建、聚合、同步、删除共用任务；唯一幂等键、租约 token、重试次数和诊断。 |
| `kg_outbox` | 已提交任务的可靠通知；投递状态独立于构建/发布状态。 |

采用 `JdbcTemplate` 和显式事务实现加锁/条件更新，未为每张表增加只转发 SQL 的 JPA Repository。模型和 ES 请求在事务外执行。

关键规则：

- 修改来源先锁上传记录，再按固定对象顺序锁图谱头；贡献、版本递增、旧投影失效、后续任务在同一事务提交。
- 聚合读取一致性快照中的完整来源集。提交时检查当前来源版本、配置代次和租约，避免旧结果覆盖新来源。
- ES 使用不可变投影 ID，批量失败不能发布；写入 `refresh=wait_for` 后再确认 MySQL 的当前可用版本。
- 查询对 ES 的 keyword 与 kNN 分支分别过滤，再用 MySQL 复核来源和版本；指定文档子集时只使用子集贡献描述。
- ACL 修改与旧贡献撤回原子提交，异步构建新 scope。成员关系变化从当前数据库读取，旧 JWT/Redis 标签不能作为最终授权依据。
- 数据库文档列表仍需经过相同的精确权限谓词复核，避免 MySQL 排序规则把 `DEFAULT` 与 `default` 等不同标签视为相同，从而与 ES 或指定文档鉴权产生差异。
- 删除与重建产生持久化 `DELETE_CHUNKS` 任务，删除范围限定 documentId 和旧 buildRevision；丢失租约不能删除另一 Worker 发布的同代次索引。
- 同 MD5 文件的上传记录、引用和索引身份独立；最后一个上传者删除时才物理删除共享原文件。

## 启用与回填

先配置正常工作的 MySQL 8、ES 8.10、Kafka、Redis、MinIO，以及现有模型配置中的 LLM 和多模态 Embedding。新流程没有单独的模型账号配置。

```dotenv
KNOWLEDGE_RAG_ENABLED=true
KNOWLEDGE_RAG_INITIALIZE_SCHEMA=false
KNOWLEDGE_CHUNK_INDEX_PREFIX=knowledge_chunks
KNOWLEDGE_GRAPH_INDEX_PREFIX=knowledge_graph
KNOWLEDGE_GRAPH_TOPIC=knowledge-graph-work
KNOWLEDGE_GRAPH_GROUP=knowledge-graph-workers
KNOWLEDGE_TOOL_TIMEOUT_SECONDS=90
KNOWLEDGE_MAX_DECISION_ROUNDS=4
KNOWLEDGE_MAX_TOOL_CALLS=8
```

生产环境先按数据库变更流程执行两份 SQL；开发环境可首次设置 `KNOWLEDGE_RAG_INITIALIZE_SCHEMA=true`，完成建表后关闭。修改 Spring 条件装配开关需重新加载应用；普通 Java 改动仍按仓库惯例优先编译并验证 IDE 热加载。

开启后，新上传和启动知识文档进入多索引链路。历史文档在知识库页面点击“创建索引”，失败或配置变化后可分别重试/重建原文与图谱。未完成回填的文档体现在 coverage.pending，不伪装为完整检索范围。

Embedding 模型、维度、索引前缀或图谱提示配置变化后，需要回填新的构建代次。当前实现不同时调用旧、新 Embedding 提供方：不匹配的向量能力返回 NOT_READY；原文 keyword 可以使用仍匹配来源版本的旧模型索引。首期不提供自动别名切换或自动全库迁移任务。

关闭 `KNOWLEDGE_RAG_ENABLED` 会回到旧查询入口。只构建了新索引的文档若需在旧入口使用，须按旧流程重建；关闭开关不会自动生成旧索引。权限过滤修正不随开关撤回。

## HTTP 接口

前缀 `/api/v1/knowledge`；用户身份来自后端认证，不接受请求体自报 owner。

| 方法与路径 | 用途 |
|---|---|
| `GET /capabilities` | 返回新链路是否开启，关闭时仍可调用。 |
| `GET /documents/{id}/indexes` | 当前授权文档的索引阶段、配置、任务诊断。 |
| `GET /artifacts` | 当前授权范围内的产物与两种索引覆盖度。 |
| `POST /documents/{id}/indexes/backfill` | 所有者准备历史来源快照并提交构建。 |
| `POST /documents/{id}/indexes/rebuild` | 所有者提交 `{"indexTypes":["CHUNK","GRAPH"]}`，返回 202 和 jobIds。 |
| `PUT /documents/{id}/access` | 所有者修改 `orgTag`、`isPublic`；撤回旧范围并调度新范围，目标组织需在当前有效组织内。 |
| `POST /retrieve` | 统一调试检索入口。 |

```json
{"type":"GRAPH","query":"支付服务依赖哪些服务","mode":"hybrid","topK":5,"documentIds":[123]}
```

结果包含 executionStatus、evidence、coverage、artifactVersions、elapsedMillis、diagnostics。状态区分 SUCCESS、EMPTY、PARTIAL、NOT_READY、FAILED、CANCELLED；引用携带 sourceId/documentId/chunkSetId 并保留旧 MD5/页码预览字段。

旧接口 `POST /api/v1/documents/{fileMd5}/reindex` 在新链路开启时返回 HTTP 202、`status=QUEUED`，实际用量暂为 null；同步旧链路仍返回 200。选择同 MD5 文档时优先使用当前用户的上传记录。

## 验证记录

2026-10-24 至 2026-10-25 使用独立 MySQL 库、ES/Kafka/Redis/MinIO 实例及本地确定性模型替身验证。业务数据库和根 `.env` 未修改，未调用真实付费 LLM/Embedding/OCR。

- 47 项相关测试通过：真实 MySQL 来源并发、租约与旧 Worker、失败独立性、权限变化、删除竞态；真实 ES 的过滤、搜索可见性、部分批量失败及限定版本清理；ReAct 多轮/并行、4/8 预算、执行中取消、上下文裁剪、引用、重建接口状态与权限及已有聊天/上传兼容测试。
- API 实测：原文和图谱均返回 SUCCESS；指定文档子集的所有引用只属于该文档；第二用户请求受限文档均返回 403。
- 同 MD5 双用户上传后，删除第二用户记录返回 200；第一用户仍有检索证据，原文件预览返回 200。
- 权限范围变更提交后，旧 scope 残留贡献为 0；两种索引均恢复 READY，buildRevision 增加。
- 组织成员撤销后复用原 JWT：检索从 200 变为 403，原文件预览返回 404；所有者仍能检索和预览。
- 配置指纹升级后，两种旧索引显示 NOT_READY，向量检索返回 NOT_READY，原文 keyword 保留可用来源；提交四份测试文档的重建均返回 202，八个索引恢复 READY，向量检索恢复 SUCCESS。
- 合成双页 PDF 返回两页文本及第 2 页内嵌图片来源；独立 PNG 返回 STANDALONE_IMAGE；原文与图谱均 READY；PDF 单页与内嵌图片预览均 200。
- 浏览器验证：两种工具显示已完成，最终答案及稳定引用写入 MySQL，重新登录可恢复历史回答，来源可打开真实文档；页面重建由“待构建”恢复“已就绪”，控制台无错误。
- 新索引状态组件和知识库页面 ESLint 通过。全量前端 typecheck 仍被已有 `frontend/playwright-kb-column.spec.ts` 缺少 `@playwright/test` 及其派生类型错误阻断。
- 整仓 `mvn test` 的既有模型配置、解析/上传及全上下文环境失败未作为本次全部通过的依据；验收使用上述明确列出的相关测试。

相关测试类位于 `src/test/java/com/yizhaoqi/smartpai/service/knowledge/`、`ChatHandlerReActFlowTest`、`AgentContextServiceTest` 等。真实服务集成测试通过 `GRAPH_TEST_MYSQL_URL`（必须为测试库 paismart_graph_test）与 `KNOWLEDGE_TEST_ES_URL=http://127.0.0.1:19200` 显式开启。

本次相关测试集：

```powershell
$env:GRAPH_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:13306/paismart_graph_test'
$env:KNOWLEDGE_TEST_ES_URL='http://127.0.0.1:19200'
mvn '-Dtest=KnowledgePipelineIntegrationTest,IndexSyncIntegrationTest,EvidenceAssemblerTest,KnowledgeAccessServiceTest,ChatHandlerReActFlowTest,ChatHandlerFinishReasonFlowTest,AgentContextServiceTest,ConversationServiceTest,UploadControllerTest,BootstrapKnowledgeInitializerTest,DocumentControllerReindexTest' test
```

集成测试会清理指定测试库/测试索引，不能指向业务存储。当前 Windows/JDK 17 环境额外使用 JVM 参数 `-Djava.net.preferIPv4Stack=true -Djdk.net.unixdomain.tmpdir=F:/code/java/学习/PaiSmart/target/nonexistent-uds` 避开过长临时路径导致的 JDK selector 初始化错误；参数仅限本次测试进程。

## 当前边界

首期是原文/图片 + 一跳图谱。未实现 PageIndex、时间线、Wiki、模糊别名合并或任意深度图算法。结构化抽取仍是模型解释，回答保留原文依据；本地模型替身验证的是协议与工程链路，真实模型的抽取质量、召回效果、配额及容量需上线前单独评测。

任务/来源快照保留用于追踪与恢复；未增加自动归档/清理历史 MySQL 快照的策略。当前逐文档/逐对象复核优先保证一致性，较大知识库需进一步压测批量回查、状态查询和生成期间复核的成本。

可用以下只读 SQL 观察积压、失败与覆盖：

```sql
SELECT work_type, work_status, COUNT(*) FROM kg_work GROUP BY work_type, work_status;
SELECT published, COUNT(*), MIN(next_attempt_at) FROM kg_outbox GROUP BY published;
SELECT index_type, phase, COUNT(*) FROM knowledge_index_state GROUP BY index_type, phase;
SELECT id, work_type, attempts, last_error FROM kg_work WHERE work_status='FAILED';
```
