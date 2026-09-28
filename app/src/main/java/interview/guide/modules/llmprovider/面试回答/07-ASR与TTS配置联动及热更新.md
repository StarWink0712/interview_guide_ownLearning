# ASR 与 TTS 配置联动及热更新

## 1. 模块基础讲解

Provider 管理 Controller 还承担语音面试的 ASR/TTS 配置入口。这部分配置来自
`VoiceInterviewProperties`，不是 `LlmProviderEntity`：

- ASR：WebSocket URL、model、API Key、language、format、sampleRate、Turn Detection 参数。
- TTS：model、API Key、voice、format、sampleRate、mode、languageType、speechRate、volume。

更新流程是：

```text
Request 非 null 字段
  -> 直接修改 VoiceInterviewProperties 内部可变对象
  -> API Key 写 llm-providers.env 的 AI_BAILIAN_API_KEY
  -> 文本方式修改 llm-providers.yml
  -> QwenAsrService.reload / QwenTtsService.reload
```

ASR 与 TTS 共用 `AI_BAILIAN_API_KEY`，所以任意一侧更新密钥都会改变另一侧重启后应使用的密钥。
返回 DTO 时 API Key 脱敏。

`YamlTextEditor` 通过缩进和 key 文本定位修改 YAML，目标是保留注释和原有格式。它支持创建父节点、
设置标量、设置 block 和删除 section，但不是完整 YAML parser。

当前最关键的实现事实：外部 `llm-providers.yml` 和 `llm-providers.env` 没有被 Spring 配置导入。
所以内存 reload 在当前进程有效，但服务重启后并不会从这两个文件恢复 ASR/TTS 修改。

## 2. 基础面试问题

**问题：ASR/TTS 为什么不存入 LlmProviderEntity？**

回答：它们使用不同协议和参数，ASR 是实时 WebSocket 输入音频，TTS 是语音合成；和 OpenAI Chat
Provider 的字段并不一致。独立配置能避免 Entity 变成大量可空字段的万能表。

**问题：更新后为什么还要调用 service.reload？**

回答：Qwen ASR/TTS Service 在字段中持有模型、URL、密钥等运行时配置。修改 Properties 对象不保证
服务内部字段自动刷新，显式 reload 才能让新会话或新合成使用最新配置。

**问题：为什么只更新非 null 字段？**

回答：支持设置页部分提交，不要求每次传全量配置。但空字符串当前会被当作有效值写入，缺少更细的
必填和范围校验。

**问题：Turn Detection 参数有什么作用？**

回答：控制 ASR 如何判断用户一轮话语结束，包括算法类型、阈值和静音持续时间。阈值过敏会切碎
句子，过钝会增加提交延迟，需要结合音频环境调优。

## 3. 进阶面试问题

**问题：直接修改 `@ConfigurationProperties` 可变对象安全吗？**

回答：多个线程可能同时读取这些普通字段，而更新线程逐字段修改，其他线程可能观察到新旧混合状态；
Qwen Service 的字段也未统一声明 volatile。更好方案是构造不可变配置快照并用 `AtomicReference`
一次替换。

**问题：ASR/TTS reload 会影响已有会话吗？**

回答：取决于 Service 如何复制配置。ASR 已建立的 WebSocket 通常继续使用旧认证和模型，新会话使用
新字段；TTS 每次合成创建会话，可能更快看到新值。生产设计应明确“配置版本对在途会话不变”。

**问题：文件写入失败时内存会怎样？**

回答：代码先改 Properties，再写 env/YAML。YAML 失败会抛 BusinessException，但没有回滚内存；env
失败只告警；reload 可能未执行。结果可能是 UI 返回失败但部分内存已改变，或返回成功但持久化缺失。

**问题：文本 YAML 编辑器的边界是什么？**

回答：它假定每级两空格、简单 `key: value`、无复杂引号、多行字符串、锚点和同名 key。字符串值
不加引号/转义，包含 `: `、`#`、换行或特殊 YAML 值时可能改变语义；它也不是原子写入。

**问题：ASR 测试为什么不应叫“配置测试”？**

回答：当前只做 TCP 连接，未验证 WebSocket Upgrade、API Key、model、format 和采样率。它只能叫
endpoint reachability。完整测试应建立短时真实 ASR 会话并发送已知音频样本。

## 4. 技术延伸问题

**问题：语音配置可以如何统一建模？**

回答：定义 `SpeechProvider` 和版本化 `AsrProfile`/`TtsProfile`，分别声明协议、模型和音频能力；API Key
引用 Secret；Voice Session 创建时记录 profileVersion，确保在途会话配置稳定。

**问题：如何无中断更新 ASR？**

回答：新配置先健康验证，标记为 READY；新会话绑定新版本，旧会话继续旧版本直到结束；确认稳定后
将新版本设为默认，最后回收旧版本。这是蓝绿配置而不是原地改字段。

**问题：配置中心能解决什么？**

回答：Nacos、Consul、Spring Cloud Config 可提供版本、推送和多实例同步，但仍要解决密钥存储、
客户端重建和在途会话策略。配置中心不是把 mutable Properties 自动变安全的魔法。

## 5. 改进方案问题

**问题：如何修复重启丢失？**

回答：首选把语音配置存数据库并加密密钥；启动从数据库构造快照。若继续用文件，必须将外部 YAML
加入 `spring.config.import=optional:file:${...}`，并显式加载 env 文件，同时验证文件优先级和路径。

**问题：如何保证更新原子性？**

回答：先校验请求并构造新快照，持久化到单一存储；成功后原子替换运行时 `AtomicReference`；失败
不改内存。通过 version 防并发覆盖，reload 接收确定的快照而不是重新读取共享 mutable object。

**问题：请求校验应补哪些规则？**

回答：URL 只允许 ws/wss 且做 SSRF 校验；sampleRate 限定支持集合；threshold 在合法范围；静音时长
为正且有上限；speechRate、volume 按 SDK 范围；format/mode/voice 使用枚举；API Key 空串不允许。

**问题：如何处理 ASR/TTS 共用 Key？**

回答：DTO 中显式显示它们属于同一 credentialRef，避免用户以为独立；更通用的设计是分别引用 Secret，
允许同 Key 复用，但不是硬编码一个环境变量名。

## 6. 故障处理问题

**问题：更新 ASR 后新会话仍使用旧模型，怎么查？**

回答：确认 `asrService.reload` 是否执行、Service 内字段是否正确复制、已有 ASR session 是否被复用，
以及请求是否落到未收到更新的其他实例。日志应带 configVersion 而不只是 model。

**问题：重启后配置恢复默认值怎么查？**

回答：查看 `spring.config.import`，确认外部 YAML/env 是否是启动输入。当前代码只写文件但未导入，这是
首要原因；之后再查文件路径、权限和配置优先级。

**问题：YAML 写完后应用无法启动，怎么恢复？**

回答：用 YAML parser 校验生成文件，定位未转义的 URL、voice 或 model；从写前备份恢复。改造后应
先写临时文件、解析校验，再原子替换，失败保留旧文件。

**问题：ASR TCP 测试成功但返回 401 怎么解释？**

回答：TCP 测试没有发送认证信息，所以只能说明网络可达。检查实际 WebSocket 握手 Authorization、
共享 Key 是否被 TTS 更新覆盖、账号模型权限及系统时间。
