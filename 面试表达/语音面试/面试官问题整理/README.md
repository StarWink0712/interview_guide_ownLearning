# 语音面试模块面试问题索引

本目录根据上一级目录中的八篇语音面试笔记整理。原始笔记保持不变；分类文件按照面试官可能追问的问题逐题组织答案，答案只重组原笔记中的信息。

## 一、会话创建与 WebSocket 握手

- 创建语音面试会话时，前端需要传入哪些参数？
- 创建会话时为什么要传入简历、JD、技能和难度？
- 会话的初始阶段是怎么确定的？
- 创建会话后，数据库和 Redis 分别保存什么？
- 创建会话接口为什么要返回 webSocketUrl？
- WebSocket 端点是怎么注册的？
- 浏览器建立 WebSocket 连接时，底层握手过程是什么？
- afterConnectionEstablished 在什么时候执行，主要做什么？
- 为什么单条 WebSocket 文本消息限制为 256KB？
- 为什么要使用 ConcurrentWebSocketSessionDecorator？
- Handler 中为什么维护三个 ConcurrentHashMap？

对应文件：[01-会话创建与WebSocket握手.md](01-会话创建与WebSocket握手.md)

## 二、ASR 实时语音识别

- 前端、服务端和 ASR 之间的语音识别链路是什么？
- startTranscription 为什么要传入三个回调？
- 为什么每个 ASR 会话都要有一把监视器锁？
- startTranscriptionLocked 如何避免重复启动 ASR？
- 为什么 ASR 连接要使用 AtomicReference 保存引用？
- 服务端与 DashScope ASR 建立连接时配置了什么？
- DashScope 推送事件后是怎么路由的？
- ASR 的中间结果和最终结果分别怎么处理？
- 为什么不能把每个 onFinal 结果直接提交给 LLM？
- ASR 为什么不需要像 TTS 一样使用 CountDownLatch？

对应文件：[02-ASR实时语音识别.md](02-ASR实时语音识别.md)

## 三、开场问题与 TTS

- 开场问题是在什么时候触发的？
- 系统如何保证重连时不重复发送开场问题？
- 开场问题模板是怎么选择的？
- 为什么开场问题采用“文本先行、音频随后”？
- 开场问题音频缓存是怎么设计的？
- 为什么还要在应用启动时预热开场音频？
- QwenTtsService.synthesize 的完整流程是什么？
- TTS 为什么要用 CountDownLatch 把异步回调转成同步等待？
- 为什么 TTS 返回的 PCM 还要手动转换为 WAV？
- WAV 音频是怎么通过 WebSocket 发给前端的？

对应文件：[03-开场问题与TTS.md](03-开场问题与TTS.md)

## 四、实时问答与并发控制

- afterConnectionEstablished 执行结束后，系统如何继续处理面试？
- handleTextMessage 为什么是面试过程的总入口？
- 用户音频是怎么发送给 ASR 的？
- 为什么 AI 播放语音时要丢弃用户音频？
- mergeBuffer 在实时问答中解决什么问题？
- control 消息支持哪些操作？
- 用户点击提交时，为什么要使用 AtomicBoolean 的 CAS 锁？
- 为什么 LLM + TTS 管线要放在虚拟线程中？
- LLM 的 Prompt 包含哪些上下文？
- LLM 的回复为什么采用流式推送？
- 一轮用户回答的完整处理流程是什么？
- 用户消息和 AI 消息分别在什么时候落库？

对应文件：[04-实时问答与并发控制.md](04-实时问答与并发控制.md)

## 五、暂停恢复与会话结束

- 语音面试支持哪几种暂停与恢复方式？
- 用户主动暂停的完整流程是什么？
- 主动暂停后为什么要删除 Redis 缓存？
- 用户主动恢复的完整流程是什么？
- 恢复连接后为什么不会重新问开场问题？
- 自动暂停是怎么检测的？
- 超时暂停会执行哪些清理操作？
- 主动暂停和超时暂停最大的区别是什么？
- 会话结束有哪些触发方式？
- endSession 的核心流程是什么？
- WebSocket 异常关闭后如何清理资源？
- endSessionIfInProgress 如何防止重复结束和重复评估？

对应文件：[05-暂停恢复与会话结束.md](05-暂停恢复与会话结束.md)

## 六、Redis Stream 异步评估

- 语音面试结束后为什么异步生成评估报告？
- 评估任务是如何投递和消费的？
- 消费者如何避免重复评估，失败后怎么处理？
- generateEvaluation 的整体流程是什么？
- 用户一句话没说就结束时，系统如何处理？
- 语音对话消息如何转换成 QaRecord？
- 问题分类是怎么推断的？
- 评估时如何选择模型和技能参考信息？
- 为什么 LLM 评估调用必须放在事务外？
- 评估结果为什么使用 JSON 字段保存？
- 前端如何获取评估进度和结果？
- 手动重新评估如何保证幂等？

对应文件：[06-Redis-Stream异步评估.md](06-Redis-Stream异步评估.md)
