# `@PreDestroy` shutdown() 方法详解

---

## 一、`@PreDestroy` 是什么

`@PreDestroy` 是 **JSR-250（javax/jakarta.annotation）** 定义的标准注解，属于 **Java 生命周期管理** 规范，不是前端框架的概念。Spring 容器会在销毁 Bean 之前自动调用标注了该注解的方法。

它的兄弟注解是 `@PostConstruct`（容器初始化后调用 `init()`），两者配对使用，形成一个 Bean 的完整生命周期：

```
Spring 容器启动
    ↓
构造方法 new()          ← Bean 创建
    ↓
依赖注入 @Autowired     ← 注入 RedisService 等
    ↓
@PostConstruct init()   ← [这个项目] 初始化消费者线程池
    ↓
Bean 正常服务中...
    ↓
Spring 容器关闭
    ↓
@PreDestroy shutdown()  ← [这个项目] 清理线程池资源
    ↓
Bean 被 GC 回收
```

---

## 二、这个方法做了三件事

```java
@PreDestroy
public void shutdown() {
    running.set(false);                              // ①
    if (executorService != null) {                    // ②
        executorService.shutdown();
    }
    log.info("{} consumer stopped: consumerName={}",  // ③
        taskDisplayName(), consumerName);
}
```

### ① `running.set(false)` —— 通知消费循环退出

`running` 是一个 `AtomicBoolean`，消费循环这样用它：

```java
while (running.get()) {        // ← 设为 false 后，循环条件变假
    consumeMessages();         // ← 不会再进入下一轮循环
}
```

这里只是**发了信号**，并没有真正中断线程。正在处理的消息会正常完成（不会丢数据），只是处理完后不再拉取新消息。

### ② `executorService.shutdown()` —— 优雅关闭线程池

`shutdown()` 的行为：

| 动作 | 说明 |
|------|------|
| 拒绝新任务 | 再有人想提交任务会被 AbortPolicy 拒绝 |
| 等待已提交任务完成 | 不会强制中断正在执行的任务 |
| 不等待队列中的任务 | 如果队列里还有排队任务，直接丢弃 |

注意这里用的是 **`shutdown()`**，不是 `shutdownNow()`：

| 方法 | 行为 | 适用场景 |
|------|------|----------|
| `shutdown()` | 温和关闭，等当前任务跑完 | **这个项目用这个** — 不丢消息 |
| `shutdownNow()` | 暴力中断，直接调 `thread.interrupt()` | 需要立刻停止的场景 |

### ③ 记录日志

```java
log.info("{} consumer stopped: consumerName={}", taskDisplayName(), consumerName);
```

具体输出类似：
```
知识库向量化 consumer stopped: consumerName=vectorize-consumer-a3f2b01c
简历分析 consumer stopped: consumerName=analyze-consumer-8e1d9f4a
```

---

## 三、整个关闭流程串联

结合 `init()` 一起看，形成完整的生命周期：

```java
// === 启动时 ===
@PostConstruct
public void init() {
    consumerName = consumerPrefix() + UUID...;        // 唯一消费者名
    executorService = new ThreadPoolExecutor(1, 1,...); // 单线程守护线程池
    running.set(true);                                  // 启动标志
    executorService.submit(this::startConsumer);        // 提交消费任务
}

// === 关闭时 ===
@PreDestroy
public void shutdown() {
    running.set(false);           // ① 发出停止信号
    executorService.shutdown();   // ② 温和关闭线程池
    // ...日志
}
```

**时间线展开**：

```
时间 →

启动：
  @PostConstruct init()
    └→ executorService.submit(startConsumer)
         └→ consumeLoop() 开始死循环拉消息
              └→ while(running.get()) ← 现在为 true
                   └→ 拉取 → 处理 → ACK → 拉取 → ...

用户按下 Ctrl+C / kill -15：
  Spring 收到关闭信号
    └→ @PreDestroy shutdown()
         ├→ running.set(false)    ← while 循环下一次判断时退出
         └→ executorService.shutdown()
              └→ 等当前正在处理的消息跑完
                    └→ 线程自然结束
                         └→ JVM 退出
```

---

## 四、为什么不直接 `shutdownNow()`？

这是个关键设计决策：

```java
// ❌ 如果用 shutdownNow()
executorService.shutdownNow();
// 后果：正在处理的消息被中断
//   → 消息已从 Stream 拉取但未 ACK
//   → 消息留在 PEL（Pending Entries List）
//   → 下次启动 recoverPendingMessages() 重新处理
//   → 可能导致重复处理（不是幂等操作就有问题）

// ✅ 实际用的是 shutdown()
executorService.shutdown();
// 后果：等当前消息处理完再退出
//   → 消息正常 ACK
//   → Redis Stream 中干净删除
//   → 无重复处理风险
```

但有一个隐患：如果消费任务本身卡死了（比如死循环或无限阻塞），`shutdown()` 永远等不到完成。生产环境建议加上超时：

```java
// 更健壮的写法
executorService.shutdown();
if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
    log.warn("线程池未能在 30 秒内关闭，强制终止");
    executorService.shutdownNow();
}
```

---

## 五、小结

`shutdown()` 方法就是 Spring 容器在销毁 Bean 时自动调用的**清理方法**，三步走：

1. **改标志位** → 告诉死循环别跑了
2. **关线程池** → 温和等当前任务完成，不丢消息  
3. **记日志** → 留下关闭记录，方便排查

整套设计配合 Redis Stream 的 ACK 机制和 `recoverPendingMessages()` 恢复机制，确保**消息不丢、不重复、可恢复**。