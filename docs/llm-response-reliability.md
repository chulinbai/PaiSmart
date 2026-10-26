# 模型响应校验与异常恢复：实现及验收

本轮实现覆盖聊天/ReAct、图谱抽取、图谱描述聚合、关键词抽取。MySQL 保存执行事实和最终回答；Redis 保存额度原子操作回执及终态恢复副本。没有引入外部 LightRAG 服务。

## 实现入口

| 入口 | 责任 |
| --- | --- |
| `LlmResult` / `LlmResponseAccumulator` / `LlmResponseValidator` | 统一 SSE 与非流式解析，区分正文和 reasoning、未知 usage 和实际零值，校验结束标记、工具参数与结束原因 |
| `LlmCallContext` / `LlmAttemptService` | 固定逻辑调用身份与 Provider，发送前持久化 attempt，申请共享恢复预算，保存观测用量及中断诊断 |
| `AgentContextService.executeRecordedTool` | 工具执行记录先于执行；结果和 `TOOL_RESULT` 同事务；复用成功结果；拒绝身份/参数冲突及不确定操作重放 |
| `ChatHandler` | 四轮决策、八次工具上限；仅两种检索并行；取消上游订阅及工具 Future；冻结前正文、引用使用同一门锁 |
| `ChatRunFinalizer` | 冻结完整意图，第一笔事务写 FINALIZING，第二笔事务写回答、引用、上下文及终态；提交后才发布终态 |
| `LlmUsageSettlementService` / `llm/*.lua` | 冻结用量、模式、窗口、版本及指纹；原子处理全部额度组件；MySQL 结算确认和每日统计同事务 |
| `GraphExtractionService` / `GraphStore` | 先区分截断与结构错误，再决定是否缓存；合法空 facts 可以缓存；最多五次 claim，包括第一次 |
| `chat-generation.ts` 及聊天组件 | 按 sequence 接收正文，按终态完整快照校正，忽略重复终态和迟到正文；显示保存延迟、中断和取消状态 |

正常流式响应需要 Provider 结束原因及 `[DONE]`。usage-only 事件合法，但不刷新进展时钟。reasoning 和工具参数新增算进展，reasoning 不当作回答。损坏的工具 JSON、同轮重复/缺失工具 ID 不进入工具执行。

空响应、协议中断、临时网络/资源故障只在尚无可见正文及副作用时重试；重试、正常 length 的一次续写、明确不支持图片时的降级，共享每 generation 两次额外准入。Provider 不自动切换。模型/工具工作使用 120 秒单调截止时间，模型首个有效增量及无进展超时均为 30 秒。底层 HTTP 自动重试关闭。

## 持久化约束

- `llm_request_attempt` 在 HTTP 前创建，记录 PREPARED/SENDING/RECEIVING/FINISHED/INTERRUPTED，以及独立的结算状态。Provider 快照不保存 API key、URL 用户信息或查询参数。保存原始 usage、估算依据、冻结结果和迟到 usage 审计值。
- SENDING 表示本地已准入，不能证明 Provider 已收到请求。进入后不退回恢复预算；旧 attempt 不重发。
- `agent_tool_execution` 的唯一身份为 generation + logical call + tool call。检索缓存命中也经过执行记录。EXECUTING 在重启后保留并标记 OUTCOME_UNKNOWN，不能回到 PREPARED。
- 回填前校验检索权限与版本。工具结果及来源正文可恢复，显示引用通过来源标识定位原文。
- `agent_runs.finalization_intent` 保存完整回答、引用、token、序号、目标状态及诊断。数据库异常先回查；Redis 副本采用同 token、同快照幂等写入且不设置 TTL。确认 MySQL 最终提交后删除副本。
- 第二笔终态事务同时保存问答、引用、上下文、运行状态及会话更新时间/默认标题；`conversations.generation_id` 唯一约束防重。
- 内存中已经冻结但尚未持久化的结果仍显示 FINALIZING；不能通过普通清理回调改写成 FAILED。保存重试不受模型的 120 秒截止时间限制。
- 启动恢复先核对意图，再将无可恢复意图的旧运行标为 RECOVERABLE；不自动续跑旧聊天。未完成回答不进入后续正常模型历史或压缩摘要。复制会话不复用 generation ID。

## 额度政策与运行维护

| 模式 | 语义 |
| --- | --- |
| DIRECT_DEBIT | 沿用现有余额准入检查，随后按冻结用量扣一次；不同请求可能合计扣成负余额 |
| RESERVE_ADJUST | 所有额度组件一起检查并预留 R，按 A−R 调整；实际消费超过预留时允许已用额度超过上限，后续准入拒绝 |
| 可以证明未发送 | 释放已有预留，不扣余额 |
| SENDING/RECEIVING 遗留 | 不假设未发送，按已保存的请求估算和已观测输出估算，明确标记 ESTIMATED |

同 attempt 的回执必须同时匹配版本、冻结指纹和金额。预留参数冲突、应存在的回执丢失或损坏均进入 RECONCILE_REQUIRED；已 SETTLED 的事实不回退，不再扣款。迟到 usage 只留审计记录。原窗口的预留不能调整新窗口。Lua 在写入前检查全部计数器的类型、整数范围及参数，防止脚本中途报错留下部分扣减。

补偿和审计任务可重复执行。待结算任务每 10 秒检查；已结算回执每分钟分批核对（每批最多 100 条、核对间隔至少 5 分钟）。终态补偿按 1、2、5、10、30 秒退避，之后每分钟尝试。超过 30 秒显示保存延迟，超过 5 分钟记录告警及 `llm.finalization.delayed` 指标。结算对账异常记录 `llm.settlement.reconcile` 指标。指标不附加 generation/attempt 等高基数标签。

数据库事务设置 5 秒超时；默认数据库连接/网络读超时、Redis 命令/连接超时为 5 秒，可通过以下环境变量调整：

```text
DB_CONNECTION_TIMEOUT_MS=5000
DB_CONNECT_TIMEOUT_MS=5000
DB_SOCKET_TIMEOUT_MS=5000
SPRING_DATA_REDIS_TIMEOUT=5s
SPRING_DATA_REDIS_CONNECT_TIMEOUT=5s
```

单次底层操作的超时不等于整轮补偿的总超时：一次补偿可能包含原调用、回查及恢复副本操作。禁止把等待超时当作事务未提交。

结算回执首版不自动删除，Redis 必须使用持久化、备份并避免淘汰回执。此实现拒绝启用 Redis Cluster 配置，不能降级成逐项扣减。发现 RECONCILE_REQUIRED 时，应对照 Provider 请求记录、MySQL attempt 和 Redis 回执人工核对；不要删除回执后盲目重试收费，也不要修改已经冻结的金额来触发第二次扣减。

## 数据库迁移

增量脚本：`src/main/resources/db/llm-reliability.sql`。它依赖上一阶段的 `agent_runs`、上下文事件和会话表已存在。

生产环境先备份并应用增量脚本，再部署服务；建议使用 `ddl-auto=validate`。脚本的 ALTER 只执行一次。开发环境 `ddl-auto=update` 已自动增加字段时，不要重复运行 ALTER。本轮只在隔离测试库验证，未修改业务库或根目录 `.env`。

新增/扩展内容包括终态意图字段、可空且唯一的回答 generation ID、响应状态和元数据、attempt 账本、工具执行日志。旧回答身份不回填，旧余额账本不重建。

## 验收与复现

使用本地可控 Provider `src/test/resources/llm-reliability-fixture.mjs`，没有向外部模型发送测试数据。真实依赖使用隔离的 MySQL 13306、Redis 16379、ES 19200；集成测试 Redis DB 8，页面运行 Redis DB 7。

后端测试包括：协议异常、usage-only/零 usage、损坏工具参数、发送前持久化、取消与截止时间、共享预算竞争、MySQL/Redis 结算故障、并发终态恢复、工具结果保存失败禁止重放、图谱五次 claim/租约争用、结构错误不缓存、合法空 facts 缓存及关键词回退。故障测试直接检查数据库、Redis 变化及执行次数。

```powershell
$env:LLM_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:13306/paismart_llm_reliability_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai'
$env:LLM_TEST_REDIS_PORT='16379'
$env:GRAPH_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:13306/paismart_graph_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai'
$env:KNOWLEDGE_TEST_ES_URL='http://127.0.0.1:19200'
mvn -q '-Dtest=Llm*,AgentContextServiceTest,ChatHandler*,ConversationServiceTest,UsageQuotaServiceTest,ChatSessionRegistryTest,*Graph*Test,Knowledge*Test,IndexSyncIntegrationTest' '-DargLine=-Djava.net.preferIPv4Stack=true -Djdk.net.unixdomain.tmpdir=F:/nonexistent-paismart-uds' test
```

测试数据库必须是独立测试库；图谱测试会清理该测试库内的图谱表。运行时验收脚本会检查当前 Provider 确实是本地 fixture：

```powershell
$env:PAISMART_ISOLATED_RUNTIME='1'
node src/test/resources/llm-reliability-runtime-test.mjs
cd frontend
node tests/chat-generation.test.mjs
```

实时验收结果保存于 `target/llm-runtime-verification.json`，数据库/Redis 核对记录位于 `target/llm-runtime-database-audit.tsv` 和 `target/llm-runtime-redis-audit.json`。实际浏览器验证了正常回答、部分正文、停止生成、刷新后的历史、重新生成新身份及引用原文预览；截图位于 `target/llm-partial-browser.png`。

全量仓库回归曾执行 162 项，出现 4 个失败、8 个错误，涉及默认 Provider 断言、LiteParse 命令、上传缓存断言和 Spring mock ServletContext 缺少 WebSocket ServerContainer；不能声称全量通过。前端 typecheck 仍被既有 `playwright-kb-column.spec.ts` 缺失 `@playwright/test` 及其类型错误阻断。新增事件顺序测试和本轮定向 lint 通过。具体分批通过数量及最终实时验收见相邻验收结果文件。

## 明确边界

不承诺外部 HTTP 恰好执行一次。若 MySQL 与 Redis 都未确认终态意图而进程崩溃，无法保证恢复尚在内存的完整回答。首次未观测输出不会伪装为实际 usage，也不会用输出预留上限代替消费。保留单活聊天执行、单实例 Redis 的部署边界，不提供多活聊天接管。旧摘要/压缩的专用恢复策略、Embedding/OCR 协议未扩展。fixture 验收证明状态与一致性协议，不代表真实模型的回答质量或所有供应商的协议兼容性。
