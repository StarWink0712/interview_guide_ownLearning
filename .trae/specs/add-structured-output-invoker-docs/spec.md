# StructuredOutputInvoker 技术文档 Spec

## Why
为 StructuredOutputInvoker 类生成结构化的技术文档，按逻辑组件拆分为多个文档文件，帮助开发人员全面理解该类的架构设计、实现细节和设计决策。

## What Changes
- 在 `app/src/main/java/interview/guide/common/ai/StructuredOutput/` 目录下创建 4 份技术文档
- 文档1：架构概览与调用关系（项目角色、依赖关系、调用链路、组件分解）
- 文档2：核心调用与重试流程（invoke() 方法、重试循环、提示词构建、错误信息清理）
- 文档3：JSON 修复引擎（convertWithRepair、状态机引号修复算法、结束符检测器）
- 文档4：监控体系与配置管理（三层监控、标签规范化、配置常量、构造函数）

## Impact
- Affected specs: 无（新文档，不影响现有功能）
- Affected code: `app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java`（仅读取分析，不修改）
- 输出目录: `app/src/main/java/interview/guide/common/ai/StructuredOutput/`

## ADDED Requirements
### Requirement: 文档1 — 架构概览与调用关系
The system SHALL 在 StructuredOutput/ 目录下创建文档，详细说明 StructuredOutputInvoker 的：
- 项目角色与职责（LLM 结构化输出统一门面）
- 上游调用方（UnifiedEvaluationService、ResumeGradingService、InterviewSkillService 等）
- 下游依赖（ChatClient、BeanOutputConverter、MeterRegistry、StructuredOutputProperties 等）
- 完整调用链路序列图
- 类的组件分解（常量区、配置区、公共方法、私有方法的分组和职责边界）

#### Scenario: 开发者查阅架构文档
- **WHEN** 开发者打开文档1
- **THEN** 能够理解该类在项目中的定位、与上下游的交互关系、以及内部组件划分

### Requirement: 文档2 — 核心调用与重试流程
The system SHALL 在 StructuredOutput/ 目录下创建文档，详细说明：
- invoke() 方法的完整执行流程（初始化→重试循环→成功/失败处理）
- 重试循环内的分支逻辑（首次 vs 重试的不同提示词策略）
- buildRetrySystemPrompt() 的三层开关组合逻辑
- sanitizeErrorMessage() 的清洗截断规则
- 异常处理的详细路径（每一步异常的处理方式）
- 包含不超过 500 字的流程总结

#### Scenario: 开发者理解核心调用逻辑
- **WHEN** 开发者打开文档2
- **THEN** 能够完整理解从 invoke() 入口到返回结果或抛出异常的每一步操作

### Requirement: 文档3 — JSON 修复引擎
The system SHALL 在 StructuredOutput/ 目录下创建文档，详细说明：
- convertWithRepair() 的双阶段策略（直接转换→本地修复→再转换）
- repairUnescapedQuotesInJsonStrings() 的状态机算法（三个状态：inString/escaping/普通字符）
- isLikelyJsonStringTerminator() 的启发式判断逻辑（跳过空白→检查 JSON 语法符）
- 状态转换图（可视化格式）
- 修复前后对比示例
- 异常处理：addSuppressed 保留修复异常的设计意图
- 包含不超过 500 字的流程总结

#### Scenario: 开发者理解 JSON 修复算法
- **WHEN** 开发者打开文档3
- **THEN** 能够理解状态机的三种状态转移逻辑和启发式判断的原理

### Requirement: 文档4 — 监控体系与配置管理
The system SHALL 在 StructuredOutput/ 目录下创建文档，详细说明：
- 三层监控的区别与协作（Micrometer 指标 / AiMetricsRecorder 业务指标 / AiInvocationLogService 观测日志）
- 每个监控方法的触发条件、记录内容和 fail-silent 策略
- normalizeContextTag() 的六步规范化规则
- 常量定义（STRICT_JSON_INSTRUCTION、指标名称、标签长度限制、正则表达式）
- 构造函数中的配置读取和边界保护（Math.max 保底）
- 所有可选依赖的 null 安全检查策略
- 配置与默认值对照表（StructuredOutputProperties 字段）
- 包含不超过 500 字的流程总结

#### Scenario: 开发者配置监控参数
- **WHEN** 开发者打开文档4
- **THEN** 能够了解所有监控指标的含义、配置开关的作用以及 null safety 的设计

### Requirement: 每个文档必须包含以下方面
The system SHALL 确保每份文档覆盖：
1. 项目角色分析
2. 代码调用与交互
3. 组件分解
4. 组件功能
5. 组件交互
6. 关键关注点
7. 设计决策理由
8. 不超过 500 字的流程总结（以段落形式呈现）

使用中文编写所有文档内容，保持专业、清晰的技术文档风格。
