# reloadProviders：缓存失效而不是“重新加载全部配置”

接口：

```text
POST /api/llm-provider/reload
```

这个方法代码很短，但最容易被名称和旧注释误解。它的准确语义是：清空当前应用实例中 Registry 的 ChatClient 与 EmbeddingModel 缓存，让后续首次访问重新创建；它不会立刻创建任何客户端，也不会自动重新绑定 Spring 的 YAML 配置对象。

---

## 1. Controller 与 Service

```java
@PostMapping("/reload")
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
public Result<Void> reloadProviders() {
  configService.reloadProviders();
  return Result.success();
}
```

```java
public void reloadProviders() {
  registry.reload();
  log.info("Manual provider reload triggered");
}
```

它没有请求体，GLOBAL 限流 5/s，也没有 `@Transactional` 和 `rwLock`。

---

## 2. 当前前端是否会触发

`frontend/src/api/llmProvider.ts` 定义了：

```typescript
reload: () => request.post<void>('/api/llm-provider/reload')
```

但当前 `frontend/src` 中没有 `llmProviderApi.reload()` 的调用点，`SettingsPage` 也没有热重载按钮。因此实际触发方只能是其他客户端、调试工具或未来新增的页面功能。

创建、更新、删除和切换默认 Provider 的 Service 方法本身会调用 `registry.reload()`，正常页面操作不需要再调用这个接口。

---

## 3. 完整 HTTP 调用链

```text
调用方 POST /api/llm-provider/reload
  → Axios/HTTP 客户端
  → Spring MVC
  → RateLimitAspect
      → ratelimit:{LlmProviderController:reloadProviders}:global
      → Redis Lua 检查 5 次/秒
  → LlmProviderController.reloadProviders()
  → LlmProviderConfigService.reloadProviders()
  → LlmProviderRegistry.reload()
  → Result.success()
  → HTTP 200 + code=200
```

---

## 4. Registry.reload 的真实代码效果

核心逻辑是：

```java
public void reload() {
  int size = clientCache.size() + embeddingModelCache.size();
  clientCache.clear();
  embeddingModelCache.clear();
  log.info("... Cache cleared ... Next access will re-create clients.", size);
}
```

被清理的两类缓存：

```text
clientCache: ConcurrentHashMap<String, ChatClient>
  ├─ providerId
  ├─ providerId:plain
  └─ providerId:voice

embeddingModelCache: ConcurrentHashMap<String, EmbeddingModel>
  └─ providerId
```

方法执行完时缓存为空，没有任何新对象。

---

## 5. 清缓存后的懒加载闭环

下一次业务请求才真正读取最新数据：

### 5.1 ChatClient

```text
业务 Service
  → registry.getChatClient(providerId)
     / getPlainChatClient(providerId)
     / getVoiceChatClient(providerId)
  → clientCache.computeIfAbsent(cacheKey, ...)
  → create*ChatClient(providerId)
  → buildChatModel(providerId)
  → loadProviderOrThrow(providerId)
      ├─ DB：providerRepository.findById + 解密 API Key
      └─ Legacy：properties.providers.get(providerId)
  → ApiPathResolver.buildOpenAiApi(baseUrl, apiKey)
  → OpenAiChatModel
  → ChatClient
  → 放回 clientCache
```

### 5.2 EmbeddingModel

```text
知识库业务
  → registry.getEmbeddingModel(providerId)
     / getDefaultEmbeddingModel()
  → embeddingModelCache.computeIfAbsent(...)
  → createEmbeddingModel(providerId)
  → 重新加载 Provider 配置
  → 校验 supportsEmbedding、embeddingModel
  → OpenAiEmbeddingModel
  → 放回 embeddingModelCache
```

所以“热重载生效”的最终标志不是 reload 接口返回，而是后续请求成功建立并使用了新客户端。

---

## 6. 数据库模式下能刷新什么

数据库模式中，后续 cache miss 会重新执行 Repository 查询。因此：

```text
数据库已经提交的新 baseUrl/model/apiKey
  → reload 清缓存
  → 下一次模型调用查询最新 Entity
  → 使用新配置创建客户端
```

默认 Provider ID 不只存在缓存里。`resolveDefaultChatProviderId()` 和 `resolveDefaultEmbeddingProviderId()` 会查询 `llm_global_setting(id=1)`；切换默认值后清缓存，可避免旧 Provider 的 Client 继续留在缓存中。

如果有人绕过 Service 直接改数据库，调用本接口能让当前实例的下一次客户端创建读取新行，但仍需保证数据库修改已提交且数据合法。

---

## 7. Legacy/YAML 模式下不能自动做什么

Legacy Registry 的加载代码是：

```text
loadProviderFromPropertiesOrThrow(providerId)
  → properties.getProviders().get(providerId)
```

这里读取的是 Spring 启动时绑定好的 `LlmProviderProperties` 内存对象，不是每次打开 `config-yaml-path` 重新解析。因此：

```text
在进程外直接编辑 YAML 文件
  → 调用 /reload
  → 只清缓存
  → 下一次仍从旧的 properties 内存对象创建 Client
```

也就是说，旧文档中“直接修改 YAML 后调用 reload 即可读取新配置”的说法并不符合当前实现。只有下面两类变化能够被下一次创建看到：

1. 通过本 Service 的 Legacy CRUD 修改，因为它同时修改了 `properties.providers` 内存 Map。
2. 存在其他机制重新绑定 `LlmProviderProperties`；当前 `reload()` 自身没有这个机制。

当前 `application.yml` 的 `spring.config.import` 也没有导入 `config-yaml-path`/`config-env-path` 指向的外部文件，所以应用重启本身同样不会自动读取这些写出文件。若部署时未额外配置外部配置源，重启会重新使用主 `application.yml` 和进程环境变量。

---

## 8. 对正在执行请求的影响

`ConcurrentHashMap.clear()` 只移除 Map 中的引用，不会销毁其他线程已经取得的对象：

```text
请求 A 已拿到旧 ChatClient 并调用远端模型
  → 此时请求 B 调用 reload
  → Map 被清空
  → 请求 A 继续使用旧对象直到完成
  → 请求 C 再获取时创建新对象
```

因此它提供的是“后续访问切换”，不是中断或迁移在途请求。

Spring AI Client 在这里也没有显式 `close()` 生命周期调用；代码只是丢弃缓存引用，之后由 JVM 管理不可达对象。

---

## 9. 并发边界

Registry 使用 `ConcurrentHashMap`，`clear()` 与 `computeIfAbsent()` 本身不会破坏 Map 结构，但不能保证跨两个 Map 的全局原子快照：

- reload 期间另一个线程可能正创建 ChatClient。
- 某个创建操作可能在 clear 前开始、clear 后把对象重新放入 Map。
- Service 的手动 `reloadProviders()` 没有取得 `rwLock`，所以可与其他配置读写并发。

正常 CRUD 是在 Service 写锁内调用 reload，能减少同一个 Service 内的交错；手动接口没有这层串行化。

---

## 10. 单实例与多实例

缓存 Map 是每个 JVM 自己的内存：

```text
实例 A 调用 /reload
  → 只清实例 A

实例 B、C
  → 不收到事件
  → 仍保留各自缓存
```

Controller 注释提到“多实例统一重载”，但当前代码没有 Redis Pub/Sub、配置版本轮询或消息广播。要让所有实例刷新，必须逐实例调用，或者另行实现广播机制。

限流计数可以因共享 Redis 而跨实例，但缓存刷新本身不是跨实例的。

---

## 11. 错误与返回

`reload()` 只执行内存 Map 操作，通常不会访问数据库或远端模型，所以接口成功不代表：

- Provider 配置可解密。
- baseUrl 可达。
- model 存在。
- Embedding 维度正确。

这些问题会延迟到下一次懒加载或真实调用时暴露。

成功响应：

```json
{ "code": 200, "message": "success", "data": null }
```

调用方若要验证闭环，应在 reload 后再执行：

```text
GET /list                → 验证管理数据
POST /{id}/test          → 验证最小聊天 HTTP 调用
真实业务请求             → 验证 Spring AI Client 和业务链
```

---

## 12. 准确的一句话总结

```text
reloadProviders
  = 清空当前实例的 ChatClient/EmbeddingModel 缓存
  + 让后续首次访问按当前数据源懒创建
  ≠ 重新读取磁盘 YAML
  ≠ 立即重建全部客户端
  ≠ 中断在途请求
  ≠ 自动通知其他实例
  ≠ 验证新配置可用
```
