## ASR 和 TTS 是两个对称的语音处理技术

它们在语音面试中形成"听"和"说"的闭环：

```
用户说话 → 麦克风采集音频
    │
    ▼
  ASR（语音转文字）
  Automatic Speech Recognition
  把语音波形 → 转成文本 "我叫张三，有5年Java经验"
    │
    ▼
  LLM（面试官大脑）
  根据回答生成下一道题或评估
    │
    ▼
  TTS（文字转语音）
  Text to Speech
  把文本 "请解释HashMap的扩容机制" → 转成语音播放
    │
    ▼
用户听到声音继续回答
  
  循环往复，形成语音面试对话
```

### 简单比喻

| | ASR | TTS |
|------|-----|-----|
| 方向 | 语音 → 文字 | 文字 → 语音 |
| 相当于 | **耳朵**（把听到的写下来） | **嘴巴**（把写的念出来） |
| 技术 | 语音识别 | 语音合成 |
| 协议 | WebSocket（实时流式传输） | HTTP/REST |
| 在这个项目 | `QwenAsrService` | `QwenTtsService` |

### 在这个项目中的配置

```yaml
# ASR 配置
app.voice-interview.qwen.asr:
    url: wss://dashscope.aliyuncs.com/api-ws/v1/realtime   # WebSocket 实时传输
    model: qwen-asr
    language: zh            # 中文识别
    sample-rate: 16000      # 16kHz 采样

# TTS 配置
app.voice-interview.qwen.tts:
    model: qwen-tts
    voice: longxiaochun     # 音色
    speech-rate: 1.0        # 语速
    volume: 100             # 音量
```

两者共享同一个 `AI_BAILIAN_API_KEY`，配置通过 `LlmProviderController` 的 `/voice/asr` 和 `/voice/tts` 接口统一管理。