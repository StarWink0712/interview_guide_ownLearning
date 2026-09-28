# createProvider、updateProvider、deleteProvider：完整生命周期调用链

三个接口共同完成 Provider 的新增、部分更新和删除。它们的共同主线是：

```text
SettingsPage 用户操作
  → 前端表单校验和请求体组装
  → llmProviderApi
  → Controller + GLOBAL 5/s 限流
  → LlmProviderConfigService 写锁
  → 数据库事务，或 Legacy YAML/.env 写入
  → LlmProviderRegistry.reload() 清缓存
  → Result<Void>
  → toast
  → loadData() 重新查询并刷新页面
```

---

## 1. Controller 方法与请求 DTO

```java
@PostMapping
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
public Result<Void> createProvider(
    @RequestBody @Valid CreateProviderRequest request)

@PutMapping("/{id}")
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
public Result<Void> updateProvider(
    @PathVariable String id,
    @RequestBody UpdateProviderRequest request)

@DeleteMapping("/{id}")
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
public Result<Void> deleteProvider(@PathVariable String id)
```

创建请求的 `id`、`baseUrl`、`apiKey`、`model` 有 `@NotBlank`，并且 Controller 使用 `@Valid`。更新请求全部可选，Controller 没有 `@Valid`；最终合法性由 Service 针对请求字段和合并后的 Entity 检查。

| 字段 | 创建 | 更新 | 语义 |
|---|---|---|---|
| `id` | 必填 | 不可修改，来自路径 | Provider 主键 |
| `baseUrl` | 必填 | `null` 不改，空白拒绝 | OpenAI 兼容 API 基地址 |
| `apiKey` | 必填 | `null` 不改，空白拒绝 | 数据库模式加密存储 |
| `model` | 必填 | `null` 不改，空白拒绝 | 聊天模型 |
| `embeddingModel` | 可选 | `null` 不改，空字符串可清除 | 向量模型 |
| `embeddingDimensions` | 可选 | `null` 不改 | 无效值回退全局维度 |
| `supportsEmbedding` | 可选 | `null` 不改 | 是否允许构建 EmbeddingModel |
| `temperature` | 可选 | `null` 不改 | ChatOptions 温度 |

---

## 2. createProvider：前端如何发起

用户点击“新增 Provider”，`openCreateModal()` 清空表单，并把向量维度默认显示为 `1024`。保存时执行 `handleCreate()`：

```text
handleCreate()
  → 检查 id/baseUrl/apiKey/model 去空白后非空
  → supportsEmbedding=true 时要求 embeddingModel
  → supportsEmbedding=true 时要求 dimensions 为正整数
  → 组装 CreateProviderRequest
      ├─ 所有字符串 trim
      ├─ supportsEmbedding 始终发送布尔值
      ├─ embeddingModel 非空才发送
      ├─ embeddingModel 非空时发送 dimensions
      └─ temperature 能解析为数字才发送
  → llmProviderApi.create(data)
  → POST /api/llm-provider
```

页面校验只是改善交互，不能替代后端校验；其他客户端可以直接调用接口。

---

## 3. createProvider：数据库模式逐层流程

```text
Controller.createProvider(request)
  → @Valid
      → @NotBlank 校验四个必填字段
      → 失败时 GlobalExceptionHandler 返回 BAD_REQUEST
  → configService.createProvider(request)
      → Spring 事务代理开启事务
      → rwLock.writeLock().lock()
      → isDatabaseBacked() == true
```

Service 内部继续执行：

### 3.1 规范化与重复检查

```text
providerId = trimOrNull(request.id())
  → providerRepository.existsById(providerId)
  → 已存在：PROVIDER_ALREADY_EXISTS

baseUrl = requireNonBlank(...)
model   = requireNonBlank(...)
apiKey  = requireNonBlank(...)
```

即使 Jakarta Validation 已检查，Service 仍做一次去空白后的防御性检查。

### 3.2 计算最终 Embedding 配置

```text
embeddingModel = trimOrNull(request.embeddingModel)
embeddingDimensions = resolveEmbeddingDimensions(request.embeddingDimensions)
supportsEmbedding =
  request.supportsEmbedding != null
    ? request.supportsEmbedding
    : embeddingModel != null
```

`resolveEmbeddingDimensions()` 在请求维度为 `null`、0 或负数时，不是报错，而是回退 `app.ai.embedding-dimensions`。

随后 `validateEmbeddingConfig()` 校验：

1. `supportsEmbedding=false` 时直接跳过。
2. 支持向量时，`embeddingModel` 必须非空。
3. 模型名不能被规则识别为聊天模型，例如 `glm-*`、`deepseek*`、`kimi*`、`moonshot*`、`qwen*`、`ernie*`。
4. 最终向量维度必须为正整数。

这只是名称启发式检查，不会请求远端验证模型是否真实存在。

### 3.3 加密与保存

```text
encryptionService.encrypt(apiKey)
  → 生成 12 字节随机 nonce
  → AES/GCM/NoPadding 加密
  → nonce、ciphertext 分别 Base64

providerRepository.save(
  LlmProviderEntity.builder()
    .id(providerId)
    .apiKeyNonce(...)
    .apiKeyCiphertext(...)
    .enabled(true)
    .builtin(false)
    ...
)
```

JPA 的 `@PrePersist` 补充 `createdAt` 和 `updatedAt`。

### 3.4 让运行时感知变化

```text
registry.reload()
  → clientCache.clear()
  → embeddingModelCache.clear()
```

它不立即创建新 Client。下一个业务请求按 Provider ID 调用 Registry 时，才从数据库加载新 Entity、解密 Key 并构建模型客户端。

Service 最后释放写锁，方法正常返回后数据库事务提交。

---

## 4. createProvider：Legacy 模式差异

Legacy 分支在数据库分支的完整后端校验之前就返回：

```text
createProviderLegacy(request)
  → properties.providers.containsKey(request.id)
  → new ProviderConfig()
  → properties.providers.put(request.id, config)
  → envKey = PROVIDER_{大写ID}_API_KEY
  → writeProviderToYaml(id, config, envKey)
      → 写 app.ai.providers.{id} 配置块
      → api-key 写成 ${ENV_KEY} 引用
  → writeEnvValue(envKey, 明文Key)
  → registry.reload()
```

重要差异：

- 使用原始 `request.id()` 作为 Map Key，没有像数据库分支那样统一 trim 后再保存。
- 依靠 Controller 的 `@Valid` 检查创建必填字段。
- 没有调用数据库分支的 `validateEmbeddingConfig()`，因此向量模型名称和最终维度校验不完全一致。
- YAML 与 `.env` 不是原子写入；内存 Map 也在文件写入前已修改。
- `.env` 写失败只记录 warn，不抛业务异常，所以接口仍可能返回成功。
- `writeProviderToYaml()` 没有写出 `supports-embedding` 字段；当前进程保留内存值，但文件内容不能完整表达该配置。
- 当前 `spring.config.import` 没有导入这两个外部文件，所以重启不会自动从它们恢复 Legacy 修改；需要额外配置导入机制才算真正持久化。

---

## 5. createProvider 返回页面

成功时 Controller 返回 `Result.success()`，`data=null`。前端：

```text
await llmProviderApi.create(data)
  → showToast("Provider 创建成功")
  → closeModal()
  → await loadData()
  → 重新并行加载 Provider/default/ASR/TTS
```

因此“新增卡片出现在页面上”不是 create 接口直接返回新 DTO，而是后续列表查询的结果。

---

## 6. updateProvider：前端请求体如何生成

当前编辑并不调用 `GET /{id}`：

```text
用户点击卡片“编辑”
  → openEditModal(providerFromList)
  → 用列表 DTO 回填 baseUrl/model/embedding/temperature
  → API Key 输入框留空
```

保存时 `handleUpdate()`：

1. 要求 `baseUrl`、`model` 非空。
2. 支持向量时要求向量模型和正维度。
3. 总是发送 `baseUrl`、`model`、`embeddingModel`、`supportsEmbedding`。
4. 只有 Key 输入框非空才发送 `apiKey`，避免把脱敏值当成新 Key。
5. 只有支持向量时才发送 `embeddingDimensions`。

```text
llmProviderApi.update(editingProvider.id, data)
  → PUT /api/llm-provider/{id}
```

---

## 7. updateProvider：数据库模式逐层流程

```text
Controller.updateProvider(id, request)
  → configService.updateProvider(id, request)
      → @Transactional
      → 获取写锁
      → getProviderEntityOrThrow(id)
          → providerRepository.findById(id)
          → 不存在抛 PROVIDER_NOT_FOUND
```

然后先检查三个不能被清空的字段：

```text
request.baseUrl != null && trim 后为空 → BAD_REQUEST
request.model   != null && trim 后为空 → BAD_REQUEST
request.apiKey  != null && trim 后为空 → BAD_REQUEST
```

按字段合并：

```text
baseUrl/model/apiKey：非 null 且非空才覆盖
embeddingModel：请求非 null 就覆盖；空字符串 trim 后变 null，可用于清除
embeddingDimensions：请求非 null就 resolve 后覆盖
supportsEmbedding：请求非 null就覆盖
temperature：请求非 null就覆盖
```

关键点是 Service 会对“合并后的最终 Entity”调用 `validateEmbeddingConfig()`。例如：

- 把 `supportsEmbedding` 改成 true，但没有已有/新 `embeddingModel`，请求失败。
- 清空 `embeddingModel`，但仍保持 `supportsEmbedding=true`，请求失败。
- 先把 `supportsEmbedding=false`，允许 `embeddingModel` 为空。

若提交新 Key：

```text
ApiKeyEncryptionService.encrypt(newKey)
  → 替换 nonce + ciphertext
```

最后：

```text
providerRepository.save(provider)
  → registry.reload()
  → 返回
  → 事务提交
```

`id` 是路径变量且不会写入 Entity，所以接口不支持修改 Provider 主键。

---

## 8. updateProvider：Legacy 模式差异

```text
updateProviderLegacy(id, request)
  → properties.providers.get(id)
  → 同样拒绝清空 baseUrl/model/apiKey
  → 合并非 null 字段到 ProviderConfig 内存对象
  → 新 Key 非 null时 updateEnvValue(PROVIDER_{ID}_API_KEY)
  → writeProviderToYaml(...)
  → registry.reload()
```

该分支没有对最终配置调用 `validateEmbeddingConfig()`，所以数据库模式与 Legacy 模式的向量配置约束并不完全相同。

此外 `writeProviderToYaml()` 只有在 `embeddingModel` 非 null 时写入该键，但它用 `setBlock()` 重写配置块；应以最终文件结果为准，不能假设所有旧字段都会保留。

---

## 9. deleteProvider：前端到 Controller

页面先显示确认对话框：

```text
点击删除
  → setDeleteConfirmId(id)
  → 用户确认
  → handleDelete()
  → llmProviderApi.delete(id)
  → DELETE /api/llm-provider/{id}
```

成功后关闭确认框、显示“Provider 已删除”，再调用 `loadData()`。

---

## 10. deleteProvider：数据库模式逐层流程

```text
configService.deleteProvider(id)
  → @Transactional
  → 获取写锁
  → getGlobalSettingOrThrow()
      → 查询 llm_global_setting(id=1)
  → id 与 defaultChatProviderId 比较
  → id 与 defaultEmbeddingProviderId 比较
      → 任一相等：PROVIDER_DEFAULT_CANNOT_DELETE
  → getProviderEntityOrThrow(id)
      → 确认 Provider 存在
  → providerRepository.deleteById(id)
  → registry.reload()
  → 释放写锁
  → 事务提交
```

必须先把聊天默认和向量默认都切换走，才能删除。即使 Provider 只是其中一个默认值，也会被拒绝。

数据库外键并没有在这段 Service 中显式维护；删除保护完全依赖全局设置中的两个 ID 比较。

---

## 11. deleteProvider：Legacy 模式差异

```text
deleteProviderLegacy(id)
  → 只检查 id == properties.defaultProvider
  → 确认 Map 中存在
  → properties.providers.remove(id)
  → removeProviderFromYaml(id)
  → removeFromEnv(PROVIDER_{ID}_API_KEY)
  → registry.reload()
```

当前代码只阻止删除 Legacy 默认聊天 Provider，没有检查 `properties.defaultEmbeddingProvider`。这与数据库分支和 Controller 注释所描述的“双默认保护”不一致。如果 Legacy 模式删除了默认向量 Provider，后续 `getDefaultEmbeddingModel()` 可能找不到配置。

`.env` 删除失败同样只记录 warn，接口可能仍返回成功。

---

## 12. 事务、锁与 Registry 的真实时序

数据库 CRUD 都有 `@Transactional`，也使用 JVM 写锁，但仍有边界：

```text
数据库 save/delete
  → registry.reload() 清缓存
  → Service 返回
  → Spring 提交事务
```

这意味着 Registry 缓存清理发生在事务提交之前。通常下一次业务请求在提交后才重建客户端，但它不是严格的“数据库提交与缓存失效原子操作”：

- 并发线程可能在事务尚未提交时发现缓存为空并读取旧数据库状态。
- 如果事务最终回滚，缓存已经被清空；下次会按回滚后的数据库状态重建。
- 已经获取旧 ChatClient 的在途请求不受清缓存影响。
- 多实例部署时只清当前实例，其他实例仍可能持有旧缓存。

写锁只能串行化当前 `LlmProviderConfigService` 实例内的写操作，不能替代分布式配置版本或事件通知。

---

## 13. 错误回传闭环

| 场景 | 后端错误 | 页面表现 |
|---|---|---|
| 创建字段为空 | `BAD_REQUEST` | 保存 Promise reject，显示后端消息 |
| ID 重复 | `PROVIDER_ALREADY_EXISTS` | 创建弹窗保留，显示错误 toast |
| 更新/删除不存在 | `PROVIDER_NOT_FOUND` | 显示错误 toast |
| 向量配置非法 | `BAD_REQUEST` | 显示具体模型或维度提示 |
| 删除默认 Provider | `PROVIDER_DEFAULT_CANNOT_DELETE` | 提示先切换默认值 |
| 加密失败 | `PROVIDER_CONFIG_WRITE_FAILED` | 写操作失败 |
| YAML 写失败 | Provider/Voice 配置写错误 | Legacy 写操作失败，但内存可能已经改变 |

全局异常处理器把业务异常包装成 HTTP 200 的 `Result.error`；Axios 根据 `code !== 200` 转成 JavaScript `Error`。

---

## 14. 生命周期闭环总结

```text
新增
  → 校验 → 加密/文件写入 → 持久化 → 清缓存 → 列表刷新

编辑
  → 列表 DTO 回填 → 合并非 null字段 → 最终配置校验
  → 新 Key 才重新加密 → 保存 → 清缓存 → 列表刷新

删除
  → 双默认保护（数据库模式）→ 删除记录/文件块/环境变量
  → 清缓存 → 列表刷新

下一次真实 LLM/Embedding 调用
  → Registry 缓存 miss
  → 读取最新配置
  → 创建新的 ChatClient/EmbeddingModel
```

页面上的“保存成功”完成了管理接口闭环；真正验证远端地址、Key 和模型是否可调用，还需要继续执行 `testProvider()`。
