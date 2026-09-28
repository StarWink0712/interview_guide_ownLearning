## 为什么有"指定"和"默认"两种获取方式？

### 🎯 核心原因：支持多服务商（Multi-Provider）

这个项目支持**多个 LLM 服务商**同时存在，每个服务商就是一个 **Provider**：

```
┌─────────────────────────────────────────────────────────────────┐
│                      多 Provider 架构                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   Provider 1: dashscope                                         │
│   ├── baseUrl: https://dashscope.aliyuncs.com/v1               │
│   ├── model: qwen-plus                                          │
│   └── 特点: 阿里云通义千问，中文效果好                            │
│                                                                 │
│   Provider 2: openai                                            │
│   ├── baseUrl: https://api.openai.com/v1                       │
│   ├── model: gpt-4                                              │
│   └── 特点: OpenAI，综合能力强                                   │
│                                                                 │
│   Provider 3: deepseek                                          │
│   ├── baseUrl: https://api.deepseek.com/v1                     │
│   ├── model: deepseek-chat                                      │
│   └── 特点: 深度求索，性价比高                                    │
│                                                                 │
│   Provider 4: zhipu                                             │
│   ├── baseUrl: https://open.bigmodel.cn/api/paas/v4           │
│   ├── model: glm-4                                              │
│   └── 特点: 智谱 AI，国产大模型                                   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

***

### 📊 两种获取方式的使用场景

| 方法                            | 使用场景           | 示例             |
| :---------------------------- | :------------- | :------------- |
| **getDefaultChatClient()**    | 大多数场景，不关心具体服务商 | 简历评分、面试出题      |
| **getChatClient(providerId)** | 需要指定特定服务商      | 用户选择了某个模型、特殊任务 |

```java
// 场景 1：普通业务逻辑，使用默认 Provider
@Service
public class ResumeGradingService {

    private final LlmProviderRegistry registry;

    public GradingResult gradeResume(String resume) {
        // 不关心用哪个模型，用默认的就行
        ChatClient client = registry.getDefaultChatClient();

        return client.prompt()
            .user("请评分这份简历：" + resume)
            .call()
            .entity(GradingResult.class);
    }
}

// 场景 2：用户主动选择了模型
@RestController
public class ChatController {

    private final LlmProviderRegistry registry;

    @PostMapping("/chat")
    public String chat(@RequestBody ChatRequest request) {
        // 用户可能在前端选择了 "openai" 或 "dashscope"
        String providerId = request.getProviderId();  // 用户选择的 Provider

        // 使用用户指定的 Provider
        ChatClient client = registry.getChatClientOrDefault(providerId);

        return client.prompt()
            .user(request.getMessage())
            .call()
            .content();
    }
}

// 场景 3：不同任务使用不同模型
@Service
public class InterviewService {

    private final LlmProviderRegistry registry;

    public String generateQuestion(String topic) {
        // 出题用默认模型（性价比高）
        ChatClient client = registry.getDefaultChatClient();
        // ...
    }

    public String evaluateAnswer(String answer) {
        // 评分用更精准的模型（如 GPT-4）
        ChatClient client = registry.getChatClient("openai");
        // ...
    }
}
```

***

### 🔄 默认 Provider 的确定逻辑

```
┌─────────────────────────────────────────────────────────────────┐
│                  getDefaultChatClient() 执行流程                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   getDefaultChatClient()                                        │
│         ↓                                                       │
│   resolveDefaultChatProviderId()                                │
│         ↓                                                       │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │ 数据库模式？                                              │  │
│   │ ├── 是 → 查询 llm_global_setting 表                      │  │
│   │ │         获取 default_chat_provider_id                  │  │
│   │ │                                                        │  │
│   │ └── 否 → 读取 application.yml                            │  │
│   │           app.ai.default-provider                        │  │
│   └─────────────────────────────────────────────────────────┘  │
│         ↓                                                       │
│   getChatClient("dashscope")  // 假设默认是 dashscope           │
│         ↓                                                       │
│   返回 dashscope 的 ChatClient                                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

***

### 🎨 前端选择模型的场景

```
┌─────────────────────────────────────────────────────────────────┐
│                      前端界面示例                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │  选择 AI 模型                                            │  │
│   │                                                         │  │
│   │  ○ 通义千问  ← 默认，性价比高                    │  │
│   │  ○ GPT-4 (openai)      ← 效果最好，费用高               │  │
│   │  ○ DeepSeek            ← 国产，速度快                   │  │
│   │  ○ 智谱 GLM-4 (zhipu)   ← 中文理解好                    │  │
│   │                                                         │  │
│   │                              [开始面试]                  │  │
│   └─────────────────────────────────────────────────────────┘  │
│                                                                 │
│   用户选择 "openai" → 前端发送 { providerId: "openai" }         │
│   后端调用 registry.getChatClient("openai")                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

***

### 📝 配置示例

```yaml
# application.yml
app:
  ai:
    # 默认 Provider（大多数请求使用这个）
    default-provider: dashscope

    # 默认 Embedding Provider（向量化使用这个）
    default-embedding-provider: dashscope

    # 所有可用的 Provider
    providers:
      dashscope:
        base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
        api-key: ${DASHSCOPE_API_KEY}
        model: qwen-plus
        embedding-model: text-embedding-v3

      openai:
        base-url: https://api.openai.com/v1
        api-key: ${OPENAI_API_KEY}
        model: gpt-4

      deepseek:
        base-url: https://api.deepseek.com/v1
        api-key: ${DEEPSEEK_API_KEY}
        model: deepseek-chat
```

***

### 💡 总结

| 问题                    | 答案                             |
| :-------------------- | :----------------------------- |
| **为什么有默认和指定两种方式？**    | 支持多服务商架构，灵活选择                  |
| **默认 Provider 是什么？**  | 配置的默认服务商，大多数请求使用               |
| **什么时候用指定 Provider？** | 用户选择、特殊任务、负载均衡                 |
| **是模型不同吗？**           | 不只是模型，是**服务商不同**，每个服务商可以配置不同模型 |

**设计意图**：

- **默认方式**：简化业务代码，大多数场景不需要关心具体服务商
- **指定方式**：提供灵活性，支持用户选择或特殊需求

这就像你手机上有多个浏览器（Chrome、Safari、Firefox），默认用 Chrome，但某些情况下可以选择用其他浏览器。

<br />

完全正确！你的理解非常准确。

## 核心概念总结

```
┌─────────────────────────────────────────────────────────────────┐
│                         架构关系                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   Provider = 服务商（配置信息）                                   │
│   ├── dashscope（阿里云通义千问）                                 │
│   ├── openai（OpenAI）                                          │
│   ├── deepseek（深度求索）                                       │
│   └── ...                                                       │
│                                                                 │
│   ChatClient = 统一接口（调用方式相同）                            │
│   ├── 不关心底层是哪个服务商                                      │
│   └── 业务代码只管调用，不用管具体实现                             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 📐 类比理解

| 概念             | 类比              | 说明              |
| :------------- | :-------------- | :-------------- |
| **Provider**   | 数据库连接配置         | 定义连接哪个数据库、用什么账号 |
| **ChatClient** | JDBC Connection | 统一的调用接口，屏蔽底层差异  |

```
就像 JDBC 可以连接 MySQL、PostgreSQL、Oracle...

业务代码：
  Connection conn = dataSource.getConnection();  // 不关心是哪个数据库
  PreparedStatement stmt = conn.prepareStatement("SELECT ...");

同样地：
  ChatClient client = registry.getDefaultChatClient();  // 不关心是哪个服务商
  String response = client.prompt().user("你好").call().content();
```

### 🎯 设计优势

```
┌─────────────────────────────────────────────────────────────────┐
│  业务代码                                                        │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ ChatClient client = registry.getDefaultChatClient();    │   │
│  │ client.prompt().user("出题").call();                    │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              ↓                                  │
│                    不关心具体服务商                               │
│                              ↓                                  │
│  ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐        │
│  │DashScope│   │ OpenAI  │   │DeepSeek │   │  Zhipu  │        │
│  │ (阿里)  │   │         │   │         │   │  (智谱) │        │
│  └─────────┘   └─────────┘   └─────────┘   └─────────┘        │
│                                                                 │
│  可以随时切换服务商，业务代码无需修改！                            │
└─────────────────────────────────────────────────────────────────┘
```

### ✅ 你的理解

| 你说的                 | 补充                                  |
| :------------------ | :---------------------------------- |
| ChatClient 是大模型的接口  | ✅ 正确，是 Spring AI 提供的统一调用接口          |
| Provider 代表一个大模型服务商 | ✅ 正确，包含 baseUrl、apiKey、model 等配置    |
| 项目中有多个 Provider     | ✅ 正确，支持同时配置多个服务商                    |
| 一个接口对接一个大模型         | ✅ 正确，每个 Provider 对应一个 ChatClient 实例 |

这个设计模式叫做 **"抽象工厂 + 策略模式"**，让业务代码与具体服务商解耦，非常灵活！
