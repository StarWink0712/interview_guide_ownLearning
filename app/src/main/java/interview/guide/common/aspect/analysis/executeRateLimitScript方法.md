这是与 Redis Lua 脚本交互的执行层，逐段分析。

1. 服务端把 key + 时间戳 + 窗口大小 + 最大令牌数 + requestId 发给 Redis
2. Redis 用 now\_ms - interval 算出窗口起始时间
3. ZRANGEBYSCORE 查出 Sorted Set 中 score 小于该边界的过期记录
4. 把过期记录消耗的令牌数加回当前余额
5. ZREMRANGEBYSCORE 删掉这些过期记录
6. 判断余额够不够 → 够就扣减、插入新记录、返回 1；不够就返回 0
7. 结果回传给切面，由切面决定放行还是拒绝

***

### 构建参数（第 163-170 行）

```java
List<Object> keysList = Collections.singletonList(key);
Object[] args = {
        String.valueOf(nowMs),       // ARGV[1]: 当前时间戳
        String.valueOf(1),           // ARGV[2]: 每次消耗 1 个令牌
        String.valueOf(intervalMs),  // ARGV[3]: 窗口大小
        String.valueOf(count),       // ARGV[4]: 最大令牌数
        requestId                    // ARGV[5]: 请求去重 ID
};
```

对应 Lua 脚本的参数接收（`rate_limit_single.lua` 第 13-18 行）：

| Java 传入       | Lua 收到    | 含义                                                  |
| ------------- | --------- | --------------------------------------------------- |
| `keysList[0]` | `KEYS[1]` | 限流 Key，如 `ratelimit:{Controller:method}:ip:1.2.3.4` |
| `args[0]`     | `ARGV[1]` | 当前毫秒时间戳                                             |
| `args[1]`     | `ARGV[2]` | 本次消耗令牌数，固定 1                                        |
| `args[2]`     | `ARGV[3]` | 时间窗口大小（毫秒）                                          |
| `args[3]`     | `ARGV[4]` | 窗口内允许最大请求数                                          |
| `args[4]`     | `ARGV[5]` | UUID，用于去重，请求id                                      |

注意 KEYS 和 ARGV 是隔离的——KEYS 用于 Redis Cluster 路由判断（决定脚本发到哪个节点），ARGV 只是数据参数，不参与路由。

***

### 正常执行路径（第 172-181 行）

```java
Object resultObj = rScript.evalSha(
        RScript.Mode.READ_WRITE,      // 读写模式
        luaScriptSha,                 // 脚本 SHA1 摘要
        RScript.ReturnType.VALUE,     // 返回单值
        keysList,                     // KEYS 列表
        args                          // ARGV 参数
);
return convertToLong(resultObj);
```

四个参数的含义：

| 参数                  | 值              | 含义                                                          |
| ------------------- | -------------- | ----------------------------------------------------------- |
| `Mode`              | `READ_WRITE`   | 脚本会读写 Redis 数据（`get/set/zadd/zrem`），集群模式下必须声明，否则 Redis 拒绝执行 |
| `SHA1`              | `luaScriptSha` | 之前 `scriptLoad` 拿到的 40 字符摘要，Redis 用它在脚本缓存中查找                |
| `ReturnType`        | `VALUE`        | 告诉 Redisson 返回值是单值（不是列表或多行），方便自动反序列化                        |
| `keysList` + `args` | 见上             | 传给 Lua 的 KEYS 和 ARGV                                        |

`resultObj` 是 Lua 脚本的返回值（Long 类型的 1 或 0），通过 `convertToLong` 统一转为 Java 的 `Long`。

***

### NOSCRIPT 容错路径（第 182-197 行）

```java
catch (org.redisson.client.RedisException e) {
    if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
        loadScript();  // 重新 scriptLoad 获取新的 SHA1
        Object resultObj = rScript.evalSha(...);  // 用新 SHA1 重试
        return convertToLong(resultObj);
    }
    throw e;
}
```

触发场景：Redis 重启后脚本缓存被清空，但切面的 `luaScriptSha` 还是旧的。此时 `evalSha` 发给 Redis 会得到 `NOSCRIPT No matching script`。

容错链路：

```
evalSha(旧SHA1) → Redis 返回 NOSCRIPT 错误
    │
    ▼
catch 捕获 RedisException
    │
    ▼
错误消息包含 "NOSCRIPT" ？
    │
   否 ──► throw e，向上抛给 around()，最终 500
    │
   是 ──► loadScript()
          │  → rScript.scriptLoad(LUA_SCRIPT)
          │  → 更新 luaScriptSha = 新SHA1
          │
          ▼
         evalSha(新SHA1) → 重新执行，成功返回
```

注意这里是**只重试一次**。如果 `loadScript` 本身失败（比如 Redis 还没恢复），异常会直接抛出，不会死循环。

***

### 两条路径的对比

| <br /> | 正常路径             | NOSCRIPT 容错路径                       |
| ------ | ---------------- | ----------------------------------- |
| 触发条件   | 每次限流请求           | Redis 重启后第一次（之后恢复正常）                |
| 调用次数   | 每次 1 次 `evalSha` | 额外 1 次 `scriptLoad` + 1 次 `evalSha` |
| 对请求的影响 | 无                | 多了 1 次 Redis 往返（毫秒级）                |

***

### 为什么不直接 try-catch 包裹重试而是只处理 NOSCRIPT

NOSCRIPT 是可恢复的瞬时错误（重载脚本即可），其他异常（连接超时、OOM、READONLY）表示 Redis 真的出问题了，不应该重试，直接向上抛让全局异常处理器统一返回 500，避免请求在已经故障的 Redis 上反复无效重试导致线程堆积。
