# UnifiedEvaluationService 技术文档

---

## 1. 类概述

`UnifiedEvaluationService` 是文字面试和语音面试共用的**面试评估核心服务**，采用 **三阶段流水线架构**：分批评估 → 合并批次 → 二次汇总，最终生成包含分类得分、逐题评估、参考答案的完整 `EvaluationReport`。

评估全流程由 LLM 驱动，对大量问答记录按配置的 `batchSize` 分批调用 LLM 评估，各批次结果合并后再次调用 LLM 进行二次汇总，生成统一的综合评语、优势列表和改进建议。整个流程内置**多层降级兜底**：单批次失败时零分兜底，二次汇总失败时降级到批次聚合结果，确保评估服务在任何情况下都能返回可用结果。

| 阶段 | 说明 | 失败策略 |
|------|------|----------|
| 阶段 1：分批评估 | 问答记录按 batchSize 分批，每批独立调用 LLM | 零分兜底（系统仍然可用） |
| 阶段 2：合并批次 | 逐题评分合并、优势/改进去重合并、综合评语拼接 | — |
| 阶段 3：二次汇总 | LLM 对所有批次结果聚合为最终评语 | 降级到批次聚合结果 |

类定义在 [UnifiedEvaluationService.java](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/evaluation/UnifiedEvaluationService.java) 。

---

## 2. 依赖分析

### 2.1 导入的类和包

| 导入路径 | 用途 |
|----------|------|
| `interview.guide.common.ai.StructuredOutputInvoker` | 结构化输出调用器（含重试和 JSON 修复） |
| `interview.guide.common.evaluation.EvaluationReport.*` | 评估报告内部 record（CategoryScore、QuestionEvaluation、ReferenceAnswer） |
| `interview.guide.common.exception.ErrorCode` | 错误码常量（`INTERVIEW_EVALUATION_FAILED`） |
| `org.slf4j.Logger` / `LoggerFactory` | 日志记录 |
| `org.springframework.ai.chat.client.ChatClient` | Spring AI 聊天客户端，执行 LLM 调用 |
| `org.springframework.ai.chat.prompt.PromptTemplate` | Prompt 模板引擎（StringTemplate） |
| `org.springframework.ai.converter.BeanOutputConverter` | 结构化输出转换器（LLM JSON → Java 对象） |
| `org.springframework.core.io.Resource` / `ResourceLoader` | 加载 classpath 下的 Prompt 模板文件 |
| `java.io.IOException` | Prompt 模板文件读取异常 |
| `java.nio.charset.StandardCharsets` | UTF-8 编码常量 |
| `java.util.*` | 集合类（ArrayList、HashMap、LinkedHashSet、Stream API） |

### 2.2 外部库依赖

- **Spring AI** — `ChatClient`、`PromptTemplate`、`BeanOutputConverter`：LLM 调用和结构化输出
- **SLF4J** — 日志记录

### 2.3 父类和接口

- 父类：`Object`（无显式继承）
- 注解：`@Service`，注册为 Spring Bean

### 2.4 配置文件与资源

**配置属性**：[InterviewEvaluationProperties](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/evaluation/InterviewEvaluationProperties.java)

```yaml
# application.yml
app:
  interview:
    evaluation:
      batch-size: 8                              # 每批次评估题目数（默认 8）
      system-prompt-path: "classpath:prompts/interview-evaluation-system.st"
      user-prompt-path: "classpath:prompts/interview-evaluation-user.st"
      summary-system-prompt-path: "classpath:prompts/interview-evaluation-summary-system.st"
      summary-user-prompt-path: "classpath:prompts/interview-evaluation-summary-user.st"
```

**Prompt 模板文件**（StringTemplate 格式）：

| 文件 | 用途 |
|------|------|
| `prompts/interview-evaluation-system.st` | 分批评估系统 Prompt |
| `prompts/interview-evaluation-user.st` | 分批评估用户 Prompt |
| `prompts/interview-evaluation-summary-system.st` | 二次汇总系统 Prompt |
| `prompts/interview-evaluation-summary-user.st` | 二次汇总用户 Prompt |

---

## 3. 变量文档

| 变量名 | 类型 | 访问修饰符 | 说明 |
|--------|------|-----------|------|
| `log` | `Logger` | `private static final` | 日志实例，手动创建（未使用 Lombok `@Slf4j`） |
| `MAX_REFERENCE_CONTEXT_CHARS` | `int` | `private static final` | 参考上下文最大字符数限制，6000 字符 |
| `systemPromptTemplate` | `PromptTemplate` | `private final` | 分批评估系统 Prompt 模板，构造函数中加载 |
| `userPromptTemplate` | `PromptTemplate` | `private final` | 分批评估用户 Prompt 模板 |
| `outputConverter` | `BeanOutputConverter<BatchReportDTO>` | `private final` | 批次评估结果的结构化输出转换器 |
| `summarySystemPromptTemplate` | `PromptTemplate` | `private final` | 二次汇总系统 Prompt 模板 |
| `summaryUserPromptTemplate` | `PromptTemplate` | `private final` | 二次汇总用户 Prompt 模板 |
| `summaryOutputConverter` | `BeanOutputConverter<SummaryDTO>` | `private final` | 汇总结果的结构化输出转换器 |
| `structuredOutputInvoker` | `StructuredOutputInvoker` | `private final` | 结构化输出调用器（含重试和 JSON 修复） |
| `evaluationBatchSize` | `int` | `private final` | 每批次评估题目数，由配置注入（最小保证为 1） |
| `resourceLoader` | `ResourceLoader` | `private final` | Spring 资源加载器，用于加载 Prompt 模板文件 |

**内部 record 定义**（共 4 个，均为纯数据载体）：

| Record | 用途 |
|--------|------|
| `BatchReportDTO` | 单批次 LLM 评估返回 DTO（整体评分 + 逐题评分 + 优势/改进） |
| `QuestionEvalDTO` | 单题评估结果 DTO（题号、得分、反馈、参考答案、要点） |
| `BatchResult` | 单个批次的评估结果包装（含起止索引 + BatchReportDTO） |
| `SummaryDTO` | 二次汇总 LLM 返回 DTO（综合评语 + 优势列表 + 改进列表） |

---

## 4. 方法文档

### 4.1 构造函数

```java
public UnifiedEvaluationService(
    StructuredOutputInvoker structuredOutputInvoker,
    ResourceLoader resourceLoader,
    InterviewEvaluationProperties evaluationProperties) throws IOException
```

- **访问修饰符**：`public`，由 Spring 容器通过构造器注入
- **参数 1**：`structuredOutputInvoker` — 结构化输出调用器（含重试和 JSON 修复）
- **参数 2**：`resourceLoader` — 资源加载器，用于加载 classpath 下的 Prompt 模板
- **参数 3**：`evaluationProperties` — 评估配置属性（batchSize、Prompt 路径）
- **逻辑**：
  1. 通过 `evaluationProperties.getXxxPath()` 获取 Prompt 模板文件路径
  2. 调用 `loadPrompt()` 加载模板内容
  3. 创建 `PromptTemplate` 和 `BeanOutputConverter` 实例
  4. 校验 `batchSize`，最小保证为 1
- **异常**：Prompt 模板文件加载失败时抛出 `IOException`

---

### 4.2 `evaluate()` — 四参数便捷版本（第 135-140 行）

```java
public EvaluationReport evaluate(ChatClient chatClient,
                                 String sessionId,
                                 List<QaRecord> qaRecords,
                                 String resumeText)
```

无参考上下文的便捷入口，委托调用五参数完整版本，`referenceContext` 传 `null`。

---

### 4.3 `evaluate()` — 五参数完整版本（第 166-209 行）

```java
public EvaluationReport evaluate(ChatClient chatClient,
                                 String sessionId,
                                 List<QaRecord> qaRecords,
                                 String resumeText,
                                 String referenceContext)
```

这是整个评估服务的核心入口方法。流程分三步：

#### 输入预处理（第 173-183 行）

- **简历文本**：`null` 保护为空字符串 → 超过 3000 字符时截断
- **参考上下文**：`null` 保护为空字符串 → 超过 `MAX_REFERENCE_CONTEXT_CHARS`（6000）时截断

超长截断的目的：控制 LLM 输入 token 数量，防止超出模型上下文窗口。

#### 阶段 1：分批评估（第 187-189 行）

```java
List<BatchResult> batchResults = evaluateInBatches(
    chatClient, sessionId, resumeContext, qaRecords, referenceBaseline
);
```

按 `evaluationBatchSize` 将问答记录分组，每组独立调用 `evaluateBatch()`。每批返回 `BatchReportDTO`（含整体评分和逐题评分），null 表示该批次失败。

#### 阶段 2：合并批次结果（第 193-197 行）

```java
List<QuestionEvalDTO> mergedEvaluations = mergeQuestionEvaluations(batchResults);
String fallbackFeedback = mergeOverallFeedback(batchResults);
List<String> fallbackStrengths = mergeListItems(batchResults, true);
List<String> fallbackImprovements = mergeListItems(batchResults, false);
```

- `mergeQuestionEvaluations`：缺失的题零分兜底
- `mergeOverallFeedback`：各批次综合评语用换行拼接
- `mergeListItems`：去重合并，最多保留 8 条

#### 阶段 3：二次汇总（第 201-204 行）

```java
SummaryDTO summary = summarizeBatchResults(...);
```

调用 LLM 对阶段 2 的合并结果进行二次汇总，生成统一的综合评语、优势和改进建议。失败时降级到批次聚合结果。

#### 最终构建（第 207-208 行）

```java
return buildReport(sessionId, qaRecords, mergedEvaluations,
    summary.overallFeedback(), summary.strengths(), summary.improvements());
```

将合并的逐题评估和二次汇总结果组装为 `EvaluationReport`。

---

### 4.4 `loadPrompt()` — 加载 Prompt 模板（第 218-221 行）

```java
private String loadPrompt(String path) throws IOException
```

通过 `ResourceLoader` 加载 classpath 下的模板文件，以 UTF-8 编码读取为字符串。

---

### 4.5 `evaluateInBatches()` — 分批评估（第 226-238 行）

```java
private List<BatchResult> evaluateInBatches(...)
```

遍历问答记录，按 `evaluationBatchSize` 切片分组，每组调用 `evaluateBatch()`。返回所有批次的 `BatchResult` 列表（含起止索引）。

```
qaRecords: [Q1, Q2, Q3, Q4, Q5, Q6, Q7, Q8, Q9, Q10]  (batchSize=4)

批次 0: [Q1, Q2, Q3, Q4] → startIndex=0, endIndex=4
批次 1: [Q5, Q6, Q7, Q8] → startIndex=4, endIndex=8
批次 2: [Q9, Q10]         → startIndex=8, endIndex=10
```

---

### 4.6 `evaluateBatch()` — 单批次 LLM 评估（第 243-273 行）

```java
private BatchReportDTO evaluateBatch(ChatClient chatClient, String sessionId,
                                      String resumeContext, String referenceContext,
                                      List<QaRecord> batch)
```

#### 步骤 1：构建 Prompt（第 246-257 行）

1. 调用 `buildQARecords()` 将 QaRecord 列表格式化为文本
2. 渲染系统 Prompt 模板（`systemPromptTemplate.render()`）
3. 构建变量 Map（`resumeText`、`qaRecords`、`referenceContext`）
4. 渲染用户 Prompt 模板（`userPromptTemplate.render(variables)`）
5. 拼接输出格式指令：`systemPrompt + "\n\n" + outputConverter.getFormat()`

#### 步骤 2：调用 LLM（第 260-266 行）

```java
return structuredOutputInvoker.invoke(
    chatClient, systemPromptWithFormat, userPrompt, outputConverter,
    ErrorCode.INTERVIEW_EVALUATION_FAILED, "批次评估失败：", "批次评估", log
);
```

通过 `StructuredOutputInvoker` 执行结构化输出调用，内部包含重试逻辑和 JSON 修复。成功时返回 `BatchReportDTO`。

#### 步骤 3：失败兜底（第 267-272 行）

catch 异常后返回 `null`，由上层 `mergeQuestionEvaluations()` 用零分兜底。

---

### 4.7 `buildQARecords()` — 格式化问答记录（第 278-287 行）

```java
private String buildQARecords(List<QaRecord> batch)
```

将问答记录列表格式化为 LLM Prompt 文本，格式如下：

```
问题1 [Java]: 请解释 JVM 内存模型...
回答: 用户的实际回答内容...

问题2 [MySQL]: 什么是索引覆盖...
回答: (未回答)
```

---

### 4.8 `mergeQuestionEvaluations()` — 合并逐题评估（第 292-312 行）

遍历每个批次的评估结果，按顺序合并逐题评测数据。逻辑：

```
对于批次中每一道题：
  if 该题有有效评估结果 → 使用 LLM 返回的评估
  else → 创建零分兜底对象（score=0, feedback="该题未成功生成评估结果...", keyPoints=[]）
```

目的：确保最终报告中的题目数与原始问答记录数完全一致，不因 LLM 返回数据缺失而丢失题目。

---

### 4.9 `mergeOverallFeedback()` — 合并综合评语（第 317-324 行）

将所有批次的有效 `overallFeedback` 用 `\n\n` 拼接。如果全部为空，返回默认兜底文案。

---

### 4.10 `mergeListItems()` — 合并去重列表项（第 330-343 行）

```java
private List<String> mergeListItems(List<BatchResult> batchResults, boolean strengthsMode)
```

- `strengthsMode=true`：合并优势列表
- `strengthsMode=false`：合并改进建议列表

使用 `LinkedHashSet` 保持插入顺序去重，最终限制最多 8 条。过滤空值和空白字符串。

---

### 4.11 `summarizeBatchResults()` — 二次汇总（第 349-381 行）

```java
private SummaryDTO summarizeBatchResults(...)
```

#### 成功路径（第 353-376 行）

1. 渲染汇总系统 Prompt 和用户 Prompt
2. 用户 Prompt 变量包括：
   - `resumeText` — 简历上下文
   - `referenceContext` — 参考基线
   - `categorySummary` — 分类得分汇总
   - `questionHighlights` — 单题亮点摘要（最多 20 条）
   - `fallbackOverallFeedback` / `fallbackStrengths` / `fallbackImprovements` — 批次聚合兜底数据
3. 调用 LLM 进行二次汇总
4. LLM 返回为空时退回到兜底数据

#### 降级路径（第 377-380 行）

LLM 调用抛异常时，直接使用批次聚合结果构建 `SummaryDTO`，确保评估不中断。

---

### 4.12 `sanitizeItems()` — 清理列表项（第 387-393 行）

优先使用 LLM 返回的主结果，为空时降级到批次聚合结果。去重并限制最多 8 条。

---

### 4.13 `buildReport()` — 构建最终评估报告（第 415-482 行）

这是报告构建的终点方法，完整逻辑：

```
1. 统计已回答题目数 → 过滤 userAnswer 为空的题
2. 遍历每一道题：
   ├── 判断是否已回答 → 未回答则 score=0
   ├── 构建 QuestionEvaluation（题号、题目、分类、用户回答、得分、反馈）
   ├── 构建 ReferenceAnswer（题号、题目、参考答案、关键要点）
   └── 按分类收集得分
3. 按分类计算平均分 → CategoryScore 列表
4. 计算总分 → 所有已回答题目的平均分（全部未回答则为 0）
5. 返回 EvaluationReport
```

---

### 4.14 `buildCategorySummary()` — 分类得分汇总文本（第 487-505 行）

用于二次汇总 Prompt，按分类统计平均分和题目数，示例输出：

```
- Java: 平均分 78, 题数 3
- MySQL: 平均分 65, 题数 2
- 算法能力: 平均分 82, 题数 5
```

---

### 4.15 `buildQuestionHighlights()` — 单题亮点摘要文本（第 510-522 行）

用于二次汇总 Prompt，限制最多 20 条。过长的题目/反馈自动截断。示例输出：

```
- Q1 | 请解释 JVM 内存模型... | 分数:78 | 反馈:回答覆盖了堆和栈...
- Q2 | 什么是索引覆盖... | 分数:65 | 反馈:基本理解正确，但缺少...
```

---

## 5. 核心功能

### 5.1 三阶段评估流水线

```
用户请求 evaluate()
    │
    ▼
阶段 1：分批评估
    ├── 按 batchSize 分组问答记录
    ├── 每组独立调用 LLM（并行潜力）
    │      ├── 成功 → BatchReportDTO
    │      └── 失败 → null（零分兜底）
    └── 返回 List<BatchResult>
    │
    ▼
阶段 2：合并批次
    ├── mergeQuestionEvaluations()  → 逐题合并（缺失零分兜底）
    ├── mergeOverallFeedback()     → 评语拼接
    ├── mergeListItems(strengths)  → 优势去重（最多 8 条）
    └── mergeListItems(improvements) → 改进去重（最多 8 条）
    │
    ▼
阶段 3：二次汇总
    ├── 拼接 Prompt（分类汇总 + 单题亮点 + 批次兜底数据）
    ├── 调用 LLM 生成最终评语
    │      ├── 成功 → LLM 生成的 unified 评语
    │      └── 失败 → 降级到批次聚合结果
    └── 返回 SummaryDTO
    │
    ▼
buildReport() → EvaluationReport
```

### 5.2 降级策略设计

| 失败场景 | 降级行为 | 影响 |
|----------|----------|------|
| 单批次 LLM 调用失败 | 该批次所有题零分兜底 | 仅该批次无有效评估，其他批次正常 |
| 二次汇总 LLM 调用失败 | 降级到批次聚合结果 | 综合评语来自各批次拼接，非 LLM 统一生成 |
| Prompt 模板加载失败 | 构造函数抛 IOException | Bean 初始化失败，服务不可用 |

降级策略的核心思想：**宁可降低评估质量，也不能中断评估流程**。

### 5.3 关键设计决策

**为什么要分批评估而不是一次性评估所有题目？**

LLM 的上下文窗口有限，当题目数量较多（如 20 题以上）时，一次性评估可能超出上下文限制导致截断。分批评估将输入控制在可控范围内，保证每批的评估质量。

**为什么要进行二次汇总？**

每个批次只能看到部分题目的表现，无法形成整体判断。二次汇总让 LLM 看到所有题目的评估摘要，从而生成更准确的全局综合评语。

**为什么二次汇总失败时降级到批次聚合而不是报错？**

二次汇总是锦上添花，而非必需品。即使 LLM 不可用，批次聚合的结果（各批次评语拼接、优势去重）也是有参考价值的。

**为什么 batchSize 要保证最小为 1？**

`Math.max(1, evaluationProperties.getBatchSize())` 防止配置错误导致死循环或异常行为。

---

## 6. 代码流程分析

### 6.1 完整调用链

```
Controller
    │
    ▼
AnswerEvaluationService / VoiceInterviewEvaluationService
    │
    ├── 1. 构建 List<QaRecord>（问答记录列表）
    │
    └── 2. 调用 unifiedEvaluationService.evaluate(chatClient, sessionId, qaRecords, resumeText, referenceContext)
              │
              ├── 2.1 输入预处理（截断超长文本）
              │
              ├── 2.2 evaluateInBatches()
              │     └── evaluateBatch() × N 次
              │           ├── buildQARecords()    ← 格式化问答
              │           ├── PromptTemplate.render()  ← 渲染 Prompt
              │           ├── structuredOutputInvoker.invoke()  ← LLM 调用（含重试）
              │           └── 失败 → return null
              │
              ├── 2.3 mergeQuestionEvaluations()  ← 逐题合并 + 零分兜底
              ├── 2.4 mergeOverallFeedback()      ← 评语拼接
              ├── 2.5 mergeListItems() × 2        ← 优势/改进去重
              │
              ├── 2.6 summarizeBatchResults()     ← 二次汇总
              │     ├── buildCategorySummary()     ← 分类得分摘要
              │     ├── buildQuestionHighlights()  ← 单题亮点摘要
              │     ├── structuredOutputInvoker.invoke()  ← LLM 二次汇总
              │     └── 失败 → 降级到批次聚合结果
              │
              └── 2.7 buildReport()
                    ├── 逐题构建 QuestionEvaluation + ReferenceAnswer
                    ├── 按分类计算平均分
                    ├── 计算总分
                    └── → EvaluationReport
              │
              ▼
        返回 EvaluationReport 给调用方
```

### 6.2 方法调用关系图

```
evaluate(chatClient, sessionId, qaRecords, resumeText, referenceContext)
  │
  ├── evaluateInBatches(chatClient, sessionId, resumeContext, qaRecords, referenceBaseline)
  │     └── evaluateBatch(chatClient, sessionId, resumeContext, referenceContext, batch)
  │           ├── buildQARecords(batch)
  │           ├── systemPromptTemplate.render()
  │           ├── userPromptTemplate.render(variables)
  │           └── structuredOutputInvoker.invoke(chatClient, ..., outputConverter, ...)
  │                 └── chatClient.prompt().system(...).user(...).call().content()
  │
  ├── mergeQuestionEvaluations(batchResults)
  ├── mergeOverallFeedback(batchResults)
  ├── mergeListItems(batchResults, strengthsMode) × 2
  │
  ├── summarizeBatchResults(...)
  │     ├── buildCategorySummary(qaRecords, evaluations)
  │     ├── buildQuestionHighlights(qaRecords, evaluations)
  │     ├── summarySystemPromptTemplate.render()
  │     ├── summaryUserPromptTemplate.render(vars)
  │     ├── structuredOutputInvoker.invoke(chatClient, ..., summaryOutputConverter, ...)
  │     └── sanitizeItems(primary, fallback) × 2
  │
  └── buildReport(sessionId, qaRecords, evaluations, feedback, strengths, improvements)
```

---

## 7. 外部交互

### 7.1 依赖的外部类

| 外部类 | 交互方式 | 数据方向 |
|--------|---------|---------|
| `StructuredOutputInvoker` | 方法调用 | 双向：传入 Prompt + ChatClient，返回结构化的 DTO |
| `InterviewEvaluationProperties` | 配置注入 | 单向：配置值流入评估服务 |
| `ChatClient` | 间接调用 | 通过 StructuredOutputInvoker 间接调用 LLM |
| `PromptTemplate` | 方法调用 | 单向：调用 `render()` 生成 Prompt 文本 |
| `BeanOutputConverter` | 方法调用 | 单向：调用 `getFormat()` 获取输出格式指令 |
| `ResourceLoader` | 方法调用 | 单向：加载 Prompt 模板文件 |
| `EvaluationReport` | 实例化 | 单向：构建并返回评估结果 |

### 7.2 被调用方

| 调用方 | 场景 |
|--------|------|
| `AnswerEvaluationService` | 文字面试评估 |
| `VoiceInterviewEvaluationService` | 语音面试评估 |

两个调用方都需要构建 `List<QaRecord>`，传入 `ChatClient` 和可选的简历文本、参考上下文。

```
AnswerEvaluationService
    │
    ├── 从 InterviewSessionEntity 构建 qaRecords
    ├── 从 ResumeEntity 获取 resumeText
    └── 调用 unifiedEvaluationService.evaluate(chatClient, sessionId, qaRecords, resumeText)
```

### 7.3 与 LLM 的交互

每次评估最多产生 N 次 LLM 调用（N = 批次数 + 1 次汇总）：

```
第 1 次 LLM 调用: evaluateBatch() → 批次 1: Q1~Q8
第 2 次 LLM 调用: evaluateBatch() → 批次 2: Q9~Q16
...
第 N 次 LLM 调用: evaluateBatch() → 批次 N: Qx~Qy
第 N+1 次 LLM 调用: summarizeBatchResults() → 二次汇总
```

所有 LLM 调用都通过 `StructuredOutputInvoker` 进行，内置重试逻辑（默认最大 3 次），单次 LLM 调用失败不中断整体流程。

---

## 8. 类的定位与角色

`UnifiedEvaluationService` 在系统架构中扮演以下几个角色：

1. **评估流水线编排者**：将复杂的面试评估任务分解为"分批评估 → 合并 → 二次汇总"三个阶段，每个阶段职责清晰

2. **降级策略执行者**：通过多层兜底机制（零分兜底、批次聚合降级）保证评估服务的高可用性

3. **Prompt 工程协调者**：负责加载、渲染和组装四个 Prompt 模板（系统/用户 × 批次/汇总），将问答记录、简历文本、参考基线等上下文注入 Prompt

4. **评估结果聚合器**：将分散的批次评估结果合并为统一的 `EvaluationReport`，包含分类得分、逐题评估、参考答案等完整信息

**与 AnswerEvaluationService / VoiceInterviewEvaluationService 的关系**：

```
AnswerEvaluationService          VoiceInterviewEvaluationService
    │                                       │
    └────── 两者都调用 ───────────────────────┘
                     │
              UnifiedEvaluationService
                     │
         ┌───────────┼───────────┐
         │           │           │
  StructuredOutputInvoker    PromptTemplate    BeanOutputConverter
         │
    ChatClient → LLM API
```

`UnifiedEvaluationService` 是**通用评估引擎**，不关心是哪类面试（文字/语音），只关心问答记录和 LLM 客户端。这是一种策略抽离：将评估算法下沉到通用层，业务特定的数据准备由各自的 Service 完成。

---

## 9. 完整流程总结

当文字面试或语音面试模块需要生成评估报告时，调用 `UnifiedEvaluationService` 的 `evaluate()` 方法。该方法接收 LLM 客户端、会话 ID、问答记录列表、简历文本和可选的参考上下文作为输入。

评估流程分为三个阶段。**阶段一（分批评估）**：将全量问答记录按 `batchSize` 分组，每批独立调用 LLM 进行评估。每个批次调用 `evaluateBatch()`，该方法首先将 QaRecord 列表格式化为可读文本，然后渲染系统 Prompt 和用户 Prompt（注入简历和参考上下文），最后通过 `StructuredOutputInvoker` 调用 LLM 获取结构化的 `BatchReportDTO`（含整体评分、逐题评分、综合评语、优势/改进建议）。单批次 LLM 调用失败时返回 `null`。

**阶段二（合并批次）**：调用 `mergeQuestionEvaluations()` 将所有批次的逐题评估按顺序合并，LLM 返回缺失的题自动以零分兜底（score=0、空反馈）；调用 `mergeOverallFeedback()` 拼接各批次的综合评语；调用 `mergeListItems()` 对优势和改进建议去重合并（最多保留 8 条，使用 `LinkedHashSet` 保持顺序）。

**阶段三（二次汇总）**：以阶段二的所有合并结果为兜底数据，再次调用 LLM 生成统一的综合评语、优势和改进建议。Prompt 中包含分类得分摘要、单题亮点摘要和批次兜底数据，便于 LLM 做出全局判断。LLM 调用失败时直接降级到批次聚合结果，不中断评估流程。

最后，`buildReport()` 将合并的逐题评估和二次汇总结果组装为 `EvaluationReport`：逐题生成 `QuestionEvaluation`（打分+反馈）和 `ReferenceAnswer`（参考答案+要点），按分类计算平均分，按已回答题目计算总分（全部未回答则为 0 分），最终返回完整的评估报告。

整个流程通过多层降级兜底确保评估服务始终可用——宁可降低评估质量也不中断流程，体现了高可用系统的设计原则。
