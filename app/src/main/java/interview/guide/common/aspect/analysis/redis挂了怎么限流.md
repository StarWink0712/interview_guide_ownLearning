## Redis 挂了怎么办？—— 高可用方案详解

---

### 当前代码的行为

现在切面 [RateLimitAspect.java](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/aspect/RateLimitAspect.java#L182-L197) 里，Redis 异常（包括连接失败）的处理路径：

```
Redis 挂了
    │
    ▼
Redisson 抛出 RedisConnectionException / RedisTimeoutException
    │
    ▼
切面的 executeRateLimitScript() 没有捕获连接异常
    │
    ▼
异常向上抛到 around() → 继续向上 → 全局异常处理器 500
    │
    ▼
请求失败，返回错误给前端
```

这是 **fail-fast 策略**——宁可服务受损，也不让限流失效导致系统裸奔。但生产环境 Redis 本身需要高可用，以下是几种方案。

---

### 方案一：Redis Sentinel（哨兵模式）

**架构：1 主 + N 从 + 3+ 哨兵**

```
                  客户端 (Redisson)
                       │
                       ▼
              ┌─────────────────┐
              │  Sentinel 集群    │  哨兵互相通信，监控主从状态
              │  S1   S2   S3   │
              └─────────────────┘
                       │
              询问 "谁是当前 master？"
                       │
                       ▼
              ┌─────────────────┐
              │    Master       │  负责所有写操作
              │  (192.168.1.1)  │
              └───────┬─────────┘
                      │ 异步复制
          ┌───────────┼───────────┐
          ▼                       ▼
   ┌─────────────┐       ┌─────────────┐
   │  Slave 1     │       │  Slave 2     │   负责读操作 + 备份
   │ (192.168.1.2) │       │ (192.168.1.3) │
   └─────────────┘       └─────────────┘
```

**数据同步机制：**

```
1. 客户端发 SET key value → Master
2. Master 立即执行写入
3. Master 把命令写入 replication buffer
4. Slave 持续从 Master 拉取增量命令（PSYNC），应用到自身
5. 默认异步复制，Master 不等 Slave 确认就返回客户端
```

**故障切换流程：**

```
Master 挂了（比如宕机）
    │
    ▼
Sentinel 检测到 Master 主观下线（SDOWN）
    │  ↓ 多个 Sentinel 确认后变客观下线（ODOWN）
    ▼
Sentinel 选举出一个 Leader 执行故障转移
    │
    ▼
Leader Sentinel 从 Slave 中选一个新 Master
  （选复制偏移量最大的，数据丢失最少）
    │
    ▼
执行 SLAVEOF NO ONE，提升为新 Master
    │
    ▼
通知其他 Slave 切换复制目标到新 Master
    │
    ▼
Sentinel 通知客户端（Redisson）新 Master 地址
    │
    ▼
Redisson 自动重连新 Master，服务恢复
```

**配置方式（Redisson 客户端）：**

```java
Config config = new Config();
config.useSentinelServers()
    .setMasterName("mymaster")                    // Sentinel 配置的 master 名称
    .addSentinelAddress("redis://192.168.1.10:26379")
    .addSentinelAddress("redis://192.168.1.11:26379")
    .addSentinelAddress("redis://192.168.1.12:26379");
```

**优点**：配置简单，Redisson 原生支持，自动故障切换
**缺点**：所有数据只有一个 Master 存储，内存受限；写压力集中在一个节点

---

### 方案二：Redis Cluster（集群模式）

**架构：数据分片，每个分片有主从**

```
                  客户端 (Redisson)
                       │
                       ▼
          ┌────────────────────────────┐
          │        Redis Cluster        │
          │                            │
          │  Slot 0~5460               │
          │  ┌──────────┐  ┌─────────┐ │
          │  │ Master A │──│ Slave A │ │
          │  └──────────┘  └─────────┘ │
          │                            │
          │  Slot 5461~10922           │
          │  ┌──────────┐  ┌─────────┐ │
          │  │ Master B │──│ Slave B │ │
          │  └──────────┘  └─────────┘ │
          │                            │
          │  Slot 10923~16383          │
          │  ┌──────────┐  ┌─────────┐ │
          │  │ Master C │──│ Slave C │ │
          │  └──────────┘  └─────────┘ │
          └────────────────────────────┘
```

**数据分布与同步：**

```
数据如何分片：
  CRC16("ratelimit:{Upload:upload}:ip:1.2.3.4") % 16384 = 9253
  9253 落在 Master B 的 slot 范围，数据存入 Master B

每个分片内部的主从同步：
  和 Sentinel 一样，Master → Slave 异步复制（PSYNC）
  主从之间只在该分片范围内同步数据，不跨分片

跨分片数据隔离：
  Master A 的数据不会同步到 Master B
  各分片是独立的，互不干扰
```

**它和本项目限流的配合——Hash Tag 的作用就在这里体现：**

```
同一方法的三条限流 Key：
  ratelimit:{Upload:upload}:global    → CRC16("Upload:upload") % 16384 = 9253 → Master B
  ratelimit:{Upload:upload}:ip:1.2.3 → CRC16("Upload:upload") % 16384 = 9253 → Master B
  ratelimit:{Upload:upload}:user:99  → CRC16("Upload:upload") % 16384 = 9253 → Master B

三条 Key 全部落在 Master B，Lua 脚本操作的所有 Key 在同一个节点上，不会报 CROSSSLOT 错误
```

**故障切换：**

```
Master B 挂了
    │
    ▼
Cluster 内其他 Master 检测到 Master B 无响应
    │  ↓ 超过半数 Master 确认后标记为 FAIL
    ▼
Slave B 被自动提升为新 Master B
    │
    ▼
Slave B 接管 Slot 5461~10922 的读写
    │
    ▼
Redisson 收到 MOVED 重定向，更新 slot 映射表
    │
    ▼
后续请求自动路由到提升后的新 Master B
```

**配置方式（Redisson）：**

```java
Config config = new Config();
config.useClusterServers()
    .addNodeAddress("redis://192.168.1.20:6379")
    .addNodeAddress("redis://192.168.1.21:6379")
    .addNodeAddress("redis://192.168.1.22:6379");
```

**优点**：水平扩展，数据分散存储；每个分片独立负责一部分数据，读写压力也分散
**缺点**：运维复杂，跨 slot 的 Lua 脚本会失败（即使用了 Hash Tag 已解决）；至少需要 6 个节点（3 主 3 从）

---

### 两种方案对比

| 维度 | Sentinel | Cluster |
|------|----------|---------|
| 数据分布 | 全量数据在每个节点 | 数据分片，每节点存一部分 |
| 存储上限 | 受单机内存限制 | 线性扩展，加节点=加内存 |
| 写性能 | 单 Master 写入 | 多 Master 并行写入 |
| Lua 脚本限制 | 无（所有 Key 在同一节点） | 必须用 Hash Tag 控制 |
| 故障切换时间 | 通常 10-30 秒 | 通常 3-5 秒（Gossip 协议更快） |
| 最少节点数 | 5 个（1 主 2 从 + 3 哨兵） | 6 个（3 主 3 从） |
| 适合场景 | 数据量不大，高可用优先 | 数据量大，高并发 + 高可用 |

---

### 方案三：应用层降级保底

不管 Redis 用 Sentinel 还是 Cluster，总有极端情况（比如整个 Redis 集群都挂了）。可以加一层应用级兜底：

```java
// 伪代码思路
@Around(...)
public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
    try {
        // 正常执行 Redis Lua 限流
        Long result = executeRateLimitScript(...);
        if (result == null || result == 0) {
            return handleRateLimitExceeded(...);
        }
    } catch (RedisConnectionException | RedisTimeoutException e) {
        // Redis 完全不可用时的兜底策略
        log.error("Redis 不可用，限流降级处理", e);
        if (failOpen) {
            // fail-open：放行请求（风险：系统可能被打爆）
            return joinPoint.proceed();
        } else {
            // fail-fast：拒绝请求（当前行为，安全优先）
            throw new RateLimitExceededException("服务繁忙，请稍后再试");
        }
    }
    return joinPoint.proceed();
}
```

也可以做**本地内存兜底**——Redis 不可用时切换到 Guava RateLimiter 做单机限流：

```java
// 本地令牌桶兜底
private final ConcurrentHashMap<String, RateLimiter> localFallback = new ConcurrentHashMap<>();

// Redis 挂了时，退化为本地单机限流，每个实例独立计数
RateLimiter limiter = localFallback.computeIfAbsent(key, 
    k -> RateLimiter.create(rule.count() / intervalSeconds));
if (!limiter.tryAcquire()) {
    throw new RateLimitExceededException(...);
}
```

---

### 实际推荐

对于这个面试平台的限流场景：

1. **中小规模**（当前阶段）→ **Redis Sentinel** 足够：部署简单，无分片带来的限制，Lua 脚本不需要考虑 Hash Tag
2. **大规模高并发** → **Redis Cluster**：代码已经用 Hash Tag 适配好了，直接切换配置即可
3. **终极兜底** → **应用层本地令牌桶降级**：保证 Redis 全挂时系统不裸奔