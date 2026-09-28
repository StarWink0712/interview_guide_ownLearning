
这个类本身注释已经非常详尽，核心职责一句话概括：**应用启动时，把 YAML 里的 Provider 种子配置导入数据库，并初始化全局默认设置。**

---

### 启动流程（`@PostConstruct`）

```
seedProvidersIfNecessary()
    │
    ├─ Provider 表为空？ → seedProviders() 从 YAML 导入种子数据
    │
    └─ ensureGlobalSetting() 确保全局设置单例存在
```

---

### 核心方法

#### 1. `seedProviders()` — 种子导入

遍历 `app.ai.providers` 配置，对每个 Provider 做：

| 步骤 | 说明 |
|------|------|
| 校验 | id、baseUrl、model 必填，缺失则跳过 |
| 加密 | API Key 用 XChaCha20-Poly1305 加密，nonce 和密文分离存储 |
| 判断 Embedding | `supportsEmbedding=true` 或配置了 `embeddingModel` 即视为支持 |
| 持久化 | 构建 `LlmProviderEntity`，标记 `builtin=true`、`enabled=true` 后入库 |

#### 2. `ensureGlobalSetting()` — 全局默认设置

使用固定的 `SINGLETON_ID`，保证全局设置只有一条记录。选择逻辑：

- **默认 Chat Provider**：配置的 `defaultProvider` → 首个 Provider → `"dashscope"` 兜底
- **默认 Embedding Provider**：配置的 `defaultEmbeddingProvider` → 默认 Chat Provider → 遍历找第一个支持 Embedding 的 Provider

`resolveExistingProvider()` 还做了二次校验——首选不在数据库中则降级到备选，防止配了不存在的 Provider ID。

#### 3. `canProvideEmbedding()` — Embedding 能力判断

三个条件同时满足才算具备 Embedding 能力：
- `enabled = true`
- `supportsEmbedding = true`
- `embeddingModel` 非空

---

### 与 `LlmProviderConfigService` 的关系

这两个类配合实现了"数据库优先、YAML 兜底"的数据源切换：

| | BootstrapService | ConfigService |
|------|------|------|
| 时机 | 应用启动一次 | 每次请求 |
| 场景 | Provider 表为空 → 导入种子 | Provider 表非空 → 直接读库 |
| 判断 | `providerRepository.count() == 0` | `isDatabaseBacked()` |