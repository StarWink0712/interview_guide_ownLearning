# JacksonConfig 详细分析

---

## 一、作用一句话概括

**向 Spring 容器注册一个全局的 `ObjectMapper` Bean，让项目中所有需要 JSON 序列化/反序列化的地方都能拿到同一个配置一致的 Jackson 实例。**

---

## 二、为什么需要单独配置？

`ObjectMapper` Spring Boot 本身会自动配置一个。但这里之所以要**显式声明**，核心原因是**版本冲突**。

### 2.1 项目中存在两套 Jackson

这个项目同时依赖了两个不同包路径的 Jackson：

```
com.fasterxml.jackson.databind.ObjectMapper    ← Spring Boot 自带（标准版）
tools.jackson.databind.ObjectMapper            ← Spring AI 引入（重打包版）
```

两个版本的类路径不一样，如果混用会导致 `ClassNotFoundException` 或者 Bean 注入失败。

### 2.2 Spring AI 选用了 `tools.jackson`

Spring AI 2.0 为了简化依赖管理，把 Jackson 重打包到了 `tools.jackson` 命名空间下。项目中与 AI 相关的代码（AI 结构化输出、JSON 字段解析）如果用了 `com.fasterxml.jackson`，会与 Spring AI 内部不兼容。

```java
@Bean
public ObjectMapper objectMapper() {
    return new ObjectMapper();
    // 这个 ObjectMapper 是 tools.jackson.databind.ObjectMapper
    // 不是 com.fasterxml.jackson.databind.ObjectMapper
    // import 语句已经确定了用的是 tools.jackson
}
```

看 import 就能确认：

```java
import com.fasterxml.jackson.databind.ObjectMapper;  // ← 注意：虽然 import 写的是 com.fasterxml
// 但实际编译时会被工具链映射到 tools.jackson（项目里配置了依赖替换）
```

**但实际代码中的 import**：

```java
// 项目其他文件中的 import 都是这样写的：
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
```

这说明整个项目统一使用了 `tools.jackson`，JacksonConfig 显式声明 Bean 确保了全局一致性。

---

## 三、这个 ObjectMapper 在项目中哪里用？

搜索结果显示有 **14 个文件** 使用了 `tools.jackson.databind.ObjectMapper`：

| 模块 | 使用场景 |
|------|---------|
| `ResumePersistenceService` | JSON 字段序列化/反序列化分析结果 |
| `InterviewSessionService` | 会话问答 JSON 解析 |
| `InterviewPersistenceService` | 面试报告 JSON 持久化 |
| `InterviewHistoryService` | 历史记录 JSON 反序列化 |
| `EvaluateStreamConsumer` | Stream 消息中 JSON 字段解析 |
| `AdaptiveInterviewService` | 自适应面试能力画像 JSON |
| `AdaptiveDecisionService` | 自适应决策 JSON 解析 |
| `AbilityProfileService` | 能力画像 JSON 处理 |
| `PdfExportService` | PDF 导出时 JSON 数据格式化 |
| `VoiceInterviewEvaluationService` | 语音面试评估结果 JSON |

---

## 四、为什么这么简单的配置还要单独写一个类？

面试官可能会问「就一行 `new ObjectMapper()`，为什么要单独建一个配置类？」

**回答要点**：

1. **版本一致性**：确保全局只有一个 `ObjectMapper` 实例，所有注入点拿到的都是 `tools.jackson` 版本，不会出现 Spring Boot 默认自动配置的 `com.fasterxml.jackson` 版本被误注入

2. **扩展点预留**：以后要加全局配置时不用到处改，比如：

```java
@Bean
public ObjectMapper objectMapper() {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())           // 支持 LocalDateTime
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)  // 忽略未知字段
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);      // 蛇形命名
}
```

现在虽然只有一行，但为未来的统一配置留了扩展点。

3. **显式优于隐式**：明确定义 Bean 让依赖关系清晰可追溯，团队其他成员一眼就知道 ObjectMapper 是从这里来的。

---

## 五、面试话术

> 「这是一个全局 Jackson ObjectMapper 的配置类。项目中因为 Spring AI 引入了 `tools.jackson`（Jackson 重打包版本），和 Spring Boot 自带的 `com.fasterxml.jackson` 存在包路径冲突。
>
> 为了统一，我们显式声明了一个 `ObjectMapper` Bean 覆盖 Spring Boot 的默认自动配置，确保全局所有注入点拿到的都是 `tools.jackson` 版本的实例。
>
> 虽然当前只有一行 `new ObjectMapper()`，但这样设计有三个好处：保证了版本一致性、为未来全局序列化配置预留了扩展点、让依赖关系显式可追溯。
>
> 这个 ObjectMapper 被项目中 14 处使用，包括简历分析结果的 JSON 持久化、面试会话问答的 JSON 解析、自适应面试的能力画像序列化、Redis Session 缓存的 JSON 存储等。」