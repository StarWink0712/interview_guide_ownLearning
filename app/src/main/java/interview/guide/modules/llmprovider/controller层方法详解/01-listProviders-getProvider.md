# listProviders 与 getProvider：Provider 查询完整闭环

这两个接口都返回 `ProviderDTO`，但使用场景不同：

- `listProviders()` 是设置页真正使用的主查询接口。
- `getProvider(id)` 已经有前端 API 封装，但当前 `SettingsPage` 没有调用；编辑弹窗直接使用列表中的对象回填。

---

## 1. 接口定义

```java
@GetMapping("/list")
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
public Result<List<ProviderDTO>> listProviders() {
  return Result.success(configService.listProviders());
}

@GetMapping("/{id}")
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
public Result<ProviderDTO> getProvider(@PathVariable String id) {
  return Result.success(configService.getProvider(id));
}
```

完整路径分别是：

```text
GET /api/llm-provider/list
GET /api/llm-provider/{id}
```

两者都是 GLOBAL 30 次/秒。限流 Key 按 Controller 方法区分，所以 `listProviders` 和 `getProvider` 各有独立计数窗口。

---

## 2. listProviders 的前端起点

页面加载时：

```text
SettingsPage.useEffect()
  → loadData()
  → llmProviderApi.list()
  → request.get<ProviderItem[]>("/api/llm-provider/list")
```

它与默认 Provider、ASR、TTS 三个查询并行执行。请求成功后：

```text
setProviders(providerList)
  → providers.map(...)
  → 每个 Provider 渲染一张卡片
      ├─ Base URL
      ├─ 聊天模型
      ├─ 是否支持向量化
      ├─ Embedding 模型与维度
      ├─ temperature
      ├─ 脱敏 API Key
      └─ 默认聊天/默认向量标记
```

创建、更新、删除、切换默认值和保存语音配置后，页面都会再次 `await loadData()`，所以 `listProviders()` 也是这些写操作的页面刷新出口。

---

## 3. listProviders 进入后端后的公共链路

```text
HTTP GET /api/llm-provider/list
  → Spring MVC 匹配 LlmProviderController.listProviders()
  → RateLimitAspect.around()
      → key = ratelimit:{LlmProviderController:listProviders}:global
      → Redis Lua 检查 1 秒滑动窗口
  → configService.listProviders()
      → rwLock.readLock().lock()
      → 读取并组装 DTO
      → finally 释放读锁
  → Result.success(List<ProviderDTO>)
  → HTTP 200
```

读锁允许多个查询并发执行，但会与创建、修改、删除等写锁互斥。它只保护当前 JVM 中的 Service 实例。

---

## 4. 数据库模式的逐层调用

正常 Spring 运行时三个数据库依赖都存在，因此进入数据库分支：

```text
LlmProviderConfigService.listProviders()
  → isDatabaseBacked() == true
  → getGlobalSettingOrThrow()
      → globalSettingRepository.findById(1L)
      → 读取 llm_global_setting 单例行
  → providerRepository.findAll()
      → 查询 llm_provider_config 全表
  → 对每个 LlmProviderEntity 映射 ProviderDTO
```

单条 Entity 的映射过程：

```text
LlmProviderEntity
  ├─ id                         → ProviderDTO.id
  ├─ baseUrl                    → ProviderDTO.baseUrl
  ├─ apiKeyNonce + ciphertext
  │    → encryptionService.decrypt(...)
  │    → maskApiKey(...)
  │    → ProviderDTO.maskedApiKey
  ├─ model                      → ProviderDTO.model
  ├─ embeddingModel             → ProviderDTO.embeddingModel
  ├─ embeddingDimensions
  │    → resolveEmbeddingDimensions(...)
  │    → 无效/空时回退 app.ai.embedding-dimensions
  ├─ supportsEmbedding          → ProviderDTO.supportsEmbedding
  ├─ temperature                → ProviderDTO.temperature
  ├─ id == defaultChatProvider  → ProviderDTO.defaultChatProvider
  └─ id == defaultEmbedding     → ProviderDTO.defaultEmbeddingProvider
```

API Key 的数据库链路是：

```text
数据库密文
  → AES-GCM 解密成明文
  → 立即脱敏
  → 只把脱敏值放入 DTO
```

脱敏规则：

- 长度大于 6：前三位 + `***` + 后三位。
- `null` 或长度小于等于 6：统一返回 `***`。

前端永远拿不到完整 Key，编辑时 API Key 输入框初始为空；只有用户填写新 Key 时才会覆盖数据库中的旧 Key。

### 4.1 查询结果顺序

这里使用 `providerRepository.findAll()`，没有 `OrderBy`，Service 也没有 `sorted()`。因此接口没有承诺稳定排序。前端按后端返回顺序渲染卡片，不应依赖某个 Provider 永远排第一。

### 4.2 全局设置缺失

如果 `llm_global_setting` 中不存在 `id=1`：

```text
getGlobalSettingOrThrow()
  → BusinessException(PROVIDER_CONFIG_READ_FAILED,
      "默认 Provider 配置未初始化")
```

这会使整个列表查询失败，而不是仅丢失两个默认标记。

---

## 5. Legacy 模式的逐层调用

当 Repository 或加密服务未注入时：

```text
LlmProviderConfigService.listProviders()
  → isDatabaseBacked() == false
  → properties.getProviders()
      → Map<String, ProviderConfig>
  → entrySet().stream()
  → 每个 Map.Entry 映射 ProviderDTO
  → toList()
```

默认标记来自：

```text
entry.id == properties.defaultProvider
entry.id == properties.defaultEmbeddingProvider
```

`supportsEmbedding` 不是只看显式布尔值，而是：

```text
supportsEmbedding == true
  或 embeddingModel 去空白后非 null
```

这里的 `properties` 是 Spring 已绑定的内存对象。查询时不会重新读取 `config-yaml-path` 指向的 YAML 文件。通过本 Service 的 Legacy CRUD 修改时，它会同时修改内存 Map；如果在进程外直接改磁盘 YAML，单纯调用列表接口不会看到新内容。

---

## 6. listProviders 返回页面的闭环

后端成功响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": "dashscope",
      "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
      "maskedApiKey": "sk-***xyz",
      "model": "qwen3.5-flash",
      "embeddingModel": "text-embedding-v3",
      "embeddingDimensions": 1024,
      "supportsEmbedding": true,
      "temperature": null,
      "defaultChatProvider": true,
      "defaultEmbeddingProvider": true
    }
  ]
}
```

`request.ts` 的响应拦截器执行：

```text
result.code == 200
  → response.data = result.data
  → llmProviderApi.list() 最终 resolve ProviderItem[]
```

页面随后调用 `setProviders()`。这里没有额外的数据转换，Java `ProviderDTO` 字段名与 TypeScript `ProviderItem` 一一对应。

---

## 7. getProvider 的完整链路

前端封装存在：

```typescript
get: (id: string) =>
  request.get<ProviderItem>(`/api/llm-provider/${id}`)
```

但对整个 `frontend/src` 搜索后，目前没有调用 `llmProviderApi.get(...)` 的页面代码。`openEditModal(provider)` 直接接收列表对象并回填表单。因此下面链路可供其他调用方使用，但不是当前编辑弹窗的实际路径。

### 7.1 数据库模式

```text
GET /api/llm-provider/{id}
  → Controller.getProvider(id)
  → configService.getProvider(id)
      → 获取读锁
      → getGlobalSettingOrThrow()
          → globalSettingRepository.findById(1L)
      → getProviderEntityOrThrow(id)
          → providerRepository.findById(id)
          → 不存在则抛 PROVIDER_NOT_FOUND
      → decryptApiKey(entity)
          → ApiKeyEncryptionService.decrypt(nonce, ciphertext)
      → maskApiKey(明文)
      → 构造 ProviderDTO
      → 释放读锁
  → Result.success(dto)
```

注意查询顺序：先读取全局设置，再按 ID 查询 Provider。即使目标 Provider 不存在，只要全局设置也损坏，可能先得到“默认 Provider 配置未初始化”。

### 7.2 Legacy 模式

```text
getProvider(id)
  → properties.getProviders().get(id)
  → null 时抛 BusinessException(PROVIDER_NOT_FOUND)
  → API Key 脱敏
  → 与 properties 中两个默认 ID 比较
  → 构造 ProviderDTO
```

路径变量不会 `trim()`。`/dashscope` 与包含空格或大小写不同的 ID 都按原字符串精确查询。

---

## 8. 异常怎样回到前端

| 失败点 | Service 行为 | 最终页面行为 |
|---|---|---|
| Provider 不存在 | `BusinessException(PROVIDER_NOT_FOUND)` | Axios reject，调用方进入 `catch` |
| 全局设置不存在 | `PROVIDER_CONFIG_READ_FAILED` | `loadData()` 整体失败 |
| API Key 解密失败 | `PROVIDER_CONFIG_READ_FAILED` | 列表或详情整体失败 |
| Redis 限流拒绝 | `RateLimitExceededException` | 全局异常处理后返回业务错误 |
| 未知异常 | 全局异常处理器兜底 | Axios 抛后端 message 或通用错误 |

因为后端约定业务异常仍使用 HTTP 200，前端不能只看 HTTP 状态；实际由 Axios 拦截器检查 `Result.code`。

---

## 9. 这两个方法的边界

1. 它们只返回 DTO，不返回 Entity，也不暴露完整 API Key。
2. 它们不会验证 Provider 的远端可用性；连通性由 `testProvider()` 单独完成。
3. 它们不会创建 ChatClient；真正业务首次使用时由 `LlmProviderRegistry` 懒加载。
4. `listProviders()` 的两个默认布尔值来自全局设置，但 SettingsPage 同时还会调用 `getDefaultProvider()` 保存独立 state。
5. 当前编辑页面依赖列表数据而不是详情接口，所以列表 DTO 一旦缺字段，编辑回填也会受影响。

最终闭环可以概括为：

```text
页面加载/写后刷新
  → list API
  → Controller 限流与委托
  → Service 加读锁
  → DB 解密后脱敏，或读取 Legacy 内存配置
  → ProviderDTO
  → Result<T>
  → Axios 解包
  → React state
  → Provider 卡片与编辑表单
```
