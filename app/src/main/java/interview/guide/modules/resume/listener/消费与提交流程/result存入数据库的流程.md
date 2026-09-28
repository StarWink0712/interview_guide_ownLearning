`result` 存入数据库的代码在 [AsyncTaskService.createOrReusePendingTask()](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/asynctask/service/AsyncTaskService.java#L132-L147) 方法内部，它调用了 `createTask()`：

---

## 调用链追踪

```java
// 第 1 层：AnalyzeStreamProducer 调用
AsyncTaskEnqueueResult result = asyncTaskService.createOrReusePendingTask(...);

// 第 2 层：AsyncTaskService.createOrReusePendingTask()
// 不存在活跃任务时，走 orElseGet → createTask()
.orElseGet(() -> createTask(taskType, businessId, streamKey, payloadJson));

// 第 3 层：AsyncTaskService.createTask() — 这里入库！
private AsyncTaskEnqueueResult createTask(...) {
    AsyncTaskEntity task = AsyncTaskEntity.builder()
        .taskType(taskType)
        .businessId(businessId)
        .streamKey(streamKey)
        .status(AsyncTaskStatusDTO.PENDING)
        .retryCount(0)
        .maxRetryCount(AsyncTaskStreamConstants.MAX_RETRY_COUNT)
        .payloadJson(payloadJson)          // ← {"resumeId":456, "contentLength":3500}
        .activeFlag(true)
        .activeKey(activeKey(taskType, businessId))
        .build();
    // ↓ 这一行就是入库操作
    return new AsyncTaskEnqueueResult(toDTO(taskRepository.save(task)), true);
    //                                   ↑ JPA save() → INSERT INTO async_task
}
```

---

## 对应数据库操作

```
Java:  taskRepository.save(task)

SQL:   INSERT INTO async_task (
           task_type, business_id, stream_key, status,
           retry_count, max_retry_count, payload_json,
           active_flag, active_key, created_at, updated_at
       ) VALUES (
           'RESUME_ANALYZE', '456', 'resume:analyze:stream', 'PENDING',
           0, 3, '{"resumeId":456,"contentLength":3500}',
           true, 'RESUME_ANALYZE:456', NOW(), NOW()
       )
       
返回:  taskId = 789（自增主键）
```

---

## 总结

| 调用 | 位置 | 行号 |
|------|------|------|
| `createOrReusePendingTask()` 入口 | [AsyncTaskService.java#L133](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/asynctask/service/AsyncTaskService.java#L133) | L133 |
| 查已有任务 | `findFirstByTaskTypeAndBusinessIdAndStatusIn...` | L140-L144 |
| 没查到 → `createTask()` | `.orElseGet(() -> createTask(...))` | L146 |
| **`taskRepository.save(task)` 入库** | [AsyncTaskService.java#L440](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/asynctask/service/AsyncTaskService.java#L440) | **L440** |

`result` 本质上是对这条数据库记录的 Java 对象封装：`new AsyncTaskEnqueueResult(toDTO(刚入库的实体), true)`。