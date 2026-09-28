<br />

一个 `@RateLimit` 规则对应**一个固定的 base key**，比如 `ratelimit:{ResumeController:uploadResume}:ip:192.168.1.5`。这个 base key 本身不变，每次该 IP 的请求到来，Lua 脚本都是操作它下面那**两个动态的 Redis 键**：

- **`:value`（String）** — 剩余令牌数在变：请求通过时减 1，过期记录回收时加回来
- **`:permits`（Sorted Set）** — 消费记录集在变：通过时 ZADD 插入新记录，ZRANGEBYSCORE 查出过期记录后 ZREMRANGEBYSCORE 删除

base key 只是命名空间，真正的"流动"数据都在两个子键里。一个方法上有多条 `@RateLimit` 注解，就有多个 base key，各自独立维护自己的一对子键，互不干扰。

<br />

`ratelimit:{ResumeController:uploadResume}:ip:192.168.1.5` 本身只是一个**前缀（base key）**，它下面实际有 **2 个 Redis 键**，由 Lua 脚本自动创建和管理：

```
一个 @RateLimit 规则
        │
        ├── ratelimit:{ResumeController:uploadResume}:ip:192.168.1.5:value    ← String 类型
        └── ratelimit:{ResumeController:uploadResume}:ip:192.168.1.5:permits  ← Sorted Set 类型
```

| 键          | 类型             | 存储内容    | 举例                                |
| :--------- | :------------- | :------ | :-------------------------------- |
| `:value`   | **String**     | 当前剩余令牌数 | `"3"`（还剩 3 次）                     |
| `:permits` | **Sorted Set** | 历史消费记录  | 每条 member 为 `uuid:1`，score 为毫秒时间戳 |

这两个键在 Lua 脚本第 20-21 行拼接出来：

```lua
local value_key = key .. ":value"
local permits_key = key .. ":permits"
```

假设一个方法上有 2 条 `@RateLimit` 注解（GLOBAL + IP），那就是 `2 × 2 = 4` 个 Redis 键。Hash Tag `{ResumeController:uploadResume}` 的作用就是保证这 4 个键都在同一个 Redis Cluster slot 上，Lua 脚本才能原子执行。

<br />

以这条限流规则为例：

```java
@RateLimit(dimension = Dimension.IP, count = 5, interval = 1, timeUnit = TimeUnit.SECONDS)
// 含义：每个 IP 每秒最多 5 次
```

Redis Key 为 `ratelimit:{ResumeController:uploadResume}:ip:192.168.1.5`，其对应的 `:permits` 键就是一个 **Sorted Set（有序集合）**。

***

### Sorted Set 的结构

Sorted Set 中每个元素有两部分：

| 部分             | 说明                                    |
| -------------- | ------------------------------------- |
| **member**（成员） | 字符串，唯一标识，如 `"a1b2c3d4:1" uuid生成，在切面内` |
| **score**（分数）  | 一个 double 数字，决定排序顺序                   |

底层是**跳表 + 哈希表**，所以按 score 范围查询（`ZRANGEBYSCORE`）非常快。

***

### 真实数据举例

假设当前时间是 `2026-06-13 10:00:00.500`，毫秒戳为 `1718258400500`，窗口 1000 毫秒（1 秒）。

5 个请求在 1 秒窗口内陆续到达，`:permits` 键的内容如下：

```
Sorted Set: ratelimit:{ResumeController:uploadResume}:ip:192.168.1.5:permits
┌──────────────────────┬─────────────────┐
│       member         │      score      │
├──────────────────────┼─────────────────┤
│ "u1-a2b3:1"          │ 1718258400050   │  ← 第 1 次请求，窗口内最早
│ "u2-c4d5:1"          │ 1718258400200   │  ← 第 2 次请求
│ "u3-e6f7:1"          │ 1718258400350   │  ← 第 3 次请求
│ "u4-g8h9:1"          │ 1718258400480   │  ← 第 4 次请求
│ "u5-i0j1:1"          │ 1718258400600   │  ← 第 5 次请求，刚好用完令牌
└──────────────────────┴─────────────────┘

对应的 :value 键 = "0"  （令牌已耗尽）
```

此时 Sorted Set 有 5 条记录，score 按时间排序。`:value` 为 0，表示令牌用完，第 6 个请求到来时 `current_val(0) < permits(1)`，Lua 脚本 return 0 拒绝。

***

### 窗口滑动后

当第 6 个请求在 `1718258401200`（过了约 700 毫秒）到达时：

```
now_ms = 1718258401200
窗口边界 = now_ms - interval = 1718258401200 - 1000 = 1718258400200
```

Lua 脚本执行 `ZRANGEBYSCORE permits 0 1718258400200`，结果：

```
查询范围：score 在 [0, 1718258400200] 的记录
┌──────────────────────┬─────────────────┐
│       member         │      score      │
├──────────────────────┼─────────────────┤
│ "u1-a2b3:1"          │ 1718258400050   │  ← 已过期！
│ "u2-c4d5:1"          │ 1718258400200   │  ← 已过期！（等于边界也算）
└──────────────────────┴─────────────────┘
```

两条记录过期，正则提取 `:1` 累加得 `expired_count = 2`，归还给 `:value`。删除过期记录后：

```
Sorted Set（删除后）:
┌──────────────────────┬─────────────────┐
│ "u3-e6f7:1"          │ 1718258400350   │  ← 还在窗口内
│ "u4-g8h9:1"          │ 1718258400480   │  ← 还在窗口内
│ "u5-i0j1:1"          │ 1718258400600   │  ← 还在窗口内
└──────────────────────┴─────────────────┘

:value 键 = "2"  （回收了 2 个过期令牌）
```

剩余令牌 2 >= 1，第 6 次请求通过，`ZADD` 写入新记录 `"u6-k1l2:1"` score = `1718258401200`，`:value` 减为 1。

***

### 和固定窗口的区别

固定窗口是 10:00:00\~10:00:01 算一个周期，临界点（比如 10:00:00.999 和 10:00:01.001）各发 5 个请求都能通过，实际 2 毫秒内打了 10 次。而滑动窗口每条记录都有精确时间戳，始终只看过去 1000 毫秒内的记录数，彻底消除了边界突发问题。
