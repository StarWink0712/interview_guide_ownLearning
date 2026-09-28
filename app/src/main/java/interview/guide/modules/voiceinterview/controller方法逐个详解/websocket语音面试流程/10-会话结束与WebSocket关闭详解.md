# 会话结束与 WebSocket 关闭详解

## 概述

语音面试会话的结束有两种触发路径：用户主动结束（通过前端按钮发起 HTTP 请求或 WebSocket 控制消息）和 WebSocket 异常断开（网络中断、浏览器关闭、标签页关闭等）。两种路径最终都会调用同一个方法 `VoiceInterviewService.endSession()` 完成会话状态更新和异步评估任务的触发，但前置的清理逻辑不同——主动结束是先更新状态再正常关闭，异常断开始时先清理运行时资源（中断 LLM 线程、停止 ASR）再兜底结束。

## 一、用户主动结束

### 方式一：HTTP REST 接口

Controller 的 `endSession` 方法极简，不做校验：

```java
@PostMapping("/sessions/{sessionId}/end")
public Result<Void> endSession(@PathVariable Long sessionId) {
    log.info("Ending session: {}", sessionId);
    voiceInterviewService.endSession(sessionId.toString());
    return Result.success();
}
```

`Long` 型 sessionId 转为 `String` 是历史兼容设计——`VoiceInterviewService` 的部分方法接受 `String` 参数（因为 WebSocket Handler 中的 sessionId 是 String 类型），内部通过 `parseSessionId()` 转回 Long。

### 方式二：WebSocket 控制消息

前端发送 `{"type":"control","action":"end_interview"}`，在 `handleControl()` 的 switch 分支中直接调用：

```java
case "end_interview":
    interviewService.endSession(sessionId);
    break;
```

---

## 二、VoiceInterviewService.endSession() 核心流程

```java
@Transactional
public void endSession(String sessionId) {
    Long sessionIdLong = parseSessionId(sessionId);
    VoiceInterviewSessionEntity session = getSession(sessionIdLong);

    // 会话不存在时静默返回——容错设计，避免因并发删除或脏数据抛异常
    if (session == null) {
        log.warn("Session not found: {}", sessionId);
        return;
    }

    endSession(session);                              // 设置结束状态
    voiceEvaluateStreamProducer.sendEvaluateTask(sessionId);  // 入队异步评估
}

private void endSession(VoiceInterviewSessionEntity session) {
    // 记录面试结束的精确时刻
    session.setEndTime(LocalDateTime.now());
    // 所有面试阶段完成
    session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED);
    // 会话生命周期结束
    session.setStatus(VoiceInterviewSessionStatus.COMPLETED);
    // 计算实际时长：使用 Java 8 Duration 类（不可变对象，线程安全）
    // between() 返回 Duration 实例，toSeconds() 转换为秒数
    session.setActualDuration(
        (int) Duration.between(session.getStartTime(), LocalDateTime.now()).toSeconds());
    // 标记评估任务待处理——此状态将触发后续异步评估流程
    session.setEvaluateStatus(AsyncTaskStatus.PENDING);

    sessionRepository.save(session);
    invalidateSessionCache(session.getId());  // 清除 Redis 缓存
}
```

结束后的五个关键字段变化：
- `endTime` → 当前时间，精确到纳秒
- `phase` → `COMPLETED`，所有面试阶段完成
- `status` → `COMPLETED`，会话生命周期结束
- `actualDuration` → `startTime` 到 `now` 的精确秒数差
- `evaluateStatus` → `PENDING`，触发后续异步评估

设置 `evaluateStatus=PENDING` 是关键——这个状态被消费者轮询检测，决定是否执行评估。评估的触发在 `endSession()` 内部方法的末尾：

```java
voiceEvaluateStreamProducer.sendEvaluateTask(sessionId);
```

这会通过 Redis Stream 的 `XADD` 命令将会话 ID 入队到 `voice:evaluate:stream`，`VoiceEvaluateStreamConsumer` 后台消费并调用 `VoiceInterviewEvaluationService.generateEvaluation()` 执行 LLM 评估。入队后 `endSession()` 立即返回，不等待评估完成。

---

## 三、WebSocket 异常断开：afterConnectionClosed()

当 WebSocket 连接因网络中断、浏览器关闭等原因断开，Spring WebSocket 框架自动调用此回调。它是清理逻辑的集中入口：

```java
@Override
public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    String sessionId = extractSessionId(session);
    try {
        // 步骤一：清理运行时内存状态
        sessions.remove(sessionId);
        SessionState removedState = sessionStates.remove(sessionId);
        if (removedState != null) {
            Thread t = removedState.getProcessingThread();
            if (t != null) t.interrupt();  // 中断正在阻塞等待 LLM 响应的虚拟线程
        }
        lastActivityTime.remove(sessionId);

        // 步骤二：停止 ASR 识别，释放到 DashScope 的 WebSocket 连接
        sttService.stopTranscription(sessionId);
        log.info("WebSocket connection closed for session: {}, status: {}", sessionId, status);

        // 步骤三：兜底结束会话（仅当状态仍为 IN_PROGRESS 时执行）
        try {
            interviewService.endSessionIfInProgress(sessionId);
        } catch (Exception endEx) {
            log.warn("Failed to auto-end session {} after disconnect: {}",
                sessionId, endEx.getMessage());
        }
    } catch (Exception e) {
        log.error("Error cleaning up session {} after close", sessionId, e);
    }
}
```

### 步骤一：清理运行时状态

三个 Map 的清除操作是幂等的——即使会话因为某种原因已经被清理过一次（例如正常结束触发过 asr stop 和 map remove），再次执行也不会抛异常。

中断 LLM 管线线程是一个关键操作。WebSocket 断开时，用户不再能接收回复，LLM 的 API 调用如果仍在等待响应就是浪费。`Thread.interrupt()` 会中断在 `conversation.connect()` 或 `CompletableFuture.get()` 上阻塞等待的虚拟线程，立即释放资源。

### 步骤二：停止 ASR

`sttService.stopTranscription(sessionId)` 在会话锁保护下执行两步操作。第一步 `conversation.endSession()` 通知 DashScope 服务端结束转写会话，等待服务端处理完最后一段音频的数据。第二步 `conversation.close()` 关闭 WebSocket 连接。之后 `sessions.remove(sessionId)` 移除 QwenAsrService 内部 Map 中的条目、`sessionLocks.remove(sessionId)` 移除锁对象防止内存泄漏。

### 步骤三：兜底结束

`endSessionIfInProgress()` 是整个关闭流程的安全网。它的幂等保护逻辑非常关键：

```java
@Transactional
public void endSessionIfInProgress(String sessionId) {
    Long sessionIdLong = parseSessionId(sessionId);
    VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong).orElse(null);

    // 幂等保护：已结束或已暂停的会话不重复操作
    if (session == null || session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
        return;
    }

    log.info("Auto-ending IN_PROGRESS session {} after WebSocket disconnect", sessionId);
    endSession(session);
}
```

这个幂等保护防止了以下典型时序场景的重复操作：
1. 用户正常点击"结束面试"→ `endSession()` 将状态设为 COMPLETED 并触发评估
2. 浏览器随后发送 WebSocket Close 帧
3. `afterConnectionClosed()` 调用 `endSessionIfInProgress()`
4. 检测到状态已经是 COMPLETED → 直接 return，不重复设置 endTime 和入队评估

如果没有这个保护，每次 WebSocket 断开都会重新触发一次评估任务，导致同一场面试被评估多次。

---

## 四、正常结束与异常断开的对比

| 场景 | 触发方式 | 处理顺序 | 是否评估 |
|------|---------|---------|---------|
| 正常结束 | HTTP POST /end 或 控制消息 | 先更新 DB 状态并触发评估，再清理资源 | 是 |
| 异常断开（IN_PROGRESS） | callback 回调 | 先清理资源（中断 LLM、停止 ASR），再兜底结束 | 是 |
| 异常断开（COMPLETED） | callback 回调 | 清理资源后 endSessionIfInProgress 直接 return | 否（已评估） |
| 异常断开（PAUSED） | callback 回调 | 清理资源后 endSessionIfInProgress 直接 return | 否（暂停状态） |

---

## 五、总结

会话结束是语音面试生命周期的终点。通过双路径设计（主动结束 + 异常兜底）确保无论何种情况都能正确关闭资源和触发评估。正常结束时先更新 DB 状态为 COMPLETED 并通过 Redis Stream 入队评估任务再清理连接资源，异常断开始时先通过 `Thread.interrupt()` 中断 LLM 管线释放虚拟线程、通过 `sttService.stopTranscription()` 释放 ASR 连接、最后通过 `endSessionIfInProgress()` 兜底结束（仅当状态仍为 `IN_PROGRESS` 时执行，防止 `COMPLETED` 和 `PAUSED` 状态的重复触发）。`@Transactional` 注解保证结束状态写入的原子性，`invalidateSessionCache` 确保后续查询获取最新 COMPLETED 状态。评估任务通过 Redis Stream 异步执行与结束流程解耦，前端通过轮询 `GET /evaluation` 获取评估进度。
