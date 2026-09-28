# TTS 配置：查询、更新与实际语音合成闭环

TTS 是文字转语音。本模块提供配置查询和更新：

```text
GET /api/llm-provider/voice/tts
PUT /api/llm-provider/voice/tts
```

当前没有 `POST /voice/tts/test`，前端也没有 TTS 测试按钮。配置能否真正工作，要在语音面试调用 `QwenTtsService.synthesize(text)` 时才能完成端到端验证。

---

## 1. Controller 方法

```java
@GetMapping("/voice/tts")
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
public Result<TtsConfigDTO> getTtsConfig()

@PutMapping("/voice/tts")
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
public Result<Void> updateTtsConfig(
    @RequestBody TtsConfigRequest request)
```

更新方法没有 `@Valid`；请求 record 的所有字段都可为 null，也没有范围约束。

---

## 2. 字段映射

| 请求/响应字段 | `VoiceInterviewProperties.QwenTtsConfig` | 设计用途 |
|---|---|---|
| `model` | `model` | DashScope 实时 TTS 模型 |
| `apiKey` / `maskedApiKey` | `apiKey` | 鉴权；查询时脱敏 |
| `voice` | `voice` | 音色 |
| `format` | `format` | 期望的音频格式配置 |
| `sampleRate` | `sampleRate` | 期望采样率配置 |
| `mode` | `mode` | 合成模式，如 commit |
| `languageType` | `languageType` | 语言类型 |
| `speechRate` | `speechRate` | 语速 |
| `volume` | `volume` | 音量 |

`null` 表示不修改原值。

---

## 3. 页面加载：getTtsConfig 完整链路

设置页初始化：

```text
SettingsPage.loadData()
  → llmProviderApi.getTtsConfig()
  → GET /api/llm-provider/voice/tts
```

后端：

```text
RateLimitAspect：GLOBAL 30/s
  → Controller.getTtsConfig()
  → configService.getTtsConfig()
      → rwLock.readLock().lock()
      → tts = voiceProperties.getQwen().getTts()
      → TtsConfigDTO.builder()
          → 复制 model/voice/format/sampleRate/mode/
            languageType/speechRate/volume
          → maskApiKey(tts.getApiKey())
      → 释放读锁
  → Result.success(dto)
  → Axios 解包
  → setTtsConfig(tts)
```

它读取运行中的内存属性，不查询数据库，也不会每次重新解析 YAML。

用户打开 TTS 弹窗时：

```text
openTtsModal()
  → 从 ttsConfig 复制字段到 ttsForm
```

脱敏 Key 不会回填到 `apiKey`，因此不输入新 Key 就不会覆盖现有凭证。

---

## 4. 前端保存流程

```text
handleSaveTts()
  → setVoiceSaving(true)
  → llmProviderApi.updateTtsConfig(ttsForm)
      → PUT /api/llm-provider/voice/tts
  → 成功：toast“TTS 配置已更新”
  → 关闭弹窗
  → await loadData()
  → finally setVoiceSaving(false)
```

前端把当前表单对象整体发送。Controller 不做字段范围校验，后端 Service 也直接赋值。

---

## 5. updateTtsConfig 的逐步执行

```text
Controller.updateTtsConfig(request)
  → configService.updateTtsConfig(request)
      → rwLock.writeLock().lock()
      → tts = voiceProperties.getQwen().getTts()
```

### 5.1 更新运行时属性对象

```text
request.model() != null        → tts.setModel(...)
request.voice() != null        → tts.setVoice(...)
request.format() != null       → tts.setFormat(...)
request.sampleRate() != null   → tts.setSampleRate(...)
request.mode() != null         → tts.setMode(...)
request.languageType() != null → tts.setLanguageType(...)
request.speechRate() != null   → tts.setSpeechRate(...)
request.volume() != null       → tts.setVolume(...)
```

没有拒绝空模型、空音色、负采样率、异常语速或负音量的逻辑。空 JSON 也会继续执行 YAML 重写和 reload。

### 5.2 API Key 更新

```text
request.apiKey() != null
  → tts.setApiKey(request.apiKey())
  → updateEnvValue("AI_BAILIAN_API_KEY", request.apiKey())
```

`.env` 写入 I/O 异常只记录 warn，不向上抛出，所以当前进程可能使用新 Key，而磁盘仍是旧 Key。

### 5.3 重写 YAML 的 TTS 块

```text
writeTtsConfigToYaml(tts)
  → mutateYamlText(VOICE_CONFIG_WRITE_FAILED, ...)
  → YamlTextEditor.setBlock(
      ["app", "voice-interview", "qwen"],
      "tts",
      values
    )
  → 写回 config-yaml-path 文件
```

目标结构：

```yaml
app:
  voice-interview:
    qwen:
      tts:
        model: ...
        api-key: ${AI_BAILIAN_API_KEY}
        voice: ...
        format: ...
        sample-rate: ...
        mode: ...
        language-type: ...
        speech-rate: ...
        volume: ...
```

YAML 写失败会抛 `BusinessException(VOICE_CONFIG_WRITE_FAILED)`。

### 5.4 热更新 TTS Service

```text
ttsService.reload(voiceProperties)
  → applyTtsConfig(voiceProperties.getQwen().getTts())
  → 把 model/apiKey/voice/format/sampleRate/mode/
    languageType/speechRate/volume 复制到 QwenTtsService 成员变量
```

最后释放写锁，Controller 返回 `Result.success()`。

---

## 6. 保存后的运行时生效路径

TTS 与 ASR 的连接生命周期不同：

- ASR 维护长连接，会话已建立后 reload 不迁移旧连接。
- TTS 的每次 `synthesize(text)` 都建立临时 WebSocket，完成后关闭。

因此 TTS 更新后：

```text
下一次需要播放 LLM 回复
  → VoiceInterviewWebSocketHandler
  → QwenTtsService.synthesize(text)
      → 读取 reload 后的成员变量
      → QwenTtsRealtimeParam(model, apiKey)
      → 建立 DashScope WebSocket
      → QwenTtsRealtimeConfig(
          voice,
          responseFormat,
          mode,
          languageType,
          speechRate,
          volume
        )
      → updateSession(config)
      → appendText(text)
      → commit()
      → 回调接收 Base64 音频分片
      → 合并 byte[]
      → 返回给上层发送到前端
      → finally 关闭临时 WebSocket
```

`synthesize()` 用 `CountDownLatch` 把异步回调转换成同步等待，最长等待 30 秒；失败或超时返回空字节数组，而不是把异常继续抛给调用方。

---

## 7. format/sampleRate 配置与实际输出的差异

虽然配置查询、更新、YAML 和 `QwenTtsService.applyTtsConfig()` 都保存了 `format`、`sampleRate`，但当前 `synthesize()` 构建 SDK 配置时调用：

```java
private QwenTtsRealtimeAudioFormat getAudioFormat() {
  return QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT;
}
```

也就是说，真正传给 DashScope SDK 的输出格式目前固定为：

```text
PCM / 24000Hz / 单声道 / 16bit
```

`tts.format` 和 `tts.sampleRate` 虽然可以在设置页修改、会写入外部 YAML、也会被 reload 到成员变量，但当前合成方法没有用它们决定 `responseFormat`。因此页面显示“已保存”不等于这两个参数改变了实际音频输出。

这是理解调用闭环时必须保留的实现事实。

---

## 8. API Key 与 ASR 的共享关系

TTS 和 ASR 都把凭证持久化到：

```text
AI_BAILIAN_API_KEY
```

并在各自 YAML 中引用同一占位符。最后一次保存 ASR 或 TTS Key 会覆盖 `.env` 中的共享值。

当前进程内又可能短暂出现不同值：

```text
ASR 已 reload Key=A
  → 保存 TTS Key=B
  → TTS 内存 Key=B
  → .env Key=B
  → ASR Service 成员变量仍可能是 A
```

当前 `spring.config.import` 没有导入这个运行时 `.env`，所以重启后不会自动从文件中的 B 绑定，而是重新取主配置源/进程环境里的 `AI_BAILIAN_API_KEY`。在当前进程和写出文件中，它们仍应被视为同一个 DashScope 凭证，而不是两套独立长期凭证。

---

## 9. 更新过程没有事务回滚

执行顺序：

```text
修改 VoiceInterviewProperties
  → 写 .env（失败只告警）
  → 写 YAML（失败抛异常）
  → QwenTtsService.reload
```

可能的部分成功状态：

- YAML 写失败：接口返回错误，但 `VoiceInterviewProperties` 已改变，TTS Service 尚未 reload。
- `.env` 写失败：接口仍可能成功，当前进程使用新值，磁盘副本仍为旧值。
- reload 失败：文件已写，运行时 Service 仍可能保留旧字段。

即使两个文件都写成功，当前应用启动配置也没有导入它们；因此这些文件不是可靠的重启恢复来源，除非部署层额外配置外部 Spring 配置源。

没有 `@Transactional` 能覆盖内存对象和两个文件，也没有显式回滚旧值。

---

## 10. TTS 配置如何真正验证

当前没有管理测试接口，所以只有分层验证：

```text
GET /voice/tts
  → 只能验证内存配置可读、Key 能脱敏

PUT /voice/tts
  → 只能验证内存赋值、YAML 写入和 reload 没抛异常

真实语音面试触发 synthesize(text)
  → 才验证 API Key、模型、音色、语言、模式、WebSocket、音频回调
```

即使保存接口成功，下列错误也可能在合成时才暴露：

- Key 无效。
- 模型或音色不存在。
- WebSocket 网络不可达。
- mode/languageType/speechRate/volume 不被 SDK 或服务端接受。
- 30 秒内未完成。

而 `synthesize()` 失败时通常返回空数组并写日志，管理页面不会自动收到这次运行时失败。

---

## 11. 返回页面的闭环

保存接口成功：

```text
Result.success(data=null)
  → Axios code=200，resolve
  → toast“TTS 配置已更新”
  → 关闭弹窗
  → loadData()
  → GET /voice/tts
  → 从 VoiceInterviewProperties 重新构造 DTO
  → 页面显示新值和脱敏 Key
```

YAML 写失败等业务异常：

```text
BusinessException
  → GlobalExceptionHandler
  → HTTP 200 + Result.error(code, message)
  → Axios reject Error(message)
  → 页面 error toast
```

---

## 12. 完整闭环总结

```text
设置页加载
  → GET /voice/tts
  → VoiceInterviewProperties
  → TtsConfigDTO + Key 脱敏
  → 弹窗回填

用户保存
  → PUT /voice/tts
  → 写锁
  → 修改内存配置
  → 可选更新共享 AI_BAILIAN_API_KEY
  → 重写 YAML tts 块
  → QwenTtsService.reload
  → 页面重新加载配置

后续语音面试
  → synthesize(text)
  → 使用新 model/key/voice/mode/language/speechRate/volume
  → 实际输出格式仍固定 PCM 24kHz 单声道 16bit
  → 建立临时 WebSocket
  → 接收并合并音频
  → 返回前端播放
```

只有最后一段成功产出音频，才算 TTS 的真正端到端闭环。
