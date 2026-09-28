# RAG、异步任务与语音指标

## 1. 模块基础讲解

三个 Recorder 对应三类业务流水线：

### RAG

- Query Rewrite：Timer + Counter，标签 enabled/status。
- Retrieval：Timer + hitCount Summary + noResult Counter，标签 rewriteEnabled/topK/status。
- Answer：Counter + Timer，标签 streaming/status。

### Redis Stream 异步任务

- `created`：生产者成功提交任务的事件数。
- `processing`：消费者开始处理次数，重试会重复增加。
- `retry`：重试事件数。
- `completed`：成功完成事件数。
- `failed`：最终失败事件数。
- 标签为 taskType 和 stream。

### 语音面试

`VoiceInterviewWebSocketHandler` 记录 ASR final/merge wait、LLM first token/总时长、TTS 时长、空音频、
完整 turn 成功/失败和错误阶段。Dashboard 只读取 `app.voice.interview.turn.duration`。

当前语音 turn 从触发 LLM 回复开始，到 LLM、TTS、消息保存和音频发送完成，基本属于 AI 回答阶段的
端到端耗时，不包含用户说话和 ASR 识别前半段。

## 2. 基础面试问题

**问题：RAG 为什么分阶段记录？**

回答：总延迟高时需要判断是查询改写、向量检索还是答案生成慢；零命中也可能是检索参数或知识库问题，
不是 LLM 生成问题。阶段指标提供可定位性。

**问题：异步任务为什么同时需要数据库状态和 Counter？**

回答：数据库状态能查询具体任务和恢复，Counter 适合吞吐/失败率趋势与告警。前者是业务事实，后者
是观测投影，不能替代任务状态机。

**问题：语音为什么看 first token latency？**

回答：实时交互的体感主要受首响应影响，总生成时间相同的情况下，流式首 token 越快用户越不觉得
卡顿。还应测首音频延迟，才真正反映“听到 AI 开口”的等待。

**问题：noResult 和 retrieval failure 有什么区别？**

回答：noResult 是检索成功但没有满足阈值的文档；failure 是检索过程异常。当前 noResult Counter 在
hitCount<=0 时都会增加，即便 success=false，查询时必须按 status 过滤，最好代码上只在成功时计零命中。

## 3. 进阶面试问题

**问题：异步 `processing` Counter 能表示当前正在处理数吗？**

回答：不能，它是累计开始次数，重试也会增加。当前并发数应使用 LongTaskTimer、Gauge 或队列 PEL；
积压量应从 Stream lag/pending 或数据库 PENDING 状态获取。

**问题：如何从 Counter 计算任务失败率？**

回答：在同一时间窗口用 `rate(failed)/rate(created)` 或按最终结果事件计算。由于 created 和 failed
存在处理时延，短窗口分母可能错位，更稳妥是 completed+failed 作为终态分母，并观察队列 lag。

**问题：语音 Timer 查询为什么可能只拿到 failure 或 success 一条？**

回答：实际记录时带 `status=success/failure`，Registry 中是两个 Timer。`find(name).timer()` 不负责按
标签加权合并，只返回一个匹配项；Dashboard 的均值不具备确定全局语义。应显式取 success 标签，或
按 count/totalTime 对所有匹配 Meter 加权。

**问题：TTS duration 的语义一致吗？**

回答：流式模式的计时从“开始等待 futures”算，不一定覆盖每个 TTS 从提交到完成的完整耗时；并行
合成时它更像收集等待时长。非流式则覆盖一次 synthesize。相同指标名混合了不同测量边界，应拆成
sentence synthesis、batch wait 和 first audio latency。

**问题：RAG hitCount Summary 为什么不能衡量召回率？**

回答：召回率需要知道相关文档全集和命中的相关文档数，线上通常没有 ground truth。hitCount 只是
返回数量，最多反映阈值和 topK 行为；质量需要离线评测或用户反馈。

## 4. 技术延伸问题

**问题：如何监控 Redis Stream 消费健康？**

回答：除了业务 Counter，还要监控 stream length、consumer group lag、pending 数、最老 pending age、
消费者存活、处理 P95、重试率和死信率。lag 与 oldest age 更能反映用户等待。

**问题：如何建立语音体验 SLI？**

回答：连接成功率、ASR final 延迟、提交到首 token、提交到首音频、完整 turn P95、音频空结果率、
WebSocket 异常断开率。按网络区域、模型和流式模式切片，但不要使用 sessionId 标签。

**问题：RAG 质量可以如何在线评估？**

回答：记录最高/最低/平均 score、引用覆盖、用户点赞、追问率和无答案率；抽样用 LLM judge 需单独标注
成本和偏差。离线评测集计算 Recall@K、MRR、NDCG 和答案 groundedness。

## 5. 改进方案问题

**问题：如何统一三个 Recorder 的设计？**

回答：建立指标目录和专用类型，不允许任意字符串；统一 status、stage、mode 标签；Timer 使用 Sample；
配置 SLO histogram；监控自身异常；所有记录器共享低基数校验和 MeterFilter。

**问题：Dashboard 应增加哪些卡片？**

回答：异步终态失败率和 oldest lag、RAG 零命中率与检索 P95、AI 429/超时率、语音首音频 P95 和 turn
成功率。总数应配趋势，不要只给一个累计数。

**问题：如何修复语音平均值？**

回答：短期按 `status=success` 精确查 Timer，并返回 sample count/进程启动时间；生产方案是 Prometheus
采集所有实例，在 24 小时窗口用 rate(totalTime)/rate(count) 聚合，返回 P95 和 scope=cluster。

**问题：如何修复 noResult 指标？**

回答：只有 `success && hitCount==0` 才增加 noResult；失败单独 error Counter；负 hitCount 不应静默
修成 0，而应记录 instrumentation error 并拒绝污染业务指标。

## 6. 故障处理问题

**问题：异步 created 持续增长但 completed 不增长，怎么查？**

回答：检查 Stream lag、consumer 是否存活、PEL oldest、processing/retry Counter 和数据库状态；再查
Redis 连接、消费者异常与下游 AI/存储。不要仅用 created-completed 估积压，因为重试和失败会影响。

**问题：RAG 查询量正常但 noResult 暴涨，怎么查？**

回答：先过滤 success=true，检查知识库是否完成向量化、Embedding 模型/维度是否切换、minScore/topK
配置、查询改写质量和 pgvector 数据量；抽样 trace 查看实际问题与命中文档。

**问题：语音 turn 延迟显示 0ms，怎么查？**

回答：区分无流量、无 Registry、Timer 名不匹配和真实 0。当前缺 Actuator/MeterRegistry 自动配置时
Recorder 会 no-op；补依赖后确认 Handler 确实走到 turn success/failure 记录点。

**问题：流式模式 TTS 指标看起来更快但用户仍觉得慢，为什么？**

回答：当前 Timer 可能只测 futures 收集阶段，不是提交到首音频；前端播放缓冲、WebSocket 发送、音频
编码也未计入。应增加 end-to-end first audible audio 指标并从前端上报体验时间。
