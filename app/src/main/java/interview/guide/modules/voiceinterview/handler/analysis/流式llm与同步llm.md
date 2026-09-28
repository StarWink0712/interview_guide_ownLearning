# 流式调用LLM vs 同步调用LLM 详细分析

## 一、基本概念对比

### 1.1 同步调用LLM

**同步调用**（Synchronous Call）是指客户端发送请求后，**等待LLM完全生成所有回复**后才能收到完整响应。就像打电话时，对方必须说完一句话你才能听到完整内容。

### 1.2 流式调用LLM

**流式调用**（Streaming Call）是指客户端发送请求后，**LLM逐字/逐句生成回复**，客户端可以实时接收到中间结果。就像视频通话时，对方说话时你能实时听到，不需要等对方说完才能听到。

---

## 二、在DashscopeLlmService中的实现

### 2.1 同步调用实现 - chat()

```java
public String chat(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
    try {
        // 1. 构建提示词上下文
        PromptContext promptContext = buildPromptContext(userInput, session, conversationHistory);
        
        // 2. 获取ChatClient
        ChatClient chatClient = llmProviderRegistry.getVoiceChatClient(provider);
        
        // 3. 同步调用 - 等待完整回复
        ChatClient.CallResponseSpec response = chatClient.prompt()
            .system(promptContext.systemPrompt())
            .user(promptContext.userPrompt())
            .call();  // 🔥 阻塞等待LLM完全输出
        
        // 4. 获取完整回复
        String content = response.chatResponse().getResult().getOutput().getText();
        String optimized = optimizeForVoice(content);
        
        return optimized;
    } catch (Exception e) {
        log.error("LLM chat error for session {}: {}", session.getId(), e.getMessage(), e);
        return mapLlmErrorToUserMessage(e);
    }
}
```

**关键特点**：
- **阻塞等待**：`.call()` 方法会阻塞直到LLM完全生成回复
- **一次性获取**：只能得到完整的 `content`，无法看到中间过程
- **简单直接**：代码逻辑简单，易于理解和维护

### 2.2 流式调用实现 - chatStreamSentences()

```java
public String chatStreamSentences(String userInput,
                                  Consumer<String> onToken,
                                  Consumer<String> onSentence,
                                  VoiceInterviewSessionEntity session,
                                  List<String> conversationHistory) {
    try {
        // 1. 构建提示词上下文
        PromptContext promptContext = buildPromptContext(userInput, session, conversationHistory);
        
        // 2. 获取ChatClient
        ChatClient chatClient = llmProviderRegistry.getVoiceChatClient(provider);
        
        // 3. 流式调用 - 逐字/逐句生成
        chatClient.prompt()
            .system(promptContext.systemPrompt())
            .user(promptContext.userPrompt())
            .stream()  // 🔥 启用流式模式
            .content() // 🔥 获取流式内容
            .doOnNext(token -> {
                // 🔥 每收到一个token就执行回调
                if (token == null || token.isEmpty()) {
                    return;
                }
                raw.append(token);  // 追加到原始文本
                
                // 5.1 检测句子边界，回调 onSentence 触发并发 TTS
                if (onSentence != null && hasTerminalSince(token)) {
                    String normalized = normalizeRealtimeText(raw.toString());
                    int currentEnd = normalized.length();
                    if (currentEnd > lastSentenceEnd.get()) {
                        String sentence = normalized.substring(lastSentenceEnd.get()).trim();
                        if (!sentence.isEmpty()) {
                            onSentence.accept(sentence);  // 🔥 触发TTS
                        }
                        lastSentenceEnd.set(currentEnd);
                    }
                }
                
                // 5.2 实时文本推送（按配置的间隔和字符增量）
                if (onToken == null) {
                    return;
                }
                long now = System.nanoTime();
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - lastEmitNanos.get());
                int currentLength = raw.length();
                boolean shouldEmit = elapsedMs >= emitIntervalMs 
                                    && currentLength - lastEmitLength.get() >= minCharsDelta;
                if (!shouldEmit) {
                    return;
                }
                String normalized = normalizeRealtimeText(raw.toString());
                if (normalized.isBlank()) {
                    return;
                }
                onToken.accept(normalized);  // 🔥 推送实时文本给前端
                lastEmitNanos.set(now);
                lastEmitLength.set(normalized.length());
            })
            .blockLast();  // 🔥 阻塞直到流式输出结束
        
        // 6. 处理最后一段和优化
        String optimized = optimizeForVoice(raw.toString());
        if (onToken != null && !optimized.isBlank()) {
            onToken.accept(optimized);
        }
        
        return optimized;
    } catch (Exception e) {
        log.error("LLM sentence stream error for session {}: {}", session.getId(), e.getMessage(), e);
        return mapLlmErrorToUserMessage(e);
    }
}
```

**关键特点**：
- **实时回调**：`.stream().content()` 启用流式模式，`.doOnNext(token)` 每收到一个token就执行回调
- **逐字/逐句处理**：可以实时处理中间结果，不等待完整回复
- **复杂逻辑**：需要处理流式数据的累积和回调逻辑

---

## 三、核心差异对比

### 3.1 调用方式对比

| 特性 | 同步调用 | 流式调用 |
|------|---------|---------|
| **调用方法** | `.call()` | `.stream().content()` |
| **等待方式** | 阻塞等待完整回复 | 阻塞等待流结束，但可实时处理中间结果 |
| **数据获取** | 一次性获取完整内容 | 逐个获取token，累积成完整内容 |
| **回调机制** | 无回调 | `.doOnNext(token)` 回调 |
| **代码复杂度** | 简单 | 复杂 |

### 3.2 时间线对比

#### 3.2.1 同步调用时间线

```
用户输入 → 发送请求 → [等待5-10秒] → 获取完整回复 → TTS合成 → 播放音频
总延迟：5-10秒（等待LLM） + 2-3秒（TTS） = 7-13秒
```

#### 3.2.2 流式调用时间线

```
用户输入 → 发送请求 → [等待1-2秒] → 首个token → 实时文本推送 → 首个句子 → TTS合成 → 播放音频
总延迟：1-2秒（首token） + 0.5秒（TTS） = 1.5-2.5秒
```

### 3.3 实际效果对比

#### 3.3.1 同步调用效果

```java
// 同步调用示例
String userInput = "介绍一下Spring的事务传播机制";

// 发送请求
ChatClient.CallResponseSpec response = chatClient.prompt()
    .system("你是一位Java面试官")
    .user(userInput)
    .call();  // 🔥 阻塞等待5-10秒

// 5-10秒后，一次性获取完整回复
String content = response.chatResponse().getResult().getOutput().getText();
// "Spring的事务传播机制主要有7种类型：REQUIRED、REQUIRES_NEW、..."
```

**用户体验**：
- ❌ 长时间等待（5-10秒）
- ❌ 无法看到AI正在思考的过程
- ❌ 首音频延迟高（7-13秒）

#### 3.3.2 流式调用效果

```java
// 流式调用示例
String userInput = "介绍一下Spring的事务传播机制";

// 发送请求
chatClient.prompt()
    .system("你是一位Java面试官")
    .user(userInput)
    .stream()
    .content()
    .doOnNext(token -> {
        // 🔥 1-2秒后开始收到token
        // "Spring" → "Spring的" → "Spring的事务" → "Spring的事务传播" → ...
        
        // 实时文本推送
        onToken.accept(normalizedText);  // 前端实时显示
        
        // 句子检测和TTS触发
        if (hasTerminalSince(token)) {
            onSentence.accept(sentence);  // 触发TTS
        }
    })
    .blockLast();  // 🔥 阻塞直到流结束
```

**用户体验**：
- ✅ 实时反馈（1-2秒后看到首字）
- ✅ 可以看到AI正在思考的过程
- ✅ 首音频延迟低（1.5-2.5秒）

---

## 四、在语音面试中的应用场景

### 4.1 流式调用的优势场景

#### 4.1.1 语音面试（当前项目使用）

```java
// 语音面试使用流式调用
llmService.chatStreamSentences(
    userInput,
    onToken -> {
        // 实时文本推送到前端
        sendTextMessage(session, partialText);
    },
    onSentence -> {
        // 每检测到一个完整句子就触发TTS
        CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
            return ttsService.synthesize(sentence);
        }, voicePipelineExecutor);
    },
    session,
    conversationHistory
);
```

**优势**：
1. **低延迟**：首音频延迟从7-13秒降低到1.5-2.5秒
2. **实时性**：用户可以看到AI正在生成回复的过程
3. **并发TTS**：句子级并发TTS，LLM生成与TTS合成并行执行

#### 4.1.2 实时聊天应用

```java
// 实时聊天应用
chatClient.prompt()
    .system("你是一位客服")
    .user(message)
    .stream()
    .content()
    .doOnNext(token -> {
        // 实时显示回复内容
        chatArea.append(token);
        // 自动滚动到最新内容
        chatArea.scrollTop = chatArea.scrollHeight;
    })
    .blockLast();
```

**优势**：
1. **用户体验好**：无需等待完整回复
2. **感知速度快**：用户感觉AI响应很快
3. **减少焦虑**：实时反馈让用户感觉系统在正常工作

### 4.2 同步调用的优势场景

#### 4.2.1 文档生成

```java
// 文档生成使用同步调用
String document = chatClient.prompt()
    .system("你是一位技术文档专家")
    .user("生成Spring Boot的完整使用指南")
    .call()
    .chatResponse()
    .getResult()
    .getOutput()
    .getText();
// 一次性获取完整文档，便于保存和处理
```

**优势**：
1. **数据完整性**：一次性获取完整内容，便于后续处理
2. **错误处理简单**：要么成功获取完整内容，要么失败重试
3. **代码简单**：不需要处理流式数据的累积逻辑

#### 4.2.2 数据分析

```java
// 数据分析使用同步调用
AnalysisResult result = chatClient.prompt()
    .system("你是一位数据分析师")
    .user("分析这个季度的销售数据")
    .call()
    .chatResponse()
    .getResult()
    .getOutput()
    .getText();
// 一次性获取完整的分析报告
```

**优势**：
1. **结果稳定**：确保获取完整分析结果
2. **处理方便**：可以直接对完整结果进行解析和处理
3. **避免部分数据**：不会出现分析不完整的情况

---

## 五、技术实现细节

### 5.1 流式调用的内部机制

#### 5.1.1 Token累积和回调

```java
StringBuilder raw = new StringBuilder();
AtomicLong lastEmitNanos = new AtomicLong(System.nanoTime());
AtomicInteger lastEmitLength = new AtomicInteger(0);

chatClient.prompt()
    .system(systemPrompt)
    .user(userPrompt)
    .stream()
    .content()
    .doOnNext(token -> {
        if (token == null || token.isEmpty()) {
            return;
        }
        
        // 1. 追加到原始文本
        raw.append(token);
        
        // 2. 检测句子边界
        if (onSentence != null && hasTerminalSince(token)) {
            String normalized = normalizeRealtimeText(raw.toString());
            int currentEnd = normalized.length();
            if (currentEnd > lastSentenceEnd.get()) {
                String sentence = normalized.substring(lastSentenceEnd.get()).trim();
                if (!sentence.isEmpty()) {
                    onSentence.accept(sentence);
                }
                lastSentenceEnd.set(currentEnd);
            }
        }
        
        // 3. 实时文本推送（频率控制）
        if (onToken == null) {
            return;
        }
        long now = System.nanoTime();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - lastEmitNanos.get());
        int currentLength = raw.length();
        boolean shouldEmit = elapsedMs >= emitIntervalMs 
                            && currentLength - lastEmitLength.get() >= minCharsDelta;
        if (!shouldEmit) {
            return;
        }
        String normalized = normalizeRealtimeText(raw.toString());
        if (normalized.isBlank()) {
            return;
        }
        onToken.accept(normalized);
        lastEmitNanos.set(now);
        lastEmitLength.set(normalized.length());
    })
    .blockLast();  // 阻塞直到流结束
```

#### 5.1.2 频率控制机制

```java
// 配置参数
int emitIntervalMs = Math.max(80, voiceInterviewProperties.getAiStreamPushIntervalMs());
int minCharsDelta = Math.max(4, voiceInterviewProperties.getAiStreamMinCharsDelta());

// 推送条件
long now = System.nanoTime();
long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - lastEmitNanos.get());
int currentLength = raw.length();
boolean shouldEmit = elapsedMs >= emitIntervalMs 
                    && currentLength - lastEmitLength.get() >= minCharsDelta;
```

**频率控制的目的**：
- **避免高频推送**：防止WebSocket消息过多导致前端卡顿
- **保证实时性**：80毫秒的最小间隔确保用户感知到实时性
- **最小增量**：4字符的最小增量避免推送无意义的内容

### 5.2 句子边界检测

```java
// 检测token是否包含终止标点
private boolean hasTerminalSince(String token) {
    for (int i = 0; i < token.length(); i++) {
        if (TERMINAL_PUNCTUATION.indexOf(token.charAt(i)) >= 0) {
            return true;
        }
    }
    return false;
}

// 终止标点符号
private static final String TERMINAL_PUNCTUATION = "。！？；!?.";
```

**句子检测的作用**：
- **TTS触发**：每个完整句子触发一次TTS合成
- **用户体验**：AI每说一个完整句子就播放一次，更自然
- **并发控制**：通过 `ttsSemaphore` 控制并发TTS数量

---

## 六、性能对比分析

### 6.1 延迟对比

| 调用方式 | 首token延迟 | 首音频延迟 | 完整回复延迟 |
|---------|-----------|-----------|-------------|
| 同步调用 | 5-10秒 | 7-13秒 | 5-10秒 |
| 流式调用 | 1-2秒 | 1.5-2.5秒 | 5-10秒 |

### 6.2 用户体验对比

| 维度 | 同步调用 | 流式调用 |
|------|---------|---------|
| **感知速度** | 慢（5-10秒等待） | 快（1-2秒看到首字） |
| **实时反馈** | 无 | 有（实时显示生成过程） |
| **交互体验** | 卡顿 | 流畅 |
| **焦虑感** | 高（长时间等待） | 低（实时反馈） |

### 6.3 系统资源对比

| 维度 | 同步调用 | 流式调用 |
|------|---------|---------|
| **内存使用** | 低（一次性处理） | 高（累积流数据） |
| **网络带宽** | 低（一次传输） | 高（多次传输） |
| **CPU使用** | 集中处理 | 分散处理 |
| **并发能力** | 低（阻塞等待） | 高（可同时处理多个流） |

---

## 七、最佳实践建议

### 7.1 选择流式调用的场景

```java
// ✅ 适合流式调用的场景
if (needRealTimeFeedback || 
    requireLowLatency || 
    enableConcurrentTTS || 
    userInteractionType == "chat") {
    // 使用流式调用
    return chatStreamSentences(userInput, onToken, onSentence, session, history);
}
```

**适用场景**：
- 实时聊天应用
- 语音交互系统
- 需要实时反馈的场景
- 用户体验要求高的应用

### 7.2 选择同步调用的场景

```java
// ✅ 适合同步调用的场景
if (requireCompleteData || 
    isDocumentGeneration || 
    isDataAnalysis || 
    userInteractionType == "batch") {
    // 使用同步调用
    return chat(userInput, session, history);
}
```

**适用场景**：
- 文档生成
- 数据分析
- 批量处理
- 需要完整结果的场景

### 7.3 混合使用策略

```java
// 根据回复长度动态选择
if (userInput.length() > 100 || expectedLongResponse) {
    // 长回复使用流式调用
    return chatStreamSentences(userInput, onToken, onSentence, session, history);
} else {
    // 短回复使用同步调用
    return chat(userInput, session, history);
}
```

---

## 八、总结

### 8.1 核心区别

| 特性 | 同步调用 | 流式调用 |
|------|---------|---------|
| **等待方式** | 等待完整回复 | 实时处理中间结果 |
| **延迟** | 高（5-10秒） | 低（1-2秒首token） |
| **用户体验** | 卡顿 | 流畅 |
| **适用场景** | 批处理、文档生成 | 实时交互、语音聊天 |

### 8.2 在语音面试中的价值

**流式调用**在语音面试中发挥了关键作用：
1. **降低首音频延迟**：从7-13秒降低到1.5-2.5秒
2. **实时文本推送**：用户可以看到AI正在生成回复的过程
3. **句子级TTS**：LLM生成与TTS合成并行执行，提升效率
4. **更好的用户体验**：实时反馈让对话更加自然流畅

### 8.3 技术趋势

随着AI应用的发展，**流式调用**正在成为主流：
- **实时性要求**：用户对响应速度的要求越来越高
- **交互体验**：实时反馈成为现代应用的标准
- **技术成熟**：流式API的稳定性和可靠性不断提升

同步调用仍然在特定场景中发挥作用，两种方式各有优势，应根据具体需求选择合适的调用方式。