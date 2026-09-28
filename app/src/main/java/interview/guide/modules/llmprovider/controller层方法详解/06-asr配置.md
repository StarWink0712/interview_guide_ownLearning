# ASR 配置：查询、更新、运行时生效与连通性测试闭环

ASR 是语音转文字。这里管理的是 Qwen/DashScope 实时 ASR 配置，覆盖三个接口：

```text
GET  /api/llm-provider/voice/asr
PUT  /api/llm-provider/voice/asr
POST /api/llm-provider/voice/asr/test
```

与 Provider CRUD 不同，ASR 配置不存入 `llm_provider_config`，而是修改 `VoiceInterviewProperties` 内存对象、运行时 YAML 和共享 `.env` Key，再通知 `QwenAsrService` 复制新配置。

---

## 1. Controller 方法

```java
@GetMapping("/voice/asr")
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
public Result<AsrConfigDTO> getAsrConfig()

@PutMapping("/voice/asr")
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
public Result<Void> updateAsrConfig(
    @RequestBody AsrConfigRequest request)

@PostMapping("/voice/asr/test")
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
public Result<ProviderTestResult> testAsrConfig()
```

更新请求没有 `@Valid`，`AsrConfigRequest` 字段也没有 `@NotBlank`、`@Min` 或范围注解。

---

## 2. 字段对应关系

| 请求/响应字段 | `VoiceInterviewProperties.AsrConfig` | 实际用途 |
|---|---|---|
| `url` | `url` | DashScope ASR WebSocket 地址 |
| `model` | `model` | 实时识别模型名 |
| `apiKey` / `maskedApiKey` | `apiKey` | 鉴权；查询只返回脱敏值 |
| `language` | `language` | 识别语言 |
| `format` | `format` | 输入音频格式 |
| `sampleRate` | `sampleRate` | 输入采样率 |
| `enableTurnDetection` | `enableTurnDetection` | 是否开启服务端轮次/VAD 检测 |
| `turnDetectionType` | `turnDetectionType` | 检测策略类型 |
| `turnDetectionThreshold` | `turnDetectionThreshold` | 检测阈值 |
| `turnDetectionSilenceDurationMs` | 同名 | 静音多久后断句 |

所有更新字段都是可选的：`null` 表示保留当前值。

---

## 3. 页面加载：getAsrConfig 完整链路

`SettingsPage.loadData()` 发起：

```text
llmProviderApi.getAsrConfig()
  → GET /api/llm-provider/voice/asr
```

后端：

```text
RateLimitAspect：GLOBAL 30/s
  → Controller.getAsrConfig()
  → configService.getAsrConfig()
      → rwLock.readLock().lock()
      → voiceProperties.getQwen().getAsr()
      → AsrConfigDTO.builder()
          → 复制 URL、模型、语言、音频和 VAD 参数
          → maskApiKey(asr.getApiKey())
      → 释放读锁
  → Result.success(dto)
```

这里读取的是运行中 `VoiceInterviewProperties` 的当前值，不是每次打开 YAML 文件，也不查询数据库。

前端收到后：

```text
setAsrConfig(asr)
  → 页面显示当前摘要
  → openAsrModal() 时复制到 asrForm
```

`openAsrModal()` 不把 `maskedApiKey` 写入表单的 `apiKey` 字段，因此用户不输入新 Key 时，保存请求不会误把脱敏字符串覆盖为真实 Key。

---

## 4. 前端保存动作

用户在 ASR 弹窗修改字段后：

```text
handleSaveAsr()
  → setVoiceSaving(true)
  → llmProviderApi.updateAsrConfig(asrForm)
      → PUT /api/llm-provider/voice/asr
  → 成功：toast“ASR 配置已更新”
  → 关闭弹窗
  → await loadData()
  → finally setVoiceSaving(false)
```

前端没有像 Provider 创建那样执行完整参数范围校验；表单值最终以请求中的非 null 字段为准。

---

## 5. updateAsrConfig 的逐步执行

```text
Controller.updateAsrConfig(request)
  → configService.updateAsrConfig(request)
      → rwLock.writeLock().lock()
      → asr = voiceProperties.getQwen().getAsr()
```

### 5.1 先修改内存配置

Service 对每个非 null 字段直接调用 setter：

```text
request.url() != null           → asr.setUrl(...)
request.model() != null         → asr.setModel(...)
request.language() != null      → asr.setLanguage(...)
request.format() != null        → asr.setFormat(...)
request.sampleRate() != null    → asr.setSampleRate(...)
request.enableTurnDetection()   → asr.setEnableTurnDetection(...)
request.turnDetectionType()     → asr.setTurnDetectionType(...)
request.turnDetectionThreshold()→ asr.setTurnDetectionThreshold(...)
request.turnDetectionSilence... → asr.setTurnDetectionSilenceDurationMs(...)
```

空字符串、负采样率、越界阈值和负静音时长不会在 Controller/Service 被拒绝。即使发送空 JSON `{}`，Service 仍会继续写一遍当前 YAML 并 reload。

### 5.2 API Key 非 null 时更新内存与 `.env`

```text
request.apiKey() != null
  → asr.setApiKey(request.apiKey())
  → updateEnvValue("AI_BAILIAN_API_KEY", request.apiKey())
      → writeEnvValue(...)
```

`.env` 写入规则：

- 文件不存在：创建并追加 `AI_BAILIAN_API_KEY=value`。
- Key 已存在：正则替换整行。
- Key 不存在：追加新行。
- `envPath` 为空：直接跳过。
- I/O 失败：只 `log.warn`，不抛异常。

因此 `.env` 写出失败时，接口仍可能继续并最终返回成功。

### 5.3 重写 ASR YAML 配置块

```text
writeAsrConfigToYaml(asr)
  → mutateYamlText(VOICE_CONFIG_WRITE_FAILED, ...)
  → 读取 config-yaml-path 对应文件的全部文本行
  → YamlTextEditor.setBlock(
      ["app", "voice-interview", "qwen"],
      "asr",
      values
    )
  → 写回文件
```

写入结构等价于：

```yaml
app:
  voice-interview:
    qwen:
      asr:
        url: ...
        model: ...
        api-key: ${AI_BAILIAN_API_KEY}
        language: ...
        format: ...
        sample-rate: ...
        enable-turn-detection: ...
        turn-detection-type: ...
        turn-detection-threshold: ...
        turn-detection-silence-duration-ms: ...
```

YAML 中不写明文 Key，只写环境变量占位符。若 YAML 写入失败，抛 `BusinessException(VOICE_CONFIG_WRITE_FAILED)`。

### 5.4 通知 ASR Service 使用新配置

```text
asrService.reload(voiceProperties)
  → applyAsrConfig(voiceProperties.getQwen().getAsr())
  → 把 url/model/apiKey/language/format/sampleRate/VAD 参数
    复制到 QwenAsrService 成员变量
```

更新完成后释放写锁，Controller 返回 `Result.success()`。

---

## 6. 新配置何时真正影响语音识别

`QwenAsrService.reload()` 的注释和实现都明确：只更新成员变量，不重建已经存在的 ASR WebSocket 会话。

```text
保存 ASR 配置
  → reload 复制新字段
  → 已建立的 sessions Map 中旧连接继续运行
  → 新建面试语音会话或后续重连
      → startTranscriptionLocked()
      → 使用当前 model/url/apiKey/音频/VAD 字段
      → 构建 OmniRealtimeParam
      → 新线程连接 DashScope
      → updateSession 下发识别和 VAD 参数
```

因此“保存成功”意味着后续新连接使用新配置，不代表当前正在说话的用户立即无缝迁移。

---

## 7. 文件写入与运行时更新不是事务

准确时序是：

```text
修改 VoiceInterviewProperties 内存对象
  → 尝试写 .env（失败只告警）
  → 写 YAML（失败会抛异常）
  → QwenAsrService.reload
```

没有数据库事务，也没有旧值快照和回滚逻辑。因此可能出现：

- YAML 失败：Controller 返回错误，但 `VoiceInterviewProperties` 已被修改；`QwenAsrService.reload()` 尚未执行。
- `.env` 失败、YAML 成功：接口可能成功，当前进程使用新 Key，但磁盘副本不完整。
- reload 发生异常：内存属性和文件可能已经更新，但 ASR Service 成员变量未全部切换。

`ReentrantReadWriteLock` 只防止同一 JVM 中此 Service 的读写交错，不提供文件事务。

---

## 8. ASR 与 TTS 共用同一个环境变量

ASR 和 TTS 的 YAML 都写：

```yaml
api-key: ${AI_BAILIAN_API_KEY}
```

两种更新也都修改同一个 `.env` Key。结果是：

```text
先保存 ASR Key=A
  → ASR 内存 Key=A，.env=A

再保存 TTS Key=B
  → TTS 内存 Key=B，.env=B
  → ASR 内存对象在当前进程可能仍为 A

应用重启
  → 当前代码不会自动导入该外部 .env
  → 实际值重新取决于主配置源和进程环境变量
```

所以它们在当前进程和写出文件的设计上共享凭证，不适合配置成两个不同 Key。还要注意，当前 `spring.config.import` 没有导入运行时 `llm-providers.yml`/`.env`；这些修改在本进程靠 reload 生效，但重启后不会自动恢复，除非部署层额外把外部文件加入 Spring 配置源。

---

## 9. testAsrConfig 的前端闭环

```text
点击“测试 ASR”
  → handleTestAsr()
  → setTestingAsr(true)
  → llmProviderApi.testAsr()
  → POST /api/llm-provider/voice/asr/test
  → 返回 ProviderTestResult
  → setAsrTestResult(result)
  → 页面根据 result.success 显示结果
```

测试结果不持久化。

---

## 10. testAsrConfig 后端实际检查内容

```text
Controller.testAsrConfig()
  → GLOBAL 10/s
  → configService.testAsrConfig()
      → 获取读锁
      → asr = voiceProperties.getQwen().getAsr()
      → URI.create(asr.url)
      → host = uri.getHost()
      → port = 显式端口，或 wss ? 443 : 80
      → new Socket()
      → socket.connect(host, port, 5000ms)
      → close socket
```

连接成功时：

```json
{
  "success": true,
  "message": "ASR WebSocket 连接成功: host",
  "model": "当前ASR模型"
}
```

任何异常都被捕获并转成 `success=false` DTO。

---

## 11. ASR 测试能证明什么、不能证明什么

它只建立普通 TCP Socket，所以能证明：

- URL 能解析出主机和端口。
- 当前应用到目标端口的 TCP 连接在 5 秒内可建立。

它不能证明：

- TLS 握手成功；对 `wss` 也没有创建 SSL Socket。
- WebSocket HTTP Upgrade 成功。
- API Key 有效，因为没有发送鉴权。
- 模型名存在。
- 音频格式、采样率和 VAD 参数被服务端接受。
- 实际语音能够识别。

所以成功提示里的“WebSocket 连接成功”从实现角度更准确地说是“WebSocket 目标端口 TCP 可达”。真正端到端验证仍需创建语音面试会话并发送音频。

---

## 12. 测试失败的响应语义

与 Provider 测试一样，网络失败是普通数据：

```text
Service catch Exception
  → ProviderTestResult(success=false, ...)
  → Controller Result.success(testResult)
  → 外层 code=200
  → Axios 正常 resolve
  → 页面检查内层 success
```

因此测试失败通常不会进入 `handleTestAsr()` 的 catch；只有 Controller 前后的系统异常、限流业务错误等才会 reject。

---

## 13. 完整闭环总结

```text
查询：
SettingsPage loadData
  → GET /voice/asr
  → VoiceInterviewProperties
  → API Key 脱敏 DTO
  → 表单回填

更新：
ASR 弹窗保存
  → PUT /voice/asr
  → 写锁
  → 修改内存属性
  → 可选写共享 .env Key
  → 重写 ASR YAML 块
  → QwenAsrService.reload
  → 后续新连接/重连使用新配置
  → 页面重新 loadData

测试：
点击测试
  → POST /voice/asr/test
  → 5 秒 TCP 端口探测
  → success DTO
  → 页面显示
```

管理闭环已经完成，但 ASR 真正可用性的最终闭环仍在 `QwenAsrService.startTranscription()` 建立带鉴权的 WebSocket 并成功返回转写文本。
