TransactionAfterCommitExecutor 代码分析

这个类的代码很短（只有 29 行），但解决了一个非常常见的坑。

***

### 一句话解释

**"等数据库事务提交成功了，再执行这个操作。"**

***

### 它解决什么问题？

先看一个场景：你的项目里有一条规定——

> 事务方法内禁止调用外部 API（LLM、S3 上传等），因为会长时间占用数据库连接。

但实际业务中确实需要在"数据写入成功后"触发某些操作，比如：

```
1. 把面试评估结果写入数据库
2. 数据库写入成功后 → 发一条 Redis Stream 消息通知消费者异步处理
```

问题来了：如果在事务中间发消息，事务还没提交就发了 → 消费者收到消息去查数据库，发现数据还不存在 → 报错。

**`TransactionAfterCommitExecutor`** **就是用来解决这个问题的**——它保证你的代码只在事务**提交成功后**才执行。

***

### 执行逻辑

```
execute(某个任务)
    │
    ├── 当前没有事务？
    │       → 直接执行任务（没有事务就不需要等）
    │
    └── 当前有事务？
            → 把任务注册到 Spring 事务管理器中
            → 等事务提交成功后
            → Spring 自动回调 afterCommit()
            → 执行你的任务
```

***

### 两种路径对比

```java
// 路径 1：当前没有事务（比如在 Controller 里直接调用，没有 @Transactional）
execute(() -> sendMessage());  
// → 立刻执行 sendMessage()

// 路径 2：当前有事务（比如在 @Transactional 方法里调用）
@Transactional
public void saveAndNotify() {
    repository.save(entity);                              // 写入数据库
    executor.execute(() -> sendMessage());               // 注册回调，暂不执行
    // 方法结束时事务提交
}  
// → 事务提交成功后 → sendMessage() 才真正执行
```

***

### 关键细节

**1.** **`TransactionSynchronizationManager.isSynchronizationActive()`**

判断当前线程是否在一个活动的事务中。如果没有事务，直接执行，不做任何等待。

**2.** **`afterCommit()`** **里的 try-catch**

```java
try {
    task.run();
} catch (Exception e) {
    log.error("afterCommit 回调执行失败", e);  // 只记日志，不抛异常
}
```

事务已经提交了，回调失败已经无法回滚。所以只记录错误日志，不向上抛异常——否则上层也不知道该回滚什么了。

**3. 不是** **`afterCompletion`** **而是** **`afterCommit`**

Spring 提供了两个回调时机：

| 回调                  | 触发时机             |
| ------------------- | ---------------- |
| `afterCommit()`     | **只有**事务成功提交后才触发 |
| `afterCompletion()` | 无论提交还是回滚都会触发     |

选 `afterCommit()` 是故意的——**只有数据确实落库了，才执行后续操作**。事务回滚的情况下，数据没写进去，就不应该发消息。

***

### 在本项目中的典型使用场景

```java
@Service
public class ResumeAnalysisService {
    
    private final TransactionAfterCommitExecutor afterCommitExecutor;
    private final ResumeAnalyzeStreamProducer streamProducer;
    
    @Transactional
    public void analyzeResume(Long resumeId) {
        // 1. 更新简历状态为"分析中"
        resumeRepo.updateStatus(resumeId, "ANALYZING");
        
        // 2. 等事务提交成功后再发 Redis Stream 消息
        afterCommitExecutor.execute(() -> 
            streamProducer.sendTask(new AnalyzeTask(resumeId))
        );
    }
}
```

如果第 2 步在事务中间直接发消息，万一日后事务回滚，消费者会处理一条"数据库中不存在"的任务。

有了 `TransactionAfterCommitExecutor`，消息只在数据确实落库后才发出，杜绝了这种数据不一致的问题。

***

### 一句话总结

> **数据库事务提交之前，别动外部系统。如果非要动，让** **`TransactionAfterCommitExecutor`** **帮你等到事务提交之后再动。**

