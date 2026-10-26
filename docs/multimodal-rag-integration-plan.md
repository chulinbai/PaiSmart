# 多模态 RAG 可复用整改与接入计划

编制日期：2026-10-21。依据 PaiSmart 当前工作区代码编制；目标项目已确认采用相同技术栈，尚未提供目标项目源码。

本文是待实施的整改方案，文中的新模块、接口、配置和表结构均为设计目标。本次只交付计划文档。

## 1. 接入目标与默认方案

默认采用“Spring Boot 模块嵌入目标后端 + Vue 组件接入目标前端 + 独立 FastAPI embedding 服务”。目标后端通过 Java Facade 使用 RAG，也可启用同一实现的 HTTP 接口。第一版无需新增独立 Java 微服务；以后需要独立部署时，增加一个启动模块即可。

接入成功的标准是：目标系统沿用自己的登录、用户、组织、文件入口、聊天会话与历史存储，通过少量适配器完成文档入库、图文检索、图片证据问答、引用预览与异常后短期恢复。

- PDF 文本、PDF 内嵌图片、独立图片继续统一进入多模态 ES 索引，用 `knowledgeType` 区分。
- 文本使用同一个多模态 embedding 模型；图文项必须在同一 input item 中产生一个联合向量。
- PDF 内嵌图片按原文档定位并按需提取，不单独持久化到 MinIO；独立图片保存为对象。
- 保留文本解析和切片的现有效果，抽取存储边界；第一版支持范围以现有文本格式、PDF 提图、独立图片为准。
- 同一索引版本只接受一个 embedding profile，默认 2048 维。模型别名相同不代表向量空间相同。
- 短期恢复继续使用 Redis，默认 30 分钟；它恢复已输出内容和状态，不续接已经中断的模型计算。
- 现有 `knowledge_base` 及其旧数据保持原用途；多模态模块拥有独立的 schema、索引与迁移脚本。

## 2. 当前实现与需要整改的边界

| 当前实现 | 接入影响 | 整改动作 |
| --- | --- | --- |
| `MultiEmbeddingClient` 依赖 `RateLimitService`、`UsageQuotaService`，使用 `multimodal.embedding.*` 配置 | 直接复制会带入 PaiSmart 的配额业务 | 提取 embedding 接口；配额以可选 hook 注入；保留原系统适配器 |
| `EmbeddingClient` 已委托 `MultiEmbeddingClient` | 两个类名容易被误认为两套模型 | 旧类只作兼容入口；新模块只注册一个活动 embedding profile |
| `MultimodalSearchService` 直接查 `UserRepository` 和 `OrgTagCacheService` | 要求目标项目复制用户表和组织模型 | 改为传入经过鉴权的 `RagAccessContext` |
| 检索的 ACL 当前写在文本 query 分支；kNN 构建未携带同一 filter | 向量召回、文本召回必须统一权限边界 | 两条分支都应用范围和 ACL 过滤，并做跨用户回归验证 |
| 检索多个异常分支最终返回空列表 | 调用方无法区分零命中与基础设施故障 | 返回结构化状态、降级标志和错误码 |
| `ParseService.parseAndSave` 直接保存 `DocumentVector` | 文本解析依赖原 `document_vectors` 表 | 提取 `ParsedDocument` 返回值；原方法成为兼容包装 |
| `MultimodalVectorizationService` 从 `DocumentVectorRepository` 读切片 | 新链路仍依赖旧表 | 改为消费解析结果或 RAG item repository |
| `indexFile` 当前先文本、再 PDF 图片；PDF 图片只由 PDFBox 提取 | 不能把历史讨论中的“并行提图”“所有文档提图”当成现状 | 第一版保持调度顺序与语义；其他格式提图单独扩展 |
| item ID、ES 删除和计数使用全局 `fileMd5` | 同文件被多个用户或系统使用时缺少独立身份 | 新增带范围的 `documentId`；hash 只负责内容校验 |
| 独立图片使用硬编码 `uploads` 和 `merged/{md5}` | 无法直接复用目标系统对象路径 | 注入 `DocumentSourcePort`，从规范化对象引用读写 |
| `FileProcessingTask.filePath` 可带预签名 URL，consumer 延迟下载 | 排队后 URL 可能失效 | 消息只传文档/任务 ID，执行时重新解析对象引用 |
| 入库先删旧数据，再写 MySQL 与 ES | 异常可能留下部分数据或让旧数据不可用 | 按索引批次写入；成功后发布，失败保留旧批次 |
| `DeepSeekClient` 同时负责消息、取图、格式转换 | 迁移模型调用时容易遗漏图片证据 | 提取 `EvidenceResolver` 与 `LlmGateway` |
| 图片失败后的文本降级位于 `ChatHandler.streamReActTurnBlocking` | 只搬 `streamResponseWithImages` 不会迁移实际主链路 | 抽取真实 ReAct 编排及其降级分支 |
| ChatHandler 依赖原会话、context、token、WebSocket registry | RAG 功能与整个聊天应用绑定 | 流事件用 DTO 输出；会话与历史通过 host port 提供 |
| Redis recovery 以 conversationId 定位；前端还会查用户全局 active generation | 多项目或多会话需要隔离，恢复可能切错会话 | 加 namespace/tenant/user 范围，前端优先当前会话 |
| Vue 组件依赖原 auth store、proxy、localStorage 和引用接口 | 直接复制页面无法独立运行 | 注入 API、鉴权与预览函数，保留纯展示组件 |
| FastAPI 默认模型目录是 Windows 绝对路径，依赖未锁版本 | 新机器部署不可复现 | 环境变量配置模型路径；交付已验证的环境锁定清单 |
| ES mapping 使用 IK，维度固定 2048；启动包含 DDL 修改逻辑 | 空环境可能启动失败，或影响宿主 schema 管理 | 显式预检和版本化迁移，不在业务启动中偷偷改表 |

当前源码基线为 Java 17、Spring Boot 3.4.2、ES Java client 8.10.0、Tika 2.9.1。PDF 默认配置已包含 LiteParse/OCR 路径，不能只按 Tika 依赖打包。精确版本以发布时的依赖清单为准。

## 3. 代码模块划分

第一版只拆下面三个可发布后端模块；PaiSmart 适配器保留在应用侧，避免为每个存储创建一个 Maven 工程。

| 交付单元 | 内容 | 允许依赖 |
| --- | --- | --- |
| `paismart-rag-api` | Facade、DTO、错误码、宿主扩展接口 | Java 类型、必要的校验注解；不依赖 PaiSmart entity |
| `paismart-rag-core` | 解析与索引编排、检索编排、证据组装、回答流程 | `rag-api`；通过 port 使用基础设施 |
| `paismart-rag-spring-boot-starter` | ES/MySQL/MinIO/Redis/Kafka 实现、HTTP/WS adapter、配置、自动装配 | `rag-core`、明确选择的 Spring 和基础设施依赖 |
| PaiSmart 应用适配层 | 原用户/组织、文件、配额、会话、引用字段映射 | 原应用服务及 `rag-api` |
| `frontend/src/features/rag/` 交付包 | TypeScript API、Pinia 工厂、图片查询/引用预览/回答展示组件 | Vue 3、Pinia、现有 UI 组件库；配置 Vite/自动导入依赖 |
| `tools/qwen3_vl_embedding_server/` | 模型服务及独立 smoke 测试、环境说明 | Python 模型运行环境 |

自动装配只扫描 RAG 自己的 entity/repository/config。共享 DataSource、Redis、MinIO 等通过具名 bean 或宿主已有 bean 注入，不能扫描整个 `com.yizhaoqi.smartpai`。HTTP 与 WebSocket 入口默认关闭，由宿主显式开启或自己实现 controller。

新接入方可以只启用入库和检索，使用自己的 LLM 问答；完整接入时再启用回答、引用与 Redis 恢复。这两种入口使用同一检索和证据模型。

Facade 的第一版调用形状固定如下，HTTP controller 和旧接口都调用这些方法：

| Facade | 主要方法 | 返回内容 |
| --- | --- | --- |
| `RagIngestionFacade` | `ingest(access, command)`、`reindex(access, documentId)`、`delete(access, documentId)`、`job(access, jobId)` | 文档/任务句柄或任务快照 |
| `RagSearchFacade` | `search(access, query)` | hits、检索方式、降级原因、profile |
| `RagAnswerFacade` | `start(access, conversationId, command)`、`subscribe(access, generationId, sink)`、`cancel(access, generationId)` | generation 句柄及有序流事件 |
| `RagEvidenceFacade` | `preview(access, itemId)`、`resolveReference(access, referenceId)` | 授权预览、源文件版本和定位信息 |
| `RagRecoveryFacade` | `snapshot(access, generationId)`、`recover(access, conversationId)` | 短期生成快照或空值 |

`access` 是宿主提供的可信对象。Facade 不接受 WebSocketSession、ServletRequest 或 PaiSmart entity；`sink` 接收统一事件，取消句柄由传输层保管。

## 4. 宿主需要实现的接口

| 接口 | 责任与最低契约 | 默认实现 |
| --- | --- | --- |
| `RagIdentityProvider` | 从当前登录态构建 namespace、tenantId、subjectId、允许访问的知识库和组织 | 宿主必须提供；PaiSmart 有独立适配器 |
| `RagPermissionPolicy` | 校验入库/读/删/改权限；构造召回 ACL；对最终证据再鉴权 | 基于 owner、组织、visibility 的实现，身份来自 host |
| `DocumentSourcePort` | 打开原文件、保存独立图片、返回 preview 对象引用；声明是否由 RAG 管理对象 | MinIO 实现；可包装宿主文件服务 |
| `ConversationPort` | 校验 conversationId 所属、读取有界历史、幂等保存问题/回答/引用/终态 | 完整回答功能必需；检索模式不需要 |
| `UsageHook` | 请求前准入、成功计量、失败释放预留 | 默认不计费；PaiSmart 适配原配额服务 |

内部 port 另有 `EmbeddingGateway`、`LlmGateway`、`RagItemRepository`、`VectorIndex`、`RecoveryStore`、`TaskDispatcher`，由 starter 提供当前技术栈实现。宿主通常不用自己实现这些接口。

`namespace` 来自项目配置，`tenantId/subjectId` 来自服务端登录态；用户 ID 一律使用不透明字符串，支持数字或 UUID。`knowledgeBaseId` 由请求指定并经过宿主授权。客户端不能直接指定自己所属租户、组织标签或绕过 ACL。

共享项目内 `PUBLIC` 默认表示当前 tenant 内公开。所有读写都先限制 namespace、tenant、knowledgeBase，再应用用户/组织/public 规则。权限上下文无法解析时返回鉴权错误，不放宽为匿名查询。

## 5. 数据、对象与引用整改

### 5.1 身份和数据归属

- 引入 `documentId`，在 `(namespace, tenantId, knowledgeBaseId, externalDocumentId)` 范围唯一；`externalDocumentId` 对应宿主文件或业务资料 ID。
- `fileMd5` 保留用于旧字段兼容；新 `contentHash` 使用 SHA-256 校验源文件。相同内容可共享物理对象，但文档身份与 ACL 必须独立。
- 引入 `sourceRevision` 和 `indexGeneration`：前者固定原文件版本，后者标识一次可发布的索引结果。
- `itemId` 由范围、documentId、sourceRevision、indexGeneration、knowledgeType 和 locator 的规范化值计算得到；重试同一任务使用同一 ID。
- 删除、计数、重建和预览均带文档范围；不得继续只用 `fileMd5` 做全局操作。

默认 MySQL 存储使用三张业务表：

| 表 | 保存内容 |
| --- | --- |
| `rag_documents` | 范围、externalDocumentId、文档名、owner/ACL、activeIndexGeneration、删除状态 |
| `rag_ingestion_jobs` | jobId、documentId、sourceRevision、indexGeneration、不可变源对象引用、modelProfile、状态/阶段/计数、attempt/lease、幂等键、错误 |
| `knowledge_multimodal_items` | 沿用三类 item 的统一元数据，补充范围/文档/版本/profile；不保存向量 |

这两张新增表服务于文件入库和重建，不新增会话状态表、检索日志表或完整状态机。宿主已有等价文档/任务表时可以替换 repository 实现，公共契约不变。

使用显式 SQL 迁移包（含从当前表升级与全新安装两条路径），接入方纳入自己的迁移工具；模块启动只验证 schema。默认安装在应用独立 schema，表名固定；不实现运行时任意表名前缀。

### 5.2 Elasticsearch

- 三类 item 仍共用一个多模态逻辑索引；目标项目默认逻辑 alias 为 `{namespace}_knowledge_multimodal`，物理索引带版本。原有同名实体索引先保留，再通过配置切换，不能直接创建同名 alias。
- 增加 `namespace/tenantId/knowledgeBaseId/documentId/sourceRevision/indexGeneration/modelProfileId` 等 keyword 字段。
- 保留 `searchText/vector/knowledgeType` 和文本/图片字段；不适用字段在统一 DTO 中为 null，不写空字符串冒充真实值。
- 默认使用已验证的 2048 维与 cosine；启动时核对 embedding 实测维度、profile 和 mapping。不能修改已有 dense_vector 的维度来复用旧向量。
- 保留 IK 分词配置并显式预检插件。切换其他分词器应通过新索引重建和检索回归，不静默改变分词行为。
- 向量只保存在 ES。ES 丢失后可由 MySQL 元数据及源文件重新 embedding 构建，无法仅从无向量的元数据表原样恢复向量。

### 5.3 图片与文件

| 类型 | 处理及存储 | 预览和引用 |
| --- | --- | --- |
| `TEXT_CHUNK` | 文本切片与同一多模态模型产生的向量 | documentId、sourceRevision、chunkId、pageNumber、anchorText |
| `DOCUMENT_IMAGE` | 从原 PDF 临时提取，图片 + 页文本/标题联合 embedding | documentId、sourceRevision、embeddedImageId、pageNumber、imageSequence |
| `STANDALONE_IMAGE` | 二进制、URL 或 base64 入库统一落到配置 MinIO 或宿主管理对象；描述与图联合 embedding | documentId、itemId、sourceRef；按请求生成可访问 URL |

`imageSequence` 明确定义为页内从 1 开始的提取顺序，不是整篇文档的图片序号，也不是肉眼阅读顺序。当前 PDFBox 资源遍历并不表示“页面上所有视觉元素”；内联图片、矢量图等不直接承诺覆盖。

固化提取器版本和资源遍历规则，并把可复现的资源路径保存为 locator；在相同 sourceRevision 上入库提图与预览提图使用同一实现。旧图片索引先保持旧定位实现，切换定位规则时重建，避免原序号指向另一张图。

持久字段保存 `storageProvider/bucket/objectKey/objectVersion`，预签名 `imageUrl` 只是有有效期的输出。浏览器用 preview resolver；模型端用 model resolver。模型无法访问 MinIO 内网 URL 时，后端读取对象转成 data URL；不依赖字符串中是否包含 localhost 来判断可达性。

PDF 引用保留原文件版本；删除或源文件失效后返回“来源已删除/不可访问”，不能给旧引用绑定新版本。RAG 只删除自己管理的独立图片对象，宿主拥有的源文件由宿主生命周期管理。

## 6. 文档入库整改

1. 通过宿主身份与权限校验，注册文档并接收文件或稳定 `sourceRef`；原 PDF 要能长期按版本读取。
2. `Idempotency-Key` 与范围、内容版本、profile 绑定；同 key 同请求返回已有 jobId，不同请求返回 409。
3. 在 MySQL 事务中写文档及 QUEUED job，然后投递 Kafka。定时补投仍为 QUEUED 的任务，配合消费幂等覆盖“事务提交成功但发送失败”。
4. Kafka payload 只含 schemaVersion、namespace、tenantId、documentId、jobId。执行者从任务记录读取源引用与 ACL，重新取得下载凭证。
5. 消费者以数据库条件更新领取任务 lease；重复投递不重复生效。默认最多 3 次自动尝试，错误分为可重试与永久失败，耗尽进入 FAILED/DLT。
6. 解析函数返回文本切片、页码、锚点；PDF 额外提图。按批执行 embedding，避免一次把全部图片/base64 留在内存。
7. MySQL item 与 ES 写入同一 indexGeneration，核对每批失败项和数量。两端写入不是分布式事务，任务状态记录缺口并允许幂等重试。
8. 只有该批次全部完成且 ES 可查询时，CAS 更新文档 activeIndexGeneration；还要验证该 job 仍是文档的最新请求，防止旧任务晚完成后覆盖新版本。查询最终结果按这个字段批量复核，过滤旧批次和未发布批次。
9. 最终复核可能减少结果数，使用有上限的候选补取；不为了凑满 topK 返回无效证据。旧批次异步清理，历史引用所需源版本按宿主保留策略保留。
10. 删除先写文档删除标记，使查询/预览立即不可用，再异步清理 MySQL item/ES；失败可重试。处理中的任务发布前检查删除标记和版本，避免重新写回已删除文档。

任务主状态使用 `QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED`，阶段使用 `FETCH/PARSE/EXTRACT_IMAGES/EMBED/INDEX/PUBLISH`。进度展示文本数、图片数、完成数；总数未知时不伪造百分比。达到图片提取上限需返回 warning 和实际数量。

## 7. Embedding 与检索整改

### 7.1 Embedding 契约

- Java 核心输入为 `EmbeddingItem(text?, image?)`；图像支持授权对象引用、HTTP(S) URL、data URL。
- 对外支持标准 `data:image/...;base64,...`；裸 base64 必须附 contentType，再由入口统一转换。限制输入字节、解码像素、batch 和超时。
- 对外接口不接收调用方机器的本地文件路径。FastAPI 的本地路径功能可留作受限部署/开发用途。
- 一次请求可含多个 item，但每个 item 第一版最多一张图片。`{text,image}` 返回一个向量，严禁拆成两次 embedding 或平均两条向量。
- Java client 校验返回 index、数量、维度、数值有限性及规范化策略；不可因响应乱序把图片向量配给另一条 item。
- profile 至少固定模型权重版本、维度、normalization、instruction、图片预处理版本；现有 `multimodal:{model}:{dimension}` 字符串不足以唯一标识这些内容。
- profile 在创建 job 时冻结。相同逻辑索引的入库和 query 统一使用它；修改模型/profile 通过新索引全量重建再切 alias。
- 把配额调用替换为 `UsageHook`。FastAPI 当前 usage 是估算值，对外注明 estimated，不当作 provider 精确 token 账单。

### 7.2 检索契约

输入为 `{knowledgeBaseId, text?, image?, topK}`，text 和 image 至少一个。文本 + 图片仍构造一个联合 query vector。topK 默认 10，范围 1..50。

正常返回 `retrievalMode=MULTIMODAL_HYBRID`；embedding 暂时失败且有文本时允许带权限的 `MULTIMODAL_TEXT_ONLY`，同时返回 `degraded=true` 和明确原因。只有图片且 embedding 失败返回错误，不能返回“成功但零命中”。

同时失败的 ES 主检索/后备检索应暴露 `RETRIEVAL_UNAVAILABLE`。401/403、参数错误和配额拒绝不得被吞成文本降级。身份、知识库及 item 权限必须同时作用于 kNN 和文本召回；最终证据、预览、模型取图都再次校验。

第一版保留当前排名参数作为基线，不在模块迁移中同时更换排序算法。用固定文本/图片/图文 query 集验证提取前后结果；修正权限导致的结果变化单独记录。

## 8. 回答、引用与模型兼容

把当前 `ChatHandler -> AgentToolRegistry -> MultimodalSearchService -> DeepSeekClient 构图 -> LlmProviderRouter.streamReActTurn` 的实际链路整理成 transport 无关的 `RagAnswerFacade`。WebSocket 只负责传输事件和取消，模型工具循环由 core 执行。

- 提供直接 RAG 回答和宿主已有 Agent 的 `search_knowledge` 工具适配；工具仅调用统一检索 Facade。
- `EvidenceResolver` 把候选 item 转成授权的文本和图片证据，默认最多 4 张图片；图片读取失败保留该项文本并记录 warning。
- `LlmGateway` 明确能力：text、user image、tool-result image、tool calls、streaming。以实际 provider/model 的协议测试确认，不根据模型名称猜测。
- 保留当前图片属于 tool result 的事实。支持时在对应 tool message 中发送图片；不支持 tool 图片时使用其文本上下文，并标记降级。
- 维持 assistant tool_calls 与各 tool result 的配对和顺序。不得为了多模态而伪造额外用户输入。
- 图像格式/能力错误或可恢复请求失败，在本回合尚未向用户输出新增内容、且未取消时，最多重试一次文本模式。取消、鉴权、配额错误直接终止；有新增输出后失败保留部分正文，不自动重放。
- 引用结构独立于回答正文，至少保存 referenceId、itemId、documentId、版本、knowledgeType、页码/图片 locator、短证据文本。模型输出的任意文件 ID 不能直接变成有效引用。
- `ConversationPort` 按 requestId/generationId 幂等保存问题、回答和引用；宿主历史加载只靠长期记录即可预览，不依赖 Redis 仍存在。
- 原 `AgentRun/ContextEvent/ContextSnapshot` 作为 PaiSmart 的 context 适配实现；其他项目可提供有界历史，不必迁入整个上下文管理数据库。

这里的模型能力整改是接口隔离与验收设计，不宣称任一具体 DeepSeek/provider 已支持 tool-result 图片。

## 9. 对外接口与事件协议

默认前缀 `/api/v1/rag`。宿主的 Spring Security 负责登录鉴权；RAG controller 只消费可信身份，不定义另一套账号体系。

| 接口 | 语义 |
| --- | --- |
| `POST /documents` | multipart 文件 + 元数据；202 返回 documentId、jobId |
| `POST /documents/import` | 导入宿主 sourceRef 或授权 URL/base64 图片；202 返回任务 |
| `GET /jobs/{jobId}` | 处理阶段、计数、警告和错误，必须鉴权 |
| `POST /jobs/{jobId}/retry` | FAILED job 显式重试，返回新 attempt/当前任务状态 |
| `POST /documents/{documentId}/reindex` | 固定源版本重新索引，202 返回新 job |
| `DELETE /documents/{documentId}` | 立即逻辑不可见，异步清理，返回任务信息 |
| `POST /search` | 文本、图片或联合检索 |
| `GET /items/{itemId}/preview` | 鉴权后返回临时 URL 或图片数据及定位信息 |
| `GET /references/{referenceId}` | 长期引用解析，读取时重新鉴权 |
| `POST /conversations/{conversationId}/generations` | 校验宿主会话，创建问答，202 返回 generationId |
| `GET /generations/{generationId}` | 获取当前/终态快照 |
| `POST /generations/{generationId}/cancel` | 幂等取消 |
| `GET /conversations/{conversationId}/recovery` | 当前用户在该会话的短期异常/生成快照，过期为 null |
| `WS /ws` | subscribe/unsubscribe 指定 generationId，消费生成事件；握手复用宿主鉴权 |

接口只对已经启用的能力注册。配置、健康探针和任务管理权限沿用宿主运维入口。HTTP 使用正确状态码；响应保留 `code/message/data` 形状并增加 requestId。

联合检索示例：

```json
{
  "knowledgeBaseId": "kb_product",
  "text": "找到这张架构图对应的部署说明",
  "image": {"type": "url", "url": "https://files.example.com/signed-image.png"},
  "topK": 10
}
```

图片的另一种输入为 `{"type":"data_url","dataUrl":"data:image/png;base64,..."}`；持久图片入库还可附 `description`。用户身份、tenant 和 org 不在此 JSON 里由浏览器自行声明。

检索响应的 data 为 `hits/retrievalMode/degraded/warnings/modelProfileId`；hit 带三类 item 字段、分数和稳定引用信息。HTTP 接口不返回向量，不返回 embedding/LLM 凭证。

统一流事件：

```json
{
  "protocolVersion": 1,
  "type": "chunk",
  "conversationId": "conv_123",
  "generationId": "gen_456",
  "seq": 8,
  "timestamp": "2026-10-21T08:00:00Z",
  "data": {"text": "检索到的架构图显示"}
}
```

事件种类为 `start/phase/tool_call/references/chunk/degraded/completion/error/cancelled`。seq 在单 generation 内单调递增；前端丢弃重复或旧序号。终态只接受一次。completion 携带最终正文摘要信息、references、finishReason 和 persistenceDegraded。

新接口优先采用请求/任务显式 ID。旧 `/search/multimodal`、上传接口、聊天 WS、preview 接口保留薄适配器，映射到 Facade，避免原前端与新项目必须同一天切换协议。

## 10. 短期恢复机制的迁移

保持轻量 Redis 方案，恢复范围从裸 conversationId 扩展到 namespace + tenantId + subjectId + conversationId。默认每个会话只允许一个活动 generation，并发新提交返回 409；不限制用户打开多个会话。

- snapshot 保存 question、已输出 content、引用、状态、最后 seq、updatedAt、错误类别和保存结果；不放完整 PDF、图片 base64 或可执行工具上下文。
- 使用同一会话范围的 Redis key 保存 snapshot 与 latest/active 索引；统一 30 分钟 TTL，活动期间更新，终态后开始最后一段保留窗口，读取不续期。
- 用原子条件更新处理“当前 generation 仍是自己才续期/清理”。旧回调不能覆盖新任务索引，CANCELLED/FAILED/COMPLETED 不能被迟到 chunk 改回 STREAMING。
- 修正现有先读后删及无条件 touch 索引的竞争边界；跨多 key Lua 操作使用一致的会话 hash tag，支持 Redis Cluster 时不跨 slot。
- 前端重连和切换会话只恢复目标会话，禁止用户全局 active generation 把另一会话内容追加进当前列表；恢复按 generationId 和 seq 去重。
- 保存轻量 worker heartbeat：15 秒续期、90 秒失联阈值为初始默认。heartbeat 独立于模型 token；失联后标记 INTERRUPTED，保留快照，避免永久显示 STREAMING。
- 正常完成并持久化成功、用户主动取消后清理 conversation recovery 指针；generation 快照保留 TTL。MySQL 保存失败时保留正文及 persistenceDegraded，允许幂等重试保存，不重新请求模型。
- 订阅时先绑定连接并缓冲新增事件，再读取快照及 lastSeq；只发送大于快照序号的事件，防止断线恢复时漏字或重复。

异常重试创建新的 generationId，复用原 conversationId，并记录 retryOf。默认用户显式重试，不自动重做有副作用的工具，也不承诺模型从原 token 继续计算。TTL 到期或 Redis 丢失后，仅使用宿主长期历史。

第一版延续单实例聊天 worker 的部署方式。接口保留 ownerInstance 字段；多实例流转发与跨实例取消属于后续部署扩展，不把本机 WebSocketSession/Future 存入 Redis。

## 11. 前端接入整改

把网络请求与组件展示分开：组件接收 `RagApiClient`、当前 conversationId、权限能力和 preview resolver，禁止内部写死 `/proxy-default`、JWT 地址或 `useAuthStore`。

1. 提供上传/导入与任务状态展示，区分“文件上传完成”和“可以被检索”。目标系统已有上传页时只接状态组件。
2. 搜索框接收文本、图片文件、MinIO URL；文件转标准 data URL 后与文本一起提交一个 query。不能把两种查询分别请求后合并冒充联合检索。
3. 结果按 TEXT_CHUNK、DOCUMENT_IMAGE、STANDALONE_IMAGE 显示；PDF 图片支持图片预览、原文件及页码/页内序号定位。
4. 引用通过稳定 referenceId 解析。URL 过期后重新解析；历史引用不只依赖当前浏览器 localStorage。
5. 流式状态在按 conversationId 索引的 Pinia state 内维护，校验 generationId/seq；切会话后的旧请求不能覆盖新会话列表。
6. exception 显示单独的错误状态，保留已生成正文；提供重试、停止及短期恢复反馈。降级标志能够区分“图片参与回答”和“仅文本上下文”。
7. 导出组件依赖清单，包括现有 Naive UI、图标、Markdown、PDF viewer、自动导入与样式变量。先提供可直接迁入的 feature 目录，稳定后再发布 npm 包。

## 12. 配置与运行环境交付

统一新增 `rag.*` 配置前缀，通过 `RagProperties` 校验。PaiSmart 兼容层接收原 `MULTIMODAL_*` 配置并转换；若新旧都填且冲突，报出冲突项，避免悄悄连接另一套模型。

`.env` 继续作为正式配置来源。当前项目通过 `DotenvEnvironmentPostProcessor` 加载，必须将等价加载能力一同交付；目标已有加载器时不重复注册。部署进程环境变量/显式启动参数按宿主 Spring 配置优先级处理，`.env` 作为本地配置，YAML 提供默认值。以集成测试固定覆盖顺序。

下列为待新增配置示例，不能直接当作当前已支持配置使用：

```yaml
rag:
  enabled: true
  namespace: ${RAG_NAMESPACE:target-system}
  web:
    enabled: ${RAG_WEB_ENABLED:true}
  embedding:
    endpoint: ${RAG_EMBEDDING_ENDPOINT:http://127.0.0.1:11435/v1/embeddings}
    model: ${RAG_EMBEDDING_MODEL:MedAIBase/Qwen3-VL-Embedding:2b}
    api-key: ${RAG_EMBEDDING_API_KEY:}
    dimension: ${RAG_EMBEDDING_DIMENSION:2048}
    profile-id: ${RAG_EMBEDDING_PROFILE_ID}
    batch-size: ${RAG_EMBEDDING_BATCH_SIZE:4}
    timeout: ${RAG_EMBEDDING_TIMEOUT:300s}
  elasticsearch:
    alias: ${RAG_ES_ALIAS:target-system_knowledge_multimodal}
  storage:
    bucket: ${RAG_MINIO_BUCKET:rag}
    object-prefix: ${RAG_OBJECT_PREFIX:target-system/}
  tasks:
    topic: ${RAG_TASK_TOPIC:target-system-rag-ingestion}
    group: ${RAG_TASK_GROUP:target-system-rag-worker}
    max-attempts: ${RAG_TASK_MAX_ATTEMPTS:3}
  recovery:
    ttl: ${RAG_RECOVERY_TTL:30m}
    heartbeat: ${RAG_RECOVERY_HEARTBEAT:15s}
    stale-after: ${RAG_RECOVERY_STALE_AFTER:90s}
  answer:
    max-images: ${RAG_ANSWER_MAX_IMAGES:4}
    image-fallback-enabled: ${RAG_IMAGE_FALLBACK_ENABLED:true}
```

基础设施连接由宿主现有 Spring/MinIO/ES bean 管理；RAG 配置只描述自己的资源名称和行为。避免全量复制原 `.env` 把数据库、JWT、支付或配额配置带到新项目。

FastAPI 继续独立 `app.py`，smoke 测试独立文件。交付 `.env.example`、启动脚本和以下清单：

- 实际验证的 Python、torch/CUDA wheel、transformers、qwen-vl-utils、Pillow 等版本锁；当前未锁的 requirements 只能作为依赖目录。
- `MODEL_PATH/MODEL_ID/MODEL_DEVICE/MODEL_DTYPE` 配置与模型权重/官方 wrapper 的版本或校验值；移除部署对 F 盘默认目录的依赖。
- 区分存活和就绪：模型未加载时 readiness 返回不可用，而不是仅根据 /health HTTP 200 判断就绪；拒绝不匹配 model/profile。
- 单 GPU 初始采用一个加载模型的 worker、有界等待队列和明确超载响应，避免多个 worker 重复占用显存；压测后再调整。
- 明确三个网络视角：Java 能访问 embedding，embedding 能访问取图源，浏览器能访问 preview。容器的 localhost 不作为宿主地址。
- 外部图片 URL 只从配置允许的源读取；允许目标 MinIO 的内网域名，校验重定向目标、大小和 MIME。base64/URL 在一处规范化，日志不输出凭证或整个图片正文。
- LiteParse CLI、OCR 数据文件/可选云 OCR、PDFBox 依赖、IK 插件、MinIO bucket 和 Kafka topic/DLT 均纳入预检清单。

## 13. 数据迁移与切换

默认交付可复用能力，目标项目使用自己的资料重新入库；是否迁移 PaiSmart 现有知识数据是独立的部署选项。

需要迁移既有资料时，按以下顺序执行：

1. 导出文档/item 元数据、owner/组织映射、原始对象清单、模型/profile/提取器版本；不导出 Redis 临时状态或账户凭证。
2. 目标系统提供旧用户/组织到新用户/组织的映射；未映射项隔离待处理，不能默认变为公开。
3. 迁移或授权访问原 PDF 和独立图片对象，核对 contentHash；PDF 内嵌图片仍不另建对象。
4. 将旧 `fileMd5` 身份映射到带范围 documentId。出现相同 md5 不同所有者时创建独立逻辑文档，禁止互相覆盖。
5. profile、维度、预处理、解析版本均有证据一致时才允许复制 ES 向量；信息不全时重新解析/embedding。
6. 在新物理索引执行批量构建，核对每文档的文本/图片数及可回查 locator；灰度比对旧新检索与引用。
7. 按项目/知识库切读取配置或 alias；旧索引保留回滚窗口。回滚检索实现不能回滚删除/权限变更，新旧路径必须应用同一当前授权。
8. 成功后冻结旧写入并安排清理。切换窗口暂停导入或记录增量同步，避免迁移期间新文件遗漏。

历史聊天若同时迁移，持久引用也要转换为新 documentId/版本/referenceId；不能只搬回答文字。目标业务会话表的结构由宿主负责。

## 14. 分阶段实施与交付

| 阶段 | 任务 | 交付与放行条件 | 估算人日 |
| --- | --- | --- | --- |
| P0 基线与契约 | 固定样本、运行版本、SPI/API/DTO、配置和身份映射 | OpenAPI/事件协议、依赖清单；目标 owner 确认适配字段 | 1–2 |
| P1 模块抽取 | 三个模块、Facade、port、PaiSmart 适配器、自动装配 | sample-host 不含 PaiSmart 用户/支付/会话 entity 也能启动检索；原入口回归 | 3–5 |
| P2 入库与检索 | 文档身份、对象引用、解析输出、幂等任务、索引发布、统一 ACL | 文本/PDF 图片/独立图片通路；删除、重试、同 hash 隔离通过 | 4–6 |
| P3 回答与恢复 | EvidenceResolver、provider 能力、流事件、历史 port、Redis 竞争修正 | 图文证据传递/降级/取消/恢复/引用持久化通过 | 3–4 |
| P4 前端与目标接入 | RAG feature 目录、目标适配器、预览/流式/鉴权联调 | 真实页面、网络返回、目标 DB/ES/MinIO/Redis 核验 | 4–6 |
| P5 灰度与交付 | 迁移演练、性能/故障回归、发布包与回滚 | 第二个系统独立部署并完成验收清单 | 2–3 |

合计约 17–26 人日，为单名熟悉代码的工程师的工作量估算；目标现有文件/权限/聊天接口差异与数据规模会改变工期。阶段按依赖顺序实施，P2/P3 的接口稳定后前端可并行。

第一批整改优先解决身份与存储隔离、模型一致性、Facade 和错误契约。组件打包、性能调优在功能链路验证后进行，防止先发布一个仍依赖 PaiSmart 全量业务的 starter。

## 15. 验收清单

| 场景 | 必须证明的结果 |
| --- | --- |
| 空白宿主接入 | 只依赖 RAG 模块、宿主适配器和基础设施即可启动，无 PaiSmart User/支付/Token 表依赖 |
| 文本/PDF/独立图片 | 三类 item 共用多模态索引；不适用字段为 null；图片和说明联合向量数量正确 |
| 图文 query | 只生成一个联合 query vector；URL 与 base64 输入等价可用 |
| 维度/profile 错误 | 明确拒绝写入/查询，不混用旧模型或默默切第二个 embedding |
| 同内容不同用户/租户/知识库 | 入库、检索、重建、删除、计数及预览互不越界 |
| 权限更新 | kNN、文本降级、证据取图、引用历史均不能看到已撤销内容 |
| PDF 定位 | 页码从 1 开始；页内图序号与按需预览一致；原版本引用不漂移 |
| 存储行为 | PDF 内嵌图不新增 MinIO 持久对象；独立图片保留稳定 sourceRef |
| Kafka 重复/延迟/中断 | 幂等重试不增加重复 item；不依赖已过期预签名 URL；任务可补投 |
| MySQL/ES 部分失败 | 未完成批次不返回；旧发布批次仍可用；失败项能够重试并对账 |
| 图片传给 LLM | stub 捕获真实请求中的工具图片块；真实目标 provider 单独完成兼容 smoke |
| 图片降级 | 同回合无新增输出且满足条件才重试一次；文本和引用保留；取消不触发降级 |
| 流中断/重连 | snapshot 内容完整到 lastSeq；终态不倒退；无重复问答/重复片段 |
| 同用户多会话 | A 会话异常，B 会话正常；重连/切换不会把 A 的内容插入 B |
| Redis TTL/进程失联 | 到期恢复返回 null；正常长等待靠 heartbeat 保活；失联显示中断而非永久生成 |
| 历史引用 | 30 分钟后仍能通过宿主持久记录定位；无权限或源被删除有明确结果 |
| 配置与部署 | `.env`/YAML/进程变量优先级测试通过；无硬编码 bucket/端口/盘符/原 proxy |
| 旧项目兼容 | 旧上传/搜索/聊天/引用入口仍能通过适配器工作，旧 knowledge_base 不被新模块清理 |

单元测试聚焦权限、ID、状态竞争和降级条件；集成测试覆盖真实 MySQL/Redis/ES/MinIO/Kafka 的隔离和故障。模型调用测试使用可记录请求的 stub，另安排真实 GPU embedding 和目标 LLM 的最小 smoke，避免把外部模型波动当成单元测试失败。

性能记录 PDF 大小/页数/图数、embedding 批耗时、索引耗时、检索 P50/P95、首字延迟、内存/显存。先与当前固定样本基线比较，再确定目标 SLA，不在尚未压测时承诺吞吐数字。

## 16. 给目标项目的最终接入包

- 版本化 Maven artifacts、依赖/BOM 对齐说明和可启动的 `sample-host`。
- 五个宿主接口的示例适配器；检索模式只要求身份、权限、文件三个，完整聊天增加 ConversationPort，UsageHook 可用默认实现。
- OpenAPI、WS 事件 schema、错误码、Java/TypeScript DTO、URL/base64/图文请求样例。
- MySQL 全新安装/升级 SQL、ES mapping/alias 脚本、初始化与预检脚本。
- `.env.example`、YAML 配置、model profile 清单、FastAPI 环境锁文件和独立 smoke 脚本。
- Vue feature 包、组件依赖说明和宿主 API client 示例。
- 固定样本验收集、同 MD5 跨用户用例、故障恢复测试、迁移核对清单与回滚步骤。

目标项目接入顺序：对齐依赖版本 -> 添加 starter -> 实现 host ports -> 配置 namespace/资源 -> 应用 schema/索引 -> 验证模型就绪 -> 接文档入口 -> 接检索/回答接口 -> 接前端引用和恢复 -> 执行验收。第一份集成必须在真实的第二个系统或不引用 PaiSmart 业务代码的 sample-host 中通过，才算完成“可复用”。

## 17. 当前证据与本次边界

本计划依据 `MultiEmbeddingClient`、`EmbeddingClient`、`MultimodalVectorizationService`、`MultimodalSearchService`、`MultimodalElasticsearchService`、`PdfImageExtractionService`、`ParseService`、`FileProcessingConsumer`、`ChatHandler`、`DeepSeekClient`、`ChatGenerationStateService`、相关 controller/model/config、Vue chat store/预览组件及 FastAPI 源码核对。

本次端口检查未发现 9527、8081、11435 及常用本地基础设施端口的 TCP 记录，未做当前部署端到端验证，也未读取目标项目源码。这里列出的“当前实现”是源码事实，“整改动作”是待实施工作；本次未修改 Java/Python/Vue 业务代码，未执行数据库或索引迁移。
