# 默认聊天 Provider 与默认向量 Provider：完整调用闭环

本文件覆盖三个接口：

```text
GET /api/llm-provider/default-provider
PUT /api/llm-provider/default-provider
PUT /api/llm-provider/default-embedding-provider
```

系统维护两个独立默认值：

- `defaultProvider`：没有显式指定 Provider 时，聊天/评估/出题等调用使用它。
- `defaultEmbeddingProvider`：知识库向量化和默认向量模型获取使用它。

它们都通过同一个 `DefaultProviderDTO` 传输，但 Service 更新时各自只读取目标字段。

---

## 1. Controller 方法

```java
@GetMapping("/default-provider")
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
public Result<DefaultProviderDTO> getDefaultProvider()

@PutMapping("/default-provider")
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
public Result<Void> updateDefaultProvider(
    @RequestBody DefaultProviderDTO request)

@PutMapping("/default-embedding-provider")
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
public Result<Void> updateDefaultEmbeddingProvider(
    @RequestBody DefaultProviderDTO request)
```

两个更新方法没有 `@Valid`；DTO 字段也没有 Jakarta Validation 注解，空值检查在 Service 中完成。

DTO 结构：

```json
{
  "defaultProvider": "dashscope",
  "defaultEmbeddingProvider": "dashscope"
}
```

---

## 2. 查询默认值：前端到数据库

设置页初始化时，`loadData()` 与列表、ASR、TTS 查询并行调用：

```text
llmProviderApi.getDefaultProvider()
  → GET /api/llm-provider/default-provider
  → Controller.getDefaultProvider()
  → configService.getDefaultProvider()
      → 获取读锁
      → 判断数据源
```

### 2.1 数据库模式

```text
globalSettingRepository.findById(1L)
  → llm_global_setting
      default_chat_provider_id
      default_embedding_provider_id
  → new DefaultProviderDTO(chatId, embeddingId)
```

固定 `id=1` 的单例行由 `LlmProviderBootstrapService.ensureGlobalSetting()` 在首次启动时创建。行缺失会抛 `PROVIDER_CONFIG_READ_FAILED`，设置页的 `Promise.all()` 随之整体失败。

### 2.2 Legacy 模式

```text
properties.getDefaultProvider()
properties.getDefaultEmbeddingProvider()
  → new DefaultProviderDTO(...)
```

### 2.3 回到页面

```text
Axios 解包 data
  → setDefaultProviderId(defaultProvider.defaultProvider)
  → setDefaultEmbeddingProviderId(defaultProvider.defaultEmbeddingProvider)
  → Provider 卡片按 ID 显示“文字默认”与“向量默认”标记
```

列表 DTO 本身也带两个默认布尔值，但页面的判断函数使用这里保存的两个独立 state。

---

## 3. 设置默认聊天 Provider：前端流程

用户点击卡片上的“设为默认聊天服务”后，页面先进入确认状态：

```text
handleSetDefault(providerId)
  → setPendingDefaultProviderId(providerId)
  → 用户确认
  → handleConfirmSetDefault()
```

请求体同时发送两个字段：

```typescript
await llmProviderApi.updateDefaultProvider({
  defaultProvider: pendingDefaultProviderId,
  defaultEmbeddingProvider: defaultEmbeddingProviderId,
});
```

但后端 `updateDefaultProvider()` 只读取 `request.defaultProvider()`，另一个字段只是随请求携带，不参与本次更新。

成功后页面显示 toast，清除待确认 ID，再 `await loadData()` 重新查询。

---

## 4. 设置默认聊天 Provider：数据库模式

```text
PUT /api/llm-provider/default-provider
  → GLOBAL 5/s 限流
  → Controller.updateDefaultProvider(request)
  → configService.updateDefaultProvider(request)
      → @Transactional 开启事务
      → 获取写锁
      → providerId = trimOrNull(request.defaultProvider)
          → null/空白：BAD_REQUEST
      → getProviderEntityOrThrow(providerId)
          → providerRepository.findById(providerId)
          → 不存在：PROVIDER_NOT_FOUND
      → getGlobalSettingOrThrow()
          → globalSettingRepository.findById(1L)
      → setting.setDefaultChatProviderId(providerId)
      → globalSettingRepository.save(setting)
      → registry.reload()
          → 清空 ChatClient 和 EmbeddingModel 缓存
      → 释放写锁
      → 事务提交
  → Result.success()
```

这里仅校验 Provider 存在，不检查 `enabled`。当前创建的 Provider 都是 `enabled=true`，但如果数据库被外部修改成 disabled，设置接口仍可写入默认 ID；Registry 真正加载时会过滤 `enabled=false` 并抛 `IllegalArgumentException`。

---

## 5. 默认聊天值如何影响下游

业务代码通常调用：

```java
llmProviderRegistry.getChatClientOrDefault(providerId)
```

选择逻辑：

```text
providerId 非 null 且非空
  → getChatClient(providerId)
  → 使用业务显式指定的 Provider

providerId 为 null/空
  → getDefaultChatClient()
  → resolveDefaultChatProviderId()
      → DB 模式查询 llm_global_setting.default_chat_provider_id
      → 空时回退 application.yml 的 app.ai.default-provider
  → getChatClient(defaultId)
```

所以切换全局默认值只影响“没有显式指定 Provider”的后续调用。面试会话若已经保存了具体 `llmProvider`，仍会使用会话自己的 ID。

Registry reload 后，下一次调用才按新默认值和新配置懒创建 Client；已经执行中的 LLM 请求不会切换。

---

## 6. 设置默认向量 Provider：前端流程

页面在发请求前先检查卡片数据：

```text
provider.supportsEmbedding == true
且 provider.embeddingModel 非空
```

不满足时直接显示错误，不打开确认流程。确认后发送：

```typescript
await llmProviderApi.updateDefaultEmbeddingProvider({
  defaultProvider: defaultProviderId,
  defaultEmbeddingProvider: pendingDefaultEmbeddingProviderId,
});
```

后端只读取 `request.defaultEmbeddingProvider()`。

---

## 7. 设置默认向量 Provider：数据库模式

```text
PUT /api/llm-provider/default-embedding-provider
  → GLOBAL 5/s
  → Controller.updateDefaultEmbeddingProvider(request)
  → configService.updateDefaultEmbeddingProvider(request)
      → @Transactional
      → 获取写锁
      → trimOrNull(defaultEmbeddingProvider)
          → 空白：BAD_REQUEST
      → getProviderEntityOrThrow(providerId)
      → 检查 provider.supportsEmbedding == true
      → 检查 embeddingModel 非空
          → 不满足：BAD_REQUEST
      → validateEmbeddingConfig(
          providerId,
          true,
          embeddingModel,
          resolveEmbeddingDimensions(...)
        )
          → 拒绝明显的聊天模型名
          → 维度必须为正
      → getGlobalSettingOrThrow()
      → setting.setDefaultEmbeddingProviderId(providerId)
      → globalSettingRepository.save(setting)
      → registry.reload()
      → 释放写锁
      → 事务提交
```

后端会重复前端的关键校验，所以绕过页面直接调用也不能把明确不支持向量的 Provider 设为默认。

---

## 8. 默认向量值如何影响知识库

默认路径：

```text
知识库向量化/RAG 组件
  → registry.getDefaultEmbeddingModel()
  → resolveDefaultEmbeddingProviderId()
      ├─ DB：llm_global_setting.default_embedding_provider_id
      ├─ 缺失时：app.ai.default-embedding-provider
      └─ 仍缺失：默认聊天 Provider
  → getEmbeddingModel(providerId)
  → embeddingModelCache.computeIfAbsent(...)
  → createEmbeddingModel(providerId)
      → 读取 Provider 配置并解密 Key
      → 校验 supportsEmbedding 和 embeddingModel
      → OpenAiEmbeddingOptions(model, dimensions)
      → OpenAiEmbeddingModel
```

切换默认向量 Provider 只改变后续使用哪个模型生成查询/文档向量，不会自动：

- 重新向量化已经入库的知识库文档。
- 删除旧向量。
- 迁移 pgvector 表结构。
- 检查新旧模型是否位于同一向量空间。

即使两个模型都输出 1024 维，向量空间也可能不兼容。切换模型后若继续用新查询向量搜索旧文档向量，语义召回可能失真。

当前 Service 只要求维度为正，没有强制等于 PostgreSQL/Spring AI 配置的 1024 维。设置页提示“当前 pgvector 表为 1024 维”，但后端并未执行相等校验。

---

## 9. Legacy 模式的实际差异

### 9.1 默认聊天更新可用

```text
updateDefaultProviderLegacy(request)
  → 校验 ID 非空且 Provider 存在
  → properties.setDefaultProvider(providerId)
  → writeDefaultProviderToYaml(providerId)
      → 写 app.ai.default-provider
      → 删除 app.ai.module-defaults 配置段
  → registry.reload()
```

### 9.2 默认向量更新没有 Legacy 分支

`updateDefaultEmbeddingProvider()` 当前没有先判断 `isDatabaseBacked()`，会直接调用：

```text
getProviderEntityOrThrow(providerId)
  → providerRepository.findById(...)
```

在使用五参数构造器的真正 Legacy 模式中，`providerRepository` 为 `null`，这里会出现空指针异常。也就是说，当前默认向量更新接口实际上只完整支持数据库模式。

这是代码现状，不应按 Controller 注释推断 Legacy 模式也已实现。

---

## 10. 与删除保护的关系

数据库删除前会同时检查：

```text
id == defaultChatProviderId
或 id == defaultEmbeddingProviderId
```

所以正确操作顺序是：

```text
准备删除 Provider A
  → 若 A 是默认聊天：先设置另一个聊天默认值
  → 若 A 是默认向量：先设置另一个向量默认值
  → 再 DELETE A
```

Legacy 删除只检查默认聊天 ID，没有检查默认向量 ID，这是两个模式的行为差异。

---

## 11. 事务与缓存时序

两个数据库更新方法都在事务内调用 `registry.reload()`：

```text
更新 setting Entity
  → repository.save
  → 清当前实例缓存
  → 方法返回
  → 事务提交
```

缓存操作不属于数据库事务，也不会随回滚恢复。多实例中的其他 Registry 也不会自动收到刷新通知。

---

## 12. 页面闭环

```text
用户选择默认值并确认
  → 前端发送两个默认字段
  → Controller 只委托
  → Service 读取本次目标字段
  → 验证 Provider 与用途能力
  → 更新 llm_global_setting 单例行
  → Registry 清缓存
  → Result.success
  → 页面 toast
  → loadData 重新查询
  → 默认徽标移动到新 Provider
  → 后续未显式指定 Provider 的业务请求使用新默认值
```

聊天默认和向量默认是独立路由。切换任何一个都不应被理解为同时修改另一个，也不应被理解为自动迁移已有业务数据。
