# executeRateLimitScript 与 Lua 执行怎么讲

## 1. 这个方法的定位

`executeRateLimitScript` 是 Java 切面和 Redis Lua 脚本之间的桥。

面试可以这样说：

> `around` 方法负责遍历限流规则，而真正把规则交给 Redis 执行的是 `executeRateLimitScript`。它把限流 Key、当前时间、窗口大小、最大次数和请求 ID 组装成 Lua 参数，通过 Redisson 的 `evalSha` 调用 Redis 里的脚本。

方法签名：

```java
private Long executeRateLimitScript(
    String key,
    long nowMs,
    String requestId,
    long intervalMs,
    double count
)
```

参数含义：

| 参数 | 含义 |
|---|---|
| `key` | 限流维度 Key，例如 `ratelimit:{Controller:method}:ip:1.2.3.4` |
| `nowMs` | 当前时间戳，毫秒 |
| `requestId` | 当前请求唯一 ID |
| `intervalMs` | 滑动窗口大小，毫秒 |
| `count` | 窗口内允许最大请求数 |

## 2. Java 参数怎么映射到 Lua

Java 组装：

```java
List<Object> keysList = Collections.singletonList(key);
Object[] args = {
    String.valueOf(nowMs),
    String.valueOf(1),
    String.valueOf(intervalMs),
    String.valueOf(count),
    requestId
};
```

Lua 接收：

```lua
local key = KEYS[1]
local now_ms = tonumber(ARGV[1])
local permits = tonumber(ARGV[2])
local interval = tonumber(ARGV[3])
local max_tokens = tonumber(ARGV[4])
local request_id = ARGV[5]
```

对应关系：

| Java | Lua | 说明 |
|---|---|---|
| `keysList[0]` | `KEYS[1]` | 限流 Key |
| `nowMs` | `ARGV[1]` | 当前毫秒时间 |
| 固定 `"1"` | `ARGV[2]` | 每次请求消耗 1 个令牌 |
| `intervalMs` | `ARGV[3]` | 窗口大小 |
| `count` | `ARGV[4]` | 最大令牌数 |
| `requestId` | `ARGV[5]` | 请求唯一 ID |

面试表达：

> 这里每次请求固定消耗 1 个令牌，所以 `ARGV[2]` 写死为 1。`count` 虽然是 double，但在当前使用上基本是整数阈值。

## 3. 为什么使用 evalSha

执行代码：

```java
Object resultObj = rScript.evalSha(
    RScript.Mode.READ_WRITE,
    luaScriptSha,
    RScript.ReturnType.VALUE,
    keysList,
    args
);
```

解释：

| 参数 | 含义 |
|---|---|
| `READ_WRITE` | 脚本会写 Redis，比如 `set`、`zadd`、`expire` |
| `luaScriptSha` | 预加载脚本后得到的 SHA1 |
| `VALUE` | 脚本返回单个值 |
| `keysList` | Redis KEYS |
| `args` | Lua ARGV |

面试表达：

> `evalSha` 比每次 `eval` 传完整脚本更高效。脚本启动时已加载到 Redis，后续只传 SHA1 摘要执行，减少网络传输和脚本解析成本。

## 4. Lua 脚本的数据结构

Lua 内部派生两个 key：

```lua
local value_key = key .. ":value"
local permits_key = key .. ":permits"
```

例如 base key：

```text
ratelimit:{KnowledgeBaseController:queryKnowledgeBase}:ip:10.0.0.8
```

派生：

```text
ratelimit:{KnowledgeBaseController:queryKnowledgeBase}:ip:10.0.0.8:value
ratelimit:{KnowledgeBaseController:queryKnowledgeBase}:ip:10.0.0.8:permits
```

| Key | 类型 | 用途 |
|---|---|---|
| `:value` | String | 当前可用令牌数 |
| `:permits` | Sorted Set | 已消费令牌记录，score 是请求时间戳 |

为什么用 Sorted Set：

> 滑动窗口需要按时间删除过期请求。Sorted Set 的 score 正好可以存时间戳，用 `zrangebyscore` 和 `zremrangebyscore` 高效找到并删除窗口外记录。

## 5. Lua 算法步骤

### 5.1 初始化令牌

```lua
local current_val = tonumber(redis.call("get", value_key)) or max_tokens
```

第一次请求时 `value_key` 不存在，就认为当前可用令牌是 `max_tokens`。

### 5.2 找出过期记录

```lua
local expired_values = redis.call("zrangebyscore", permits_key, 0, now_ms - interval)
```

含义：

```text
当前窗口是 [now_ms - interval, now_ms]
所有早于 now_ms - interval 的请求都已经过期
```

### 5.3 回收过期令牌

```lua
local p = tonumber(string.match(v, ":(%d+)$"))
expired_count = expired_count + p
```

member 格式：

```text
requestId:permits
```

当前 permits 是 1，所以过期一条记录就回收 1 个令牌。

然后删除过期记录：

```lua
redis.call("zremrangebyscore", permits_key, 0, now_ms - interval)
```

令牌回收：

```lua
current_val = math.min(max_tokens, current_val + expired_count)
```

用 `math.min` 防止回收超过最大令牌数。

### 5.4 判断配额

```lua
if current_val < permits then
    return 0
end
```

不足就拒绝。

### 5.5 扣减令牌并记录本次请求

```lua
local permit_record = request_id .. ":" .. permits
redis.call("zadd", permits_key, now_ms, permit_record)
redis.call("set", value_key, current_val - permits)
```

### 5.6 设置 TTL

```lua
local expire_time = math.ceil(interval * 2 / 1000)
if expire_time < 1 then expire_time = 1 end
redis.call("expire", value_key, expire_time)
redis.call("expire", permits_key, expire_time)
```

面试解释：

> TTL 设置成窗口 2 倍，是为了让冷数据自动清理，同时保证窗口内需要回收的记录不会太早被删掉。

### 5.7 返回结果

```lua
return 1
```

当前脚本语义：

```text
1 = 通过
0 = 拒绝
```

不要说它返回剩余配额，除非后续改 Lua。

## 6. 为什么 Lua 能保证并发安全

如果用多条 Redis 命令在 Java 侧执行：

```text
GET value
ZRANGEBYSCORE expired
ZREM expired
SET value
ZADD record
```

并发下可能出现：

```text
两个请求同时读到 current_val = 1
两个请求都认为可以通过
结果超发
```

Lua 在 Redis 内执行：

> Redis 执行 Lua 脚本时是单线程原子执行的，脚本执行过程中不会穿插其他客户端命令，所以不会出现判断和扣减之间的并发竞态。

## 7. NOSCRIPT 容错

问题：

```text
Redis 重启
SCRIPT FLUSH
主从切换后脚本缓存丢失
```

此时 `evalSha` 会报：

```text
NOSCRIPT No matching script. Please use EVAL.
```

代码处理：

```java
if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
    loadScript();
    Object resultObj = rScript.evalSha(...);
    return convertToLong(resultObj);
}
throw e;
```

面试表达：

> 我没有因为一次 NOSCRIPT 就让请求失败，而是重新 `scriptLoad`，拿到新 SHA 后重试一次。这能处理 Redis 重启后脚本缓存失效的问题。

## 8. convertToLong 为什么需要

Redisson 返回值可能是：

```text
Number
String
其他/NULL
```

代码处理：

```java
if (obj instanceof Number n) return n.longValue();
if (obj instanceof String s) return Long.parseLong(s);
其他情况记录 warn 返回 null
```

面试表达：

> 这是为了兼容 Redis/Redisson 返回值类型差异，避免强转导致 ClassCastException。无法转换时返回 null，外层会当作限流失败处理。

## 9. 当前实现的边界

### 9.1 Lua 返回值和注释不一致

Java 注释说“返回剩余配额”，但 Lua 返回 `1/0`。

面试诚实说法：

> 当前代码里 result 只是通过/拒绝标记。注释可以修正。如果希望前端提示剩余次数，可以改 Lua 返回剩余令牌数。

### 9.2 Redis 非 NOSCRIPT 异常没有降级策略

当前：

```text
连接失败、超时、Redis 不可用
  -> RedisException 向上抛出
```

后续：

```text
fail-open：Redis 故障时放行
fail-closed：Redis 故障时拒绝
按接口成本配置策略
```

### 9.3 requestId 不是全链路幂等 ID

当前 requestId 每次在切面内部生成。

> 它能保证 Sorted Set member 唯一，但不能防止客户端重试同一个业务请求时重复计数。要做到这点，需要请求头传稳定的 Idempotency-Key。

## 10. 高频追问

### 问：为什么不用 Redis ZSET 的数量直接判断？

答：

> 也可以只用 ZSET 存时间戳，每次删除过期记录后 `ZCARD` 判断数量。当前实现额外维护 `value` 作为可用令牌数，扣减和回收都更直接，但实现复杂度也更高。两种方案都可行。

### 问：Lua 脚本为什么设置两个 key 的过期时间？

答：

> 限流 key 只在接口被访问时有意义。如果长时间没人访问，不设置过期会一直占 Redis 内存。设置窗口 2 倍的 TTL，可以让冷 key 自动清理。

### 问：`count` 是 double，Lua 会不会有小数问题？

答：

> 当前项目实际配置都是整数，比如 2、5、10、30。虽然注解字段是 double，但每次消耗固定 1，如果传小数阈值语义会不清晰。后续可以把 count 改成 long，或者真正支持权重令牌。

### 问：`evalSha` 第一次执行前 SHA 从哪里来？

答：

> Spring Bean 初始化后 `@PostConstruct init()` 会调用 `loadScript()`，内部通过 `rScript.scriptLoad(LUA_SCRIPT)` 把脚本加载到 Redis，并保存返回的 SHA1。

## 11. 一段完整回答

> `executeRateLimitScript` 是切面调用 Redis Lua 的适配层。它把限流 key、当前时间、窗口大小、最大请求数和请求 ID 转成 Lua 的 KEYS/ARGV，然后用 Redisson `evalSha` 执行预加载脚本。Lua 里维护两个 key：String 类型的 `:value` 保存当前可用令牌，Sorted Set 类型的 `:permits` 保存窗口内请求记录。每次请求先按时间戳删除过期记录并回收令牌，再判断令牌是否够，够就扣减并写入本次请求，不够返回 0。因为整个过程在 Redis Lua 中原子执行，所以适合分布式多实例限流。代码还处理了 Redis `NOSCRIPT`，脚本缓存丢失时会重新加载并重试一次。

