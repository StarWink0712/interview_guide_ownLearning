## rate\_limit\_single.lua 详细分析

这个 Lua 脚本实现了**滑动时间窗口限流**，由 [RateLimitAspect](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/aspect/RateLimitAspect.java) 通过 Redisson 的 `evalSha` 调用，每次检查一个维度的限流规则。逐段分析如下：

`rate_limit_single.lua` 是一个基于 Redis 实现滑动时间窗口限流的 Lua 原子脚本，接收 `KEYS[1]`（限流 Key，如 `ratelimit:{Controller:method}:ip:1.2.3.4`）和 5 个 ARGV 参数（当前毫秒时间戳、每次消耗令牌数、窗口大小毫秒、最大令牌数、请求唯一 ID），内部维护两个 Redis 键——`key:value`（String 类型，存储当前剩余令牌数）和 `key:permits`（Sorted Set 类型，存储历史消费记录，member 为 `request_id:消耗数`，score 为消费时间戳）。脚本执行时先从 `:value` 读取当前剩余令牌（首次不存在则初始化为最大令牌数），然后通过 `ZRANGEBYSCORE key:permits 0 (now_ms - interval)` 查出所有已滑出时间窗口的过期消费记录，遍历正则提取每条记录的消耗令牌数并累加，接着用 `ZREMRANGEBYSCORE` 批量删除过期记录、归还回收的令牌到 `current_val`（上限不超过 `max_tokens`）；随后判断剩余令牌是否足够本次消耗，不够则直接 return 0 通知切面拒绝请求，足够则用 `ZADD` 将本次消费记录写入 Sorted Set、用 `SET` 更新剩余令牌数，最后为两个键设置 TTL（窗口的 2 倍秒数，最小 1 秒）防止冷数据残留。整个脚本利用 Redis 单线程执行 Lua 的天然原子性保证了"查询过期 → 归还令牌 → 判断 → 扣减"四步操作无竞态条件，实现精确的滑动窗口而非粗暴的固定窗口重置，同时通过请求 ID 写入 member 做到网络重试去重。切面通过 Redisson 的 `evalSha` 以 SHA1 摘要方式调用该脚本，遍历每个 `@RateLimit` 注解规则逐维执行，任一规则返回 0 即触发限流拒绝。

***

### 一、参数接收（13-21行）

```lua
local key = KEYS[1]           -- 如 ratelimit:{ResumeController:uploadResume}:ip:1.2.3.4
local now_ms = tonumber(ARGV[1])   -- 当前毫秒时间戳
local permits = tonumber(ARGV[2])  -- 每次消耗令牌数（固定为 1）
local interval = tonumber(ARGV[3]) -- 时间窗口大小（毫秒）
local max_tokens = tonumber(ARGV[4]) -- 窗口内允许的最大请求数（即注解的 count）
local request_id = ARGV[5]     -- UUID，用于去重
```

在同一个限流 Key 下维护两个 Redis 键：

| 键             | 类型         | 用途                                            |
| ------------- | ---------- | --------------------------------------------- |
| `key:value`   | String     | 当前剩余可用令牌数                                     |
| `key:permits` | Sorted Set | 消费记录集合，member 是 `request_id:消耗数`，score 是消费时间戳 |

***

### 二、获取当前令牌（23-24行）

```lua
local current_val = tonumber(redis.call("get", value_key)) or max_tokens
```

从 `:value` 键读取剩余令牌数。首次请求该键不存在，返回 nil，`or max_tokens` 兜底为最大值——相当于窗口初始化。

***

### 三、回收过期令牌——滑动窗口核心（27-42行）

```lua
local expired_values = redis.call("zrangebyscore", permits_key, 0, now_ms - interval)
```

从 Sorted Set 中查出所有 score 小于 `now_ms - interval` 的记录，也就是**已经滑出时间窗口的旧消费记录**。

```lua
if #expired_values > 0 then
    local expired_count = 0
    for _, v in ipairs(expired_values) do
        local p = tonumber(string.match(v, ":(%d+)$"))
        if p then
            expired_count = expired_count + p
        end
    end
    redis.call("zremrangebyscore", permits_key, 0, now_ms - interval)
    if expired_count > 0 then
        current_val = math.min(max_tokens, current_val + expired_count)
    end
end
```

分三步：

1. **统计**：遍历过期记录，从 member 字符串 `request_id:1` 中正则提取出消耗的令牌数并累加
2. **删除**：`zremrangebyscore` 把这些过期记录从 Sorted Set 中清除
3. **归还**：把回收的令牌数加回 `current_val`，用 `math.min(max_tokens, ...)` 防止超过上限

这就是滑动窗口和固定窗口的本质区别：**时间流逝，窗口滑动，过期请求自动释放令牌**，而不是等到整点重置。

***

### 四、判断令牌是否充足（44-47行）

```lua
if current_val < permits then
    return 0
end
```

剩余令牌不够本次消耗，返回 0 表示拒绝。切面收到 0 就会进入限流拒绝流程。

***

### 五、扣减令牌并记录（50-52行）

```lua
local permit_record = request_id .. ":" .. permits   -- 如 "uuid-1234:1"
redis.call("zadd", permits_key, now_ms, permit_record)
redis.call("set", value_key, current_val - permits)
```

- 向 Sorted Set 插入一条消费记录，member 含 `request_id` 用于去重，score 为当前时间
- 更新剩余令牌数

两次写操作没有用事务包装？因为 **Redis 单线程执行 Lua 脚本本身就是原子的**，不需要额外的事务。

***

### 六、设置过期时间（54-58行）

```lua
local expire_time = math.ceil(interval * 2 / 1000)
if expire_time < 1 then expire_time = 1 end
redis.call("expire", value_key, expire_time)
redis.call("expire", permits_key, expire_time)
```

窗口的 2 倍作为 TTL（秒），最小 1 秒。这是为了防止长时间无请求时 Key 残留占用内存。2 倍窗口是安全余量，保证即使刚好有记录在窗口边缘，也不会被过早清理。

***

### 七、返回成功（60行）

```lua
return 1
```

返回 1 表示本次请求通过限流检查。切面收到非 0 非 null 就继续检查下一条规则或放行。

***

### 整体流程图

```
请求进入（ARGV 传入参数）
        │
        ▼
  读取 :value 键获取当前令牌数
        │
        ▼
  从 :permits (ZSet) 查出过期记录
        │
   有过期？──是──► 统计过期令牌 → ZREMRANGEBYSCORE 删除 → 归还令牌
        │
        ▼
  当前令牌 >= 本次消耗？
        │
   否 ──► return 0（拒绝）
        │
   是 ──► ZADD 写入消费记录 → SET 更新令牌 → 设置 TTL
        │
        ▼
   return 1（通过）
```

### 设计亮点

1. **真正的滑动窗口**：不是等整点重置，而是根据每条记录的精确时间戳判断是否过期，避免边界突发流量
2. **请求级去重**：`request_id` 写入 member，即使同一请求因网络重试多次到达，member 相同，ZADD 只会更新 score 不会重复计数
3. **Lua 原子性**：整个流程在 Redis 单线程中一次性执行，读取→判断→写入三步无竞态条件
4. **自动内存回收**：过期记录被 `zremrangebyscore` 清理 + TTL 兜底，不会无限增长

