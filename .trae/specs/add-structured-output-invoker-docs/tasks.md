# Tasks

- [x] Task 1: 创建文档1 — 架构概览与调用关系
  - 输出文件: `StructuredOutput/01-架构概览与调用关系.md`
  - 内容: 项目角色与职责、上游调用方列表、下游依赖列表、完整调用链路序列图、类的组件分解、流程总结

- [x] Task 2: 创建文档2 — 核心调用与重试流程
  - 输出文件: `StructuredOutput/02-核心调用与重试流程.md`
  - 内容: invoke() 完整执行流程、重试循环分支逻辑、buildRetrySystemPrompt() 三层开关逻辑、sanitizeErrorMessage() 清洗规则、异常处理路径、流程总结

- [x] Task 3: 创建文档3 — JSON 修复引擎
  - 输出文件: `StructuredOutput/03-JSON修复引擎.md`
  - 内容: convertWithRepair() 双阶段策略、状态机算法详解（三种状态）、isLikelyJsonStringTerminator() 启发式判断、状态转换图、修复示例、异常处理设计、流程总结

- [x] Task 4: 创建文档4 — 监控体系与配置管理
  - 输出文件: `StructuredOutput/04-监控体系与配置管理.md`
  - 内容: 三层监控对比、各监控方法详解、normalizeContextTag() 规则、常量定义、构造函数配置读取、null safety 策略、配置对照表、流程总结

# Task Dependencies
- 所有 Task 相互独立，可并行执行
