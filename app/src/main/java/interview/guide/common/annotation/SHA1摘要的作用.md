### SHA1 摘要是什么

SHA1 是一种哈希算法，给任意一段数据算出固定 40 个字符（160 bit）的"指纹"。比如：

```
Lua 脚本内容（可能几百行）
        │
        ▼  SHA1 哈希
  "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0"
```

同一个脚本永远算出同一个 SHA1，脚本改一个字 SHA1 就完全不同。Redis 用它作为 Lua 脚本的"编号"，免去每次执行时传输完整脚本。

---

### 在这个限流代码里是怎么用的

流程分两步，在 [RateLimitAspect](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/aspect/RateLimitAspect.java#L69-L81) 中：

**第一步：启动时加载脚本（只传一次）**

```java
@PostConstruct
public void init() {
    rScript = redissonClient.getScript(StringCodec.INSTANCE);
    loadScript();
}

private void loadScript() {
    this.luaScriptSha = rScript.scriptLoad(LUA_SCRIPT);  // 传完整脚本，Redis 缓存起来
    // Redis 返回："a1b2c3d4..."
}
```

实际发生的事：

```
应用启动
    │
    ▼
scriptLoad(完整Lua脚本)  ──►  Redis 内部：
    │                          1. 缓存脚本全文
    │                          2. 计算 SHA1 = "a1b2c3..."
    │                          3. 返回 SHA1
    ▼
应用保存 SHA1：luaScriptSha = "a1b2c3..."
```

**第二步：每次请求用 SHA1 执行（不传脚本）**

```java
// 第 174 行：每次请求只传 SHA1，不传脚本内容
Object resultObj = rScript.evalSha(
    RScript.Mode.READ_WRITE,
    luaScriptSha,          // ← 只传 40 个字符的 SHA1
    RScript.ReturnType.VALUE,
    keysList,
    args
);
```

实际发生的事：

```
每次限流请求
    │
    ▼
evalSha("a1b2c3...", keys, args)  ──►  Redis 内部：
    │                                   1. 用 SHA1 查找已缓存的脚本
    │                                   2. 找到，执行脚本
    │                                   3. 返回结果
    ▼
拿到限流结果
```

---

### 为什么不用直接传脚本（EVAL vs EVALSHA）

| 方式 | 每次传输内容 | 带宽消耗 |
|------|-------------|---------|
| `EVAL` | 完整脚本（几百字节） | 大 |
| `EVALSHA` | 40 字符 SHA1 | 小 |

限流是高频操作（每个请求打一次），用 EVALSHA 每次省几百字节，日积月累很可观。同时 Redis 内部用 SHA1 做索引查找缓存脚本，O(1) 时间。

---

### NOSCRIPT 容错 —— SHA1 失效的情况

SHA1 缓存在 Redis 内存里，Redis 重启后缓存清空，SHA1 就失效了。这时 `evalSha` 会报 `NOSCRIPT` 错误。

代码中的处理（[RateLimitAspect.java](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/aspect/RateLimitAspect.java#L182-L197)）：

```java
try {
    return evalSha(...);  // 正常走 SHA1
} catch (RedisException e) {
    if (e.getMessage().contains("NOSCRIPT")) {
        loadScript();     // 重新 scriptLoad，拿到新 SHA1
        return evalSha(...); // 用新 SHA1 重试一次
    }
    throw e;
}
```

Redis 重启后的完整链路：

```
Redis 重启 → 脚本缓存清除
    │
    ▼
evalSha("旧SHA1") → Redis 返回 NOSCRIPT 错误
    │
    ▼
切面捕获 NOSCRIPT → 调用 scriptLoad 重新加载 → 拿到新 SHA1
    │
    ▼
evalSha("新SHA1") → Redis 找到脚本 → 正常执行返回
    │
    ▼
后续请求用新 SHA1，不再触发 NOSCRIPT
```