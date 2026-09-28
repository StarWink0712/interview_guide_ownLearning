# deleteSession 方法完整实现流程详解

> 接口路径：`DELETE /api/voice-interview/sessions/{sessionId}`  
> 所属类：`VoiceInterviewController`  
> 功能：删除指定的语音面试会话及其关联的所有数据（会话记录、对话消息、评估结果）  
> 限流策略：无

---

## 一、前端交互触发点

### 触发场景

用户在语音面试历史记录页点击某条记录的"删除"按钮并确认后触发。

- **界面元素**：历史列表中的"删除"按钮（通常带确认弹窗）
- **触发方式**：用户确认删除 → 调用 API → 列表刷新

```typescript
// 前端 API 调用（voiceInterviewApi.ts）
await voiceInterviewApi.deleteSession(sessionId);
// 删除成功后从列表中移除
setSessions(prev => prev.filter(s => s.sessionId !== sessionId));
```

### API 层封装

```typescript
async deleteSession(sessionId: number): Promise<void> {
    return request.delete(`/api/voice-interview/sessions/${sessionId}`);
}
```

---

## 二、后端处理全链路

### 2.1 路由映射

```
DELETE /api/voice-interview/sessions/{sessionId}
  │
  ▼
VoiceInterviewController.deleteSession(sessionId)
```

### 2.2 Controller 层

```java
@DeleteMapping("/sessions/{sessionId}")
public Result<Void> deleteSession(@PathVariable Long sessionId) {
    log.info("Deleting voice interview session: {}", sessionId);
    voiceInterviewService.deleteSession(sessionId);
    return Result.success();
}
```

### 2.3 Service 层 — deleteSession()

**文件**：`VoiceInterviewService.java` 第 645-654 行

```java
@Transactional
public void deleteSession(Long sessionId) {
    // 1. 校验会话是否存在
    if (!sessionRepository.existsById(sessionId)) {
        throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND,
            "会话不存在: " + sessionId);
    }

    // 2. 逐级删除关联数据
    evaluationRepository.findBySessionId(sessionId).ifPresent(evaluationRepository::delete);  // 评估结果
    messageRepository.deleteBySessionId(sessionId);                                            // 对话消息
    sessionRepository.deleteById(sessionId);                                                   // 会话本体

    log.info("Deleted voice interview session: {}", sessionId);
}
```

### 2.4 删除顺序

由于语音面试三个实体之间**不通过 JPA 级联关系关联**（通过 `sessionId` 字段而非外键引用），Service 层手动逐级删除：

1. **先删评估结果**：`evaluationRepository.delete()`（存在则删除）
2. **再删对话消息**：`messageRepository.deleteBySessionId()`（批量删除该会话所有消息）
3. **最后删会话本体**：`sessionRepository.deleteById()`

### 2.5 与文本面试级联删除的区别

文本面试通过 JPA 的 `cascade = CascadeType.ALL + orphanRemoval = true` 实现级联删除，删除 Session 时 JPA 自动删除所有 Answer。语音面试无此配置，需手动逐级删除。

---

## 三、返回 JSON 示例

```json
{
    "code": 200,
    "message": "success",
    "data": null
}
```

**会话不存在**：
```json
{
    "code": 10001,
    "message": "会话不存在: 999",
    "data": null
}
```

---

## 四、代码模块调用关系

```
DELETE /api/voice-interview/sessions/{sessionId}
  │
  ▼
VoiceInterviewController.deleteSession(sessionId)
  └── voiceInterviewService.deleteSession(sessionId)
      ├── [不存在] → BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, 10001)
      ├── evaluationRepository.findBySessionId().ifPresent(...delete)  → 删除评估
      ├── messageRepository.deleteBySessionId()                        → 删除消息
      ├── sessionRepository.deleteById()                               → 删除会话
      └── log.info(...)

  Result.success()
  ▼ JSON { code: 200, data: null }
```

---

## 五、异常处理机制

| 场景 | 错误码 | 处理方式 |
|------|--------|---------|
| 会话不存在 | 10001 (VOICE_SESSION_NOT_FOUND) | `BusinessException` 抛出，`@Transactional` 回滚 |
| 数据库删除失败 | 内部错误 | `@Transactional` 回滚，保证原子性 |
| Redis 缓存残留 | 不影响 | 缓存 1h TTL 后自然过期（不主动清理） |

### 注意事项

1. **物理删除**：不做软删除，数据不可恢复
2. **Redis 缓存不清理**：删除操作只操作数据库，Redis 中 `voice:interview:session:{id}` 缓存残留，依赖 1 小时 TTL 自然过期
3. **事务原子性**：`@Transactional` 保证三条删除语句要么全部成功要么全部回滚

---

## 六、流程总结

`deleteSession` 是语音面试的**物理删除接口**。先校验会话存在性（不存在抛 10001）→ 依次删除评估结果、对话消息、会话本体（位于同一个 `@Transactional` 事务内，保证原子性）→ 返回成功。注意 Redis 缓存不会同步清理，残留缓存需等待 1 小时 TTL 自然过期。
