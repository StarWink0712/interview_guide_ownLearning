# 开场问题与 TTS

## 1. 开场问题是在什么时候触发的？

**回答：** 前后端 WebSocket 握手成功后，`afterConnectionEstablished()` 在启动 ASR、发送欢迎消息之后调用 `triggerOpeningQuestionIfNeeded(sessionId, safeSession)`。该方法放到虚拟线程池中异步执行，不阻塞 WebSocket 建连流程，也不妨碍 ASR 启动。

## 2. 系统如何保证重连时不重复发送开场问题？

**回答：** 异步任务先检查 WebSocket 会话是否存在且仍然打开，再通过 sessionId 查询 `voice_interview_messages` 历史消息。如果已经有历史消息，说明是暂停恢复或网络重连，方法直接返回；只有首次连接且历史为空时才继续查询会话实体并生成开场问题。

## 3. 开场问题模板是怎么选择的？

**回答：** `buildOpeningQuestion()` 读取会话的 `skillId` 和 `VoiceInterviewProperties.opening` 配置。首先查 `skillQuestions` Map 是否有该技能的专属模板；未命中时判断 skillId 是否在 `algorithmSkills` 列表中，是则使用算法方向默认问题；否则使用后端方向默认问题。算法和后端默认问题都优先读取配置，没有配置时使用硬编码兜底，后端默认问题也是最终兜底。

## 4. 为什么开场问题采用“文本先行、音频随后”？

**回答：** 拿到开场问题文本后，系统先调用 `sendTextMessage`，让前端立即展示问题，再调用 `saveMessage(sessionId, null, aiReply)` 将 AI 消息保存到数据库。随后才获取或合成 WAV 音频并发送。这样即使 TTS 合成需要时间，用户也能先看到题目；如果音频为空或连接已经关闭，只记录日志，不影响已经发送的文本。

## 5. 开场问题音频缓存是怎么设计的？

**回答：** `openingAudioCache` 使用 `ConcurrentHashMap`，以完整的开场问题文本为 Key、WAV 字节数组为 Value。`getOpeningWavAudio(aiReply)` 先查缓存，命中就直接返回；未命中才实时调用 TTS，把 PCM 转成 WAV 后回填缓存。这样相同模板的后续会话可以直接复用音频。

## 6. 为什么还要在应用启动时预热开场音频？

**回答：** `@PostConstruct` 触发 `warmupOpeningAudioCache()`，再由虚拟线程异步收集 `skillQuestions.values()`、算法默认问题和后端默认问题，逐个完成 TTS、PCM 转 WAV 并写入缓存。预热不阻塞应用启动，同时让用户第一次创建相应技能的语音面试时也能直接命中缓存，减少开场等待时间。

## 7. QwenTtsService.synthesize 的完整流程是什么？

**回答：** Spring 启动后，`@PostConstruct` 先校验配置中的 API Key。调用 `synthesize(text)` 时先校验文本，再创建 `CountDownLatch` 和音频容器；随后构建模型、URL、API Key 等连接参数和 WebSocket 回调，创建 TTS 客户端，配置音色、模式、语言、语速和音量，调用 `appendText` 发送文本并通过 `commit` 提交。服务端返回的 PCM 分片不断追加到容器，完成或报错时将 latch 减一，调用线程最多等待 30 秒，最后取得完整 PCM 数据。

## 8. TTS 为什么要用 CountDownLatch 把异步回调转成同步等待？

**回答：** Handler 调用 `synthesize()` 后必须拿到完整音频，才能继续转换 WAV 并发送前端，因此这是“一次请求、等待完整结果”的调用。TTS 底层虽然通过 WebSocket 异步返回音频分片，但业务方法不能立即返回空数组，所以用 `CountDownLatch` 等待完成回调，最长 30 秒；完成和错误事件都会释放等待，避免线程一直阻塞。

## 9. 为什么 TTS 返回的 PCM 还要手动转换为 WAV？

**回答：** TTS 回调中的音频容器保存的是原始 PCM 分片，浏览器不能直接把它当作完整音频文件播放。Handler 的 `convertPcmToWav` 按 24kHz、16bit、单声道参数构造 44 字节 WAV 文件头，包括 RIFF 头、fmt 块和 data 块，各整数按小端序写入，再把 PCM 数据拼到文件头之后，得到浏览器可以直接播放的 WAV 字节数组。

## 10. WAV 音频是怎么通过 WebSocket 发给前端的？

**回答：** `sendAudio()` 先把 WAV 字节数组编码成 Base64，再与对应文本一起包装成 JSON，最后用 WebSocket 文本帧发送给前端。前端解析 JSON、Base64 解码音频，并通过 `AudioContext` 播放。

