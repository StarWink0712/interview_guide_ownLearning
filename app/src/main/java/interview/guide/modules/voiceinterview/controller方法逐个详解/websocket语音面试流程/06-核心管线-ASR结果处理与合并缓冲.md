# 核心管线第二步：ASR 结果处理与合并缓冲

## 概述

ASR 识别完成后，Handler 的 `handleSttResult()` 方法接收 DashScope 返回的文本并进行处理。这里有一个关键的设计决策：ASR 的最终定稿文本**不会自动送入 LLM**，而是暂存到 `SessionState` 的合并缓冲区（`mergeBuffer`）中，等待用户手动点击"提交"按钮后才触发 LLM 回复。这个设计权衡了自动化的便利性和识别的准确性——自动触发虽减少操作步骤，但识别错误时无法修正；在面试场景中回答内容的准确性至关重要，因此选择了手动提交的交互模式。

## 两种识别结果的处理路径

`handleSttResult()` 接收三个参数，根据 `isFinalSegment` 的值走两条完全不同的处理路径：

```java
private void handleSttResult(String sessionId, String recognizedText, boolean isFinalSegment) {
    WebSocketSession session = sessions.get(sessionId);
    SessionState state = sessionStates.get(sessionId);
    if (session == null || state == null) return;

    if (!isFinalSegment) {
        // 路径A：部分识别结果 → 实时字幕
        //     用户还在说话时 ASR 持续返回中间文本，直接推送到前端显示
        state.markSttActivity();
        sendSubtitle(session, recognizedText, false);
        return;
    }

    // 路径B：最终定稿（VAD 400ms 静默后断句的结果）
    //     追加到合并缓冲区，等待用户手动提交
    log.debug("STT final segment for session {}: {}", sessionId, recognizedText);
    state.appendFinalSttSegment(recognizedText);
    // 推送合并缓冲区的预览文本作为最终字幕（isFinal:true 提示用户已定稿）
    sendSubtitle(session, state.getMergeBufferPreview(), false);
}
```

两条路径的职责分工很清晰。路径 A 负责"实时性"——用户说话时看到字幕变化，知道系统在听。路径 B 负责"准确性"——VAD 断句后的完整文本进入缓冲区暂存。两条路径都调用 `markSttActivity()` 更新时间戳——这个时间戳用于后续的暂停超时检测，`checkPauseTimeout()` 定时任务检查的就是这个时间戳。

---

## mergeBuffer 合并缓冲区的设计

合并缓冲区解决了一个实际问题——用户可能分多次 VAD 断句说话。典型场景：用户先说"我主要使用 Spring Boot"，停顿超过 400ms 触发 VAD 断句产生第一条最终结果；接着又说"构建微服务，对 MyBatis 也有深入理解"，再次断句产生第二条最终结果。两条结果分属两次 VAD 断句，但属于同一轮对话。`mergeBuffer` 将多条 ASR 定稿文本按到达顺序累积拼接，用户提交时将缓冲区中全部内容一次性送入 LLM。

合并缓冲区的实现基于 `AtomicReference<String>`：

```java
// SessionState 中的四个合并缓冲相关方法

// 追加 STT 最终定稿片段。使用 updateAndGet 原子操作保证线程安全——
// 即使 ASR 的 onFinal 回调从不同线程并发调用，文本也不会丢失或乱序。
void appendFinalSttSegment(String segment) {
    String s = segment == null ? "" : segment.trim();
    if (s.isEmpty()) return;

    mergeBuffer.updateAndGet(prev -> {
        if (prev == null || prev.isEmpty()) {
            mergeStartedAt.set(System.currentTimeMillis());  // 记录合并开始时间
            return s;
        }
        return prev + s;  // 累积拼接，不添加分隔符
    });
    markSttActivity();
}

// 获取合并缓冲区的当前预览文本，用于实时字幕
String getMergeBufferPreview() {
    String s = mergeBuffer.get();
    return s == null ? "" : s;
}

// 取出全部累积文本并清空缓冲区——在用户提交时调用
// getAndSet("") 是原子操作，保证取出和清空在一步完成
String takeMergeBufferAndClear() {
    mergeStartedAt.set(0);
    return mergeBuffer.getAndSet("");
}

// 标记 STT 活动（更新最近活动时间），供暂停超时检测使用
void markSttActivity() {
    lastSttActivityAt.set(System.currentTimeMillis());
}
```

`mergeStartedAt` 是一个辅助时间戳，记录缓冲区第一次有内容的时间。它用于额外的最长等待补充判定——如果用户第一次断句后过了很长时间（例如 30 秒）还没有点击提交，系统可以自动触发提交（当前版本未实现此功能，但已预留了时间戳）。

---

## 字幕消息

`sendSubtitle()` 构建 `WebSocketSubtitleMessage` 对象通过 WebSocket 发送：

```java
private void sendSubtitle(WebSocketSession session, String text, boolean isFinal) {
    WebSocketSubtitleMessage subtitle = WebSocketSubtitleMessage.builder()
            .type("subtitle")
            .text(text)
            .isFinal(isFinal)
            .build();
    sendMessage(session, toJson(subtitle));
}
```

消息格式为 `{"type":"subtitle","text":"识别出的文本","isFinal":true/false}`。`isFinal` 标记让前端区分字幕的两种状态——实时变化的用灰色斜体表示暂定状态，已定稿的用正常黑色字体表示确认状态。这个简单的标记极大地影响了用户对系统能力的感知——实时变化的字幕让用户知道"系统正在听"，定稿的字幕让用户知道"识别完成，可以提交了"。

---

## 用户文本的额外输入路径

除了语音识别，`handleControl()` 在处理 `submit` 操作时还支持前端通过 `data.text` 字段传入用户手动输入的文本。这个功能覆盖了语音识别不准确时用户手动修改文本的场景：

前端提交消息可以附带手动文本：
```json
{"type":"control","action":"submit","data":{"text":"用户手动修改后的文本"}}
```

Handler 的处理逻辑：

```java
case "submit":
    // 前端可能通过 data.text 传入手动编辑的文本（覆盖 ASR 识别结果）
    if (control.getData() != null) {
        Object textObj = control.getData().get("text");
        if (textObj instanceof String text && !text.isBlank()) {
            SessionState state = sessionStates.get(sessionId);
            if (state != null) state.appendFinalSttSegment(text);
        }
    }
    flushMergedUtteranceToLlm(sessionId);
    break;
```

如果提供了 `data.text`，先调用 `appendFinalSttSegment(text)` 追加到合并缓冲区，再 `flushMergedUtteranceToLlm()` 触发 LLM。如果不提供 `data.text`（纯语音识别提交），直接 `flushMergedUtteranceToLlm()`，缓冲区中已有 ASR 识别累积的文本。这个双路径设计让语音识别和手动输入无缝共存。

---

## 不自动触发 LLM 的理由

ASR 最终结果不自动触发 LLM 是一个经过权衡的设计决策。自动触发的优势是减少用户操作步骤，劣势是识别错误时无法修正。在面试场景中，如果 ASR 将"我精通微服务架构"识别为"我精通微信服务架构"，自动送入 LLM 会让面试官基于错误理解提问，后续的所有对话都建立在错误的前提之上，严重影响面试质量。系统选择了一个折中方案——自动推送实时字幕让用户看到识别进度，但将"是否送入 LLM"的最终决定权交给用户。

---

## 总结

ASR 结果处理是语音面试核心管线的第二步，承上启下连接 ASR 识别和 LLM 生成。它通过 `handleSttResult()` 的 `isFinalSegment` 分支将部分结果实时推送字幕提升交互体感、将最终定稿追加到 `mergeBuffer` 合并缓冲区解决多次 VAD 断句的分段问题、通过手动提交给用户检查和修正识别结果的时间窗口、通过 `data.text` 字段支持文本输入作为语音识别的补充路径。合并缓冲区的 `AtomicReference` 原子操作保证了 ASR 并发回调场景下的文本安全，`takeMergeBufferAndClear()` 的 `getAndSet` 保证了取用和清空在一步完成不丢失数据。
